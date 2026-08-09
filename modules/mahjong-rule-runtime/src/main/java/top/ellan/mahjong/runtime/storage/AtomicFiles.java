package top.ellan.mahjong.runtime.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Same-filesystem atomic replacement helper. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, content);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
