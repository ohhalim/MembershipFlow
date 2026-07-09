package com.membershipflow.collect.collector;

import com.membershipflow.collect.collector.DongaCourseLinkFetcher.CourseLink;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DongaCollector implements PriceCollector {

    private static final String SOURCE_NAME = "동아골프";
    private static final String URL         = "https://www.dongagolf.co.kr/membership/sise/";
    // 헤더: [회원권명][금일시세][전일시세][등락][시세추이][상담]
    // class="list_even"이지만 class="price-data-list"(TOP10, 급등락)은 제외
    private static final String ROW_SELECTOR = "table.list_even:not(.price-data-list) tbody tr";

    // 목록 페이지에서 종목별 상세 링크(custid/code)를 뽑는 공용 컴포넌트 (#141에서 분리, #144에서 재사용)
    private final DongaCourseLinkFetcher linkFetcher;

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

        List<CourseLink> links;
        try {
            links = linkFetcher.fetchCourseLinks();
        } catch (Exception e) {
            log.warn("[{}] 상세 링크(custid/code) 수집 실패 - source_key 없이 진행: {}", SOURCE_NAME, e.getMessage());
            links = Collections.emptyList();
        }

        return parse(doc, links);
    }

    // 헤더: [회원권명][금일시세][전일시세][등락][시세추이][상담]
    List<CollectedPrice> parse(Document doc, List<CourseLink> links) {
        Elements rows = doc.select(ROW_SELECTOR);
        if (rows.isEmpty()) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 행 없음 (selector=" + ROW_SELECTOR + ")");
        }

        // 동아는 시세 목록과 상세 링크 목록이 같은 페이지(같은 <a> 텍스트)에서 나오므로
        // 완전일치가 기본 경로이고, 정규화 키는 공백/표기 차이에 대한 안전망이다.
        Map<String, CourseLink> linksByRawName = links.stream()
                .collect(Collectors.toMap(CourseLink::name, Function.identity(), (a, b) -> a));
        Map<String, CourseLink> linksByNormalizedKey = links.stream()
                .collect(Collectors.toMap(l -> normalizedKey(l.name()), Function.identity(), (a, b) -> a));

        List<CollectedPrice> result = new ArrayList<>();
        int matched = 0;
        for (Element row : rows) {
            Elements tds = row.select("td");
            if (tds.size() < 2) continue;

            String courseName = tds.get(0).text().trim();
            if (courseName.isBlank()) continue;

            String priceText = tds.get(1).text().trim();
            if (priceText.isBlank() || priceText.equals("-")) continue;

            long price;
            try {
                price = parsePrice(priceText);
            } catch (CollectException e) {
                log.warn("[{}] 가격 파싱 실패 - 종목: {}, 값: {}", SOURCE_NAME, courseName, priceText);
                continue;
            }

            // 이름 중간에 포함된 구분만 우선 추출 (null이면 CollectService에서
            // alias/CourseNameNormalizer 추출값 → REGULAR 순으로 결정)
            MembershipType membershipType = CourseNameNormalizer.extractEmbeddedType(courseName);

            String sourceKey = resolveSourceKey(courseName, linksByRawName, linksByNormalizedKey);
            if (sourceKey != null) matched++;

            result.add(new CollectedPrice(
                    courseName, null, CourseType.GOLF,
                    membershipType, null, price, SOURCE_NAME, sourceKey));
        }

        log.info("[{}] 파싱 완료: {}건 (링크 매칭 {}건)", SOURCE_NAME, result.size(), matched);
        return result;
    }

    private String resolveSourceKey(String courseName, Map<String, CourseLink> linksByRawName,
                                     Map<String, CourseLink> linksByNormalizedKey) {
        CourseLink link = linksByRawName.get(courseName);
        if (link == null) {
            link = linksByNormalizedKey.get(normalizedKey(courseName));
        }
        return link != null ? link.custid() + ":" + link.code() : null;
    }

    // (정규명|구분) 조합 키. 완전일치 실패 시 fallback 매칭용
    private String normalizedKey(String rawName) {
        CourseNameNormalizer.NormalizedCourse normalized = CourseNameNormalizer.normalize(rawName);
        MembershipType embedded = CourseNameNormalizer.extractEmbeddedType(rawName);
        MembershipType type = embedded != null ? embedded : normalized.type();
        return normalized.name() + "|" + type;
    }

    // "43,800" (만원) → 438,000,000 (원)
    private long parsePrice(String text) {
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isBlank()) throw new CollectException("가격 파싱 실패: " + text);
        return Long.parseLong(cleaned) * 10_000L;
    }
}
