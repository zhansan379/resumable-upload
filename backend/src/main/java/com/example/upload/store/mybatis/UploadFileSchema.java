package com.example.upload.store.mybatis;

/**
 * 元数据与合并锁的建表语句（MyBatis 与 MyBatis-Plus 两个实现共用，保持表结构一致）。
 * 列类型刻意选各库通用子集：BOOLEAN（MySQL 自动映射 TINYINT）、BIGINT、VARCHAR。
 */
public final class UploadFileSchema {

    private UploadFileSchema() {
    }

    public static final String CREATE_RECORD_TABLE = "CREATE TABLE IF NOT EXISTS upload_file_record ("
            + "tenant_id VARCHAR(64) NOT NULL DEFAULT '',"
            + "file_hash VARCHAR(64) NOT NULL,"
            + "file_name VARCHAR(512) NOT NULL,"
            + "size BIGINT NOT NULL,"
            + "stored_path VARCHAR(768) NOT NULL,"
            + "upload_time BIGINT NOT NULL,"
            + "verified BOOLEAN NOT NULL,"
            + "PRIMARY KEY (tenant_id, file_hash))";

    public static final String CREATE_LOCK_TABLE = "CREATE TABLE IF NOT EXISTS upload_file_lock ("
            + "file_hash VARCHAR(200) NOT NULL,"
            + "locked_by VARCHAR(64) NOT NULL,"
            + "locked_until BIGINT NOT NULL,"
            + "PRIMARY KEY (file_hash))";
}
