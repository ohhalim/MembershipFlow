package com.membershipflow.collect.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MembershipSearchCollectorTest {

    private MembershipSearchCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MembershipSearchCollector();
    }

    @Test
    @DisplayName("첫 번째 개인 시세표의 골프장명과 가격을 파싱한다")
    void parse_validFirstTable_returnsCollectedPrices() {
        Document doc = Jsoup.parse("""
                <table class="golf-personal-table"><tbody>
                  <tr class="golf-personal-row">
                    <td class="golf-personal-name">88</td>
                    <td class="golf-personal-price">47,500</td>
                  </tr>
                  <tr class="golf-personal-row">
                    <td class="golf-personal-name">가야-우대</td>
                    <td class="golf-personal-price">17,500만원</td>
                  </tr>
                </tbody></table>
                <table class="golf-personal-table"><tbody>
                  <tr class="golf-personal-row">
                    <td class="golf-personal-name">오래된 중복표</td>
                    <td class="golf-personal-price">1</td>
                  </tr>
                </tbody></table>
                """);

        List<CollectedPrice> result = collector.parse(doc);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).courseName()).isEqualTo("88");
        assertThat(result.get(0).price()).isEqualTo(475_000_000L);
        assertThat(result.get(0).sourceName()).isEqualTo("회원권SEARCH");
        assertThat(result.get(1).price()).isEqualTo(175_000_000L);
    }

    @Test
    @DisplayName("개인 시세표가 없으면 파싱 실패로 처리한다")
    void parse_noTable_throwsCollectException() {
        assertThatThrownBy(() -> collector.parse(Jsoup.parse("<html></html>")))
                .isInstanceOf(CollectException.class)
                .hasMessageContaining("개인 시세표 없음");
    }
}
