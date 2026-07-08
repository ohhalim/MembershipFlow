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

class MsiseCollectorTest {

    private MsiseCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MsiseCollector();
    }

    // 실제 사이트(http://www.m-sise.com/page/siseGolfInfo.php) 응답 구조를 그대로 재현한 fixture.
    // 헤더: [골프장][구분][홀수][전주시세][금주시세][등락], 골프장 셀은 미종료 <a> 앵커를 포함한다.
    private static final String REGTABLE_FIXTURE = """
            <div class="siseinfo_unit">단위 : 만원</div>
            <table class="regtable" width="100%">
            <thead>
            <tr><th>골프장</th><th>구분</th><th>홀수</th><th>전주시세</th><th>금주시세</th><th>등락()</th></tr>
            </thead>
            <tbody>
                <tr>
                    <td align=center><a href='javascript:' class='link-detailview' data-key1='165' data-cate1='G'>88cc</td>
                    <td align=center>개인</td>
                    <td align=center>36홀</td>
                    <td align=center>38,000</td>
                    <td align=center>39,000</td>
                    <td align=center>▲ 1,000</td>
                </tr>
                <tr>
                    <td align=center><a href='javascript:' class='link-detailview' data-key1='117' data-cate1='G'>가야</td>
                    <td align=center>우대</td>
                    <td align=center>45홀+퍼9</td>
                    <td align=center>18,500</td>
                    <td align=center>17,500</td>
                    <td align=center>▼ -1,000</td>
                </tr>
                <tr>
                    <td align=center><a href='javascript:' class='link-detailview' data-key1='6' data-cate1='G'>가평베네스트</td>
                    <td align=center>일반</td>
                    <td align=center>27홀</td>
                    <td align=center>130,000</td>
                    <td align=center>130,000</td>
                    <td align=center> - </td>
                </tr>
            </tbody>
            </table>
            """;

    @Test
    @DisplayName("정상 HTML을 파싱하면 CollectedPrice 목록을 반환한다")
    void parse_validHtml_returnsCollectedPrices() {
        // given
        Document doc = Jsoup.parse(REGTABLE_FIXTURE);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then — 등락(6번째 td)이 '-'(보합)인 가평베네스트도 금주시세 값은 있으므로 포함된다
        assertThat(result).hasSize(3);

        CollectedPrice first = result.get(0);
        assertThat(first.courseName()).isEqualTo("88cc");
        assertThat(first.membershipType()).isEqualTo(MembershipType.REGULAR); // "개인" → REGULAR
        assertThat(first.holes()).isEqualTo(36);
        assertThat(first.price()).isEqualTo(390_000_000L); // 39,000만원 × 10,000
        assertThat(first.courseType()).isEqualTo(CourseType.GOLF);
        assertThat(first.sourceName()).isEqualTo("시세닷컴");

        CollectedPrice second = result.get(1);
        assertThat(second.courseName()).isEqualTo("가야");
        assertThat(second.membershipType()).isEqualTo(MembershipType.PREFERRED); // "우대" → PREFERRED
        assertThat(second.holes()).isEqualTo(45); // "45홀+퍼9" → 앞자리 45
        assertThat(second.price()).isEqualTo(175_000_000L); // 17,500만원

        CollectedPrice third = result.get(2);
        assertThat(third.courseName()).isEqualTo("가평베네스트");
        assertThat(third.price()).isEqualTo(1_300_000_000L); // 130,000만원 (보합이어도 가격은 유효)
    }

    @Test
    @DisplayName("금주시세가 '-'인 행은 건너뛴다")
    void parse_dashPrice_skipsRow() {
        // given — 금주시세(4번째 td) 자체가 '-'인 경우
        Document doc = Jsoup.parse("""
                <table class="regtable">
                  <tbody>
                    <tr>
                      <td align=center><a href='javascript:' class='link-detailview' data-key1='1' data-cate1='G'>레이크사이드</td>
                      <td align=center>일반</td>
                      <td align=center>36홀</td>
                      <td align=center>43,500</td>
                      <td align=center> - </td>
                      <td align=center> - </td>
                    </tr>
                    <tr>
                      <td align=center><a href='javascript:' class='link-detailview' data-key1='2' data-cate1='G'>가야</td>
                      <td align=center>주중</td>
                      <td align=center>18홀</td>
                      <td align=center>8,800</td>
                      <td align=center>8,900</td>
                      <td align=center>▲ 100</td>
                    </tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then — 금주시세가 '-'인 레이크사이드만 제외, 가야 1건만 남음
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("가야");
    }

    @Test
    @DisplayName("data-cate1이 골프(G)가 아닌 행은 방어적으로 제외한다")
    void parse_nonGolfCategory_filteredOut() {
        // given — 콘도/휘트니스 등 다른 종목이 섞여 들어온 경우를 가정한 방어 테스트
        Document doc = Jsoup.parse("""
                <table class="regtable">
                  <tbody>
                    <tr>
                      <td align=center><a href='javascript:' class='link-detailview' data-key1='1' data-cate1='C'>모콘도</td>
                      <td align=center>일반</td>
                      <td align=center>-</td>
                      <td align=center>10,000</td>
                      <td align=center>10,000</td>
                      <td align=center>-</td>
                    </tr>
                    <tr>
                      <td align=center><a href='javascript:' class='link-detailview' data-key1='2' data-cate1='G'>정상골프장</td>
                      <td align=center>일반</td>
                      <td align=center>18홀</td>
                      <td align=center>9,000</td>
                      <td align=center>9,100</td>
                      <td align=center>▲ 100</td>
                    </tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("정상골프장");
    }

    @Test
    @DisplayName("컬럼이 5개 미만인 행은 건너뛴다")
    void parse_insufficientColumns_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <table class="regtable">
                  <tbody>
                    <tr><td>불완전행</td><td>일반</td><td>36홀</td></tr>
                    <tr>
                      <td align=center><a href='javascript:' class='link-detailview' data-key1='9' data-cate1='G'>정상행</td>
                      <td align=center>일반</td>
                      <td align=center>27홀</td>
                      <td align=center>9,000</td>
                      <td align=center>9,100</td>
                      <td align=center>▲ 100</td>
                    </tr>
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
    @DisplayName("tbody가 비어있으면 빈 리스트를 반환한다 (다른 scate 구분에는 결과가 없을 수 있음)")
    void parse_emptyTable_returnsEmptyList() {
        // given
        Document doc = Jsoup.parse("<table class=\"regtable\"><tbody></tbody></table>");

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("페이지네이션 nav에서 최대 page 번호를 감지한다")
    void detectTotalPages_withPagination_returnsMaxPage() {
        // given — 실제 사이트의 nav.pg_wrap 구조 재현 (맨끝 링크도 마지막 페이지를 가리킴)
        Document doc = Jsoup.parse("""
                <nav class="pg_wrap"><span class="pg"><strong class="pg_current">1</strong>
                <a href="?&scate=P&sprice=&page=2" class="pg_page">2</a>
                <a href="?&scate=P&sprice=&page=3" class="pg_page">3</a>
                <a href="?&scate=P&sprice=&page=4" class="pg_page">4</a>
                <a href="?&scate=P&sprice=&page=5" class="pg_page">5</a>
                <a href="?&scate=P&sprice=&page=5" class="pg_page pg_end">맨끝</a>
                </span></nav>
                """);

        // when
        int totalPages = collector.detectTotalPages(doc);

        // then
        assertThat(totalPages).isEqualTo(5);
    }

    @Test
    @DisplayName("페이지네이션 nav가 없으면 1페이지로 간주한다")
    void detectTotalPages_withoutPagination_returnsOne() {
        // given — 법인(scate=C)처럼 결과가 없거나 1페이지뿐인 경우
        Document doc = Jsoup.parse("<table class=\"regtable\"><tbody></tbody></table>");

        // when
        int totalPages = collector.detectTotalPages(doc);

        // then
        assertThat(totalPages).isEqualTo(1);
    }

    @Test
    @DisplayName("파싱된 코스명은 CourseNameNormalizer를 거치면 기존 코스명과 동일한 정규명으로 통합된다")
    void parse_courseNames_normalizeConsistentlyWithOtherSources() {
        // given — "88cc"(시세닷컴) vs "88(팔팔)"(동아, alias 대상)처럼 표기가 다른 동일 코스를
        // CourseNameNormalizer가 같은 정규명으로 모으는지 확인 (alias 테이블은 CollectService에서 별도 처리)
        Document doc = Jsoup.parse(REGTABLE_FIXTURE);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        CollectedPrice msiseCc = result.get(0); // "88cc"
        CourseNameNormalizer.NormalizedCourse normalized =
                CourseNameNormalizer.normalize(msiseCc.courseName());
        assertThat(normalized.name()).isEqualTo("88"); // "88cc" → cc 접미사 제거 → "88"

        CollectedPrice gaya = result.get(1); // "가야" (구분 컬럼 분리형 — 이름에는 구분이 안 붙음)
        CourseNameNormalizer.NormalizedCourse gayaNormalized =
                CourseNameNormalizer.normalize(gaya.courseName());
        assertThat(gayaNormalized.name()).isEqualTo("가야");
        assertThat(gayaNormalized.type()).isNull(); // 이름 자체에는 구분이 없음 — 구분 컬럼(우대)이 이미 membershipType에 반영됨
    }
}
