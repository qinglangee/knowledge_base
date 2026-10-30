package com.kb.controller;

import com.kb.common.ApiResponse;
import com.kb.dto.Dtos;
import com.kb.dto.Dtos.DirectoryCreateRequest;
import com.kb.dto.Dtos.LibraryCreateRequest;
import com.kb.service.LibraryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/libraries")
public class LibraryController {

    private final LibraryService service;

    public LibraryController(LibraryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Dtos.LibraryDTO>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<Dtos.LibraryDTO> create(@RequestBody LibraryCreateRequest req) {
        return ApiResponse.ok(service.create(req.name()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Dtos.LibraryDTO> rename(@PathVariable Long id, @RequestBody LibraryCreateRequest req) {
        return ApiResponse.ok(service.rename(id, req.name()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/dirs")
    public ApiResponse<List<Dtos.DirectoryDTO>> dirs(@PathVariable Long id) {
        return ApiResponse.ok(service.listDirs(id));
    }

    @PostMapping("/{id}/dirs")
    public ApiResponse<Dtos.DirectoryDTO> addDir(@PathVariable Long id, @RequestBody DirectoryCreateRequest req) {
        return ApiResponse.ok(service.addDirectory(id, req.path()));
    }

    @PostMapping("/{id}/rescan")
    public ApiResponse<Void> rescan(@PathVariable Long id) {
        service.rescanLibrary(id);
        return ApiResponse.ok();
    }
}
