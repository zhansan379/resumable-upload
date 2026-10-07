package com.example.upload.store.spi;

/**
 * 已合并文件的存储定位信息。
 * locator 是后端私有的存储地址：本地实现 = 相对上传根目录的路径；S3 实现 = 对象 bucket 内 key。
 * 协议层只透传 locator（存入元数据、回传给 FileStorage），绝不解析它。
 */
public record StoredObject(String locator, long size) {
}
