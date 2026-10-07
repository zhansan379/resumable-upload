package com.example.upload.store.local;

import com.example.upload.store.spi.MergeLock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** JVM 内合并锁（默认实现）：每个 key 一把监视器锁，单实例语义。 */
public class LocalMergeLock implements MergeLock {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withLock(String key, Supplier<T> action) {
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            return action.get();
        }
    }
}
