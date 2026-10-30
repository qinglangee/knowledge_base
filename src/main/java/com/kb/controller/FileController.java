package com.kb.controller;

import com.kb.common.ApiResponse;
import com.kb.common.BusinessException;
import com.kb.dto.Dtos.TextPreviewDTO;
import com.kb.service.FilePreviewService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
public class FileController {

    private final FilePreviewService service;

    public FileController(FilePreviewService service) {
        this.service = service;
    }

    /**
     * 原始内容预览（图片 / PDF），供 img 标签、iframe 使用。
     */
    @GetMapping("/api/file/raw")
    public ResponseEntity<byte[]> raw(@RequestParam String path) {
        Path p = service.checkAccess(path);
        if (!service.isRawType(p)) {
            throw new BusinessException(400, "该文件类型不支持原始预览，请使用 /api/file/text");
        }
        FilePreviewService.RawFile raw = service.raw(p);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(raw.mimeType()))
                .body(raw.bytes());
    }

    /**
     * 文本预览：文本文件直接返回内容，Office 文档返回 Tika 抽取的纯文本。
     */
    @GetMapping("/api/file/text")
    public ApiResponse<TextPreviewDTO> text(@RequestParam String path) {
        Path p = service.checkAccess(path);
        return ApiResponse.ok(service.text(p));
    }
}
