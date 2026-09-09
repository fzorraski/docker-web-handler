package br.com.fzdevx.domain.shared;

import java.util.Optional;

/**
 * Detects common raster image formats from their leading bytes ("magic numbers").
 * Used to validate pasted/dropped images without trusting the client-provided MIME type.
 */
public final class ImageSignature {

    /** Number of leading bytes needed to identify every supported format. */
    public static final int HEADER_LENGTH = 12;

    private ImageSignature() {}

    /**
     * Returns the file extension (without dot) for a supported image, or empty when the
     * header does not match PNG, JPEG, GIF, or WebP.
     */
    public static Optional<String> detectExtension(byte[] header) {
        if (header == null) return Optional.empty();
        if (startsWith(header, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of("png");
        if (startsWith(header, 0xFF, 0xD8, 0xFF)) return Optional.of("jpg");
        if (startsWith(header, 'G', 'I', 'F', '8')) return Optional.of("gif");
        if (startsWith(header, 'R', 'I', 'F', 'F') && header.length >= 12
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return Optional.of("webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int... expected) {
        if (data.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((data[i] & 0xFF) != (expected[i] & 0xFF)) return false;
        }
        return true;
    }
}
