package com.kb.controller;

import com.kb.common.ApiResponse;
import com.kb.dto.Dtos;
import com.kb.service.LibraryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 单个目录级别的操作。
 */
@RestController
@RequestMapping("/api/dirs")
public class DirectoryController {

    private final LibraryService service;

    public DirectoryController(LibraryService service) {
        this.service = service;
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.deleteDirectory(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/rescan")
    public ApiResponse<Void> rescan(@PathVariable Long id) {
        service.rescanDirectory(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/errors")
    public ApiResponse<List<Dtos.ScanErrorDTO>> errors(@PathVariable Long id) {
        return ApiResponse.ok(service.listErrors(id));
    }
}
