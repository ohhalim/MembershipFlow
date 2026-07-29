package com.membershipflow.collect.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** KB회원권거래소 공개 골프 시세 페이지 수집기. */
@Slf4j
@Component
public class KbGolfCollector implements PriceCollector {

    private static final String SOURCE_NAME = "KB회원권거래소";
    private static final String URL = "https://www.kbgolf.co.kr/golf";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<CollectedPrice> collect() {
        try {
            Document doc = Jsoup.connect(URL)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .timeout(20_000)
                    .get();
            return parse(doc);
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " HTML 요청 실패", e);
        }
    }

    List<CollectedPrice> parse(Document doc) {
        Element priceBoard = doc.selectFirst("astro-island[component-url*=PriceBoard][props]");
        if (priceBoard == null) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 시세 데이터 없음");
        }

        JsonNode rows;
        try {
            JsonNode props = objectMapper.readTree(priceBoard.attr("props"));
            rows = props.path("initial").path(1);
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " 시세 데이터 파싱 실패", e);
        }
        if (!rows.isArray() || rows.isEmpty()) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 시세 행 없음");
        }

        List<CollectedPrice> result = new ArrayList<>();
        for (JsonNode encodedRow : rows) {
            JsonNode item = encodedRow.path(1);
            String courseName = decodedValue(item, "name").asText("").trim();
            long priceInTenThousands = decodedValue(item, "todayPrice").asLong(0);
            if (courseName.isBlank() || priceInTenThousands <= 0) continue;

            try {
                long price = Math.multiplyExact(priceInTenThousands, 10_000L);
                MembershipType membershipType =
                        CourseNameNormalizer.extractEmbeddedType(courseName);
                result.add(new CollectedPrice(
                        courseName, null, CourseType.GOLF,
                        membershipType, null, price, SOURCE_NAME));
            } catch (ArithmeticException e) {
                log.warn("[{}] 가격 범위 초과 - 종목: {}, 값: {}",
                        SOURCE_NAME, courseName, priceInTenThousands);
            }
        }

        log.info("[{}] 파싱 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    private JsonNode decodedValue(JsonNode item, String field) {
        JsonNode encoded = item.path(field);
        return encoded.isArray() && encoded.size() > 1 ? encoded.path(1) : encoded;
    }
}
