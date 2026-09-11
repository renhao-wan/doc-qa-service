package io.github.renhaowan.docqa.service;

import io.github.renhaowan.docqa.model.dto.SearchResultDTO;

import java.util.List;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/7/30 12:13
 * @Version: v1.0.0
 * @Description: TODO
 **/
public interface SearXNGService {

    /**
     * 调用 SearXNG Api, 获取搜索列表
     * @param query 搜索关键词
     * @return
     */
    List<SearchResultDTO> search(String query);
}
