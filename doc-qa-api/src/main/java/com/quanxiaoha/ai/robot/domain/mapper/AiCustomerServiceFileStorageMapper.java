package com.quanxiaoha.ai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quanxiaoha.ai.robot.domain.dos.AiCustomerServiceFileStorageDO;

import java.time.LocalDate;
import java.util.Objects;

/**
 * @Author: 犬小哈
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
