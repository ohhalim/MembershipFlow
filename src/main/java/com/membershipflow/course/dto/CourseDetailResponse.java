package com.membershipflow.course.dto;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;

import java.util.List;

public record CourseDetailResponse(
        Long id,
        String name,
        String region,
        String category,
        String membershipType,
        Integer holes,
        Long latestPrice,
        String updatedAt,
        Double changeRate,
        String latestPriceSource,
        List<SourcePrice> sources,
        boolean watchlisted,
        Long targetPrice,
        CourseInfoDto info
) {
    public CourseDetailResponse(Long id, String name, String region,
                                String category, String membershipType, Integer holes,
                                Long latestPrice, String updatedAt, Double changeRate,
                                List<SourcePrice> sources, boolean watchlisted,
                                Long targetPrice, CourseInfoDto info) {
        this(id, name, region, category, membershipType, holes,
                latestPrice, updatedAt, changeRate, null, sources,
                watchlisted, targetPrice, info);
    }

    public record SourcePrice(
            String sourceName,
            String sourceUrl,
            Long price,
            String updatedAt,
            boolean isLowest,
            boolean fresh
    ) {
        public SourcePrice(String sourceName, String sourceUrl, Long price,
                           String updatedAt, boolean isLowest) {
            this(sourceName, sourceUrl, price, updatedAt, isLowest, true);
        }
    }

    // 골프장 부가정보 (#141) — 수집 전이면 null
    public record CourseInfoDto(
            String address,
            String membershipIntro,
            String courseIntro,
            String priceOutlook,
            List<GreenFeeDto> greenFees,
            String caddieFee,
            String cartFee
    ) {
        public record GreenFeeDto(String grade, Long weekday, Long weekend) {}
    }

    public static CourseDetailResponse of(
            Long id, String name, String region,
            CourseType courseType, MembershipType membershipType, Integer holes,
            List<com.membershipflow.price.dto.LatestSourcePriceResponse> rawPrices,
            Long latestPrice, String updatedAt, Double changeRate,
            String latestPriceSource,
            boolean watchlisted, Long targetPrice, CourseInfoDto info) {

        List<com.membershipflow.price.dto.LatestSourcePriceResponse> freshPrices = rawPrices.stream()
                .filter(com.membershipflow.price.dto.LatestSourcePriceResponse::fresh)
                .toList();

        Long minPrice = freshPrices.stream()
                .map(com.membershipflow.price.dto.LatestSourcePriceResponse::price)
                .filter(p -> p != null)
                .min(Long::compareTo)
                .orElse(null);

        List<SourcePrice> sources = freshPrices.stream()
                .map(p -> new SourcePrice(
                        p.sourceName(),
                        p.sourceUrl(),
                        p.price(),
                        p.collectedAt() != null ? p.collectedAt().toString() : null,
                        minPrice != null && minPrice.equals(p.price()),
                        p.fresh()
                ))
                .toList();

        return new CourseDetailResponse(id, name, region,
                courseType != null ? courseType.name() : null,
                membershipType != null ? membershipType.name() : null,
                holes, latestPrice, updatedAt, changeRate, latestPriceSource,
                sources, watchlisted, targetPrice, info);
    }

    /** 기존 호출부와의 소스 호환성을 유지하는 팩토리. */
    public static CourseDetailResponse of(
            Long id, String name, String region,
            CourseType courseType, MembershipType membershipType, Integer holes,
            List<com.membershipflow.price.dto.LatestSourcePriceResponse> rawPrices,
            Long latestPrice, String updatedAt, Double changeRate,
            boolean watchlisted, Long targetPrice, CourseInfoDto info) {
        return of(id, name, region, courseType, membershipType, holes, rawPrices,
                latestPrice, updatedAt, changeRate, null, watchlisted, targetPrice, info);
    }
}
