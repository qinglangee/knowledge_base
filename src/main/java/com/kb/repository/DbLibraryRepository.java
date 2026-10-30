package com.kb.repository;

import com.kb.entity.DbLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DbLibraryRepository extends JpaRepository<DbLibrary, Long> {

    boolean existsByName(String name);

    Optional<DbLibrary> findByName(String name);
}
