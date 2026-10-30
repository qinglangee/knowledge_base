package com.kb.controller;

import com.kb.common.ApiResponse;
import com.kb.dto.Dtos.SearchResponse;
import com.kb.service.SearchService;
import com.kb.service.SettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService searchService;
    private final SettingService settingService;

    public SearchController(SearchService searchService, SettingService settingService) {
        this.searchService = searchService;
        this.settingService = settingService;
    }

    /**
     * @param q    查询词
     * @param lib  索引库 id；不传表示在所有库中查询
     * @param page 页码，从 0 开始
     * @param size 每页数量，不传使用设置中的默认值
     * @param ext  扩展名过滤，如 "pdf,docx"，可选
     * @param from 修改时间下限 yyyy-MM-dd，可选
     * @param to   修改时间上限 yyyy-MM-dd，可选
     */
    @GetMapping("/api/search")
    public ApiResponse<SearchResponse> search(@RequestParam String q,
                                              @RequestParam(required = false) Long lib,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String ext,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        SearchResponse resp = searchService.search(q, lib, page, size, ext, from, to);
        if (lib != null) {
            settingService.saveLastLibrary(lib);
        }
        return ApiResponse.ok(resp);
    }
}
