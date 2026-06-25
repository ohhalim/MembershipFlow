package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DongaHistoryCollector {

    private static final String LISTING_URL   = "https://www.dongagolf.co.kr/membership/sise/";
    private static final String DETAIL_URL    = "https://www.dongagolf.co.kr/membership/info?custid=%s&code=%s";
    private static final Pattern CUSTID_CODE  = Pattern.compile("href=\"/membership/info\\?custid=(\\d+)&code=(\\d+)\"[^>]*>([^<]+)</a>");
    private static final Pattern CATEGORIES   = Pattern.compile("categories:\\s*\\[([^\\]]+)\\]");
    private static final Pattern SERIES_DATA  = Pattern.compile("data:\\s*\\[([^\\]]+)\\]");
    private static final Pattern SERIES_NAME  = Pattern.compile("name:\\s*'([^']+)'");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yy/MM/dd");

    public record HistoricalPrice(
            String courseName,
            CourseType courseType,
            MembershipType membershipType,
            long price,
            LocalDateTime collectedAt
    ) {}

    public List<HistoricalPrice> collectAll() {
        List<CourseLink> links = fetchCourseLinks();
        log.info("[동아히스토리] 종목 {}개 수집 시작", links.size());

        List<HistoricalPrice> result = new ArrayList<>();
        int success = 0, fail = 0;

        for (CourseLink link : links) {
            try {
                result.addAll(fetchHistory(link));
                success++;
                Thread.sleep(300); // rate limit
            } catch (Exception e) {
                log.warn("[동아히스토리] 실패: {} - {}", link.name(), e.getMessage());
                fail++;
            }
        }

        log.info("[동아히스토리] 완료: 성공={}, 실패={}", success, fail);
        return result;
    }

    private List<CourseLink> fetchCourseLinks() {
        try {
            Document doc = Jsoup.connect(LISTING_URL)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .timeout(15_000)
                    .get();

            List<CourseLink> links = new ArrayList<>();
            Matcher m = CUSTID_CODE.matcher(doc.html());
            while (m.find()) {
                String custid = m.group(1);
                String code   = m.group(2);
                String name   = m.group(3).trim();
                if (!name.isBlank()) {
                    links.add(new CourseLink(custid, code, name));
                }
            }
            return links;
        } catch (IOException e) {
            throw new CollectException("동아 목록 페이지 요청 실패", e);
        }
    }

    private List<HistoricalPrice> fetchHistory(CourseLink link) throws IOException {
        String url = String.format(DETAIL_URL, link.custid(), link.code());
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                .timeout(15_000)
                .get();

        String html = doc.html();

        Matcher nameMatcher = SERIES_NAME.matcher(html);
        String courseName = nameMatcher.find() ? nameMatcher.group(1) : link.name();

        Matcher catMatcher = CATEGORIES.matcher(html);
        Matcher dataMatcher = SERIES_DATA.matcher(html);

        if (!catMatcher.find() || !dataMatcher.find()) {
            log.debug("[동아히스토리] 차트 데이터 없음: {}", link.name());
            return List.of();
        }

        String[] dateStrs = catMatcher.group(1).replaceAll("\"", "").split(",");
        String[] priceStrs = dataMatcher.group(1).split(",");

        if (dateStrs.length != priceStrs.length) {
            log.warn("[동아히스토리] 날짜/가격 배열 길이 불일치: {}", link.name());
            return List.of();
        }

        MembershipType membershipType = extractMembershipType(courseName);
        List<HistoricalPrice> result = new ArrayList<>();

        for (int i = 0; i < dateStrs.length; i++) {
            try {
                String dateStr = dateStrs[i].trim();
                long price = Long.parseLong(priceStrs[i].trim()) * 10_000L;
                LocalDate date = LocalDate.parse(dateStr, FMT);
                LocalDateTime collectedAt = date.atTime(7, 0);

                result.add(new HistoricalPrice(courseName, CourseType.GOLF, membershipType, price, collectedAt));
            } catch (Exception e) {
                log.debug("[동아히스토리] 데이터 파싱 실패: {} idx={}", link.name(), i);
            }
        }

        return result;
    }

    private MembershipType extractMembershipType(String name) {
        if (name.contains("주중")) return MembershipType.WEEKDAY;
        if (name.contains("주말")) return MembershipType.WEEKEND;
        if (name.contains("우대")) return MembershipType.PREFERRED;
        if (name.contains("법인")) return MembershipType.CORPORATE;
        if (name.contains("가족") || name.contains("부부")) return MembershipType.FAMILY;
        if (name.contains("개인")) return MembershipType.INDIVIDUAL;
        return MembershipType.REGULAR;
    }

    private record CourseLink(String custid, String code, String name) {}
}
