package com.knowyourinterview.api.functional;

import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-POUT — the contributor payout ledger. See {@code docs/09-test-plan.md} §7.12.
 *
 * <p>Nothing in this module moves money: an admin wires the flat fee themselves and records it
 * here. That makes the ledger the <em>only</em> record of what has been paid, so its integrity is
 * the whole feature. The case that matters most is FT-POUT-04 — marking an already-paid payout
 * paid again would quietly invite a second real bank transfer, with nothing to reconcile against
 * because the reference field is free text nobody validates.
 */
class PayoutFunctionalIT extends FunctionalTestBase {

    private UUID pendingPayoutId() {
        return UUID.fromString(jdbc.queryForObject("SELECT id::text FROM payouts LIMIT 1", String.class));
    }

    // --- The admin queue -----------------------------------------------------------------------

    @Test
    @DisplayName("FT-POUT-01: approving an experience puts an actionable payout in the admin queue")
    void approvalPutsAnActionablePayoutInTheQueue() {
        Actor contributor = registerUser("Priya Contributor");
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);

        JSONArray queue = jsonArrayOf(get("/api/v1/admin/payouts", admin));

        assertThat(queue.length()).isEqualTo(1);
        JSONObject owed = queue.getJSONObject(0);
        assertThat(owed.getString("experienceId")).isEqualTo(experienceId.toString());
        assertThat(owed.getString("status")).isEqualTo("PENDING");
        assertThat(owed.getLong("amountPaise")).isEqualTo(CONTRIBUTOR_PAYOUT_PAISE);
        // The admin is about to send money by hand, so they need to know who to and for what.
        assertThat(owed.getString("contributorEmail")).isEqualTo(contributor.email());
        assertThat(owed.getString("contributorDisplayName")).isEqualTo("Priya Contributor");
        assertThat(owed.getString("company")).isEqualTo("Acme Corp");
        assertThat(owed.getString("roleTitle")).isEqualTo("Backend Engineer");
        assertThat(owed.isNull("paidAt")).isTrue();
    }

    @Test
    @DisplayName("FT-POUT-02: marking paid records who paid, what reference, and when")
    void markingPaidRecordsTheDetails() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);
        UUID payoutId = pendingPayoutId();

        ResponseEntity<String> response = post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", admin,
                new JSONObject().put("reference", "UPI-2026-08-01-000123"));

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject paid = jsonOf(response);
        assertThat(paid.getString("status")).isEqualTo("PAID");
        assertThat(paid.getString("payoutReference")).isEqualTo("UPI-2026-08-01-000123");
        assertThat(paid.isNull("paidAt")).isFalse();
        // paid_by_admin_id is the accountability trail for a manual transfer.
        assertThat(jdbc.queryForObject("SELECT paid_by_admin_id::text FROM payouts WHERE id = ?",
                String.class, payoutId)).isEqualTo(admin.id().toString());
    }

    @Test
    @DisplayName("FT-POUT-03: the reference is optional, with or without a body")
    void referenceIsOptional() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);
        UUID payoutId = pendingPayoutId();

        ResponseEntity<String> response = post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", admin, null);

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(jsonOf(response).getString("status")).isEqualTo("PAID");
        assertThat(jsonOf(response).isNull("payoutReference")).isTrue();
    }

    @Test
    @DisplayName("FT-POUT-04: a payout can't be marked paid twice")
    void payoutCannotBeMarkedPaidTwice() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);
        UUID payoutId = pendingPayoutId();
        post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", admin,
                new JSONObject().put("reference", "UPI-FIRST"));

        ResponseEntity<String> second = post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", admin,
                new JSONObject().put("reference", "UPI-SECOND"));

        // There is no un-mark and no edit-after-paid, by design: an admin who can re-mark a
        // payout has no way to tell whether they already sent the money.
        assertThat(statusOf(second)).isEqualTo(400);
        assertThat(messageOf(second)).contains("already marked paid");
        assertThat(jdbc.queryForObject("SELECT payout_reference FROM payouts WHERE id = ?",
                String.class, payoutId))
                .as("the original reference must survive a rejected second attempt")
                .isEqualTo("UPI-FIRST");
    }

    @Test
    @DisplayName("FT-POUT-05: a paid payout leaves the queue")
    void paidPayoutLeavesTheQueue() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin, Payloads.experience("PaidOutCo", "Engineer", "1."));
        publishedExperience(contributor, admin, Payloads.experience("StillOwedCo", "Engineer", "2."));
        UUID firstPayout = UUID.fromString(jdbc.queryForObject(
                "SELECT p.id::text FROM payouts p JOIN experiences e ON e.id = p.experience_id "
                        + "WHERE e.company = 'PaidOutCo'", String.class));

        post("/api/v1/admin/payouts/" + firstPayout + "/mark-paid", admin, null);

        JSONArray queue = jsonArrayOf(get("/api/v1/admin/payouts", admin));
        assertThat(queue.length()).isEqualTo(1);
        assertThat(queue.getJSONObject(0).getString("company")).isEqualTo("StillOwedCo");
    }

    @Test
    @DisplayName("FT-POUT-06: marking an unknown payout paid is a 404")
    void unknownPayoutIs404() {
        Actor admin = registerAdmin();

        assertThat(statusOf(post("/api/v1/admin/payouts/" + UUID.randomUUID() + "/mark-paid", admin, null)))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("FT-POUT-07: an over-long reference is rejected")
    void overLongReferenceIsRejected() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);
        UUID payoutId = pendingPayoutId();

        ResponseEntity<String> response = post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", admin,
                new JSONObject().put("reference", "X".repeat(300)));

        // The column is VARCHAR(255); catching it in validation gives a 400 with a named field
        // instead of a 409 from a truncation error.
        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(jsonOf(response).getJSONObject("fieldErrors").keySet()).contains("reference");
        assertThat(jdbc.queryForObject("SELECT status FROM payouts WHERE id = ?", String.class, payoutId))
                .isEqualTo("PENDING");
    }

    // --- The contributor's own view ----------------------------------------------------------------

    @Test
    @DisplayName("FT-POUT-08: /payouts/mine only ever shows the caller's own payouts")
    void payoutsMineIsScopedToTheCaller() {
        Actor firstContributor = registerUser();
        Actor secondContributor = registerUser();
        Actor admin = registerAdmin();
        UUID firstExperience = publishedExperience(firstContributor, admin,
                Payloads.experience("FirstCo", "Engineer", "1."));
        UUID secondExperience = publishedExperience(secondContributor, admin,
                Payloads.experience("SecondCo", "Engineer", "2."));

        JSONArray firsts = jsonArrayOf(get("/api/v1/payouts/mine", firstContributor));
        JSONArray seconds = jsonArrayOf(get("/api/v1/payouts/mine", secondContributor));

        assertThat(firsts.length()).isEqualTo(1);
        assertThat(firsts.getJSONObject(0).getString("experienceId")).isEqualTo(firstExperience.toString());
        assertThat(seconds.length()).isEqualTo(1);
        assertThat(seconds.getJSONObject(0).getString("experienceId")).isEqualTo(secondExperience.toString());
    }

    @Test
    @DisplayName("FT-POUT-09: a contributor's own payout view carries no identity fields")
    void payoutsMineOmitsIdentityFields() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);

        JSONObject mine = jsonArrayOf(get("/api/v1/payouts/mine", contributor)).getJSONObject(0);

        // forContributor deliberately nulls these — there's no reason to echo a contributor's
        // own email back at them, and it keeps the admin-only shape from leaking by accident if
        // this DTO is ever reused for a list containing more than one person.
        assertThat(mine.isNull("contributorEmail")).isTrue();
        assertThat(mine.isNull("contributorDisplayName")).isTrue();
        assertThat(mine.getString("company")).isEqualTo("Acme Corp");
        assertThat(mine.getLong("amountPaise")).isEqualTo(CONTRIBUTOR_PAYOUT_PAISE);
    }

    @Test
    @DisplayName("FT-POUT-10: the contributor can see when they were paid, and with what reference")
    void contributorSeesThePaidState() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);
        UUID payoutId = pendingPayoutId();
        assertThat(jsonArrayOf(get("/api/v1/payouts/mine", contributor)).getJSONObject(0).getString("status"))
                .isEqualTo("PENDING");

        post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", admin,
                new JSONObject().put("reference", "NEFT-99887766"));

        JSONObject mine = jsonArrayOf(get("/api/v1/payouts/mine", contributor)).getJSONObject(0);
        assertThat(mine.getString("status")).isEqualTo("PAID");
        assertThat(mine.getString("payoutReference")).isEqualTo("NEFT-99887766");
        assertThat(mine.isNull("paidAt")).isFalse();
    }

    // --- Nothing owed --------------------------------------------------------------------------

    @Test
    @DisplayName("FT-POUT-11: a free contribution owes nobody anything")
    void freeContributionCreatesNoPayout() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID free = createDraft(contributor, Payloads.freeContribution());
        addRound(contributor, free);
        post("/api/v1/experiences/" + free + "/submit", contributor, null);

        assertThat(statusOfExperience(free)).isEqualTo("PUBLISHED");
        assertThat(countRows("payouts")).isZero();
        assertThat(jsonArrayOf(get("/api/v1/admin/payouts", admin))).isEmpty();
        assertThat(jsonArrayOf(get("/api/v1/payouts/mine", contributor))).isEmpty();
    }

    @Test
    @DisplayName("FT-POUT-12: payout administration is closed to non-admins")
    void payoutAdministrationIsAdminOnly() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor outsider = registerUser();
        publishedExperience(contributor, admin);
        UUID payoutId = pendingPayoutId();

        assertThat(statusOf(get("/api/v1/admin/payouts", outsider))).isEqualTo(403);
        assertThat(statusOf(post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", outsider,
                new JSONObject().put("reference", "self-service")))).isEqualTo(403);
        // Not even the contributor who is owed the money can mark their own payout paid.
        assertThat(statusOf(post("/api/v1/admin/payouts/" + payoutId + "/mark-paid", contributor, null)))
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status FROM payouts WHERE id = ?", String.class, payoutId))
                .isEqualTo("PENDING");
    }
}
