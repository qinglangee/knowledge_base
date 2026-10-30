package com.kb.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 注册到某个索引库下的待扫描目录。
 */
@Entity
@Table(name = "indexed_directory")
public class IndexedDirectory {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SCANNING = "SCANNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long libraryId;

    /** 规范化后的绝对路径 */
    @Column(nullable = false, length = 500)
    private String path;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "file_count")
    private int fileCount;

    private LocalDateTime scannedAt;

    public Long getId() {
        return id;
    }

    public Long getLibraryId() {
        return libraryId;
    }

    public void setLibraryId(Long libraryId) {
        this.libraryId = libraryId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public LocalDateTime getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(LocalDateTime scannedAt) {
        this.scannedAt = scannedAt;
    }
}
