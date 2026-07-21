package com.knowyourinterview.api.payout;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.knowyourinterview.api.payout.dto.PayoutResponse;
import com.knowyourinterview.api.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final PayoutService payoutService;

    public PayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @GetMapping("/mine")
    public List<PayoutResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return payoutService.listMine(user.id());
    }
}
