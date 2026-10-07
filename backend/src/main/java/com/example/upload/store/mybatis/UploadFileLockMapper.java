package com.example.upload.store.mybatis;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 合并租约锁 Mapper（MyBatis 与 MyBatis-Plus 宿主共用，纯注解 SQL）。
 * key 为协议层作用域 ID（启用租户时为 {tenant}/{hash}）。
 */
@Mapper
public interface UploadFileLockMapper {

    @Update(UploadFileSchema.CREATE_LOCK_TABLE)
    void createTable();

    @Insert("INSERT INTO upload_file_lock (file_hash, locked_by, locked_until) "
            + "VALUES (#{fileHash}, #{lockedBy}, #{lockedUntil})")
    int insert(@Param("fileHash") String fileHash, @Param("lockedBy") String lockedBy,
               @Param("lockedUntil") long lockedUntil);

    /** 抢占已过期或本实例持有的锁：影响行数 > 0 视为获得 */
    @Update("UPDATE upload_file_lock SET locked_by = #{lockedBy}, locked_until = #{lockedUntil} "
            + "WHERE file_hash = #{fileHash} AND (locked_until < #{now} OR locked_by = #{lockedBy})")
    int takeOverExpired(@Param("fileHash") String fileHash, @Param("lockedBy") String lockedBy,
                        @Param("lockedUntil") long lockedUntil, @Param("now") long now);

    @Delete("DELETE FROM upload_file_lock WHERE file_hash = #{fileHash} AND locked_by = #{lockedBy}")
    int release(@Param("fileHash") String fileHash, @Param("lockedBy") String lockedBy);
}
