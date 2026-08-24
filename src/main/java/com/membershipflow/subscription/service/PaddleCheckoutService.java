package com.membershipflow.subscription.service;

import com.membershipflow.subscription.client.PaddlePaymentsClient;
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
        String transactionId = paddlePaymentsClient.createTransaction(
                priceResolver.resolve(context.billingCycle()),
                context.attemptId(), context.memberId(), context.planId());
        stateService.attachTransaction(context.attemptId(), transactionId);
        return new PaddleTransactionResponse(transactionId);
    }
}
