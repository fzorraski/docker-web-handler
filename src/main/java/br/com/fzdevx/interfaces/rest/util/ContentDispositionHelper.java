package br.com.fzdevx.interfaces.rest.util;


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
        return filename
                .replaceAll("[/\\\\\"'\r\n\t\0]", "_")
                .replaceAll("[^\\x20-\\x7E]", "_")
                .trim();
    }
}
