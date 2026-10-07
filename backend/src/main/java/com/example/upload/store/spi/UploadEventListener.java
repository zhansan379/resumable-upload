package com.example.upload.store.spi;

import com.example.upload.store.FileRecord;

/**
 * 上传生命周期事件 SPI：集成方（业务系统）订阅这些回调做自己的事——
 * 入库、通知、统计、触发后续流程。方法全部默认空实现，按需覆写；
 * 回调在协议层同步调用且逐个隔离（单个监听器抛异常只记日志，不影响上传主流程）。
 */
public interface UploadEventListener {

    /** 合并成功且已入索引（秒传命中的重复合并不触发）。 */
    default void onMergeCompleted(FileRecord record) {
    }

    /** 合并后异步 MD5 复核有了结论（通过或失败都触发）。 */
    default void onVerifyCompleted(FileRecord record, boolean passed) {
    }

    /** 文件记录被删除（含存储侧清理）。 */
    default void onDeleted(String fileHash) {
    }
}
