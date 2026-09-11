package com.quanxiaoha.ai.robot.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.unit.DataSizeUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Maps;
import com.quanxiaoha.ai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.quanxiaoha.ai.robot.domain.dos.FileChunkInfoDO;
import com.quanxiaoha.ai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.quanxiaoha.ai.robot.domain.mapper.FileChunkInfoMapper;
import com.quanxiaoha.ai.robot.enums.AiCustomerServiceFileStatusEnum;
import com.quanxiaoha.ai.robot.enums.ResponseCodeEnum;
import com.quanxiaoha.ai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.quanxiaoha.ai.robot.exception.BizException;
import com.quanxiaoha.ai.robot.model.vo.customerService.*;
import com.quanxiaoha.ai.robot.service.CustomerService;
import com.quanxiaoha.ai.robot.utils.PageResponse;
import com.quanxiaoha.ai.robot.utils.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author: 犬小哈
 * @Date: 2025/8/11 15:48
 * @Version: v1.0.0
 * @Description: AI 客服
 **/
@Service
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    @Value("${customer-service.file-storage-path}")
    private String fileStoragePath;

    @Value("${customer-service.chunk-path}")
    private String chunkPath;

    @Resource
    private AiCustomerServiceFileStorageMapper aiCustomerServiceFileStorageMapper;
    @Resource
    private ApplicationEventPublisher eventPublisher; // 注入事件发布器
    @Resource
    private VectorStore vectorStore;
    @Resource
    private FileChunkInfoMapper fileChunkInfoMapper;

