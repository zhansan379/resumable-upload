package com.example.upload.store.spi;

import java.util.function.Supplier;

/**
 * 合并互斥锁 SPI。协议层在合并关键区（分片校验 + 固化 + 索引写入）前必须持有锁，
 * 防止同一文件被并发合并。key 是协议层的作用域 ID（启用租户时为 {tenant}/{hash}）。
 * <ul>
 *   <li>本地实现：JVM 内 synchronized（单实例语义）；</li>
 *   <li>JDBC 实现：数据库租约锁（TTL 抢占），支撑多实例部署。</li>
 * </ul>
 * 实现约定：action 内抛出的异常原样向上传播，锁必须保证释放（finally）。
 */
public interface MergeLock {

    <T> T withLock(String key, Supplier<T> action);
}
