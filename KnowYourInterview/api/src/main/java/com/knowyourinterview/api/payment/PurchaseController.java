package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.knowyourinterview.api.payment.dto.ConfirmPaymentRequest;
import com.knowyourinterview.api.payment.dto.CreateOrderResponse;
import com.knowyourinterview.api.payment.dto.PurchaseResponse;
import com.knowyourinterview.api.security.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @PostMapping("/api/v1/experiences/{id}/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateOrderResponse createOrder(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return purchaseService.createOrder(user.id(), id);
    }

    @PostMapping("/api/v1/purchases/confirm")
    public PurchaseResponse confirm(
            @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ConfirmPaymentRequest req) {
        return purchaseService.confirmPayment(user.id(), req);
    }

    @GetMapping("/api/v1/purchases/mine")
    public List<PurchaseResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return purchaseService.listMine(user.id());
    }
}
