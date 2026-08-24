package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.subscription.client.PaddlePaymentsClient;
import com.membershipflow.subscription.client.PaddleTransactionRejectedException;
import com.membershipflow.subscription.dto.PaddleTransactionResponse;
import com.membershipflow.subscription.entity.BillingCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaddleCheckoutServiceTest {

    @Mock
    private PaddleCheckoutStateService stateService;
    @Mock
    private PaddlePriceResolver priceResolver;
    @Mock
    private PaddlePaymentsClient paymentsClient;

    private PaddleCheckoutService service;
    private PaddleCheckoutStateService.CheckoutContext context;

    @BeforeEach
    void setUp() {
        service = new PaddleCheckoutService(stateService, priceResolver, paymentsClient);
        context = new PaddleCheckoutStateService.CheckoutContext(
                "attempt-id", 10L, 20L, BillingCycle.MONTHLY);
        when(stateService.create(10L, 20L)).thenReturn(context);
        when(priceResolver.resolve(BillingCycle.MONTHLY)).thenReturn("pri_monthly");
    }

    @Test
    void createTransaction_attachesCreatedTransaction() {
        when(paymentsClient.createTransaction(
                "pri_monthly", "attempt-id", 10L, 20L))
                .thenReturn("txn_test");

        PaddleTransactionResponse response = service.createTransaction(10L, 20L);

        assertThat(response.transactionId()).isEqualTo("txn_test");
        verify(stateService).attachTransaction("attempt-id", "txn_test");
        verify(stateService, never()).failRejectedTransaction("attempt-id");
    }

    @Test
    void createTransaction_marksAttemptFailedForExplicitClientRejection() {
        when(paymentsClient.createTransaction(
                "pri_monthly", "attempt-id", 10L, 20L))
                .thenThrow(new PaddleTransactionRejectedException(new RuntimeException()));

        assertThatThrownBy(() -> service.createTransaction(10L, 20L))
                .isInstanceOf(BusinessException.class);

        verify(stateService).failRejectedTransaction("attempt-id");
        verify(stateService, never()).attachTransaction("attempt-id", "txn_test");
    }

    @Test
    void createTransaction_keepsAttemptPendingForUncertainFailure() {
        when(paymentsClient.createTransaction(
                "pri_monthly", "attempt-id", 10L, 20L))
                .thenThrow(new BusinessException(
                        com.membershipflow.common.exception.ErrorCode.PAYMENT_FAILED_ERROR));

        assertThatThrownBy(() -> service.createTransaction(10L, 20L))
                .isInstanceOf(BusinessException.class);

        verify(stateService, never()).failRejectedTransaction("attempt-id");
        verify(stateService, never()).attachTransaction("attempt-id", "txn_test");
    }
}
