package com.membershipflow.collect.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.membershipflow.course.entity.MembershipType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoolMembershipCollectorTest {

    private CoolMembershipCollector collector;

    @BeforeEach
    void setUp() {
        collector = new CoolMembershipCollector();
    }

    @Test
    @DisplayName("공개 JSON의 회원권명과 만원 단위 가격을 파싱한다")
    void parse_validItems_returnsCollectedPrices() {
        String body = """
                {"asOf":"2026-07-29","items":[
                  {"name":"88(팔팔)","price":46400,"change":-300},
                  {"name":"서울(여자)","price":83000,"change":0}
                ]}
                """;

        List<CollectedPrice> result = collector.parse(body);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).courseName()).isEqualTo("88(팔팔)");
        assertThat(result.get(0).price()).isEqualTo(464_000_000L);
        assertThat(result.get(0).sourceName()).isEqualTo("회원권 쿨거래");
        assertThat(result.get(1).membershipType()).isNull();
        assertThat(CourseNameNormalizer.normalize(result.get(1).courseName()).type())
                .isEqualTo(MembershipType.FEMALE);
    }

    @Test
    @DisplayName("빈 시세 응답은 파싱 실패로 처리한다")
    void parse_emptyItems_throwsCollectException() {
        assertThatThrownBy(() -> collector.parse("{\"asOf\":\"2026-07-29\",\"items\":[]}"))
                .isInstanceOf(CollectException.class)
                .hasMessageContaining("시세 행 없음");
    }

    @Test
    @DisplayName("가격 범위를 넘는 행만 건너뛴다")
    void parse_overflowingPrice_skipsOnlyInvalidItem() {
        String body = """
                {"items":[
                  {"name":"범위초과","price":922337203685478},
                  {"name":"정상종목","price":10000}
                ]}
                """;

        assertThat(collector.parse(body)).singleElement()
                .extracting(CollectedPrice::courseName)
                .isEqualTo("정상종목");
    }
}
