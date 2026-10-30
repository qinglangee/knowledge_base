package com.kb.repository;

import com.kb.entity.ScanError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScanErrorRepository extends JpaRepository<ScanError, Long> {

    List<ScanError> findByDirectoryId(Long directoryId);

    List<ScanError> findByDirectoryIdOrderByIdAsc(Long directoryId);

    long countByDirectoryId(Long directoryId);
}