//    /**
//     * 上传 Markdown 问答文件
//     *
//     * @param file
//     * @return
//     */
//    @Override
//    public Response<?> uploadMarkdownFile(MultipartFile file) {
//        // 校验文件不能为空
//        if (file == null || file.isEmpty()) {
//            throw new BizException(ResponseCodeEnum.UPLOAD_FILE_CANT_EMPTY);
//        }
//
//        // 获取原始文件名（去除空格）
//        String originalFilename = StringUtils.trimToEmpty(file.getOriginalFilename());
//
//        // 验证文件类型，仅支持 Markdown
//        if (StringUtils.isBlank(originalFilename) || !isMarkdownFile(originalFilename)) {
//            throw new BizException(ResponseCodeEnum.ONLY_SUPPORT_MARKDOWN);
//        }
//
//        try {
//            // 重新生成文件名 (防止文件名冲突导致覆盖)
//            String newFilename = UUID.randomUUID().toString() + "-" + originalFilename;
//
//            // 构建存储路径
//            Path storageDirectory = Paths.get(mdStoragePath);
//            Path targetPath = storageDirectory.resolve(newFilename);
//
//            // 确保目录存在
//            Files.createDirectories(storageDirectory);
//
//            // 保存文件
//            file.transferTo(targetPath.toFile());
//
//            // 记录操作日志
//            log.info("## Markdown 问答文件存储成功, 文件名：{} -> 存储路径：{}", originalFilename, targetPath);
//
//            // 存储入库
//            AiCustomerServiceFileStorageDO aiCustomerServiceFileStorageDO = AiCustomerServiceFileStorageDO.builder()
//                    .originalFileName(originalFilename)
//                    .newFileName(newFilename)
//                    .filePath(targetPath.toString())
//                    .fileSize(file.getSize())
//                    .status(AiCustomerServiceMdStatusEnum.PENDING.getCode())
//                    .createTime(LocalDateTime.now())
//                    .updateTime(LocalDateTime.now())
//                    .build();
//
//            aiCustomerServiceMdStorageMapper.insert(aiCustomerServiceFileStorageDO);
//
//            // 获取主键 ID
//            Long id =  aiCustomerServiceFileStorageDO.getId();
//
//            // 元数据
//            Map<String, Object> metadatas = Maps.newHashMap();
//            metadatas.put("mdStorageId", id); // 关联的文件存储表主键 ID
//            metadatas.put("originalFileName", originalFilename); // 文件原始名称
//
//            // 发布事件
//            eventPublisher.publishEvent(AiCustomerServiceMdUploadedEvent.builder()
//                            .id(id)
//                            .filePath(targetPath.toString())
//                            .metadatas(metadatas)
//                            .build());
//
//            return Response.success();
//
//        } catch (IOException e) {
//            log.error("## Markdown 问答文件上传失败：{}", originalFilename, e);
//            throw new BizException(ResponseCodeEnum.UPLOAD_FILE_FAILED);
//        }
//    }

    /**
     * 删除 Markdown 问答文件
     *
     * @param deleteMarkdownFileReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> deleteMarkdownFile(DeleteMarkdownFileReqVO deleteMarkdownFileReqVO) {
        // 文件记录 ID
        Long id = deleteMarkdownFileReqVO.getId();

        // 查询该文件记录
        AiCustomerServiceFileStorageDO aiCustomerServiceFileStorageDO = aiCustomerServiceFileStorageMapper.selectById(id);

        // 若记录不存在
        if (Objects.isNull(aiCustomerServiceFileStorageDO)) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NOT_FOUND);
        }

        // 正在处理中的文件，无法删除
        AiCustomerServiceFileStatusEnum statusEnum = AiCustomerServiceFileStatusEnum.codeOf(aiCustomerServiceFileStorageDO.getStatus());
        if (Objects.equals(statusEnum, AiCustomerServiceFileStatusEnum.PENDING) // 待向量化
                || Objects.equals(statusEnum, AiCustomerServiceFileStatusEnum.VECTORIZING)) { // 向量化中...
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_CANT_DELETE);
        }

        // 删除文件表记录
        aiCustomerServiceFileStorageMapper.deleteById(id);

        // 删除向量化数据
        vectorStore.delete(String.format("mdStorageId == %s", id));

        // 删除本地文件
        String filePath = aiCustomerServiceFileStorageDO.getFilePath();
        try {
            FileUtils.forceDelete(new File(filePath));
        } catch (IOException e) {
            log.error("## Markdown 问答文件删除失败：", e);
        }

        return Response.success();
    }

    /**
     * 分页查询 Markdown 问答文件
     *
     * @param findMarkdownFilePageListReqVO
     * @return
     */
    @Override
    public PageResponse<FindMarkdownFilePageListRspVO> findMarkdownFilePageList(FindMarkdownFilePageListReqVO findMarkdownFilePageListReqVO) {
        // 获取当前页、以及每页需要展示的数据数量
        Long current = findMarkdownFilePageListReqVO.getCurrent();
        Long size = findMarkdownFilePageListReqVO.getSize();

        String fileName = findMarkdownFilePageListReqVO.getFileName();
        // 起始结束日期
        LocalDate startDate = findMarkdownFilePageListReqVO.getStartDate();
        LocalDate endDate = findMarkdownFilePageListReqVO.getEndDate();

        // 结束日期加一天，确保结束日期包含当天数据
        if (Objects.nonNull(endDate)) {
            endDate = endDate.plusDays(1);
        }

        // 执行分页查询
        Page<AiCustomerServiceFileStorageDO> mdStorageDOPage = aiCustomerServiceFileStorageMapper
                .selectPageList(current, size, fileName, startDate, endDate);

        List<AiCustomerServiceFileStorageDO> mdStorageDOS = mdStorageDOPage.getRecords();
        // DO 转 VO
        List<FindMarkdownFilePageListRspVO> vos = null;
        if (CollUtil.isNotEmpty(mdStorageDOS)) {
            vos = mdStorageDOS.stream()
                    .map(mdStorageDO -> FindMarkdownFilePageListRspVO.builder() // 构建返参 VO 实体类
                            .id(mdStorageDO.getId())
                            .originalFileName(mdStorageDO.getFileName())
                            .fileSize(DataSizeUtil.format(mdStorageDO.getFileSize())) // Hutool 工具库提供的字节转换
                            .status(mdStorageDO.getStatus())
                            .createTime(mdStorageDO.getCreateTime())
                            .updateTime(mdStorageDO.getUpdateTime())
                            .remark(mdStorageDO.getRemark())
                            .build())
                    .collect(Collectors.toList());
        }

        return PageResponse.success(mdStorageDOPage, vos);
    }

    /**
     * 修改  Markdown 问答文件信息
     *
     * @param updateMarkdownFileReqVO
     * @return
     */
    @Override
    public Response<?> updateMarkdownFile(UpdateMarkdownFileReqVO updateMarkdownFileReqVO) {
        // 文件 ID
        Long id = updateMarkdownFileReqVO.getId();
        // 备注
        String remark = updateMarkdownFileReqVO.getRemark();

        // 根据 ID 修改备注信息
        int count = aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder()
                        .id(id)
                        .remark(remark)
                        .updateTime(LocalDateTime.now())
                        .build());

        // 若影响的行数为 0， 说明该文件记录不存在
        if (count == 0 ) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NOT_FOUND);
        }

        return Response.success();
    }

    /**
     * 检查文件是否存在
     *
     * @param checkFileReqVO
     * @return
     */
    @Override
    public Response<CheckFileRspVO> checkFile(CheckFileReqVO checkFileReqVO) {
        String fileMd5 = checkFileReqVO.getFileMd5();
        // 查询对应 MD5 值的文件记录是否已经存在
        AiCustomerServiceFileStorageDO fileStorageDO = aiCustomerServiceFileStorageMapper
                .selectByMd5(fileMd5);

        // 文件记录不存在，需要上传
        if (Objects.isNull(fileStorageDO)) {
            return Response.success(CheckFileRspVO.builder()
                    .exists(false)
                    .needUpload(true)
                    .build());
        }

        // 若文件记录已存在
        Integer status = fileStorageDO.getStatus();
        AiCustomerServiceFileStatusEnum statusEnum = AiCustomerServiceFileStatusEnum.codeOf(status);

        // 判断当前处理状态
        // 文件已完整上传，支持秒传
        if (!Objects.equals(statusEnum, AiCustomerServiceFileStatusEnum.UPLOADING)) {
            return Response.success(CheckFileRspVO.builder()
                    .exists(true)
                    .needUpload(false)
                    .build());
        }

        // 文件正在上传中，返回已上传的分片序号
        List<FileChunkInfoDO> chunks = fileChunkInfoMapper.selecChunkedtList(fileMd5);
        List<Integer> uploadedChunks = chunks.stream()
                .map(FileChunkInfoDO::getChunkNumber)
                .toList();

        return Response.success(CheckFileRspVO.builder()
                .exists(true)
                .needUpload(true)
                .uploadedChunks(uploadedChunks)
                .build());
    }

    /**
     * 文件分片上传
     *
     * @param uploadChunkReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> uploadChunk(UploadChunkReqVO uploadChunkReqVO) {
        String fileMd5 = uploadChunkReqVO.getFileMd5();
        Integer chunkNumber = uploadChunkReqVO.getChunkNumber();
        MultipartFile chunk = uploadChunkReqVO.getChunk();

        // 检查当前分片是否已上传
        Long count = fileChunkInfoMapper.selectCountByMd5AndChunkNum(fileMd5, chunkNumber);
        if (count > 0) {
            log.info("## 分片已存在: fileMd5={}, chunkNumber={}", fileMd5, chunkNumber);
            return Response.success();
        }

        // 创建分片目录（确保父目录也存在）
        String chunkDir = chunkPath + File.separator + fileMd5;
        File chunkDirFile = new File(chunkDir);
        try {
            FileUtils.forceMkdir(chunkDirFile);
        } catch (IOException e) {
            log.error("## 创建分片目录失败: {}", chunkDir);
            throw new RuntimeException(e);
        }

        // 保存分片文件到本地
        String chunkFileName = chunkNumber + ".chunk";
        File chunkFile = new File(chunkDirFile, chunkFileName);
        try {
            chunk.transferTo(chunkFile);
        } catch (IOException e) {
            log.error("## 保存分片文件失败: {}", chunkFileName);
            throw new RuntimeException(e);
        }

        // 保存分片记录
        FileChunkInfoDO chunkInfo = FileChunkInfoDO.builder()
                .fileMd5(fileMd5)
                .chunkNumber(chunkNumber)
                .chunkPath(chunkFile.getAbsolutePath()) // 分片文件存储路径
                .chunkSize(chunk.getSize())
                .build();
        fileChunkInfoMapper.insert(chunkInfo);

        // 查询当前 MD5 对应的文件是否存在
        AiCustomerServiceFileStorageDO fileStorageDO = aiCustomerServiceFileStorageMapper.selectByMd5(fileMd5);

        // 已上传的分片数，默认为 1
        int uploadedChunks = 1;

        // 若不存在，写入数据
        if (Objects.isNull(fileStorageDO)) {
            fileStorageDO = AiCustomerServiceFileStorageDO.builder()
                    .fileMd5(fileMd5)
                    .fileName(uploadChunkReqVO.getFileName())
                    .fileSize(uploadChunkReqVO.getFileSize()) // 原始文件大小
                    .totalChunks(uploadChunkReqVO.getTotalChunks())
                    .uploadedChunks(uploadedChunks) // 默认已上传分片数为 1
                    .status(AiCustomerServiceFileStatusEnum.UPLOADING.getCode()) // 状态：上传中...
                    .filePath(Strings.EMPTY)
                    .build();
            aiCustomerServiceFileStorageMapper.insert(fileStorageDO);
        } else { // 存在，则进行更新操作，将已上传分片数 +1
            aiCustomerServiceFileStorageMapper.incrementUploadedChunks(fileStorageDO.getId());
            uploadedChunks += fileStorageDO.getUploadedChunks();
        }

        log.info("## 分片上传成功: fileMd5={}, chunkNumber={}, progress={}/{}",
                fileMd5, chunkNumber, uploadedChunks, fileStorageDO.getTotalChunks());

        return Response.success();
    }

    /**
     * 文件分片合并
     *
     * @param mergeChunkReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> mergeChunk(MergeChunkReqVO mergeChunkReqVO) {
        String fileMd5 = mergeChunkReqVO.getFileMd5();

        // 检查文件元记录是否存在
        AiCustomerServiceFileStorageDO fileStorageDO = aiCustomerServiceFileStorageMapper.selectByMd5(fileMd5);

        // 要合并的目标文件不存在
        if (Objects.isNull(fileStorageDO)) {
            throw new BizException(ResponseCodeEnum.MERGE_CHUNK_NOT_FOUND);
        }

        // 查询所有已上传分片
        List<FileChunkInfoDO> chunks = fileChunkInfoMapper.selecChunkedtList(fileMd5);

        // 若已上传分片数不等于总分片数，说明分片数不完整
        if (chunks.size() != fileStorageDO.getTotalChunks()) {
            throw new BizException(ResponseCodeEnum.CHUNK_NUM_NOT_COMPLETE);
        }

        // 创建文件目录
        File uploadDir = new File(fileStoragePath);
        try {
            FileUtils.forceMkdir(uploadDir);
        } catch (IOException e) {
            log.error("## 创建文件合并目录失败: {}", uploadDir);
            throw new RuntimeException(e);
        }

        // 合并文件的名称
        String finalFileName = System.currentTimeMillis() + "_" + fileStorageDO.getFileName();
        // 新建合并文件
        File finalFile = new File(uploadDir, finalFileName);

        // 合并分片
        try (FileOutputStream fos = new FileOutputStream(finalFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            for (FileChunkInfoDO chunkInfo : chunks) {
                // 读取分片文件
                File chunkFile = new File(chunkInfo.getChunkPath());
                try (FileInputStream fis = new FileInputStream(chunkFile);
                     BufferedInputStream bis = new BufferedInputStream(fis)) {

                    // 分块读取（8kb 缓冲区），减少IO操作次数，同时避免一次性加载所有分片到内存，导致内存占满
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, len);
                    }
                }
            }
        } catch (Exception e) {
            log.error("## 合并文件失败: ", e);
            throw new RuntimeException(e);
        }

        // 更新文件信息
        aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder()
                .id(fileStorageDO.getId())
                .status(AiCustomerServiceFileStatusEnum.PENDING.getCode()) // 合并完成，等待向量化
                .filePath(finalFile.getAbsolutePath())
                .build());

        // 删除分片文件所在的目录和记录
        String chunkDir = chunkPath + File.separator + fileMd5;
        try {
            FileUtils.forceDelete(new File(chunkDir));
        } catch (IOException e) {
            log.error("## 删除分片文件失败: ", e);
            throw new RuntimeException(e);
        }

        fileChunkInfoMapper.deleteByMd5(fileMd5);

        log.info("## 文件合并成功: fileMd5={}, filePath={}", fileMd5, finalFile.getAbsolutePath());

        // 合并完成后，发布事件，进行向量化处理
        // 获取主键 ID
        Long id =  fileStorageDO.getId();

        // 元数据
        Map<String, Object> metadatas = Maps.newHashMap();
        metadatas.put("mdStorageId", id); // 关联的文件存储表主键 ID
        metadatas.put("originalFileName", fileStorageDO.getFileName()); // 文件原始名称

        // 发布事件
        eventPublisher.publishEvent(AiCustomerServiceMdUploadedEvent.builder()
                .id(id)
                .filePath(finalFile.getAbsolutePath())
                .metadatas(metadatas)
                .build());

        return Response.success();
    }

    /**
     * 验证文件是否为 Markdown 格式
     */
    private boolean isMarkdownFile(String filename) {
        if (StringUtils.isBlank(filename)) {
            return false;
        }

        // 获取文件扩展名
        String extension = FilenameUtils.getExtension(filename);
        return StringUtils.equalsIgnoreCase(extension, "md");
    }
}
