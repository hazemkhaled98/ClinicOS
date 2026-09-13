package com.clinicos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.shared.AttachmentService.Attachment;
import com.clinicos.shared.AttachmentService.ContentInfo;

@SpringBootTest(classes = Application.class)
class AttachmentServiceIT extends AbstractPostgresIntegrationTest {

    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("clinicos.storage.endpoint", MINIO::getS3URL);
        registry.add("clinicos.storage.access-key", MINIO::getUserName);
        registry.add("clinicos.storage.secret-key", MINIO::getPassword);
    }

    @Autowired
    private AttachmentService attachmentService;

    private UUID clinicA;
    private UUID clinicB;
    private UUID membershipId;

    @BeforeEach
    void seedClinics() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
            UUID userId = TestFixtures.insertUser(connection, clinicA);
            membershipId = TestFixtures.insertMembership(connection, clinicA, userId);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void uploadRoundTripsThroughContentInfoAndOpen() throws Exception {
        TenantContext.set(clinicA);
        byte[] bytes = { 1, 2, 3, 4, 5 };
        MockMultipartFile file = new MockMultipartFile("photo", "proof.png", "image/png", bytes);

        Attachment attachment = attachmentService.upload(clinicA, membershipId, file);

        assertThat(attachment.contentType()).isEqualTo("image/png");
        assertThat(attachment.byteSize()).isEqualTo(bytes.length);
        assertThat(attachment.storageKey()).startsWith(clinicA + "/");

        ContentInfo info = attachmentService.contentInfo(clinicA, attachment.id());
        assertThat(info.contentType()).isEqualTo("image/png");
        assertThat(info.byteSize()).isEqualTo(bytes.length);

        try (InputStream in = attachmentService.open(clinicA, attachment.id())) {
            assertThat(in.readAllBytes()).containsExactly(bytes);
        }
    }

    @Test
    void openFromAnotherClinicIsRejected() {
        TenantContext.set(clinicA);
        MockMultipartFile file = new MockMultipartFile("photo", "proof.png", "image/png",
                new byte[] { 1, 2, 3 });
        Attachment attachment = attachmentService.upload(clinicA, membershipId, file);

        TenantContext.set(clinicB);
        assertThatThrownBy(() -> attachmentService.open(clinicB, attachment.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}