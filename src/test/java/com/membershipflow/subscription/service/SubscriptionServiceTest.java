package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.common.util.BillingKeyEncryptor;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.client.TossPaymentsClient;
import com.membershipflow.subscription.entity.BillingAttempt;
import com.membershipflow.subscription.entity.PaymentHistory;
import com.membershipflow.subscription.entity.PaymentStatus;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.repository.BillingAttemptRepository;
import com.membershipflow.subscription.repository.PaymentHistoryRepository;
import com.membershipflow.subscription.repository.SubscriptionPlanRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final long SUBSCRIPTION_ID = 1L;

    @Mock MemberRepository           memberRepository;
    @Mock SubscriptionPlanRepository planRepository;
    @Mock BillingAttemptRepository   billingAttemptRepository;
    @Mock SubscriptionRepository     subscriptionRepository;
    @Mock PaymentHistoryRepository   paymentHistoryRepository;
    @Mock TossPaymentsClient         tossPaymentsClient;
    @Mock BillingKeyEncryptor        billingKeyEncryptor;
    @Mock InitialPaymentStateService initialPaymentStateService;

    @InjectMocks SubscriptionService subscriptionService;

    Member member;
    SubscriptionPlan plan;

    @BeforeEach
    void setUp() {
        member = Member.builder().id(10L).email("sub@test.com").build();
        plan = mock(SubscriptionPlan.class);
    }

    @Test
    @DisplayName("플랜 목록은 활성 플랜만 ID 순으로 조회한다")
    void getPlans_returnsActivePlansOnly() {
        given(planRepository.findAllByActiveTrueOrderById()).willReturn(List.of(plan));

        assertThat(subscriptionService.getPlans()).hasSize(1);

        then(planRepository).should().findAllByActiveTrueOrderById();
        then(planRepository).should(never()).findAll();
    }

    @Test
    @DisplayName("비활성 또는 존재하지 않는 플랜은 결제 준비를 차단한다")
    void prepare_inactivePlan_throwsBeforeAttemptCreation() {
        given(memberRepository.findByIdForUpdate(member.getId())).willReturn(Optional.of(member));
        given(planRepository.findByIdAndActiveTrue(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.prepare(member.getId(), 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND);

        then(billingAttemptRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("동일 회원의 유효한 결제 준비 요청은 새 시도를 만들지 않고 기존 customerKey를 재사용한다")
    void prepare_reusesExistingPendingAttempt() {
        BillingAttempt active = BillingAttempt.builder()
                .member(member).plan(plan).customerKey("existing-customer-key")
                .build();
        given(memberRepository.findByIdForUpdate(member.getId())).willReturn(Optional.of(member));
        given(planRepository.findByIdAndActiveTrue(1L)).willReturn(Optional.of(plan));
        given(subscriptionRepository.findByMemberId(member.getId())).willReturn(Optional.empty());
        given(billingAttemptRepository.findAllByMemberIdAndStatusInOrderByIdAsc(
                eq(member.getId()), any())).willReturn(List.of(active));
        given(plan.getId()).willReturn(1L);

        var response = subscriptionService.prepare(member.getId(), 1L);

        assertThat(response.customerKey()).isEqualTo("existing-customer-key");
        then(billingAttemptRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("처리 중인 결제 준비 요청은 새 시도를 만들지 않는다")
    void prepare_processingAttempt_blocksNewAttempt() {
        BillingAttempt processing = BillingAttempt.builder()
                .member(member).plan(plan).customerKey("processing-customer-key")
                .build();
        processing.startProcessing();
        given(memberRepository.findByIdForUpdate(member.getId())).willReturn(Optional.of(member));
        given(planRepository.findByIdAndActiveTrue(1L)).willReturn(Optional.of(plan));
        given(subscriptionRepository.findByMemberId(member.getId())).willReturn(Optional.empty());
        given(billingAttemptRepository.findAllByMemberIdAndStatusInOrderByIdAsc(
                eq(member.getId()), any())).willReturn(List.of(processing));

        assertThatThrownBy(() -> subscriptionService.prepare(member.getId(), 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
        then(billingAttemptRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("빌링키 발급 전에 중단된 처리 상태는 10분 후 종료하고 새 인증을 허용한다")
    void prepare_abandonedBillingIssueAttempt_allowsNewAttempt() {
        BillingAttempt abandoned = BillingAttempt.builder()
                .member(member).plan(plan).customerKey("abandoned-customer-key")
                .build();
        abandoned.startProcessing();
        ReflectionTestUtils.setField(
                abandoned, "processingAt", LocalDateTime.now().minusMinutes(11));
        given(memberRepository.findByIdForUpdate(member.getId())).willReturn(Optional.of(member));
        given(planRepository.findByIdAndActiveTrue(1L)).willReturn(Optional.of(plan));
        given(subscriptionRepository.findByMemberId(member.getId())).willReturn(Optional.empty());
        given(billingAttemptRepository.findAllByMemberIdAndStatusInOrderByIdAsc(
                eq(member.getId()), any())).willReturn(List.of(abandoned));

        var response = subscriptionService.prepare(member.getId(), 1L);

        assertThat(abandoned.getStatus())
                .isEqualTo(com.membershipflow.subscription.entity.BillingAttemptStatus.FAILED);
        assertThat(response.customerKey()).isNotEqualTo("abandoned-customer-key");
        then(billingAttemptRepository).should().save(any(BillingAttempt.class));
    }

    private Subscription subscriptionDueAt(LocalDateTime nextBillingAt) {
        Subscription sub = Subscription.builder()
                .member(member).plan(plan)
                .billingKey("enc-key").customerKey("customer-key")
                .startedAt(LocalDateTime.now().minusMonths(1))
                .nextBillingAt(nextBillingAt)
                .build();
        ReflectionTestUtils.setField(sub, "id", SUBSCRIPTION_ID);
        return sub;
    }

    @Test
    @DisplayName("종료일이 지난 취소 구독은 이용 종료 상태와 종료일을 반환한다")
    void getMySubscription_expiredCancelled_returnsInactiveServiceState() {
        LocalDateTime serviceEndsAt = LocalDateTime.now().minusDays(1);
        Subscription sub = subscriptionDueAt(serviceEndsAt);
        sub.cancel();
        given(subscriptionRepository.findByMemberId(member.getId())).willReturn(Optional.of(sub));

        var response = subscriptionService.getMySubscription(member.getId());

        assertThat(response.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(response.serviceActive()).isFalse();
        assertThat(response.serviceEndsAt()).isEqualTo(serviceEndsAt);
    }

    @Test
    @DisplayName("결제일이 도래한 ACTIVE 구독은 정기결제를 실행한다")
    void processBilling_dueActiveSubscription_charges() {
        // given
        Subscription sub = subscriptionDueAt(LocalDateTime.now().minusHours(1));
        given(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).willReturn(Optional.of(sub));
        given(billingKeyEncryptor.decrypt("enc-key")).willReturn("raw-key");
        given(plan.getPrice()).willReturn(9900);
        given(plan.getName()).willReturn("프리미엄");
        given(tossPaymentsClient.charge(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .willReturn(new TossPaymentsClient.PaymentResponse("pay-key", "2026-07-10", 9900, null));

        // when
        subscriptionService.processBilling(SUBSCRIPTION_ID);

        // then
        then(tossPaymentsClient).should()
                .charge(anyString(), anyString(), anyInt(), anyString(), anyString());
        then(paymentHistoryRepository).should().save(any());
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("승인 응답 유실 후 주문 조회에서 결제를 확인하면 추가 과금 없이 성공 처리한다")
    void processBilling_chargeResponseLost_recoversApprovedPayment() {
        Subscription sub = subscriptionDueAt(
                LocalDateTime.of(2026, 7, 25, 0, 0));
        given(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID))
                .willReturn(Optional.of(sub));
        given(billingKeyEncryptor.decrypt("enc-key")).willReturn("raw-key");
        given(plan.getPrice()).willReturn(9900);
        given(plan.getName()).willReturn("프리미엄");
        given(tossPaymentsClient.charge(
                anyString(), anyString(), anyInt(), anyString(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR));
        given(tossPaymentsClient.findPaymentByOrderId("AUTO-1-20260725000000-0"))
                .willReturn(Optional.of(new TossPaymentsClient.PaymentResponse(
                        "recovered-payment-key", "2026-07-25", 9900, null)));

        subscriptionService.processBilling(SUBSCRIPTION_ID);

        ArgumentCaptor<PaymentHistory> historyCaptor =
                ArgumentCaptor.forClass(PaymentHistory.class);
        then(paymentHistoryRepository).should().save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getTossOrderId())
                .isEqualTo("AUTO-1-20260725000000-0");
        assertThat(historyCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(historyCaptor.getValue().getTossPaymentKey())
                .isEqualTo("recovered-payment-key");
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("승인 내역이 없는 결제 실패는 다음 재시도에서 새 주문번호를 사용한다")
    void processBilling_confirmedFailure_nextRetryUsesNextAttemptOrderId() {
        Subscription sub = subscriptionDueAt(
                LocalDateTime.of(2026, 7, 25, 0, 0));
        given(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID))
                .willReturn(Optional.of(sub));
        given(billingKeyEncryptor.decrypt("enc-key")).willReturn("raw-key");
        given(plan.getPrice()).willReturn(9900);
        given(plan.getName()).willReturn("프리미엄");
        given(tossPaymentsClient.charge(
                anyString(), anyString(), anyInt(), anyString(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR));
        given(tossPaymentsClient.findPaymentByOrderId(anyString()))
                .willReturn(Optional.empty());

        subscriptionService.processBilling(SUBSCRIPTION_ID);
        subscriptionService.processBilling(SUBSCRIPTION_ID);

        ArgumentCaptor<PaymentHistory> historyCaptor =
                ArgumentCaptor.forClass(PaymentHistory.class);
        then(paymentHistoryRepository).should(org.mockito.Mockito.times(2))
                .save(historyCaptor.capture());
        assertThat(historyCaptor.getAllValues())
                .extracting(PaymentHistory::getTossOrderId)
                .containsExactly(
                        "AUTO-1-20260725000000-0",
                        "AUTO-1-20260725000000-1");
        assertThat(sub.getFailCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("취소된 구독은 결제일이 지났어도 과금하지 않는다 (#178 재검증 가드)")
    void processBilling_cancelledSubscription_skipsCharge() {
        // given — 배치 조회 이후 사용자가 취소한 상황
        Subscription sub = subscriptionDueAt(LocalDateTime.now().minusHours(1));
        sub.cancel();
        given(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).willReturn(Optional.of(sub));

        // when
        subscriptionService.processBilling(SUBSCRIPTION_ID);

        // then
        then(tossPaymentsClient).should(never())
                .charge(anyString(), anyString(), anyInt(), anyString(), anyString());
        then(paymentHistoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("nextBillingAt이 미래면(이미 결제됨) 중복 과금하지 않는다 (#178 멱등성)")
    void processBilling_alreadyBilled_skipsCharge() {
        // given — 같은 배치에서 중복 호출됐거나 이미 갱신된 상황
        Subscription sub = subscriptionDueAt(LocalDateTime.now().plusDays(20));
        given(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).willReturn(Optional.of(sub));

        // when
        subscriptionService.processBilling(SUBSCRIPTION_ID);

        // then
        then(tossPaymentsClient).should(never())
                .charge(anyString(), anyString(), anyInt(), anyString(), anyString());
        then(paymentHistoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("여러 회원의 활성 구독 여부를 한 번에 조회한다")
    void getSubscriberMemberIds_returnsBatchQueryResult() {
        // given
        List<Long> memberIds = List.of(10L, 20L);
        given(subscriptionRepository.findSubscriberMemberIds(
                eq(memberIds),
                eq(SubscriptionStatus.ACTIVE),
                eq(SubscriptionStatus.CANCELLED),
                any(LocalDateTime.class)))
                .willReturn(List.of(10L));

        // when
        Set<Long> result = subscriptionService.getSubscriberMemberIds(memberIds);

        // then
        assertThat(result).containsExactly(10L);
        then(subscriptionRepository).should().findSubscriberMemberIds(
                eq(memberIds),
                eq(SubscriptionStatus.ACTIVE),
                eq(SubscriptionStatus.CANCELLED),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("최초 결제 전에 빌링키와 고정 주문번호를 저장한다")
    void handleCallback_newAttempt_persistsBillingKeyBeforeCharge() {
        var initial = new InitialPaymentStateService.InitialPaymentContext(
                "new-customer-key", member.getId(), "프리미엄", 9900,
                null, null, "issue-idempotency-key", null, false, false);
        var stored = new InitialPaymentStateService.InitialPaymentContext(
                "new-customer-key", member.getId(), "프리미엄", 9900,
                "enc-new-key", "ORDER-new-customer-key",
                "issue-idempotency-key", "charge-idempotency-key", false, true);
        given(initialPaymentStateService.claim("new-customer-key")).willReturn(initial);
        given(tossPaymentsClient.issueBillingKey(
                "new-customer-key", "auth-key", "issue-idempotency-key"))
                .willReturn(new TossPaymentsClient.BillingKeyResponse(
                        "new-billing-key", "new-customer-key",
                        new TossPaymentsClient.BillingKeyResponse.CardInfo("1234-****"),
                        "국민", "1234-****"));
        given(billingKeyEncryptor.encrypt("new-billing-key")).willReturn("enc-new-key");
        given(initialPaymentStateService.storeBillingKey(
                "new-customer-key", "enc-new-key", "ORDER-new-customer-key",
                "1234-****", "국민"))
                .willReturn(stored);
        given(billingKeyEncryptor.decrypt("enc-new-key")).willReturn("new-billing-key");
        given(tossPaymentsClient.charge(
                "new-billing-key", "new-customer-key", 9900,
                "ORDER-new-customer-key", "프리미엄 구독 결제", "charge-idempotency-key"))
                .willReturn(completedInitialPayment("pay-key", "ORDER-new-customer-key"));

        subscriptionService.handleCallback("new-customer-key", "auth-key");

        then(initialPaymentStateService).should().storeBillingKey(
                "new-customer-key", "enc-new-key", "ORDER-new-customer-key",
                "1234-****", "국민");
        then(tossPaymentsClient).should().charge(
                "new-billing-key", "new-customer-key", 9900,
                "ORDER-new-customer-key", "프리미엄 구독 결제", "charge-idempotency-key");
        then(initialPaymentStateService).should().complete(
                eq("new-customer-key"), any(TossPaymentsClient.PaymentResponse.class));
    }

    @Test
    @DisplayName("최초 결제 승인 응답 유실 시 고정 주문번호 조회로 성공을 복구한다")
    void handleCallback_chargeResponseLost_recoversByOrderId() {
        var stored = new InitialPaymentStateService.InitialPaymentContext(
                "customer-key", member.getId(), "프리미엄", 9900,
                "encrypted-key", "ORDER-customer-key",
                "issue-idempotency-key", "charge-idempotency-key", false, true);
        given(initialPaymentStateService.claim("customer-key")).willReturn(stored);
        given(billingKeyEncryptor.decrypt("encrypted-key")).willReturn("raw-key");
        given(tossPaymentsClient.charge(
                "raw-key", "customer-key", 9900,
                "ORDER-customer-key", "프리미엄 구독 결제", "charge-idempotency-key"))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR));
        var recovered = completedInitialPayment("payment-key", "ORDER-customer-key");
        given(tossPaymentsClient.findPaymentByOrderId("ORDER-customer-key"))
                .willReturn(Optional.of(recovered));

        subscriptionService.handleCallback("customer-key", "auth-key");

        then(tossPaymentsClient).should(never())
                .issueBillingKey(anyString(), anyString(), anyString());
        then(initialPaymentStateService).should().complete("customer-key", recovered);
    }

    @Test
    @DisplayName("처리 중 콜백은 재과금하지 않고 기존 주문 승인만 복구한다")
    void handleCallback_processingAttempt_recoversWithoutChargingAgain() {
        var processing = new InitialPaymentStateService.InitialPaymentContext(
                "customer-key", member.getId(), "프리미엄", 9900,
                "encrypted-key", "ORDER-customer-key", false, true);
        var recovered = completedInitialPayment("payment-key", "ORDER-customer-key");
        given(initialPaymentStateService.claim("customer-key")).willReturn(processing);
        given(tossPaymentsClient.findPaymentByOrderId("ORDER-customer-key"))
                .willReturn(Optional.of(recovered));

        subscriptionService.handleCallback("customer-key", "auth-key");

        then(tossPaymentsClient).should(never()).charge(
                anyString(), anyString(), anyInt(), anyString(), anyString());
        then(initialPaymentStateService).should().complete("customer-key", recovered);
    }

    @Test
    @DisplayName("처리 중 결제의 승인 상태를 확인할 수 없으면 재과금하지 않고 PROCESSING을 유지한다")
    void handleCallback_processingAttempt_statusCheckFailure_doesNotComplete() {
        var processing = new InitialPaymentStateService.InitialPaymentContext(
                "customer-key", member.getId(), "프리미엄", 9900,
                "encrypted-key", "ORDER-customer-key", false, true);
        given(initialPaymentStateService.claim("customer-key")).willReturn(processing);
        given(tossPaymentsClient.findPaymentByOrderId("ORDER-customer-key"))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_STATUS_CHECK_FAILED));

        assertThatThrownBy(() -> subscriptionService.handleCallback("customer-key", "auth-key"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_STATUS_CHECK_FAILED);
        then(tossPaymentsClient).should(never()).charge(
                anyString(), anyString(), anyInt(), anyString(), anyString());
        then(initialPaymentStateService).should(never())
                .complete(anyString(), any(TossPaymentsClient.PaymentResponse.class));
    }

    @Test
    @DisplayName("이미 완료된 최초 결제 콜백은 외부 결제를 다시 호출하지 않는다")
    void handleCallback_completedAttempt_returnsStoredSubscription() {
        var completed = new InitialPaymentStateService.InitialPaymentContext(
                "customer-key", member.getId(), "프리미엄", 9900,
                "encrypted-key", "ORDER-customer-key", true);
        given(initialPaymentStateService.claim("customer-key")).willReturn(completed);

        subscriptionService.handleCallback("customer-key", "auth-key");

        then(initialPaymentStateService).should()
                .getCompletedSubscription(member.getId());
        then(tossPaymentsClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("활성 구독이 있으면 결제 호출 전에 SUBSCRIPTION_ALREADY_EXISTS로 차단한다 (#179)")
    void handleCallback_activeSubscriptionExists_blocksBeforeCharge() {
        given(initialPaymentStateService.claim("dup-customer-key"))
                .willThrow(new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS));

        // when / then — 토스 결제/빌링키 발급이 아예 호출되지 않아야 함 (돈 안 나감)
        assertThatThrownBy(() -> subscriptionService.handleCallback("dup-customer-key", "auth-key"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        then(tossPaymentsClient).should(never())
                .issueBillingKey(anyString(), anyString(), anyString());
        then(tossPaymentsClient).should(never())
                .charge(anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("처리 중 최초 결제는 저장된 멱등키로 같은 승인 요청을 안전하게 재호출한다")
    void handleCallback_processingAttempt_retriesChargeWithSameIdempotencyKey() {
        var processing = new InitialPaymentStateService.InitialPaymentContext(
                "customer-key", member.getId(), "프리미엄", 9900,
                "encrypted-key", "ORDER-customer-key",
                "issue-idempotency-key", "charge-idempotency-key", false, true);
        var response = completedInitialPayment("payment-key", "ORDER-customer-key");
        given(initialPaymentStateService.claim("customer-key")).willReturn(processing);
        given(billingKeyEncryptor.decrypt("encrypted-key")).willReturn("raw-key");
        given(tossPaymentsClient.charge(
                "raw-key", "customer-key", 9900,
                "ORDER-customer-key", "프리미엄 구독 결제", "charge-idempotency-key"))
                .willReturn(response);

        subscriptionService.handleCallback("customer-key", "auth-key");

        then(tossPaymentsClient).should().charge(
                "raw-key", "customer-key", 9900,
                "ORDER-customer-key", "프리미엄 구독 결제", "charge-idempotency-key");
        then(initialPaymentStateService).should().complete("customer-key", response);
    }

    @Test
    @DisplayName("중단된 빌링키 발급 시도는 외부 요청 없이 새 카드 인증을 요구한다")
    void handleCallback_abandonedBillingIssue_requiresReauthentication() {
        var abandoned = new InitialPaymentStateService.InitialPaymentContext(
                "customer-key", member.getId(), "프리미엄", 9900,
                null, null, "issue-idempotency-key", null,
                false, false, true);
        given(initialPaymentStateService.claim("customer-key")).willReturn(abandoned);

        assertThatThrownBy(() ->
                subscriptionService.handleCallback("customer-key", "auth-key"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND);

        then(tossPaymentsClient).shouldHaveNoInteractions();
    }

    private TossPaymentsClient.PaymentResponse completedInitialPayment(
            String paymentKey, String orderId) {
        return new TossPaymentsClient.PaymentResponse(
                paymentKey, orderId, "DONE", "BILLING", "2026-07-25", 9900, null);
    }
}
