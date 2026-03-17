package br.com.fzdevx.interfaces.rest.util;

// ⚠ SECURITY — OWASP A01: sanitize filenames for Content-Disposition headers to prevent header injection
public final class ContentDispositionHelper {

    private ContentDispositionHelper() {}

    /**
     * Builds a safe Content-Disposition header value for file downloads.
     * Strips characters that could enable header injection (newlines, quotes, non-ASCII).
     */
    public static String buildAttachmentHeader(String filename) {
        String safe = sanitizeFilename(filename);
        return "attachment; filename=\"" + safe + "\"";
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "download";
        }
        // Remove path separators, null bytes, newlines, quotes, and non-printable chars
        return filename
                .replaceAll("[/\\\\\"'\r\n\t\0]", "_")
                .replaceAll("[^\\x20-\\x7E]", "_")
                .trim();
    }
}
