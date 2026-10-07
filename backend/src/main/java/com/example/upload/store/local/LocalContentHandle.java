package com.example.upload.store.local;

import com.example.upload.store.spi.ContentHandle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本地文件只读句柄。每个读取方法独立开 FileChannel，
 * Range 流被限定为最多读取 length 字节（读完即 EOF），与 HTTP 单区间语义一致。
 */
public record LocalContentHandle(Path file) implements ContentHandle {

    @Override
    public long size() throws IOException {
        return Files.size(file);
    }

    @Override
    public InputStream readFully() throws IOException {
        return Files.newInputStream(file, StandardOpenOption.READ);
    }

    @Override
    public InputStream readRange(long start, long length) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        channel.position(start);
        return new InputStream() {
            private long remaining = length;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = channel.read(ByteBuffer.allocate(1));
                if (b < 0) {
                    remaining = 0;
                    return -1;
                }
                remaining--;
                return b & 0xFF;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = channel.read(ByteBuffer.wrap(buf, off, (int) Math.min(len, remaining)));
                if (n < 0) {
                    remaining = 0;
                    return -1;
                }
                remaining -= n;
                return n;
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }
}
