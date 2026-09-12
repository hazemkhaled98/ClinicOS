package com.clinicos.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.clinicos.shared.AttachmentService;
import com.clinicos.shared.AttachmentService.ContentInfo;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Serves stored attachment bytes. Stays behind the authenticated-by-default
 * security chain — only a logged-in clinic member can fetch a photo, and the
 * service 404s any attachment that is missing or belongs to another clinic.
 */
@Controller
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping("/attachments/{id}")
    public void getAttachment(@PathVariable("id") UUID attachmentId, HttpSession session,
            HttpServletResponse response) throws IOException {
        UUID clinicId = AdminAccess.clinicId(session);
        if (clinicId == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        try {
            ContentInfo info = attachmentService.contentInfo(clinicId, attachmentId);
            response.setContentType(info.contentType());
            response.setContentLengthLong(info.byteSize());
            response.setHeader("Content-Disposition", "inline; filename=\"" + attachmentId + "\"");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            try (InputStream in = attachmentService.open(clinicId, attachmentId)) {
                in.transferTo(response.getOutputStream());
            }
        } catch (IllegalArgumentException e) {
            response.sendError(HttpStatus.NOT_FOUND.value());
        }
    }
}