package com.membershipflow.collect.repository;

import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CrawlSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectRunRepository extends JpaRepository<CollectRun, Long> {

    // 같은 소스의 직전 CollectRun (현재 run 제외, id 내림차순 첫 번째) — 수집량 급감 탐지용 (#159)
    Optional<CollectRun> findTopBySourceAndIdLessThanOrderByIdDesc(CrawlSource source, Long id);
}
