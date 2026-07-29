package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/** 프리미엄회원권 공개 홈페이지의 골프회원권 시세 수집기. */
@Slf4j
@Component
public class PremiumGolfCollector implements PriceCollector {

    private static final String SOURCE_NAME = "프리미엄회원권";
    private static final String URL = "https://www.premiumgolf.co.kr/";
    private static final String ROW_SELECTOR =
            "div[data-tab=tab01] div.cont__body.type2 > div.colum";

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<CollectedPrice> collect() {
        Document doc;
        try {
            doc = Jsoup.connect(URL)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .timeout(15_000)
                    .get();
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " HTML 요청 실패", e);
        }
        return parse(doc);
    }

    List<CollectedPrice> parse(Document doc) {
        Elements rows = doc.select(ROW_SELECTOR);
        if (rows.isEmpty()) {
            throw new CollectException(
                    SOURCE_NAME + " 파싱 실패: 행 없음 (selector=" + ROW_SELECTOR + ")");
        }

        List<CollectedPrice> result = new ArrayList<>();
        for (Element row : rows) {
            Elements cells = row.children();
            if (cells.size() < 2) continue;

            String courseName = cells.get(0).text().trim();
            if (courseName.isBlank()) continue;

            String priceText = findPriceCell(cells);
            if (priceText == null) {
                log.warn("[{}] 가격 셀 없음 - 종목: {}", SOURCE_NAME, courseName);
                continue;
            }

            long price;
            try {
                price = parsePrice(priceText);
            } catch (CollectException e) {
                log.warn("[{}] 가격 파싱 실패 - 종목: {}, 값: {}", SOURCE_NAME, courseName, priceText);
                continue;
            }

            MembershipType membershipType = CourseNameNormalizer.extractEmbeddedType(courseName);
            result.add(new CollectedPrice(
                    courseName, null, CourseType.GOLF,
                    membershipType, null, price, SOURCE_NAME));
        }

        log.info("[{}] 파싱 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    private String findPriceCell(Elements cells) {
        for (int i = 1; i < cells.size(); i++) {
            String text = cells.get(i).text().trim();
            if (text.matches("[0-9][0-9,]*")) {
                return text;
            }
        }
        return null;
    }

    private long parsePrice(String text) {
        String cleaned = text.replace(",", "");
        if (cleaned.isBlank()) {
            throw new CollectException("가격 파싱 실패: " + text);
        }
        try {
            long priceInTenThousands = Long.parseLong(cleaned);
            return Math.multiplyExact(priceInTenThousands, 10_000L);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new CollectException("가격 파싱 실패: " + text, e);
        }
    }
}
