package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.PaymentProvider;
import com.membershipflow.subscription.entity.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {
    boolean existsByPaymentProviderAndExternalEventId(
            PaymentProvider paymentProvider, String externalEventId);
}
