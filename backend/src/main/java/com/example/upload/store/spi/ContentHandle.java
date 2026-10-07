package com.example.upload.store.spi;

import java.io.IOException;
import java.io.InputStream;

/**
 * 已合并文件内容的只读句柄。下载（全量 / Range 区间）与 MD5 复核都经由它，
 * 使协议层不出现任何本地文件概念。
 * <p>
 * 每个读取方法返回独立限界的流：Range 流读到 length 字节即 EOF（即使底层存储能读更多）。
 */
public interface ContentHandle extends AutoCloseable {

    /** 内容总字节数（与 stat/元数据记录一致）。 */
    long size() throws IOException;

    /** 全量读取流。调用方负责关闭。 */
    InputStream readFully() throws IOException;

    /** 从 start 起读 length 字节（对应 HTTP Range 的单区间语义）。调用方负责关闭。 */
    InputStream readRange(long start, long length) throws IOException;

    /** 实现持有连接等共享资源时覆写；默认空实现（本地实现的每个流自持资源）。 */
    @Override
    default void close() {
    }
}
