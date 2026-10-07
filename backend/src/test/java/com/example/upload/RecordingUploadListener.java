package com.example.upload;

import com.example.upload.store.FileRecord;
import com.example.upload.store.spi.UploadEventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件 SPI 录制器（测试专用，位于 test 源码目录，生产构建不含）。
 * 通过组件扫描注册进两个后端套件的上下文，断言生命周期回调确实被触发。
 */
@Component
public class RecordingUploadListener implements UploadEventListener {

    public static final List<String> mergeEvents = new CopyOnWriteArrayList<>();
    public static final List<String> deletedEvents = new CopyOnWriteArrayList<>();
    public static final Map<String, Boolean> verifyEvents = new ConcurrentHashMap<>();

    @Override
    public void onMergeCompleted(FileRecord record) {
        mergeEvents.add(record.getFileHash());
    }

    @Override
    public void onVerifyCompleted(FileRecord record, boolean passed) {
        verifyEvents.put(record.getFileHash(), passed);
    }

    @Override
    public void onDeleted(String fileHash) {
        deletedEvents.add(fileHash);
    }
}
