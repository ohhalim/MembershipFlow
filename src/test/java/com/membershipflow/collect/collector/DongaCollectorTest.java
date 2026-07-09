package com.membershipflow.collect.collector;

import com.membershipflow.collect.collector.DongaCourseLinkFetcher.CourseLink;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DongaCollectorTest {

    private DongaCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DongaCollector(new DongaCourseLinkFetcher());
    }

    // 실제 사이트: 시세 목록과 상세 링크 목록이 같은 페이지의 같은 <a> 텍스트에서 나온다
    private static final List<CourseLink> SAMPLE_LINKS = List.of(
            new CourseLink("10130", "1103", "88(팔팔)"),
            new CourseLink("10002", "2513", "가야-주중"),
            new CourseLink("10002", "2512", "가야우대"));

    @Test
    @DisplayName("정상 HTML을 파싱하면 CollectedPrice 목록을 반환한다")
    void parse_validHtml_returnsCollectedPrices() {
        // given
        // 헤더: [회원권명][금일시세][전일시세][등락][시세추이][상담]
        Document doc = Jsoup.parse("""
                <table class="list_even">
                  <tbody>
                    <tr><td>88(팔팔)</td><td>43,800</td><td>43,800</td><td>0</td><td></td><td></td></tr>
                    <tr><td>가야-주중</td><td>8,900</td><td>8,900</td><td>0</td><td></td><td></td></tr>
                    <tr><td>가야우대</td><td>17,000</td><td>17,000</td><td>0</td><td></td><td></td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc, SAMPLE_LINKS);

        // then
        assertThat(result).hasSize(3);

        CollectedPrice first = result.get(0);
        assertThat(first.courseName()).isEqualTo("88(팔팔)");
        assertThat(first.price()).isEqualTo(438_000_000L);  // 43,800만원 × 10,000
        assertThat(first.courseType()).isEqualTo(CourseType.GOLF);
        // 이름 중간에 구분 키워드가 없으면 null → CollectService에서 alias/normalizer로 최종 결정
        assertThat(first.membershipType()).isNull();
        assertThat(first.holes()).isNull();
        assertThat(first.sourceName()).isEqualTo("동아골프");
        // 완전일치로 custid:code가 채워진다 (#144)
        assertThat(first.sourceKey()).isEqualTo("10130:1103");
    }

    @Test
    @DisplayName("링크 목록에 없는 코스명은 sourceKey 없이 수집된다")
    void parse_noMatchingLink_leavesSourceKeyNull() {
        // given
        Document doc = Jsoup.parse("""
                <table class="list_even">
                  <tbody>
                    <tr><td>매칭안됨CC</td><td>10,000</td><td>10,000</td><td>0</td><td></td><td></td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc, SAMPLE_LINKS);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceKey()).isNull();
    }

    @Test
    @DisplayName("링크 목록이 비어있어도(수집 실패) 가격 수집 자체는 계속된다")
    void parse_emptyLinks_stillCollectsPrices() {
        // given
        Document doc = Jsoup.parse("""
                <table class="list_even">
                  <tbody>
                    <tr><td>88(팔팔)</td><td>43,800</td><td>43,800</td><td>0</td><td></td><td></td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc, List.of());

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceKey()).isNull();
    }

    @Test
    @DisplayName("코스명에 '주중'이 포함되면 WEEKDAY로 매핑된다")
    void parse_weekdayInName_mapsToWeekday() {
        // given
        Document doc = Jsoup.parse("""
                <table class="list_even">
                  <tbody>
                    <tr><td>가야-주중</td><td>8,900</td><td>8,900</td><td>0</td><td></td><td></td></tr>
                    <tr><td>레이크주말</td><td>25,000</td><td>25,000</td><td>0</td><td></td><td></td></tr>
                    <tr><td>한양우대</td><td>12,000</td><td>12,000</td><td>0</td><td></td><td></td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc, List.of());

        // then
        assertThat(result.get(0).membershipType()).isEqualTo(MembershipType.WEEKDAY);
        assertThat(result.get(1).membershipType()).isEqualTo(MembershipType.WEEKEND);
        assertThat(result.get(2).membershipType()).isEqualTo(MembershipType.PREFERRED);
    }

    @Test
    @DisplayName("price-data-list 테이블은 selector에서 제외된다")
    void parse_priceDataListTable_isExcluded() {
        // given — TOP10 테이블(price-data-list)과 전체 테이블(list_even만) 함께 존재
        Document doc = Jsoup.parse("""
                <table class="price-data-list list_even border">
                  <tbody>
                    <tr><td>TOP10종목</td><td>99,999</td><td>99,999</td><td>0</td></tr>
                  </tbody>
                </table>
                <table class="list_even">
                  <tbody>
                    <tr><td>일반종목</td><td>10,000</td><td>10,000</td><td>0</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc, List.of());

        // then — price-data-list 제외, 일반 테이블 1건만
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("일반종목");
    }

    @Test
    @DisplayName("금일시세가 비어있거나 '-'인 행은 건너뛴다")
    void parse_emptyOrDashPrice_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <table class="list_even">
                  <tbody>
                    <tr><td>빈시세</td><td></td><td></td><td>0</td></tr>
                    <tr><td>대시시세</td><td>-</td><td>-</td><td>0</td></tr>
                    <tr><td>정상종목</td><td>10,000</td><td>10,000</td><td>0</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc, List.of());

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("정상종목");
    }

    @Test
    @DisplayName("대상 테이블이 없으면 CollectException을 던진다")
    void parse_noMatchingTable_throwsCollectException() {
        // given
        Document doc = Jsoup.parse("<html><body></body></html>");

        // when / then
        assertThatThrownBy(() -> collector.parse(doc, List.of()))
                .isInstanceOf(CollectException.class)
                .hasMessageContaining("행 없음");
    }
}
