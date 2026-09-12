package io.github.renhaowan.docqa.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.unit.DataSizeUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Maps;
import io.github.renhaowan.docqa.domain.dos.KnowledgeBaseFileDO;
import io.github.renhaowan.docqa.domain.dos.KnowledgeBaseChunkDO;
import io.github.renhaowan.docqa.domain.mapper.KnowledgeBaseFileMapper;
import io.github.renhaowan.docqa.domain.mapper.KnowledgeBaseChunkMapper;
import io.github.renhaowan.docqa.enums.KnowledgeBaseFileStatusEnum;
import io.github.renhaowan.docqa.enums.ResponseCodeEnum;
import io.github.renhaowan.docqa.event.KnowledgeBaseFileUploadedEvent;
import io.github.renhaowan.docqa.exception.BizException;
import io.github.renhaowan.docqa.model.vo.knowledgeBase.*;
import io.github.renhaowan.docqa.service.KnowledgeBaseService;
import io.github.renhaowan.docqa.utils.AuthContext;
import io.github.renhaowan.docqa.utils.PageResponse;
import io.github.renhaowan.docqa.utils.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/8/11 15:48
 * @Version: v1.0.0
 * @Description: 企业知识库
 **/
@Service
@Slf4j
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    @Value("${knowledge-base.file-storage-path}")
    private String fileStoragePath;

    @Value("${knowledge-base.chunk-path}")
    private String chunkPath;

    @Resource
    private KnowledgeBaseFileMapper aiKnowledgeBaseFileStorageMapper;
    @Resource
    private ApplicationEventPublisher eventPublisher; // 注入事件发布器
    @Resource
    private VectorStore vectorStore;
    @Resource
    private KnowledgeBaseChunkMapper knowledgeBaseChunkMapper;

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
        KnowledgeBaseFileDO aiKnowledgeBaseFileStorageDO = aiKnowledgeBaseFileStorageMapper.selectById(id);

        // 若记录不存在
        if (Objects.isNull(aiKnowledgeBaseFileStorageDO)) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NOT_FOUND);
        }

        // 只有上传者本人能删除。这里刻意返回「无权操作」而不是「文件不存在」：
        // 文件在列表里人人可见，谎称不存在只会让用户困惑——与对话侧的处理正好相反
        // （对话是私有的，越权时谎称不存在才能真正不泄露其存在性，见 ChatServiceImpl）。
        if (!Objects.equals(aiKnowledgeBaseFileStorageDO.getUploaderId(), AuthContext.getCurrentUserId())) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NO_PERMISSION);
        }

        // 正在处理中的文件，无法删除
        KnowledgeBaseFileStatusEnum statusEnum = KnowledgeBaseFileStatusEnum.codeOf(aiKnowledgeBaseFileStorageDO.getStatus());
        if (Objects.equals(statusEnum, KnowledgeBaseFileStatusEnum.PENDING) // 待向量化
                || Objects.equals(statusEnum, KnowledgeBaseFileStatusEnum.VECTORIZING)) { // 向量化中...
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_CANT_DELETE);
        }

        // 删除文件表记录
        aiKnowledgeBaseFileStorageMapper.deleteById(id);

        // 删除向量化数据
        vectorStore.delete(String.format("mdStorageId == %s", id));

        // 删除本地文件。记录里只存了文件名，所在目录由 file-storage-path 推导
        // （绝对化规则与合并时保持一致，见 mergeChunk）
        String storedFileName = aiKnowledgeBaseFileStorageDO.getStoredFileName();

        // ⚠️ 必须判空：UPLOADING 状态下还没合并出文件，stored_file_name 是空串，
        // 而 new File(dir, "") 指向的是存储目录**本身**，FileUtils.forceDelete 对目录
        // 是递归删除——不拦住的话会把整个文件存储目录删掉
        if (Objects.nonNull(storedFileName) && !storedFileName.isBlank()) {
            File storedFile = new File(Paths.get(fileStoragePath).toAbsolutePath().normalize().toFile(), storedFileName);
            try {
                FileUtils.forceDelete(storedFile);
            } catch (IOException e) {
                log.error("## Markdown 问答文件删除失败：{}", storedFileName, e);
            }
        }

        // 清理分片残留：删除「上传中」（UPLOADING）的文件时，磁盘上的分片文件
        // 与 t_knowledge_base_chunk 记录必须一并回收——该状态是放行删除的，所以这是常规路径。
        // ⚠️ 不清理的话该 MD5 会永久卡死：重传时 checkFile 返回「需上传」，
        //    但 uploadChunk 的幂等快速路径只认分片表、直接返回成功，主记录永远重建不出来，
        //    于是 mergeChunk 恒报 MERGE_CHUNK_NOT_FOUND(20006)，怎么传都合并不了。
        String fileMd5 = aiKnowledgeBaseFileStorageDO.getFileMd5();
        knowledgeBaseChunkMapper.deleteByMd5(fileMd5);

        // 分片目录由 chunk-path + fileMd5 推导（与 uploadChunk / mergeChunk 的拼法保持一致）
        File chunkDirFile = Paths.get(chunkPath, fileMd5).toAbsolutePath().normalize().toFile();
        if (chunkDirFile.exists()) {
            try {
                FileUtils.forceDelete(chunkDirFile);
            } catch (IOException e) {
                log.error("## 分片目录删除失败：{}", chunkDirFile, e);
            }
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
        Page<KnowledgeBaseFileDO> mdStorageDOPage = aiKnowledgeBaseFileStorageMapper
                .selectPageList(current, size, fileName, startDate, endDate);

        List<KnowledgeBaseFileDO> mdStorageDOS = mdStorageDOPage.getRecords();
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

        // 先查记录：改备注同样只有上传者本人有权。
        // 原来直接 updateById 再看影响行数，拿不到 uploader_id，无法做归属判断。
        KnowledgeBaseFileDO record = aiKnowledgeBaseFileStorageMapper.selectById(id);

        if (Objects.isNull(record)) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NOT_FOUND);
        }

        if (!Objects.equals(record.getUploaderId(), AuthContext.getCurrentUserId())) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NO_PERMISSION);
        }

        // 根据 ID 修改备注信息
        int count = aiKnowledgeBaseFileStorageMapper.updateById(KnowledgeBaseFileDO.builder()
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
        KnowledgeBaseFileDO fileStorageDO = aiKnowledgeBaseFileStorageMapper
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
        KnowledgeBaseFileStatusEnum statusEnum = KnowledgeBaseFileStatusEnum.codeOf(status);

        // 判断当前处理状态
        // 文件已完整上传，支持秒传
        if (!Objects.equals(statusEnum, KnowledgeBaseFileStatusEnum.UPLOADING)) {
            return Response.success(CheckFileRspVO.builder()
                    .exists(true)
                    .needUpload(false)
                    .build());
        }

        // 文件正在上传中，返回已上传的分片序号
        List<KnowledgeBaseChunkDO> chunks = knowledgeBaseChunkMapper.selecChunkedtList(fileMd5);
        List<Integer> uploadedChunks = chunks.stream()
                .map(KnowledgeBaseChunkDO::getChunkNumber)
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

        // 快速路径：分片已存在，直接幂等返回（省掉一次磁盘写入）
        Long count = knowledgeBaseChunkMapper.selectCountByMd5AndChunkNum(fileMd5, chunkNumber);
        if (count > 0) {
            // ⚠️ 光看分片表不够：主记录可能已被删除，而分片是残留（历史脏数据；
            //    正常路径下 deleteMarkdownFile 会连分片一并清理）。
            //    此时若直接返回，主记录永远重建不出来，mergeChunk 恒报 MERGE_CHUNK_NOT_FOUND。
            if (Objects.nonNull(aiKnowledgeBaseFileStorageMapper.selectByMd5(fileMd5))) {
                log.info("## 分片已存在: fileMd5={}, chunkNumber={}", fileMd5, chunkNumber);
                return Response.success();
            }

            log.warn("## 主记录缺失但分片仍有残留，就地重建主记录: fileMd5={}", fileMd5);
            restoreFileStorage(fileMd5, uploadChunkReqVO);
            return Response.success();
        }

        // 创建分片目录（确保父目录也存在）。
        // ⚠️ 必须转成绝对路径，不能直接把配置里的相对路径拼给 transferTo：
        //    MultipartFile.transferTo 走的是 Servlet 容器的 Part.write()，
        //    它把**相对路径**解析为相对于 multipart 临时目录
        //    （%TEMP%\tomcat.8080.xxx\work\Tomcat\localhost\ROOT\），
        //    而上面的 forceMkdir 是按 JVM 工作目录解析的。
        //    两者规则不同，结果就是「目录建好了、文件却写不进去」，
        //    报 FileNotFoundException 且路径看起来莫名其妙。
        String chunkDir = Paths.get(chunkPath, fileMd5).toAbsolutePath().normalize().toString();
        File chunkDirFile = new File(chunkDir);
        try {
            FileUtils.forceMkdir(chunkDirFile);
        } catch (IOException e) {
            // 把异常对象一并传给日志，否则堆栈丢失，只剩一行「创建失败」无法定位
            // （最常见的原因是 knowledge-base.chunk-path 配了本机不存在的绝对路径）
            log.error("## 创建分片目录失败: {}", chunkDir, e);
            throw new BizException(ResponseCodeEnum.STORAGE_DIR_UNAVAILABLE);
        }

        // 保存分片文件到本地
        String chunkFileName = chunkNumber + ".chunk";
        File chunkFile = new File(chunkDirFile, chunkFileName);
        try {
            chunk.transferTo(chunkFile);
        } catch (IOException e) {
            log.error("## 保存分片文件失败: {}", chunkFileName, e);
            throw new BizException(ResponseCodeEnum.UPLOAD_FILE_FAILED);
        }

        // 保存分片记录。
        // 上面的 selectCount 与这里的写入之间仍存在竞态窗口（前端是 3 路并发上传），
        // 所以走 ON CONFLICT DO NOTHING，由唯一索引 uk_kb_chunk_md5_number 做最终裁决。
        // 注意不能改成「捕获 DuplicateKeyException」：PostgreSQL 中约束冲突会让整个事务
        // 进入 aborted 状态，后续语句全部失败，而本方法是 @Transactional 的，catch 也救不回来。
        int chunkInserted = knowledgeBaseChunkMapper.insertChunkIgnoreDuplicate(KnowledgeBaseChunkDO.builder()
                .fileMd5(fileMd5)
                .chunkNumber(chunkNumber)
                .chunkName(chunkFileName) // 只存文件名，所在目录由 chunk-path + fileMd5 推导
                .chunkSize(chunk.getSize())
                .createTime(LocalDateTime.now())
                .build());

        // 并发的另一个线程已写入过这条分片记录（也已计过数），这里幂等返回，不能重复计数
        if (chunkInserted == 0) {
            log.info("## 分片被并发请求抢先写入，幂等返回: fileMd5={}, chunkNumber={}", fileMd5, chunkNumber);
            return Response.success();
        }

        // 写入文件主记录。首次上传时，并发的多个分片请求都会走到这里，
        // 同样靠 ON CONFLICT 兜底：只有第一个真正创建记录，其余什么都不做。
        LocalDateTime now = LocalDateTime.now();
        int fileInserted = aiKnowledgeBaseFileStorageMapper.insertFileIgnoreDuplicate(
                KnowledgeBaseFileDO.builder()
                        .fileMd5(fileMd5)
                        .fileName(uploadChunkReqVO.getFileName())
                        .fileSize(uploadChunkReqVO.getFileSize()) // 原始文件大小
                        .totalChunks(uploadChunkReqVO.getTotalChunks())
                        .uploadedChunks(1) // 本次创建，当前分片已计入，故初始为 1
                        .status(KnowledgeBaseFileStatusEnum.UPLOADING.getCode()) // 状态：上传中...
                        .uploaderId(AuthContext.getCurrentUserId()) // 上传者，删改权限依据
                        .storedFileName(Strings.EMPTY) // 尚未合并，还没有落盘文件
                        .createTime(now)
                        .updateTime(now)
                        .build());

        // 取回主记录（无论是本次创建的，还是并发请求先创建的），拿主键 ID 与总分片数
        KnowledgeBaseFileDO fileStorageDO = aiKnowledgeBaseFileStorageMapper.selectByMd5(fileMd5);

        // 主记录不是本次创建的，说明是并发的其他分片请求先建的，
        // 它当时只把自己那一片计了数，所以当前分片要单独补上
        if (fileInserted == 0 && Objects.nonNull(fileStorageDO)) {
            aiKnowledgeBaseFileStorageMapper.incrementUploadedChunks(fileStorageDO.getId());
        }

        log.info("## 分片上传成功: fileMd5={}, chunkNumber={}, totalChunks={}",
                fileMd5, chunkNumber, Objects.nonNull(fileStorageDO)
                        ? fileStorageDO.getTotalChunks() : uploadChunkReqVO.getTotalChunks());

        return Response.success();
    }

    /**
     * 重建丢失的文件主记录（分片仍在、主记录已被删除的历史脏数据）
     * <p>
     * 仅在 {@code uploadChunk} 的快速路径中兜底调用——正常路径下删除文件会连分片一并清理，
     * 这个分支理论上不会走到；一旦走到，说明库里已有「有分片、无主记录」的状态。
     * <p>
     * ⚠️ {@code uploadedChunks} 取分片表里的实际条数，而不是固定写 1：
     * 这个 MD5 可能已经攒了多个分片，写成 1 会让 {@code mergeChunk} 的
     * 「已上传分片数 != 总分片数」校验误判为分片不完整。
     *
     * @param fileMd5 文件 MD5
     * @param reqVO   本次上传请求，提供文件名、文件大小与总分片数
     */
    private void restoreFileStorage(String fileMd5, UploadChunkReqVO reqVO) {
        int uploadedChunks = knowledgeBaseChunkMapper.selecChunkedtList(fileMd5).size();
        LocalDateTime now = LocalDateTime.now();

        aiKnowledgeBaseFileStorageMapper.insertFileIgnoreDuplicate(
                KnowledgeBaseFileDO.builder()
                        .fileMd5(fileMd5)
                        .fileName(reqVO.getFileName())
                        .fileSize(reqVO.getFileSize())
                        .totalChunks(reqVO.getTotalChunks())
                        .uploadedChunks(uploadedChunks) // 已存在的分片数，不是 1
                        .status(KnowledgeBaseFileStatusEnum.UPLOADING.getCode()) // 仍需继续上传，状态回到上传中
                        .uploaderId(AuthContext.getCurrentUserId()) // 重建时以本次请求者为归属人
                        .storedFileName(Strings.EMPTY)
                        .createTime(now)
                        .updateTime(now)
                        .build());
    }

    /**
     * 文件分片合并
     * <p>
     * ⚠️ <b>本方法是知识库侧唯一绕过权限模型的路径，且这个缺口尚未修复。</b>
     * 它<b>不校验 {@code uploader_id}</b>：任何登录用户只要把某个 fileMd5 的分片补齐
     * （分片接口本身也不校验归属），就能合并出「别人的」文件记录——下面的
     * {@code updateById} 会覆盖该记录的 {@code stored_file_name} 并把状态改回
     * {@code PENDING}，随后重新触发向量化。
     * <p>
     * 合法续传只发生在记录处于 {@code UPLOADING} 状态时（此时 {@code stored_file_name}
     * 还是空串，覆盖它没有副作用）。而当目标记录已经 {@code COMPLETED} 时，
     * 这次覆盖会把别人已经向量化、正在被所有人 RAG 检索到的正文整体替换掉——
     * 这是该缺口里最有害的一种，因为影响面是全体用户的检索结果，而不只是文件归属人。
     * <p>
     * 已知的缓解方向（例如「状态不是 {@code UPLOADING} 时拒绝合并」）属于行为变更
     * 且会波及合法的重传场景，提交时未采纳。改动本方法前请先确认这一段是否仍然成立。
     *
     * @param mergeChunkReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> mergeChunk(MergeChunkReqVO mergeChunkReqVO) {
        String fileMd5 = mergeChunkReqVO.getFileMd5();

        // 检查文件元记录是否存在
        KnowledgeBaseFileDO fileStorageDO = aiKnowledgeBaseFileStorageMapper.selectByMd5(fileMd5);

        // 要合并的目标文件不存在
        if (Objects.isNull(fileStorageDO)) {
            throw new BizException(ResponseCodeEnum.MERGE_CHUNK_NOT_FOUND);
        }

        // 查询所有已上传分片
        List<KnowledgeBaseChunkDO> chunks = knowledgeBaseChunkMapper.selecChunkedtList(fileMd5);

        // 若已上传分片数不等于总分片数，说明分片数不完整
        if (chunks.size() != fileStorageDO.getTotalChunks()) {
            throw new BizException(ResponseCodeEnum.CHUNK_NUM_NOT_COMPLETE);
        }

        // 创建文件目录。
        // 绝对化 + 规范化，否则拼出来的路径会把配置里的 "./" 原样带进去，
        // 得到 "D:\...\doc-qa-api\.\data\files" 这种夹着 .\ 的怪路径。
        // 注意 getAbsoluteFile() 只拼上工作目录、**不解析** "." 和 ".."，必须用 normalize()。
        File uploadDir = Paths.get(fileStoragePath).toAbsolutePath().normalize().toFile();
        try {
            FileUtils.forceMkdir(uploadDir);
        } catch (IOException e) {
            log.error("## 创建文件合并目录失败: {}", uploadDir, e);
            throw new BizException(ResponseCodeEnum.STORAGE_DIR_UNAVAILABLE);
        }

        // 合并文件的名称
        String finalFileName = System.currentTimeMillis() + "_" + fileStorageDO.getFileName();
        // 新建合并文件
        File finalFile = new File(uploadDir, finalFileName);

        // 分片文件所在目录：由配置 + fileMd5 推导（记录里只存了文件名，不存绝对路径，
        // 这样换机器或挪目录后历史记录依然有效）。
        // ⚠️ 这里的绝对化必须与 uploadChunk 中的写法保持一致，否则一边按 JVM 工作目录建、
        //    一边按别的基准找，合并时会找不到分片文件。
        String chunkDir = Paths.get(chunkPath, fileMd5).toAbsolutePath().normalize().toString();

        // 合并分片
        try (FileOutputStream fos = new FileOutputStream(finalFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            for (KnowledgeBaseChunkDO chunkInfo : chunks) {
                // 读取分片文件
                File chunkFile = new File(chunkDir, chunkInfo.getChunkName());
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
            log.error("## 合并文件失败: fileMd5={}", fileMd5, e);
            throw new BizException(ResponseCodeEnum.FILE_MERGE_FAILED);
        }

        // 更新文件信息
        aiKnowledgeBaseFileStorageMapper.updateById(KnowledgeBaseFileDO.builder()
                .id(fileStorageDO.getId())
                .status(KnowledgeBaseFileStatusEnum.PENDING.getCode()) // 合并完成，等待向量化
                .storedFileName(finalFileName) // 只存文件名，目录由 file-storage-path 推导
                .build());

        // 删除分片文件所在的目录和记录
        try {
            FileUtils.forceDelete(new File(chunkDir));
        } catch (IOException e) {
            // 清理失败同样抛出并回滚：此时文件已合并，但状态与记录的更新一并撤销，
            // 避免留下「分片还在、状态却已进入待向量化」的不一致中间态
            log.error("## 删除分片文件失败: {}", chunkDir, e);
            throw new BizException(ResponseCodeEnum.FILE_MERGE_FAILED);
        }

        knowledgeBaseChunkMapper.deleteByMd5(fileMd5);

        log.info("## 文件合并成功: fileMd5={}, filePath={}", fileMd5, finalFile.getAbsolutePath());

        // 合并完成后，发布事件，进行向量化处理
        // 获取主键 ID
        Long id =  fileStorageDO.getId();

        // 元数据
        Map<String, Object> metadatas = Maps.newHashMap();
        metadatas.put("mdStorageId", id); // 关联的文件存储表主键 ID
        metadatas.put("originalFileName", fileStorageDO.getFileName()); // 文件原始名称

        // 发布事件
        eventPublisher.publishEvent(KnowledgeBaseFileUploadedEvent.builder()
                .id(id)
                .filePath(finalFile.getAbsolutePath())
                .metadatas(metadatas)
                .build());

        return Response.success();
    }
}
