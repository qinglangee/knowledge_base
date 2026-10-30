package com.kb.repository;

import com.kb.entity.IndexedDirectory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndexedDirectoryRepository extends JpaRepository<IndexedDirectory, Long> {

    List<IndexedDirectory> findByLibraryIdOrderById(Long libraryId);
}
