package com.kb.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 索引库（需求中的"数据库"）：一个库对应一个独立的 Lucene 索引目录。
 */
@Entity
@Table(name = "db_library")
public class DbLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** 相对于索引根目录的索引存放路径，如 lib_1 */
    @Column(name = "index_path", nullable = false, length = 100)
    private String indexRelativePath;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIndexRelativePath() {
        return indexRelativePath;
    }

    public void setIndexRelativePath(String indexRelativePath) {
        this.indexRelativePath = indexRelativePath;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
