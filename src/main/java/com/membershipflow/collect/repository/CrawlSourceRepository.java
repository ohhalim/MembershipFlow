package com.membershipflow.collect.repository;

import com.membershipflow.collect.entity.CrawlSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrawlSourceRepository extends JpaRepository<CrawlSource, Long> {

    List<CrawlSource> findAllByActiveTrue();

    Optional<CrawlSource> findByName(String name);
}
