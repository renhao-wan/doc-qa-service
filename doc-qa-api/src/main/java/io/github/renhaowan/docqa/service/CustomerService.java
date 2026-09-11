package io.github.renhaowan.docqa.service;


import io.github.renhaowan.docqa.model.vo.customerService.*;
import io.github.renhaowan.docqa.utils.PageResponse;
import io.github.renhaowan.docqa.utils.Response;

/**
 * @author: Renhao-Wan
 * @url: https://github.com/Renhao-Wan
 * @date: 2023-09-15 14:03
 * @description: AI 客服
 **/
public interface CustomerService {

//    /**
//     * 上传 Markdown 问答文件
//     * @param file
//     * @return
//     */
//    Response<?> uploadMarkdownFile(MultipartFile file);

    /**
     * 删除 Markdown 问答文件
     * @param deleteMarkdownFileReqVO
     * @return
     */
    Response<?> deleteMarkdownFile(DeleteMarkdownFileReqVO deleteMarkdownFileReqVO);

    /**
     * 分页查询 Markdown 问答文件
     * @param findMarkdownFilePageListReqVO
     * @return
     */
    PageResponse<FindMarkdownFilePageListRspVO> findMarkdownFilePageList(FindMarkdownFilePageListReqVO findMarkdownFilePageListReqVO);

    /**
     * 修改  Markdown 问答文件信息
     * @param updateMarkdownFileReqVO
     * @return
     */
    Response<?> updateMarkdownFile(UpdateMarkdownFileReqVO updateMarkdownFileReqVO);

    /**
     * 检查文件是否存在
     * @param checkFileReqVO
     * @return
     */
    Response<CheckFileRspVO> checkFile(CheckFileReqVO checkFileReqVO);

    /**
     * 文件分片上传
     * @param uploadChunkReqVO
     * @return
     */
    Response<?> uploadChunk(UploadChunkReqVO uploadChunkReqVO);

    /**
     * 文件分片合并
     * @param mergeChunkReqVO
     * @return
     */
    Response<?> mergeChunk(MergeChunkReqVO mergeChunkReqVO);
}
