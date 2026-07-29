package com.membershipflow.collect.collector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/** 회원권 쿨거래가 공개하는 당일 골프회원권 시세 JSON 수집기. */
@Slf4j
@Component
public class CoolMembershipCollector implements PriceCollector {

    private static final String SOURCE_NAME = "회원권 쿨거래";
    private static final String URL = "https://script.google.com/macros/s/"
            + "AKfycbwDqNSVvxhIfPiGOC6ebo_q9AwUiZY2ANqdG26i5oVvcX3h72eL6eNks91sp2s6Wvrw8g/exec?type=price";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<CollectedPrice> collect() {
        try {
            String body = Jsoup.connect(URL)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .ignoreContentType(true)
                    .timeout(20_000)
                    .execute()
                    .body();
            return parse(body);
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " JSON 요청 실패", e);
        }
    }

    List<CollectedPrice> parse(String body) {
        PriceResponse response;
        try {
            response = objectMapper.readValue(body, PriceResponse.class);
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " JSON 파싱 실패", e);
        }

        if (response.items() == null || response.items().isEmpty()) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 시세 행 없음");
        }

        List<CollectedPrice> result = new ArrayList<>();
        for (PriceItem item : response.items()) {
            if (item.name() == null || item.name().isBlank() || item.price() <= 0) continue;

            try {
                long price = Math.multiplyExact(item.price(), 10_000L);
                MembershipType membershipType =
                        CourseNameNormalizer.extractEmbeddedType(item.name());
                result.add(new CollectedPrice(
                        item.name().trim(), null, CourseType.GOLF,
                        membershipType, null, price, SOURCE_NAME));
            } catch (ArithmeticException e) {
                log.warn("[{}] 가격 범위 초과 - 종목: {}, 값: {}",
                        SOURCE_NAME, item.name(), item.price());
            }
        }

        log.info("[{}] 파싱 완료: {}건, 기준일={}", SOURCE_NAME, result.size(), response.asOf());
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceResponse(String asOf, List<PriceItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceItem(String name, long price) {}
}
