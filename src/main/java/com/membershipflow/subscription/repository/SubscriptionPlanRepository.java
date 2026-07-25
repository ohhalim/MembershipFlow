package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {
    List<SubscriptionPlan> findAllByActiveTrueOrderById();

    Optional<SubscriptionPlan> findByIdAndActiveTrue(Long id);
}
