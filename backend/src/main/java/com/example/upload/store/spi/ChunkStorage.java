package com.example.upload.store.spi;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * 分片存储 SPI。以 fileHash 为协议身份组织分片，不感知合并语义。
 * <p>
 * 实现约定（两种后端都成立）：
 * <ul>
 *   <li>{@link #save} 必须幂等：同一 (fileHash, chunkIndex) 重复写入以后到者为准，
 *       且"半个分片"不得被 {@link #list} 观察为已传（本地实现 = 临时文件 + 原子移动；
 *       S3 实现 = uploadPart，以 ETag 登记为准）；</li>
 *   <li>{@link #list} 只返回"确认完整"的分片，按编号升序；</li>
 *   <li>分片状态以存储端为事实源，进程重启不丢（磁盘/对象存储天然满足）。</li>
 * </ul>
 * <p>
 * S3 实现提示：save 映射为 multipart upload 的 uploadPart（首次懒创建 multipart 会话），
 * deleteAll 映射为 abortMultipartUpload，deleteOrphansOlderThan 映射为
 * ListMultipartUploads + abort 过期会话。
 */
public interface ChunkStorage {

    /**
     * 保存一个分片，返回确认落盘的字节数。
     * 分片参数的合法性（index 范围、内容非空）由调用方（协议层）校验；
     * contentLength 为分片字节数（S3 的 uploadPart 必需，实现可据此做落盘一致性检查）。
     */
    long save(String fileHash, int chunkIndex, int totalChunks, long contentLength, InputStream in)
            throws IOException;

    /** 已存在的分片列表（编号升序，含每片字节数）。没有任何分片时返回空列表而非异常。 */
    List<ChunkInfo> list(String fileHash) throws IOException;

    /** 删除该 hash 的全部分片（取消上传 / 合并完成后清理）。实现自行吞并 IO 异常（记日志），不向上抛。 */
    void deleteAll(String fileHash);

    /**
     * 清理"孤儿分片"：客户端消失后无人调用删除，超过 deadline 未再更新的分片被清除。
     * 本地实现按分片目录最后修改时间判定；返回清理的分片组（目录/上传会话）个数。
     */
    int deleteOrphansOlderThan(Instant deadline);

    /* ---------------- 能力声明（协议层据此收紧校验） ---------------- */

    /**
     * 非末片分片的最小字节数。S3 multipart 要求除最后一个分片外每片 ≥ 5MiB，
     * 协议层在分片保存时前置拒绝，避免到合并阶段才失败。
     * 本地磁盘默认 1（不限制）。
     */
    default long minNonLastPartSize() {
        return 1;
    }

    /**
     * 分片数量上限。S3 multipart 上限 10000 片；协议层取与 app.upload.max-total-chunks 的较小值。
     * 本地磁盘默认无额外限制（Integer.MAX_VALUE，由配置项约束）。
     */
    default int maxChunks() {
        return Integer.MAX_VALUE;
    }
}
