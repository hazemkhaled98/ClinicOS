package com.clinicos.shared;

import java.io.InputStream;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

/**
 * Stores and serves proof photos (and other images) for a clinic. Bytes live
 * in the shared MinIO bucket under {@code <clinicId>/<uuid>.<ext>}; the
 * {@code attachment} table holds the pointer. MinIO is not transactional with
 * Postgres, so uploads write the object first and only then the row —
 * a DB rollback never leaves a row pointing at a missing object.
 *
 * <p>Every call is tenancy-checked twice: explicitly by {@code clinicId}
 * scoping here, and again by Postgres RLS on the bound tenant. A missing or
 * cross-tenant attachment raises {@link IllegalArgumentException} with a
 * user-facing Arabic message (the controller maps it to 404).
 */
public interface AttachmentService {

    Attachment upload(UUID clinicId, UUID uploadedByMembershipId, MultipartFile file);

    InputStream open(UUID clinicId, UUID attachmentId);

    ContentInfo contentInfo(UUID clinicId, UUID attachmentId);

    record Attachment(UUID id, String storageKey, String contentType, long byteSize) {
    }

    record ContentInfo(String contentType, long byteSize) {
    }
}