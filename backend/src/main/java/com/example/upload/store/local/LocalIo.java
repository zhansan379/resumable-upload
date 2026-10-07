package com.example.upload.store.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 本地磁盘后端共享的小工具。写操作遵循本项目两条铁律：
 * 临时文件 + 原子移动（杜绝"半个分片/半个文件"被观察为已传）；
 * 清理类操作静默降级（记日志不抛出，与协议层的错误语义解耦）。
 */
final class LocalIo {

    private LocalIo() {
    }

    static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteDirQuietly(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // 清理失败只影响磁盘占用，不影响正确性（该目录会被下次清理任务重试）
                }
            });
        } catch (IOException e) {
            // 同上，静默降级
        }
    }
}
