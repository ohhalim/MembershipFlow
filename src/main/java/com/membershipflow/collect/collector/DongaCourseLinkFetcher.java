package com.membershipflow.collect.collector;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 동아골프 시세 목록 페이지에서 종목별 상세 페이지 링크(custid/code)를 수집한다.
 * DongaHistoryCollector(시세 히스토리)와 DongaInfoCollector(부가정보)가 공용으로 사용.
 */
@Slf4j
@Component
public class DongaCourseLinkFetcher {

    static final String LISTING_URL = "https://www.dongagolf.co.kr/membership/sise/";
    private static final Pattern CUSTID_CODE_URL = Pattern.compile("custid=(\\d+)&code=(\\d+)");

    public record CourseLink(String custid, String code, String name) {}

    @PostConstruct
    void init() {
        // 동아골프 서버가 512-bit DH 키를 사용하므로 해당 제한만 제거
        String current = Security.getProperty("jdk.tls.disabledAlgorithms");
        if (current != null) {
            String updated = Arrays.stream(current.split(","))
                    .map(String::trim)
                    .filter(s -> !s.startsWith("DH keySize"))
                    .collect(Collectors.joining(", "));
            Security.setProperty("jdk.tls.disabledAlgorithms", updated);
            log.info("[동아링크] DH keySize 제한 해제 완료");
        }
    }

    public List<CourseLink> fetchCourseLinks() {
        try {
            Document doc = Jsoup.connect(LISTING_URL)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .timeout(15_000)
                    .get();
            return parseLinks(doc);
        } catch (IOException e) {
            throw new CollectException("동아 목록 페이지 요청 실패", e);
        }
    }

    List<CourseLink> parseLinks(Document doc) {
        List<CourseLink> links = new ArrayList<>();
        // .html()은 &를 &amp;로 인코딩해 regex 미매칭 → Jsoup 선택자 + .attr()로 대체
        for (var a : doc.select("a[href*=/membership/info]")) {
            String href = a.attr("href");
            Matcher m = CUSTID_CODE_URL.matcher(href);
            if (m.find()) {
                String name = a.text().trim();
                if (!name.isBlank()) {
                    links.add(new CourseLink(m.group(1), m.group(2), name));
                }
            }
        }
        return links;
    }
}
