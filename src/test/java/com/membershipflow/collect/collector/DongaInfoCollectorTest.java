package com.membershipflow.collect.collector;

import com.membershipflow.collect.collector.DongaInfoCollector.CollectedCourseInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DongaInfoCollectorTest {

    private DongaInfoCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DongaInfoCollector(new DongaCourseLinkFetcher());
    }

    // 실제 동아 상세페이지(88CC, custid=10130&code=1103) 구조 기반 fixture
    private static final String FULL_PAGE = """
            <div class="additional">
              <h4 class="blue_tit mt40 mb20">회원권 추가 정보</h4>
              <table class="horizon"><tbody class="additional-infomation">
                <tr><th>회원권 소개</th>
                    <td data-tpl-name="introduce" id="intro">88컨트리클럽은 보훈기금 증식을 목적으로 설립된 골프장입니다.<br>
                    뛰어난 접근성으로 많은 사랑을 받고 있습니다.</td></tr>
                <tr><th>코스 소개</th>
                    <td data-tpl-name="course-introduce" id="course_intro">서코스 : 총 6,247m, 환상의 명코스입니다. <br><br>
                    동코스 : 총 6,484m, 역동적 코스입니다.</td></tr>
                <tr><th>연혁</th>
                    <td data-tpl-name="history" id="history">1988년 07월 08일 골프장 개장</td></tr>
                <tr><th>위치 정보</th>
                    <td data-tpl-name="location" id="location">경기도 용인시 기흥구 석성로521번길 169<br><br>
                    강남 기준 30~40분 정도 소요</td></tr>
                <tr><th>시세 흐름</th>
                    <td id="price_flow">개인과 법인의 수요가 끊이지 않아 강보합세를 유지하고 있습니다.</td></tr>
                <tr><th>향후 전망</th>
                    <td id="vision">투명한 운영과 회원관리로 많은 골퍼들에게 사랑을 받고 있습니다.</td></tr>
              </tbody></table>
            </div>
            <h4 class="blue_tit mt40 mb0">골프장 이용 정보</h4>
            <h5 class="table_tit small">그린피</h5>
            <table class="list_even">
              <thead><tr><th>구분</th><th>주중</th><th>주말</th></tr></thead>
              <tbody>
                <tr><td>정회원</td><td>68,000</td><td>73,000</td></tr>
                <tr><td>가족회원</td><td>95,000</td><td>260,000</td></tr>
                <tr><td>비회원</td><td>210,000</td><td>260,000</td></tr>
              </tbody>
            </table>
            <h5 class="table_tit small">캐디피 &amp; 카트비</h5>
            <table class="horizon"><tbody><tr>
              <th class="left">캐디피</th><td>1캐디 4백 - 150,000 (1팀당)</td>
              <th>카트비</th><td class="right">100,000(1대당)</td>
            </tr></tbody></table>
            """;

    @Test
    @DisplayName("상세 페이지의 모든 섹션을 파싱한다")
    void parse_fullPage_extractsAllSections() {
        // given
        Document doc = Jsoup.parse(FULL_PAGE);

        // when
        CollectedCourseInfo info = collector.parse(doc, "88(팔팔)");

        // then
        assertThat(info.courseName()).isEqualTo("88(팔팔)");
        assertThat(info.membershipIntro())
                .contains("보훈기금 증식")
                .contains("뛰어난 접근성");
        assertThat(info.courseIntro()).contains("서코스").contains("동코스");
        assertThat(info.priceOutlook())
                .contains("강보합세")           // 시세 흐름
                .contains("투명한 운영");        // 향후 전망
        assertThat(info.caddieFee()).isEqualTo("1캐디 4백 - 150,000 (1팀당)");
        assertThat(info.cartFee()).isEqualTo("100,000(1대당)");
    }

    @Test
    @DisplayName("위치 정보 첫 줄만 주소로 추출한다 (접근성 설명 제외)")
    void parse_location_firstLineOnlyAsAddress() {
        // given
        Document doc = Jsoup.parse(FULL_PAGE);

        // when
        CollectedCourseInfo info = collector.parse(doc, "88(팔팔)");

        // then
        assertThat(info.address()).isEqualTo("경기도 용인시 기흥구 석성로521번길 169");
    }

    @Test
    @DisplayName("그린피 테이블을 [구분|주중|주말] 원 단위 숫자로 파싱한다")
    void parse_greenFeeTable_returnsGradeRows() {
        // given
        Document doc = Jsoup.parse(FULL_PAGE);

        // when
        CollectedCourseInfo info = collector.parse(doc, "88(팔팔)");

        // then
        assertThat(info.greenFees()).hasSize(3);
        assertThat(info.greenFees().get(0).grade()).isEqualTo("정회원");
        assertThat(info.greenFees().get(0).weekday()).isEqualTo(68_000L);
        assertThat(info.greenFees().get(0).weekend()).isEqualTo(73_000L);
        assertThat(info.greenFees().get(2).grade()).isEqualTo("비회원");
        assertThat(info.greenFees().get(2).weekday()).isEqualTo(210_000L);
    }

    @Test
    @DisplayName("섹션이 없는 페이지는 해당 필드만 null로 반환한다")
    void parse_missingSections_returnsNullFields() {
        // given
        Document doc = Jsoup.parse("<html><body><p>내용 없음</p></body></html>");

        // when
        CollectedCourseInfo info = collector.parse(doc, "어딘가CC");

        // then
        assertThat(info.courseName()).isEqualTo("어딘가CC");
        assertThat(info.address()).isNull();
        assertThat(info.membershipIntro()).isNull();
        assertThat(info.courseIntro()).isNull();
        assertThat(info.priceOutlook()).isNull();
        assertThat(info.greenFees()).isNull();
        assertThat(info.caddieFee()).isNull();
        assertThat(info.cartFee()).isNull();
    }

    @Test
    @DisplayName("그린피 금액이 비어있거나 '-'인 행은 건너뛴다")
    void parse_emptyOrDashGreenFee_skipsRow() {
        // given
        Document doc = Jsoup.parse("""
                <h5 class="table_tit small">그린피</h5>
                <table class="list_even">
                  <thead><tr><th>구분</th><th>주중</th><th>주말</th></tr></thead>
                  <tbody>
                    <tr><td>정회원</td><td>-</td><td>-</td></tr>
                    <tr><td></td><td>50,000</td><td>60,000</td></tr>
                    <tr><td>비회원</td><td>210,000</td><td>-</td></tr>
                  </tbody>
                </table>
                """);

        // when
        CollectedCourseInfo info = collector.parse(doc, "어딘가CC");

        // then — 정상 행 1건만 (한쪽 금액만 있어도 유지)
        assertThat(info.greenFees()).hasSize(1);
        assertThat(info.greenFees().get(0).grade()).isEqualTo("비회원");
        assertThat(info.greenFees().get(0).weekday()).isEqualTo(210_000L);
        assertThat(info.greenFees().get(0).weekend()).isNull();
    }

    @Test
    @DisplayName("시세 흐름/향후 전망 중 하나만 있어도 priceOutlook에 담긴다")
    void parse_onlyVision_stillFillsPriceOutlook() {
        // given
        Document doc = Jsoup.parse("""
                <table><tbody>
                  <tr><th>향후 전망</th><td id="vision">안정적인 시세가 예상됩니다.</td></tr>
                </tbody></table>
                """);

        // when
        CollectedCourseInfo info = collector.parse(doc, "어딘가CC");

        // then
        assertThat(info.priceOutlook()).isEqualTo("안정적인 시세가 예상됩니다.");
    }
}
