package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AceGolfCollectorTest {

    private AceGolfCollector collector;

    @BeforeEach
    void setUp() {
        collector = new AceGolfCollector();
    }

    // 실제 사이트(https://www.acegolf.com/membership/i_golf_info_money.html) 응답 구조를 그대로 재현한 fixture.
    // 헤더 행 없이 tbody에 데이터 행만 존재. 컬럼: [회원권명(td.cc)][금일시세][등락][등락율][시가표준액][그래프][상담]
    private static final String LIST_TABLE_FIXTURE = """
            <table class="list">
              <tbody>
                <tr>
                  <td class="cc"><a href="golf_detail_info.php?code=e20&m_id=e20_p01" target="_parent">88(팔팔)</a></td>
                  <td class="number" style="width: 70px;">45,000</td>
                  <td class="number" style="width: 60px;">0</td>
                  <td class="number" style="width: 60px;">0.00%</td>
                  <td class="number" style="width: 60px;">9,990</td>
                  <td style="width: 49px;"><a href="javascript:go_graph('e20_p01');"><img src="/images/membership/golf/btn_graph.gif"></a></td>
                  <td style="width: 49px;"><a href="/membership/pop_sell_consult1.php"><img src="/images/membership/golf/btn_go.gif"></a></td>
                </tr>
                <tr>
                  <td class="cc"><a href="golf_detail_info.php?code=l04&m_id=l04_p01" target="_parent">가야</a></td>
                  <td class="number" style="width: 70px;">14,700</td>
                  <td class="number" style="width: 60px;">0</td>
                  <td class="number" style="width: 60px;">0.00%</td>
                  <td class="number" style="width: 60px;">7,250</td>
                  <td style="width: 49px;"><a href="javascript:go_graph('l04_p01');"><img src="/images/membership/golf/btn_graph.gif"></a></td>
                  <td style="width: 49px;"><a href="/membership/pop_sell_consult1.php"><img src="/images/membership/golf/btn_go.gif"></a></td>
                </tr>
                <tr>
                  <td class="cc"><a href="golf_detail_info.php?code=l04&m_id=l04_p02" target="_parent">가야 우대</a></td>
                  <td class="number" style="width: 70px;">16,800</td>
                  <td class="number" style="width: 60px;">0</td>
                  <td class="number" style="width: 60px;">0.00%</td>
                  <td class="number" style="width: 60px;">8,700</td>
                  <td style="width: 49px;"><a href="javascript:go_graph('l04_p02');"><img src="/images/membership/golf/btn_graph.gif"></a></td>
                  <td style="width: 49px;"><a href="/membership/pop_sell_consult1.php"><img src="/images/membership/golf/btn_go.gif"></a></td>
                </tr>
                <tr>
                  <td class="cc"><a href="golf_detail_info.php?code=k03&m_id=k03_p01" target="_parent">강남300 주중개인</a></td>
                  <td class="number" style="width: 70px;">2,300</td>
                  <td class="number" style="width: 60px;">0</td>
                  <td class="number" style="width: 60px;">0.00%</td>
                  <td class="number" style="width: 60px;">1,200</td>
                  <td style="width: 49px;"><a href="javascript:go_graph('k03_p01');"><img src="/images/membership/golf/btn_graph.gif"></a></td>
                  <td style="width: 49px;"><a href="/membership/pop_sell_consult1.php"><img src="/images/membership/golf/btn_go.gif"></a></td>
                </tr>
              </tbody>
            </table>
            """;

    @Test
    @DisplayName("정상 HTML을 파싱하면 CollectedPrice 목록을 반환한다")
    void parse_validHtml_returnsCollectedPrices() {
        // given
        Document doc = Jsoup.parse(LIST_TABLE_FIXTURE);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        assertThat(result).hasSize(4);

        CollectedPrice first = result.get(0);
        assertThat(first.courseName()).isEqualTo("88(팔팔)");
        assertThat(first.price()).isEqualTo(450_000_000L);  // 45,000만원 × 10,000
        assertThat(first.courseType()).isEqualTo(CourseType.GOLF);
        // 이름 중간에 구분 키워드가 없으면 null → CollectService에서 alias/normalizer로 최종 결정
        assertThat(first.membershipType()).isNull();
        assertThat(first.holes()).isNull();  // 에이스회원권은 홀수 미제공
        assertThat(first.sourceName()).isEqualTo("에이스회원권");

        CollectedPrice second = result.get(1);
        assertThat(second.courseName()).isEqualTo("가야");
        assertThat(second.price()).isEqualTo(147_000_000L); // 14,700만원
    }

    @Test
    @DisplayName("코스명에 붙은 구분 키워드('우대', '주중')로 회원권 종류를 추정한다")
    void parse_embeddedTypeInName_mapsToMembershipType() {
        // given
        Document doc = Jsoup.parse(LIST_TABLE_FIXTURE);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then — "가야 우대" → PREFERRED, "강남300 주중개인" → WEEKDAY (주중이 개인보다 우선)
        assertThat(result.get(2).courseName()).isEqualTo("가야 우대");
        assertThat(result.get(2).membershipType()).isEqualTo(MembershipType.PREFERRED);
        assertThat(result.get(3).courseName()).isEqualTo("강남300 주중개인");
        assertThat(result.get(3).membershipType()).isEqualTo(MembershipType.WEEKDAY);
    }

    @Test
    @DisplayName("EUC-KR로 인코딩된 응답도 meta charset 감지로 한글 코스명이 정상 파싱된다")
    void parse_eucKrEncodedHtml_parsesKoreanCourseNames() throws IOException {
        // given — 실제 응답과 동일하게 HTTP 헤더에 charset이 없다고 가정하고
        // meta http-equiv(euc-kr)만으로 Jsoup이 인코딩을 감지하는 경로를 검증
        String html = """
                <html><head>
                <meta http-equiv="Content-Type" content="text/html; charset=euc-kr">
                </head><body>
                """ + LIST_TABLE_FIXTURE + "</body></html>";
        byte[] eucKrBytes = html.getBytes(Charset.forName("EUC-KR"));

        // when — charsetName=null: Jsoup이 BOM/meta 태그에서 인코딩을 스스로 감지
        Document doc = Jsoup.parse(
                new ByteArrayInputStream(eucKrBytes), null,
                "https://www.acegolf.com/membership/i_golf_info_money.html");
        List<CollectedPrice> result = collector.parse(doc);

        // then — 한글이 깨지면 정규화/코스 매칭이 전부 실패하므로 원문 그대로인지 검증
        assertThat(doc.charset().name()).isEqualToIgnoringCase("EUC-KR");
        assertThat(result).hasSize(4);
        assertThat(result).extracting(CollectedPrice::courseName)
                .containsExactly("88(팔팔)", "가야", "가야 우대", "강남300 주중개인");
    }

    @Test
    @DisplayName("금일시세가 비어있거나 '-'인 행은 건너뛴다")
    void parse_emptyOrDashPrice_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <table class="list">
                  <tbody>
                    <tr><td class="cc">빈시세</td><td class="number"></td><td>0</td><td>0.00%</td><td>1,000</td></tr>
                    <tr><td class="cc">대시시세</td><td class="number">-</td><td>0</td><td>0.00%</td><td>1,000</td></tr>
                    <tr><td class="cc">정상종목</td><td class="number">10,000</td><td>0</td><td>0.00%</td><td>1,000</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("정상종목");
    }

    @Test
    @DisplayName("가격에 숫자가 없는 행은 warn 로그 후 건너뛴다")
    void parse_unparsablePrice_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <table class="list">
                  <tbody>
                    <tr><td class="cc">문의종목</td><td class="number">문의</td><td>0</td><td>0.00%</td><td>1,000</td></tr>
                    <tr><td class="cc">정상종목</td><td class="number">10,000</td><td>0</td><td>0.00%</td><td>1,000</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseName()).isEqualTo("정상종목");
    }

    @Test
    @DisplayName("컬럼이 2개 미만이거나 코스명이 빈 행은 건너뛴다")
    void parse_insufficientColumnsOrBlankName_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <table class="list">
                  <tbody>
                    <tr><td class="cc">단일셀행</td></tr>
                    <tr><td class="cc"></td><td class="number">5,000</td><td>0</td><td>0.00%</td><td>1,000</td></tr>
                    <tr><td class="cc">정상종목</td><td class="number">10,000</td><td>0</td><td>0.00%</td><td>1,000</td></tr>
                  </tbody>
                </table>
                """);

        // when
        List<CollectedPrice> result = collector.parse(doc);

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
        assertThatThrownBy(() -> collector.parse(doc))
                .isInstanceOf(CollectException.class)
                .hasMessageContaining("행 없음");
    }

    @Test
    @DisplayName("파싱된 코스명은 CourseNameNormalizer를 거치면 기존 코스명과 동일한 정규명으로 통합된다")
    void parse_courseNames_normalizeConsistentlyWithOtherSources() {
        // given — "가야 우대"(에이스, 공백 포함)가 동아의 "가야우대"와 같은 (가야, PREFERRED)로
        // 정규화되는지 확인 (alias 테이블은 CollectService에서 별도 처리)
        Document doc = Jsoup.parse(LIST_TABLE_FIXTURE);

        // when
        List<CollectedPrice> result = collector.parse(doc);

        // then
        CourseNameNormalizer.NormalizedCourse gayaPreferred =
                CourseNameNormalizer.normalize(result.get(2).courseName()); // "가야 우대"
        assertThat(gayaPreferred.name()).isEqualTo("가야");
        assertThat(gayaPreferred.type()).isEqualTo(MembershipType.PREFERRED);

        CourseNameNormalizer.NormalizedCourse gaya =
                CourseNameNormalizer.normalize(result.get(1).courseName()); // "가야"
        assertThat(gaya.name()).isEqualTo("가야");
        assertThat(gaya.type()).isNull();
    }
}
