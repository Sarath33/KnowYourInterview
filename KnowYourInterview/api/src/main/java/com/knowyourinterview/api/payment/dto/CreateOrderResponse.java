package com.knowyourinterview.api.payment.dto;

import java.util.UUID;

public record CreateOrderResponse(
        UUID experienceId, String razorpayOrderId, int amountPaise, String currency, String razorpayKeyId) {
}
