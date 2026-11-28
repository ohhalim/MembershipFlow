package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.subscription.client.PaddleCircuitOpenException;
import com.membershipflow.subscription.client.PaddlePaymentsClient;
import com.membershipflow.subscription.client.PaddleTransactionRejectedException;
import com.membershipflow.subscription.dto.PaddleTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaddleCheckoutService {

    private final PaddleCheckoutStateService stateService;
    private final PaddlePriceResolver priceResolver;
    private final PaddlePaymentsClient paddlePaymentsClient;

    public PaddleTransactionResponse createTransaction(Long memberId, Long planId) {
        PaddleCheckoutStateService.CheckoutContext context = stateService.create(memberId, planId);
        if (context.existingTransactionId() != null) {
            return new PaddleTransactionResponse(context.existingTransactionId());
        }

        String transactionId;
        try {
            transactionId = paddlePaymentsClient.createTransaction(
                    priceResolver.resolve(context.billingCycle()),
                    context.attemptId(), context.memberId(), context.planId());
        } catch (PaddleTransactionRejectedException e) {
            stateService.failRejectedTransaction(context.attemptId());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
        } catch (PaddleCircuitOpenException e) {
            stateService.failRejectedTransaction(context.attemptId());
            throw new BusinessException(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE);
        }
        stateService.attachTransaction(context.attemptId(), transactionId);
        return new PaddleTransactionResponse(transactionId);
    }
}
