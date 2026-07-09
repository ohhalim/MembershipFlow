package com.membershipflow.collect.collector;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 실제 사이트에 HTTP 요청을 보내는 수동 검증용 테스트.
 * CI에서는 실행하지 않는다 (@Disabled).
 */
@Disabled("실제 네트워크 필요 — 수동으로만 실행")
class DongbuCollectorManualTest {

    @Test
    void dongbu_실제_파싱_확인() {
        // given
        DongbuCollector collector = new DongbuCollector();

        // when
        List<CollectedPrice> prices = collector.collect();

        // then
        System.out.println("=== 동부회원권 수집 결과: " + prices.size() + "건 ===");
        prices.stream().limit(10).forEach(p ->
                System.out.printf("%-25s | %s | %s | 홀수=%s | %,d원%n",
                        p.courseName(), p.courseType(), p.membershipType(),
                        p.holes(), p.price()));
    }

    @Test
    void donga_실제_파싱_확인() {
        // given
        DongaCollector collector = new DongaCollector(new DongaCourseLinkFetcher());

        // when
        List<CollectedPrice> prices = collector.collect();

        // then
        System.out.println("=== 동아골프 수집 결과: " + prices.size() + "건 ===");
        prices.stream().limit(10).forEach(p ->
                System.out.printf("%-25s | %s | %s | %,d원%n",
                        p.courseName(), p.courseType(), p.membershipType(), p.price()));
    }
}
