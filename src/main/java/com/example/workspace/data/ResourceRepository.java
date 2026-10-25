package com.example.workspace.data;

import com.example.workspace.domain.BookableResource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<BookableResource, Long> {
    Optional<BookableResource> findByCode(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BookableResource r where r.id = :id")
    Optional<BookableResource> findLockedById(@Param("id") Long id);
}
