package com.membershipflow.collect.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbGolfCollectorTest {

    private KbGolfCollector collector;

    @BeforeEach
    void setUp() {
        collector = new KbGolfCollector();
    }

    @Test
    @DisplayName("공개 페이지의 Astro 초기 데이터에서 전체 골프 시세를 파싱한다")
    void parse_validProps_returnsCollectedPrices() {
        Document doc = Jsoup.parse("""
                <astro-island component-url="/_astro/PriceBoard.js"
                  props='{"category":[0,"golf"],"initial":[1,[[0,{"name":[0,"88(팔팔)"],"todayPrice":[0,46500]}],[0,{"name":[0,"골드(주주)"],"todayPrice":[0,34500]}]]]}'></astro-island>
                """);

        List<CollectedPrice> result = collector.parse(doc);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).courseName()).isEqualTo("88(팔팔)");
        assertThat(result.get(0).price()).isEqualTo(465_000_000L);
        assertThat(result.get(0).sourceName()).isEqualTo("KB회원권거래소");
        assertThat(result.get(1).price()).isEqualTo(345_000_000L);
    }

    @Test
    @DisplayName("시세 데이터가 없으면 파싱 실패로 처리한다")
    void parse_noPriceBoard_throwsCollectException() {
        assertThatThrownBy(() -> collector.parse(Jsoup.parse("<html></html>")))
                .isInstanceOf(CollectException.class)
                .hasMessageContaining("시세 데이터 없음");
    }
}
