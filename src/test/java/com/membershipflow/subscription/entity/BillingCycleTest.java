package com.membershipflow.subscription.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BillingCycleTest {

    private static final LocalDateTime BILLED_AT =
            LocalDateTime.of(2026, 8, 8, 12, 0);

    @Test
    @DisplayName("월간 플랜의 다음 결제일은 1개월 뒤다")
    void monthly_advancesOneMonth() {
        assertThat(BillingCycle.MONTHLY.nextBillingAt(BILLED_AT))
                .isEqualTo(BILLED_AT.plusMonths(1));
    }

    @Test
    @DisplayName("연간 플랜의 다음 결제일은 12개월 뒤다")
    void annual_advancesTwelveMonths() {
        assertThat(BillingCycle.ANNUAL.nextBillingAt(BILLED_AT))
                .isEqualTo(BILLED_AT.plusMonths(12));
    }
}
