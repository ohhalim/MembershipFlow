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
        List<SourcePrice> sources,
        boolean watchlisted,
        Long targetPrice,
        CourseInfoDto info
) {
    public record SourcePrice(
            String sourceName,
            String sourceUrl,
            Long price,
            String updatedAt,
            boolean isLowest
    ) {}

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
            boolean watchlisted, Long targetPrice, CourseInfoDto info) {

        Long minPrice = rawPrices.stream()
                .map(com.membershipflow.price.dto.LatestSourcePriceResponse::price)
                .filter(p -> p != null)
                .min(Long::compareTo)
                .orElse(null);

        List<SourcePrice> sources = rawPrices.stream()
                .map(p -> new SourcePrice(
                        p.sourceName(),
                        p.sourceUrl(),
                        p.price(),
                        p.collectedAt() != null ? p.collectedAt().toString() : null,
                        minPrice != null && minPrice.equals(p.price())
                ))
                .toList();

        return new CourseDetailResponse(id, name, region,
                courseType != null ? courseType.name() : null,
                membershipType != null ? membershipType.name() : null,
                holes, sources, watchlisted, targetPrice, info);
    }
}
