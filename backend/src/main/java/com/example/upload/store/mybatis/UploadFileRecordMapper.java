package com.example.upload.store.mybatis;

import com.example.upload.store.FileRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 纯 MyBatis 注解版秒传索引 Mapper（无 XML、无 MyBatis-Plus 依赖）。
 * 列别名对齐 FileRecord 属性名，不依赖 mapUnderscoreToCamelCase 配置。
 * MyBatis-Plus 宿主同样可用（@Mapper 扫描由两家的 starter 都支持），
 * 但 type=mybatis-plus 时优先使用 MP 专属实现以遵循其习惯用法。
 */
@Mapper
public interface UploadFileRecordMapper {

    String COLUMNS = "tenant_id AS tenant, file_hash AS fileHash, file_name AS fileName, "
            + "size AS size, stored_path AS storedPath, upload_time AS uploadTime, verified AS verified";

    @Update(UploadFileSchema.CREATE_RECORD_TABLE)
    void createTable();

    @Select("SELECT " + COLUMNS + " FROM upload_file_record WHERE tenant_id = #{tenantId} AND file_hash = #{fileHash}")
    FileRecord selectOne(@Param("tenantId") String tenantId, @Param("fileHash") String fileHash);

    @Select("SELECT COUNT(*) > 0 FROM upload_file_record WHERE tenant_id = #{tenantId} AND file_hash = #{fileHash}")
    boolean exists(@Param("tenantId") String tenantId, @Param("fileHash") String fileHash);

    @Insert("INSERT INTO upload_file_record "
            + "(tenant_id, file_hash, file_name, size, stored_path, upload_time, verified) "
            + "VALUES (#{r.tenant}, #{r.fileHash}, #{r.fileName}, #{r.size}, #{r.storedPath}, #{r.uploadTime}, #{r.verified})")
    int insert(@Param("r") FileRecord record);

    @Update("UPDATE upload_file_record SET file_name = #{r.fileName}, size = #{r.size}, stored_path = #{r.storedPath}, "
            + "upload_time = #{r.uploadTime}, verified = #{r.verified} "
            + "WHERE tenant_id = #{r.tenant} AND file_hash = #{r.fileHash}")
    int update(@Param("r") FileRecord record);

    @Update("UPDATE upload_file_record SET verified = #{verified} WHERE tenant_id = #{tenantId} AND file_hash = #{fileHash}")
    int updateVerified(@Param("tenantId") String tenantId, @Param("fileHash") String fileHash, @Param("verified") boolean verified);

    @Delete("DELETE FROM upload_file_record WHERE tenant_id = #{tenantId} AND file_hash = #{fileHash}")
    int delete(@Param("tenantId") String tenantId, @Param("fileHash") String fileHash);

    @Select("SELECT " + COLUMNS + " FROM upload_file_record")
    List<FileRecord> selectAll();
}
