package com.membershipflow.collect.collector;

import com.membershipflow.collect.collector.DongaCourseLinkFetcher.CourseLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 동아골프 종목 상세 페이지에서 골프장 부가정보를 수집한다 (#141).
 *
 * <p>페이지 구조 (2026-07 실측):
 * <ul>
 *   <li>회원권 소개: {@code td#intro}, 코스 소개: {@code td#course_intro}</li>
 *   <li>위치 정보: {@code td#location} — 첫 줄이 주소, 이후 줄은 접근성 설명</li>
 *   <li>시세 흐름: {@code td#price_flow}, 향후 전망: {@code td#vision}</li>
 *   <li>그린피: "그린피" h5 다음의 테이블 (구분|주중|주말)</li>
 *   <li>캐디피 &amp; 카트비: "캐디피" h5 다음 테이블의 th/td 쌍</li>
 * </ul>
 * 섹션이 없는 골프장도 있으므로 필드별로 null을 허용하고, 개별 페이지 실패는 skip한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DongaInfoCollector {

    private static final String INFO_URL = "https://www.dongagolf.co.kr/membership/info?custid=%s&code=%s";

    private final DongaCourseLinkFetcher linkFetcher;

    public record GreenFee(String grade, Long weekday, Long weekend) {}

    public record CollectedCourseInfo(
            String courseName,
            String address,
            String membershipIntro,
            String courseIntro,
            String priceOutlook,
            List<GreenFee> greenFees,
            String caddieFee,
            String cartFee
    ) {}

    public List<CollectedCourseInfo> collectAll() {
        List<CourseLink> links = linkFetcher.fetchCourseLinks();

        // 같은 골프장(custid)의 회원권 여러 개(일반/우대/주중 등)는 상세 정보를 공유 → custid당 1회만 요청
        Map<String, CourseLink> byClub = links.stream().collect(Collectors.toMap(
                CourseLink::custid, link -> link, (a, b) -> a, LinkedHashMap::new));

        log.info("[동아부가정보] 골프장 {}곳 수집 시작 (링크 {}개)", byClub.size(), links.size());

        List<CollectedCourseInfo> result = new ArrayList<>();
        int fail = 0;
        for (CourseLink link : byClub.values()) {
            try {
                result.add(parse(fetchInfoPage(link), link.name()));
                Thread.sleep(300); // rate limit
            } catch (Exception e) {
                log.warn("[동아부가정보] 실패: {} - {}", link.name(), e.getMessage());
                fail++;
            }
        }

        log.info("[동아부가정보] 완료: 성공={}, 실패={}", result.size(), fail);
        return result;
    }

    private Document fetchInfoPage(CourseLink link) throws java.io.IOException {
        return Jsoup.connect(String.format(INFO_URL, link.custid(), link.code()))
                .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                .timeout(15_000)
                .get();
    }

    CollectedCourseInfo parse(Document doc, String courseName) {
        String location = textWithLineBreaks(doc.selectFirst("td#location"));
        String priceFlow = textWithLineBreaks(doc.selectFirst("td#price_flow"));
        String vision    = textWithLineBreaks(doc.selectFirst("td#vision"));

        return new CollectedCourseInfo(
                courseName,
                extractAddress(location),
                textWithLineBreaks(doc.selectFirst("td#intro")),
                textWithLineBreaks(doc.selectFirst("td#course_intro")),
                joinNonBlank(priceFlow, vision),
                parseGreenFees(doc),
                parseFeeCell(doc, "캐디피"),
                parseFeeCell(doc, "카트비"));
    }

    // 위치 정보 첫 줄이 주소, 이후 줄("강남 기준 30~40분 소요" 등)은 제외
    private String extractAddress(String location) {
        if (location == null) return null;
        String first = location.split("\n", 2)[0].trim();
        return first.isBlank() ? null : first;
    }

    // "그린피" 제목 다음 테이블의 [구분|주중|주말] 행 파싱. 없거나 비면 null
    private List<GreenFee> parseGreenFees(Document doc) {
        Element table = tableAfterHeading(doc, "그린피");
        if (table == null) return null;

        List<GreenFee> fees = new ArrayList<>();
        for (Element row : table.select("tbody tr")) {
            var tds = row.select("td");
            if (tds.size() < 3) continue;
            String grade = tds.get(0).text().trim();
            Long weekday = parseWon(tds.get(1).text());
            Long weekend = parseWon(tds.get(2).text());
            if (grade.isBlank() || (weekday == null && weekend == null)) continue;
            fees.add(new GreenFee(grade, weekday, weekend));
        }
        return fees.isEmpty() ? null : fees;
    }

    // "캐디피 & 카트비" 테이블에서 라벨(th)에 해당하는 td 값 추출
    private String parseFeeCell(Document doc, String label) {
        Element table = tableAfterHeading(doc, "캐디피");
        if (table == null) return null;
        for (Element th : table.select("th")) {
            if (th.text().contains(label)) {
                Element td = th.nextElementSibling();
                if (td != null && "td".equals(td.tagName())) {
                    String text = td.text().trim();
                    return text.isBlank() ? null : text;
                }
            }
        }
        return null;
    }

    // 소제목(h5) 텍스트 뒤에 오는 첫 table
    private Element tableAfterHeading(Document doc, String heading) {
        for (Element h5 : doc.select("h5")) {
            if (!h5.text().contains(heading)) continue;
            Element next = h5.nextElementSibling();
            while (next != null && !"table".equals(next.tagName())) {
                next = next.nextElementSibling();
            }
            return next;
        }
        return null;
    }

    // "68,000" → 68000 (원 단위). 숫자가 없으면 null
    private Long parseWon(String text) {
        if (text == null) return null;
        String digits = text.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Long.parseLong(digits);
    }

    // <br>을 개행으로 보존해 문단 구조를 유지한 텍스트. 비면 null
    private String textWithLineBreaks(Element el) {
        if (el == null) return null;
        Element copy = el.clone();
        copy.select("br").forEach(br -> br.replaceWith(new TextNode("\n")));
        String cleaned = Arrays.stream(copy.wholeText().split("\n"))
                .map(String::trim)
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String joinNonBlank(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "\n\n" + b;
    }
}
