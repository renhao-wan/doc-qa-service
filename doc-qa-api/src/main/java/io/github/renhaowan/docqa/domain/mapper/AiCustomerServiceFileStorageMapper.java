package io.github.renhaowan.docqa.domain.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.renhaowan.docqa.domain.dos.AiCustomerServiceFileStorageDO;
import org.apache.ibatis.annotations.Insert;

import java.time.LocalDate;
import java.util.Objects;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/8/11 11:36
 * @Version: v1.0.0
 * @Description: TODO
 **/
public interface AiCustomerServiceFileStorageMapper extends BaseMapper<AiCustomerServiceFileStorageDO> {

    /**
     * 分页查询
     * @param current
     * @param size
     * @return
     */
    default Page<AiCustomerServiceFileStorageDO> selectPageList(Long current, Long size, String fileName, LocalDate startDate, LocalDate endDate) {
        // 分页对象(查询第几页、每页多少数据)
        Page<AiCustomerServiceFileStorageDO> page = new Page<>(current, size);

        // 构建查询条件
        LambdaQueryWrapper<AiCustomerServiceFileStorageDO> wrapper = Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .like(StringUtils.isNotBlank(fileName), AiCustomerServiceFileStorageDO::getFileName, fileName) // like 模块查询
                .ge(Objects.nonNull(startDate), AiCustomerServiceFileStorageDO::getCreateTime, startDate) // 大于等于 startDate
                .le(Objects.nonNull(endDate), AiCustomerServiceFileStorageDO::getCreateTime, endDate)  // 小于等于 endDate
                .orderByDesc(AiCustomerServiceFileStorageDO::getCreateTime); // 按创建时间倒叙

        return selectPage(page, wrapper);
    }

    /**
     * 根据文件 MD5 值查询
     * @param fileMd5
     * @return
     */
    default AiCustomerServiceFileStorageDO selectByMd5(String fileMd5) {
        return selectOne(Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .eq(AiCustomerServiceFileStorageDO::getFileMd5, fileMd5));
    }

    /**
     * 幂等写入文件主记录：若 file_md5 已存在，则什么都不做
     * <p>
     * 与分片表的唯一索引同理——{@code uploadChunk} 原来是「先 selectByMd5 判空再 insert」，
     * 前端 3 路并发上传时，几个线程会同时读到 null、同时插入，
     * 撞上 uk_file_storage_md5 后抛 DuplicateKeyException，接口直接失败。
     * <p>
     * ⚠️ 用 ON CONFLICT 而不是捕获异常，原因见 {@link FileChunkInfoMapper#insertChunkIgnoreDuplicate}：
     * PostgreSQL 中约束冲突会让整个事务进入 aborted 状态，catch 住也没用。
     *
     * @param fileStorageDO 文件主记录
     * @return 影响行数：1 = 本次创建，0 = 已被并发请求创建
     */
    @Insert("""
            INSERT INTO t_ai_customer_service_file_storage
                (file_md5, file_name, file_path, file_size, total_chunks, uploaded_chunks, status, create_time, update_time)
            VALUES
                (#{fileMd5}, #{fileName}, #{filePath}, #{fileSize}, #{totalChunks}, #{uploadedChunks}, #{status}, #{createTime}, #{updateTime})
            ON CONFLICT (file_md5) DO NOTHING
            """)
    int insertFileIgnoreDuplicate(AiCustomerServiceFileStorageDO fileStorageDO);

    /**
     * 已上传分片数 +1
     * @param id
     * @return
     */
    default int incrementUploadedChunks(Long id) {
        return update(Wrappers.<AiCustomerServiceFileStorageDO>lambdaUpdate()
                .eq(AiCustomerServiceFileStorageDO::getId, id)
                .setSql("uploaded_chunks = uploaded_chunks + 1"));
    }

}
