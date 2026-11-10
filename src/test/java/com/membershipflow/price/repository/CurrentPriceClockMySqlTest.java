package com.membershipflow.price.repository;

import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.collect.repository.CrawlSourceRepository;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.entity.PriceHistory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 운영은 앱이 KST, MySQL이 UTC라 DB 시계가 9시간 늦다 (#439).
// CI JVM은 UTC라 DB를 -09:00으로 띄워서 같은 차이를 만든다.
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CurrentPriceClockMySqlTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("membershipflow")
            .withCommand("--default-time-zone=-09:00");

    @Autowired
    PriceHistoryRepository priceHistoryRepository;
    @Autowired
    MembershipCourseRepository courseRepository;
    @Autowired
    CrawlSourceRepository crawlSourceRepository;

    MembershipCourse course;
    CrawlSource source;

    @BeforeEach
    void setUp() {
        course = courseRepository.save(MembershipCourse.builder()
                .name("시계테스트CC")
                .courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build());
        source = crawlSourceRepository.save(CrawlSource.builder()
                .name("시계테스트거래소")
                .baseUrl("https://example.com")
                .crawlType(CrawlType.JSOUP)
                .active(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        priceHistoryRepository.deleteAll();
        courseRepository.delete(course);
        crawlSourceRepository.delete(source);
    }

    @Test
    @DisplayName("방금 저장한 가격이 현재가 조회에 바로 나온다")
    void justCollectedPriceIsCurrent() {
        PriceHistory saved = saveAt(LocalDateTime.now().minusMinutes(1));

        assertThat(priceHistoryRepository.findLatestBySource(course.getId()))
                .extracting(PriceHistory::getId).containsExactly(saved.getId());
        assertThat(priceHistoryRepository.findLatestByCourseIds(List.of(course.getId())))
                .extracting(PriceHistory::getId).containsExactly(saved.getId());
        assertThat(priceHistoryRepository.findLatestPerSourceEntitiesByCourseIds(List.of(course.getId())))
                .extracting(PriceHistory::getId).containsExactly(saved.getId());
        assertThat(priceHistoryRepository.findLatestPerSourceByCourseIds(List.of(course.getId())))
                .hasSize(1);
        assertThat(priceHistoryRepository.countCoursesUpdatedSince(LocalDateTime.now().minusHours(1)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("방금 저장한 가격이 가격순 목록의 대표가로 나온다")
    void justCollectedPriceIsListedPrice() {
        saveAt(LocalDateTime.now().minusMinutes(1));

        List<MembershipCourse> courses = courseRepository.searchWithPriceSort(
                "시계테스트", null, null, null, "price_asc", 10, 0);

        assertThat(courses).hasSize(1);
        assertThat(courses.get(0).getLatestPrice()).isEqualTo(100_000_000L);
    }

    @Test
    @DisplayName("48시간이 지난 가격은 현재가에서 빠진다")
    void stalePriceIsExcluded() {
        saveAt(LocalDateTime.now().minusHours(49));

        assertThat(priceHistoryRepository.findLatestBySource(course.getId())).isEmpty();
        assertThat(priceHistoryRepository.findLatestByCourseIds(List.of(course.getId()))).isEmpty();
    }

    @Test
    @DisplayName("미래 시각 가격은 현재가에서 빠진다")
    void futurePriceIsExcluded() {
        saveAt(LocalDateTime.now().plusHours(1));

        assertThat(priceHistoryRepository.findLatestBySource(course.getId())).isEmpty();
        assertThat(priceHistoryRepository.findLatestByCourseIds(List.of(course.getId()))).isEmpty();
        assertThat(priceHistoryRepository.countCoursesUpdatedSince(LocalDateTime.now().minusHours(1)))
                .isZero();
    }

    @Test
    @DisplayName("방금 저장한 가격으로 상승 랭킹이 잡힌다")
    void justCollectedPriceIsRanked() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime baseTime = now.minusDays(7);
        saveAt(baseTime.minusHours(1), 90_000_000L);
        saveAt(now.minusMinutes(1), 100_000_000L);

        List<Object[]> rows = courseRepository.findRankingPage(
                baseTime, baseTime.minusDays(7), baseTime.plusDays(7), null, "GAIN", 10, 0);
        long total = courseRepository.countRanking(
                baseTime, baseTime.minusDays(7), baseTime.plusDays(7), null, "GAIN");

        assertThat(rows).hasSize(1);
        assertThat(total).isEqualTo(1);
    }

    private PriceHistory saveAt(LocalDateTime collectedAt) {
        return saveAt(collectedAt, 100_000_000L);
    }

    private PriceHistory saveAt(LocalDateTime collectedAt, long price) {
        return priceHistoryRepository.save(PriceHistory.builder()
                .course(course)
                .source(source)
                .price(price)
                .collectedAt(collectedAt)
                .build());
    }
}
