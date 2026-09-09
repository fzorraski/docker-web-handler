package br.com.fzdevx.domain.shared;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSignatureTest {

    @Test
    void detectsPng() {
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
        assertEquals(Optional.of("png"), ImageSignature.detectExtension(header));
    }

    @Test
    void detectsJpeg() {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
        assertEquals(Optional.of("jpg"), ImageSignature.detectExtension(header));
    }

    @Test
    void detectsGif() {
        assertEquals(Optional.of("gif"), ImageSignature.detectExtension("GIF89a".getBytes()));
    }

    @Test
    void detectsWebp() {
        byte[] header = {'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertEquals(Optional.of("webp"), ImageSignature.detectExtension(header));
    }

    @Test
    void riffWithoutWebpMarkerIsRejected() {
        byte[] header = {'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'A', 'V', 'E'};
        assertTrue(ImageSignature.detectExtension(header).isEmpty());
    }

    @Test
    void rejectsTextAndShortInputs() {
        assertTrue(ImageSignature.detectExtension("hello world".getBytes()).isEmpty());
        assertTrue(ImageSignature.detectExtension(new byte[0]).isEmpty());
        assertTrue(ImageSignature.detectExtension(null).isEmpty());
        assertTrue(ImageSignature.detectExtension(new byte[]{(byte) 0x89, 'P'}).isEmpty());
    }
}
