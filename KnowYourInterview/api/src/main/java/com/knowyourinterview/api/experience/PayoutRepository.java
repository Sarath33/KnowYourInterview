package com.knowyourinterview.api.experience;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {
}
