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
        Long targetPrice
) {
    public record SourcePrice(
            String sourceName,
            String sourceUrl,
            Long price,
            String updatedAt,
            boolean isLowest
    ) {}

    public static CourseDetailResponse of(
            Long id, String name, String region,
            CourseType courseType, MembershipType membershipType, Integer holes,
            List<com.membershipflow.price.dto.LatestSourcePriceResponse> rawPrices,
            boolean watchlisted, Long targetPrice) {

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
                holes, sources, watchlisted, targetPrice);
    }
}
