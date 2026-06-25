package com.membershipflow.collect.collector;

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

class DongbuCollectorTest {

    private DongbuCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DongbuCollector();
    }

    @Test
    @DisplayName("정상 HTML을 파싱하면 CollectedPrice 목록을 반환한다")
    void parse_validHtml_returnsCollectedPrices() {
        // given
        // 헤더: [골프장][구분][홀수][전주시세][금주시세][등락]
        Document doc = Jsoup.parse("""
                <table class="regtable">
                  <tbody>
                    <tr><td>레이크사이드</td><td>일반</td><td>36</td><td>43,500</td><td>43,800</td><td>▲ 300</td></tr>
                    <tr><td>가야</td><td>주중</td><td>18</td><td>8,800</td><td>8,900</td><td>▲ 100</td></tr>
                    <tr><td>가평베네스트</td><td>주주</td><td>27</td><td>130,000</td><td>130,000</td><td>-</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        assertThat(result).hasSize(3);

        CollectedPrice first = result.get(0);
        assertThat(first.courseName()).isEqualTo("레이크사이드");
        assertThat(first.membershipType()).isEqualTo(MembershipType.REGULAR);
        assertThat(first.holes()).isEqualTo(36);
        assertThat(first.price()).isEqualTo(438_000_000L);  // 43,800만원 × 10,000
        assertThat(first.courseType()).isEqualTo(CourseType.GOLF);
        assertThat(first.sourceName()).isEqualTo("동부회원권");

        CollectedPrice second = result.get(1);
        assertThat(second.membershipType()).isEqualTo(MembershipType.WEEKDAY);
        assertThat(second.price()).isEqualTo(89_000_000L);  // 8,900만원
    }

    @Test
    @DisplayName("금주시세가 '-'인 행은 건너뛴다")
    void parse_dashPrice_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <table class="regtable">
                  <tbody>
                    <tr><td>레이크사이드</td><td>일반</td><td>36</td><td>43,500</td><td>-</td><td>-</td></tr>
                    <tr><td>가야</td><td>주중</td><td>18</td><td>8,800</td><td>8,900</td><td>-</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then — '-' 행 제외, 정상 행 1건만
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("가야");
    }

    @Test
    @DisplayName("컬럼이 5개 미만인 행은 건너뛴다")
    void parse_insufficientColumns_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <table class="regtable">
                  <tbody>
                    <tr><td>불완전행</td><td>일반</td><td>36</td></tr>
                    <tr><td>정상행</td><td>일반</td><td>27</td><td>9,000</td><td>9,100</td><td>▲ 100</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("정상행");
    }

    @Test
    @DisplayName("tbody가 비어있으면 CollectException을 던진다")
    void parse_emptyTable_throwsCollectException() {
        // given
        Document doc = Jsoup.parse("<table class=\"regtable\"><tbody></tbody></table>");

        // when / then
        assertThatThrownBy(() -> collector.parse(doc))
                .isInstanceOf(CollectException.class)
                .hasMessageContaining("행 없음");
    }

    @Test
    @DisplayName("홀수가 '45+퍼9' 같은 복합 형식이면 앞 숫자만 파싱한다")
    void parse_complexHoles_parsesLeadingNumber() {
        // given
        Document doc = Jsoup.parse("""
                <table class="regtable">
                  <tbody>
                    <tr><td>가야</td><td>우대</td><td>45+퍼9</td><td>17,600</td><td>17,800</td><td>▲ 200</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then — '45+퍼9'에서 leading digit만 파싱 → 45
        assertThat(result).hasSize(1);
        assertThat(result.get(0).holes()).isEqualTo(45);
    }
}
