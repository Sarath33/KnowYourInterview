package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRepository extends JpaRepository<Purchase, UUID> {

    Optional<Purchase> findByRazorpayOrderId(String razorpayOrderId);

    Optional<Purchase> findByIdAndUserId(UUID id, UUID userId);

    List<Purchase> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
