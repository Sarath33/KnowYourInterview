package com.knowyourinterview.api.payout;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.knowyourinterview.api.payout.dto.MarkPayoutPaidRequest;
import com.knowyourinterview.api.payout.dto.PayoutResponse;
import com.knowyourinterview.api.security.AuthenticatedUser;

/** All routes here require ROLE_ADMIN — enforced in SecurityConfig via the /api/v1/admin/** pattern. */
@RestController
@RequestMapping("/api/v1/admin/payouts")
public class AdminPayoutController {

    private final PayoutService payoutService;

    public AdminPayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @GetMapping
    public List<PayoutResponse> queue() {
        return payoutService.queue();
    }

    /** Called after the admin has actually wired the contributor's flat fee themselves
     * (bank transfer/UPI) — see Payout.java for why this isn't a live RazorpayX call. */
    @PostMapping("/{id}/mark-paid")
    public PayoutResponse markPaid(
            @AuthenticationPrincipal AuthenticatedUser admin,
            @PathVariable UUID id,
            @RequestBody(required = false) MarkPayoutPaidRequest req) {
        String reference = req == null ? null : req.reference();
        return payoutService.markPaid(admin.id(), id, reference);
    }
}
