package com.membershipflow.collect.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PremiumGolfCollectorTest {

    private PremiumGolfCollector collector;

    @BeforeEach
    void setUp() {
        collector = new PremiumGolfCollector();
    }

    @Test
    @DisplayName("골프 탭의 회원권명과 금일시세를 파싱한다")
    void parse_validRows_returnsCollectedPrices() {
        Document doc = Jsoup.parse("""
                <div data-tab="tab01">
                  <div class="cont__body type2">
                    <div class="colum">
                      <div>가평베네스트</div><div>150,000</div><div>▲ 0</div>
                    </div>
                    <div class="colum">
                      <div>동래베네스트(남자)</div><div>27,000</div><div>▼ 500</div>
                    </div>
                  </div>
                </div>
                """);

        List<CollectedPrice> result = collector.parse(doc);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).courseName()).isEqualTo("가평베네스트");
        assertThat(result.get(0).price()).isEqualTo(1_500_000_000L);
        assertThat(result.get(0).courseType()).isEqualTo(CourseType.GOLF);
        assertThat(result.get(0).sourceName()).isEqualTo("프리미엄회원권");
        assertThat(result.get(1).membershipType()).isNull();
        CourseNameNormalizer.NormalizedCourse normalized =
                CourseNameNormalizer.normalize(result.get(1).courseName());
        assertThat(normalized.name()).isEqualTo("동래베네스트");
        assertThat(normalized.type()).isEqualTo(MembershipType.MALE);
    }

    @Test
    @DisplayName("중복 텍스트 셀이 있어도 첫 숫자 셀을 금일시세로 사용한다")
    void parse_extraTextCell_usesFirstNumericCell() {
        Document doc = Jsoup.parse("""
                <div data-tab="tab01">
                  <div class="cont__body type2">
                    <div class="colum">
                      <div>기흥</div><div>기흥</div><div>30,850</div><div>▲ 100</div>
                    </div>
                  </div>
                </div>
                """);

        List<CollectedPrice> result = collector.parse(doc);

        assertThat(result).singleElement()
                .extracting(CollectedPrice::price)
                .isEqualTo(308_500_000L);
    }

    @Test
    @DisplayName("숫자 가격 셀이 없는 행은 건너뛴다")
    void parse_noNumericPrice_skipsRow() {
        Document doc = Jsoup.parse("""
                <div data-tab="tab01">
                  <div class="cont__body type2">
                    <div class="colum"><div>문의종목</div><div>문의</div><div>-</div></div>
                    <div class="colum"><div>정상종목</div><div>10,000</div><div>0</div></div>
                  </div>
                </div>
                """);

        List<CollectedPrice> result = collector.parse(doc);

        assertThat(result).singleElement()
                .extracting(CollectedPrice::courseName)
                .isEqualTo("정상종목");
    }

    @Test
    @DisplayName("long 범위를 넘는 가격 행만 건너뛴다")
    void parse_overflowingPrice_skipsOnlyInvalidRows() {
        Document doc = Jsoup.parse("""
                <div data-tab="tab01">
                  <div class="cont__body type2">
                    <div class="colum"><div>파싱범위초과</div><div>9,223,372,036,854,775,808</div><div>0</div></div>
                    <div class="colum"><div>곱셈범위초과</div><div>922,337,203,685,478</div><div>0</div></div>
                    <div class="colum"><div>정상종목</div><div>10,000</div><div>0</div></div>
                  </div>
                </div>
                """);

        List<CollectedPrice> result = collector.parse(doc);

        assertThat(result).singleElement()
                .extracting(CollectedPrice::courseName)
                .isEqualTo("정상종목");
    }

    @Test
    @DisplayName("골프 시세 행이 없으면 파싱 실패로 처리한다")
    void parse_noRows_throwsCollectException() {
        Document doc = Jsoup.parse("<html><body></body></html>");

        assertThatThrownBy(() -> collector.parse(doc))
                .isInstanceOf(CollectException.class)
                .hasMessageContaining("행 없음");
    }
}
