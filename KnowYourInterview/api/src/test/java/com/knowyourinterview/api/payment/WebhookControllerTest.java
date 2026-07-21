package com.knowyourinterview.api.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.security.SecurityConfig;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
@Import({SecurityConfig.class, JwtService.class})
class WebhookControllerTest {

    private static final String CAPTURED_PAYLOAD = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_abc123",
                    "order_id": "order_abc123"
                  }
                }
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseService purchaseService;

    // SecurityConfig now wires RateLimitingFilter, which needs this bean to exist.
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void isReachableWithoutAJwt() throws Exception {
        // No Authorization header at all — Razorpay's servers call this, not a logged-in
        // browser. Confirms SecurityConfig's permitAll rule for this path is in effect.
        when(purchaseService.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "valid-sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAPTURED_PAYLOAD))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingSignatureHeader() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAPTURED_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(purchaseService, never()).handlePaymentCaptured(anyString(), anyString());
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        when(purchaseService.verifyWebhookSignature(anyString(), eq("bad-sig"))).thenReturn(false);

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "bad-sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAPTURED_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(purchaseService, never()).handlePaymentCaptured(anyString(), anyString());
    }

    @Test
    void handlesPaymentCapturedEventOnValidSignature() throws Exception {
        when(purchaseService.verifyWebhookSignature(anyString(), eq("valid-sig"))).thenReturn(true);

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "valid-sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAPTURED_PAYLOAD))
                .andExpect(status().isOk());

        verify(purchaseService).handlePaymentCaptured("order_abc123", "pay_abc123");
    }

    @Test
    void ignoresUnhandledEventTypesButStillReturns200() throws Exception {
        when(purchaseService.verifyWebhookSignature(anyString(), eq("valid-sig"))).thenReturn(true);

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "valid-sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"order.paid","payload":{}}
                                """))
                .andExpect(status().isOk());

        verify(purchaseService, never()).handlePaymentCaptured(anyString(), anyString());
    }
}
