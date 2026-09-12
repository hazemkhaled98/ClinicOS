package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.Attachment.ATTACHMENT;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.clinicos.shared.jooq.tables.records.AttachmentRecord;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;

@Service
public class DefaultAttachmentService implements AttachmentService {

    private final MinioClient minioClient;
    private final StorageProperties storage;
    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultAttachmentService(MinioClient minioClient, StorageProperties storage,
            DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.minioClient = minioClient;
        this.storage = storage;
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Attachment upload(UUID clinicId, UUID uploadedByMembershipId, MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/") || contentType.equals("image/svg+xml")) {
            throw new IllegalArgumentException("الملف يجب أن يكون صورة");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("الملف فارغ");
        }
        String storageKey = clinicId + "/" + UUID.randomUUID() + "."
                + extension(contentType, file.getOriginalFilename());
        put(storageKey, contentType, file);
        long byteSize = file.getSize();
        return transactionTemplate.execute(status -> {
            UUID id = dsl.insertInto(ATTACHMENT)
                    .set(ATTACHMENT.CLINIC_ID, clinicId)
                    .set(ATTACHMENT.STORAGE_KEY, storageKey)
                    .set(ATTACHMENT.CONTENT_TYPE, contentType)
                    .set(ATTACHMENT.BYTE_SIZE, Math.toIntExact(byteSize))
                    .set(ATTACHMENT.UPLOADED_BY, uploadedByMembershipId)
                    .returningResult(ATTACHMENT.ID)
                    .fetchOne(ATTACHMENT.ID);
            return new Attachment(id, storageKey, contentType, byteSize);
        });
    }

    @Override
    public InputStream open(UUID clinicId, UUID attachmentId) {
        Attachment attachment = transactionTemplate.execute(status -> fetch(clinicId, attachmentId));
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(storage.bucket())
                    .object(attachment.storageKey())
                    .build());
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw minioFailure(e);
        }
    }

    @Override
    public ContentInfo contentInfo(UUID clinicId, UUID attachmentId) {
        Attachment attachment = transactionTemplate.execute(status -> fetch(clinicId, attachmentId));
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(storage.bucket())
                    .object(attachment.storageKey())
                    .build());
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw minioFailure(e);
        }
        return new ContentInfo(attachment.contentType(), attachment.byteSize());
    }

    private Attachment fetch(UUID clinicId, UUID attachmentId) {
        AttachmentRecord row = dsl.selectFrom(ATTACHMENT)
                .where(ATTACHMENT.ID.eq(attachmentId))
                .and(ATTACHMENT.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (row == null) {
            throw new IllegalArgumentException("المرفق غير موجود");
        }
        return new Attachment(row.getId(), row.getStorageKey(), row.getContentType(), row.getByteSize());
    }

    private void put(String storageKey, String contentType, MultipartFile file) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storage.bucket())
                    .object(storageKey)
                    .stream(file.getInputStream(), file.getSize(), -1L)
                    .contentType(contentType)
                    .build());
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw minioFailure(e);
        }
    }

    private RuntimeException minioFailure(Exception e) {
        if (e instanceof ErrorResponseException errorResponse
                && errorResponse.errorResponse() != null
                && "NoSuchKey".equals(errorResponse.errorResponse().code())) {
            return new IllegalArgumentException("المرفق غير موجود");
        }
        return new IllegalStateException("تعذر الوصول إلى خدمة التخزين", e);
    }

    private String extension(String contentType, String originalFilename) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            case "image/bmp" -> "bmp";
            default -> {
                if (originalFilename != null && originalFilename.lastIndexOf('.') >= 0) {
                    yield originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
                }
                yield "bin";
            }
        };
    }
}