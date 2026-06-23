package com.membershipflow.price.dto;

import java.time.LocalDate;

public record PricePointDto(
        LocalDate date,
        long avgPrice,
        long minPrice,
        long maxPrice,
        long count
) {}
