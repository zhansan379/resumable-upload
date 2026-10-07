package com.example.upload.store.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/** MyBatis-Plus 版秒传索引 Mapper：CRUD 走 BaseMapper，建表走注解 DDL。 */
@Mapper
public interface UploadFileRecordPlusMapper extends BaseMapper<UploadFileRecordEntity> {

    @Update(UploadFileSchema.CREATE_RECORD_TABLE)
    void createTable();
}
