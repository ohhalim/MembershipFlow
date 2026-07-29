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

/** 회원권SEARCH 공개 홈페이지의 개인 골프회원권 시세 수집기. */
@Slf4j
@Component
public class MembershipSearchCollector implements PriceCollector {

    private static final String SOURCE_NAME = "회원권SEARCH";
    private static final String URL = "https://membershipsearch.com/";
    private static final String ROW_SELECTOR =
            "table.golf-personal-table:first-of-type tbody tr.golf-personal-row";

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
                    .maxBodySize(1_000_000)
                    .get();
            return parse(doc);
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " HTML 요청 실패", e);
        }
    }

    List<CollectedPrice> parse(Document doc) {
        Element firstTable = doc.selectFirst("table.golf-personal-table");
        if (firstTable == null) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 개인 시세표 없음");
        }
        Elements rows = firstTable.select("tbody tr.golf-personal-row");
        if (rows.isEmpty()) {
            throw new CollectException(
                    SOURCE_NAME + " 파싱 실패: 행 없음 (selector=" + ROW_SELECTOR + ")");
        }

        List<CollectedPrice> result = new ArrayList<>();
        for (Element row : rows) {
            Element nameCell = row.selectFirst("td.golf-personal-name");
            Element priceCell = row.selectFirst("td.golf-personal-price");
            if (nameCell == null || priceCell == null) continue;

            String courseName = nameCell.text().trim();
            if (courseName.isBlank()) continue;

            try {
                long price = parsePrice(priceCell.text());
                MembershipType membershipType =
                        CourseNameNormalizer.extractEmbeddedType(courseName);
                result.add(new CollectedPrice(
                        courseName, null, CourseType.GOLF,
                        membershipType, null, price, SOURCE_NAME));
            } catch (CollectException e) {
                log.warn("[{}] 가격 파싱 실패 - 종목: {}, 값: {}",
                        SOURCE_NAME, courseName, priceCell.text());
            }
        }

        log.info("[{}] 파싱 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    private long parsePrice(String text) {
        String cleaned = text.replace(",", "").replace("만원", "").trim();
        if (!cleaned.matches("[0-9]+")) {
            throw new CollectException("가격 파싱 실패: " + text);
        }
        try {
            return Math.multiplyExact(Long.parseLong(cleaned), 10_000L);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new CollectException("가격 파싱 실패: " + text, e);
        }
    }
}
