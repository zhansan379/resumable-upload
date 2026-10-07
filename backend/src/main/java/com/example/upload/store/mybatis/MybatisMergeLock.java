package com.example.upload.store.mybatis;

import com.example.upload.exception.BusinessException;
import com.example.upload.store.spi.MergeLock;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 数据库租约锁（MyBatis/MyBatis-Plus 宿主共用）：支撑多实例部署下的合并互斥。
 * 两级加锁：本地先按 key 串行（同实例线程互斥），再拿数据库租约（跨实例互斥）。
 * 语义：INSERT 抢占；主键冲突时若租约已过期（TTL）或本实例持有则接管；
 * 等待超过 60s 仍拿不到 → 409（另一节点正在合并同一文件）。
 * 注意 TTL 必须大于合并关键区最长耗时（S3 后端关键区只做校验 + complete，秒级）。
 */
public class MybatisMergeLock implements MergeLock {

    private static final Logger log = LoggerFactory.getLogger(MybatisMergeLock.class);

    /** 租约时长与等待上限（毫秒） */
    static final long LEASE_MILLIS = 60_000;
    static final long WAIT_MILLIS = 60_000;
    private static final long POLL_INTERVAL_MILLIS = 150;

    private final UploadFileLockMapper mapper;
    private final String ownerId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, Object> localGates = new ConcurrentHashMap<>();

    public MybatisMergeLock(UploadFileLockMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        mapper.createTable();
        log.info("合并锁表 upload_file_lock 就绪（实例 owner={}）", ownerId);
    }

    @Override
    public <T> T withLock(String key, Supplier<T> action) {
        synchronized (localGates.computeIfAbsent(key, k -> new Object())) {
            long deadline = System.currentTimeMillis() + WAIT_MILLIS;
            while (!acquire(key)) {
                if (System.currentTimeMillis() >= deadline) {
                    throw BusinessException.conflict("同一文件正在其他节点合并，请稍后重试");
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw BusinessException.conflict("等待合并锁被中断");
                }
            }
            try {
                return action.get();
            } finally {
                mapper.release(key, ownerId);
            }
        }
    }

    private boolean acquire(String key) {
        try {
            mapper.insert(key, ownerId, System.currentTimeMillis() + LEASE_MILLIS);
            return true;
        } catch (DuplicateKeyException e) {
            // 主键冲突 = 已被持有：租约过期或本实例持有则接管，否则继续等
            return mapper.takeOverExpired(key, ownerId, System.currentTimeMillis() + LEASE_MILLIS,
                    System.currentTimeMillis()) > 0;
        }
    }
}
