package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.knowyourinterview.api.payment.dto.CreateOrderResponse;
import com.knowyourinterview.api.payment.dto.PurchaseResponse;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.security.SecurityConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PurchaseController.class)
@Import({SecurityConfig.class, JwtService.class})
class PurchaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private PurchaseService purchaseService;

    // SecurityConfig now wires RateLimitingFilter, which needs this bean to exist.
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private String bearerTokenFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId, "viewer@example.com", false).token();
    }

    @Test
    void createOrderRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/experiences/" + UUID.randomUUID() + "/purchase"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOrderReturns201WhenAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID experienceId = UUID.randomUUID();
        when(purchaseService.createOrder(eq(userId), eq(experienceId)))
                .thenReturn(new CreateOrderResponse(experienceId, "order_abc123", 9900, "INR", "rzp_test_key"));

        mockMvc.perform(post("/api/v1/experiences/" + experienceId + "/purchase")
                        .header("Authorization", bearerTokenFor(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.razorpayOrderId").value("order_abc123"))
                .andExpect(jsonPath("$.amountPaise").value(9900));
    }

    @Test
    void confirmRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/purchases/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"razorpayOrderId":"order_abc","razorpayPaymentId":"pay_abc","razorpaySignature":"sig"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmReturnsPurchaseWhenAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID experienceId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        when(purchaseService.confirmPayment(eq(userId), any()))
                .thenReturn(new PurchaseResponse(
                        purchaseId, experienceId, 9900, Purchase.Status.PAID, java.time.Instant.now()));

        mockMvc.perform(post("/api/v1/purchases/confirm")
                        .header("Authorization", bearerTokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"razorpayOrderId":"order_abc","razorpayPaymentId":"pay_abc","razorpaySignature":"sig"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void confirmRejectsBlankFields() throws Exception {
        mockMvc.perform(post("/api/v1/purchases/confirm")
                        .header("Authorization", bearerTokenFor(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"razorpayOrderId":"","razorpayPaymentId":"pay_abc","razorpaySignature":"sig"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mineRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/purchases/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void mineReturnsListWhenAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        when(purchaseService.listMine(userId)).thenReturn(List.of(new PurchaseResponse(
                UUID.randomUUID(), UUID.randomUUID(), 9900, Purchase.Status.PAID, java.time.Instant.now())));

        mockMvc.perform(get("/api/v1/purchases/mine").header("Authorization", bearerTokenFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PAID"));
    }
}
