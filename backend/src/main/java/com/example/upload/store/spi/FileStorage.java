package com.example.upload.store.spi;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 合并产物（最终文件）存储 SPI。以 locator（{@link StoredObject}）为寻址单位。
 * <p>
 * S3 实现提示：completeMerge = completeMultipartUpload（合并前重复合并保护映射为 HeadObject
 * 按大小比对），open = GetObject（Range 用请求头实现），delete = DeleteObject，
 * usableBytes 返回负数表示"容量不适用"。
 */
public interface FileStorage {

    /**
     * 把已传分片固化为最终文件，返回存储定位信息。
     * safeFileName 由协议层清洗（去非法字符、限长），实现自决定是否体现在 locator 中。
     * 分片齐全性与总字节数校验在协议层完成，实现可假设分片完整；中途存储异常抛 IOException。
     * 实现须自带"上次合并成功但索引写入失败"的恢复语义：目标已存在且大小一致时复用，
     * 大小不一致（上次合并中途死亡留下的残缺文件）时删除重做。
     */
    StoredObject completeMerge(String fileHash, String safeFileName, long totalSize, int totalChunks)
            throws IOException;

    /**
     * locator 是否存在且为可读的最终文件（用于秒传命中判断与启动复核）。
     * "不存在"的判定标准与 {@link #stat} 一致：抛出下述两种异常之一。
     */
    default boolean exists(String locator) {
        try {
            stat(locator);
            return true;
        } catch (FileNotFoundException | java.nio.file.NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 存储定位信息（含大小）。
     * 不存在时抛 {@link java.nio.file.NoSuchFileException} 或 {@link FileNotFoundException}
     * （本地实现自然抛前者；S3 等非文件系统实现抛后者）。
     */
    StoredObject stat(String locator) throws IOException;

    /** 打开只读句柄。"不存在"的异常约定同 {@link #stat}。 */
    ContentHandle open(String locator) throws IOException;

    /** 删除最终文件（及其空父级目录等实现私有附属物）。实现自行吞并 IO 异常（记日志），不向上抛。 */
    void delete(String locator);

    /**
     * 剩余可写容量（字节）；&lt;=0 表示实现无法给出（如对象存储），协议层据此跳过水位前置检查。
     * 本地实现返回最终文件目录所在盘的可用空间。
     */
    long usableBytes();
}
