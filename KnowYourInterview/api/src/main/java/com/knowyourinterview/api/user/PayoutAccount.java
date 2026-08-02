package com.knowyourinterview.api.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A contributor's payout destination — who to pay and where (their UPI VPA). One row per
 * user (user_id is UNIQUE, see V1), upserted through the profile page.
 * <p>
 * The nullable razorpayx_contact_id / razorpayx_fund_account_id columns from V1 are
 * deliberately not mapped: payouts are a manual bank/UPI transfer today (see Payout.java),
 * so nothing reads or writes those, and mapping unused columns would only invite confusion.
 */
@Entity
@Table(name = "payout_accounts")
public class PayoutAccount {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "account_holder_name", nullable = false)
    private String accountHolderName;

    @Column(name = "upi_vpa")
    private String upiVpa;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PayoutAccount() {
        // JPA
    }

    public PayoutAccount(UUID id, UUID userId, String accountHolderName, String upiVpa) {
        this.id = id;
        this.userId = userId;
        this.accountHolderName = accountHolderName;
        this.upiVpa = upiVpa;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Overwrites the destination in place — the profile form is a full replace, not a patch. */
    public void update(String accountHolderName, String upiVpa) {
        this.accountHolderName = accountHolderName;
        this.upiVpa = upiVpa;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public String getUpiVpa() {
        return upiVpa;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
