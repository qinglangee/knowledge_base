package com.kb.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 接口传输对象集中定义。
 */
public final class Dtos {

    public record LibraryCreateRequest(String name) {
    }

    public record DirectoryCreateRequest(String path) {
    }

    public record LibraryDTO(Long id, String name, String indexRelativePath, LocalDateTime createdAt, int dirCount) {
    }

    public record DirectoryDTO(Long id, Long libraryId, String path, String status, int fileCount,
                               LocalDateTime scannedAt, Integer progressDone, Integer progressTotal,
                               int errorCount) {
    }

    public record ScanErrorDTO(Long id, String path, String error, LocalDateTime createdAt) {
    }

    public record SearchItem(String name, String path, String ext, long size, long mtime, float score,
                             String snippet, Long libraryId, String libraryName) {
    }

    public record SearchResponse(long total, int page, int size, long tookMs, List<SearchItem> items) {
    }

    public record SettingsDTO(String indexRoot, int pageSize, Long lastLibraryId,
                              Integer port, int runningPort, String analyzer, String supportedExts) {
    }

    public record SettingsUpdateRequest(String indexRoot, Integer pageSize, Long lastLibraryId,
                                        Integer port, String analyzer, String supportedExts) {
    }

    public record TextPreviewDTO(String type, String mimeType, String fileName, long size, String content,
                                 boolean truncated) {
    }

    private Dtos() {
    }
}
