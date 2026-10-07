package com.example.upload.store.spi;

import com.example.upload.store.FileRecord;

import java.util.List;

/**
 * 元数据（秒传索引）SPI：fileHash → 已合并文件记录。
 * 协议层用它做秒传判定、文件列表与删除；locator 字段是后端私有的存储地址，只透传不解析。
 * 实现必须持久化（进程重启不丢索引），写入需防损坏（临时文件 + 原子替换或事务存储）。
 * <p>
 * S3 部署下的推荐实现是 JDBC（本地 JSON 实现无法多实例共享）。
 */
public interface MetadataStore {

    /** 记录不存在时返回 null（沿用协议层现有判空语义）。 */
    FileRecord get(String hash);

    boolean containsKey(String hash);

    void put(FileRecord record);

    /** 合并后异步 MD5 复核回写。记录不存在时静默忽略。 */
    void updateVerified(String hash, boolean verified);

    /** @return 删除前记录是否存在。 */
    boolean remove(String hash);

    /** 全量记录（文件列表用），实现自行保证快照语义。 */
    List<FileRecord> all();
}
