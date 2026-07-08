package br.com.fzdevx.infrastructure.persistence;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file replacement: writes to a sibling tmp file and atomically
 * moves it over the target, cleaning the tmp file up on failure. Shared by
 * the JSON repositories and the audit log so the write strategy lives in
 * one place. Callers are responsible for their own locking.
 */
final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    static void write(Path target, String content) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp." + System.nanoTime());
        try {
            Files.writeString(tmp, content);
            move(tmp, target);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
