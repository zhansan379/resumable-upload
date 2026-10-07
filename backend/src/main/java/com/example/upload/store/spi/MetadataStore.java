package com.example.upload.store.spi;

import com.example.upload.store.FileRecord;

import java.util.List;

/**
 * 元数据（秒传索引）SPI：租户 + fileHash → 已合并文件记录。
 * 协议层用它做秒传判定、文件列表与删除；locator 字段是后端私有的存储地址，只透传不解析。
 * 实现必须持久化（进程重启不丢索引），写入需防损坏（临时文件 + 原子替换或事务存储）。
 * <p>
 * 租户语义：未启用租户时 tenant 为 null，行为与历史版本完全一致；
 * 启用后秒传/删除/复核只在同租户域内生效。JSON 实现单实例；多实例部署用 JDBC 实现。
 */
public interface MetadataStore {

    /** 记录不存在时返回 null（沿用协议层现有判空语义）。 */
    FileRecord get(String tenant, String hash);

    boolean containsKey(String tenant, String hash);

    /** 租户信息取自 record 本身（record.getTenant()）。 */
    void put(FileRecord record);

    /** 合并后异步 MD5 复核回写。记录不存在时静默忽略。 */
    void updateVerified(String tenant, String hash, boolean verified);

    /** @return 删除前记录是否存在。 */
    boolean remove(String tenant, String hash);

    /** 全量记录（跨租户；协议层的文件列表按需过滤），实现自行保证快照语义。 */
    List<FileRecord> all();
}
