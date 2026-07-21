package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceOutcome;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.payment.dto.ConfirmPaymentRequest;
import com.knowyourinterview.api.payment.dto.PurchaseResponse;
import com.razorpay.Utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for PurchaseService — repositories are mocked, and the real Razorpay
 * SDK is never contacted. `client()` builds a live RazorpayClient internally, so the
 * happy-path "successfully created an order" branch of createOrder can't be unit-tested
 * without either a network call or refactoring PurchaseService to accept an injectable
 * order-creation port — that gap is intentional here and called out in the test-coverage
 * writeup rather than worked around with a network call. Everything reachable without
 * hitting Razorpay (guard clauses, idempotency, signature-verification branching via the
 * static Utils methods, webhook handling) is covered.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    @Mock
    private ExperienceRepository experienceRepository;
    @Mock
    private PurchaseRepository purchaseRepository;
    @Mock
    private EntitlementRepository entitlementRepository;

    private PurchaseService service;

    @BeforeEach
    void setUp() {
        // Blank keys are intentional: it lets createOrder's guard-clause tests run without
        // ever reaching the real Razorpay network call (see class Javadoc above).
        service = new PurchaseService(
                experienceRepository, purchaseRepository, entitlementRepository, "", "", "whsec_test");
    }

    private Experience publishedExperience() {
        Experience experience = new Experience(
                UUID.randomUUID(), UUID.randomUUID(), "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", 19900);
        experience.markPendingReview();
        experience.publish();
        return experience;
    }

    // --- createOrder guard clauses ---

    @Test
    void createOrderRejectsUnknownExperience() {
        UUID missingId = UUID.randomUUID();
        when(experienceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(UUID.randomUUID(), missingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createOrderRejectsUnpublishedExperience() {
        Experience draft = new Experience(
                UUID.randomUUID(), UUID.randomUUID(), "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", 19900);
        when(experienceRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.createOrder(UUID.randomUUID(), draft.getId()))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void createOrderRejectsWhenViewerAlreadyHasEntitlement() {
        Experience experience = publishedExperience();
        UUID userId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(entitlementRepository.existsByUserIdAndExperienceId(userId, experience.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.createOrder(userId, experience.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("already have access");

        verify(purchaseRepository, never()).save(any());
    }

    @Test
    void createOrderSurfacesConfigurationErrorWhenRazorpayKeysAreMissing() {
        Experience experience = publishedExperience();
        UUID userId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(entitlementRepository.existsByUserIdAndExperienceId(userId, experience.getId())).thenReturn(false);

        // service was built with blank keyId/keySecret in setUp() — client() throws before
        // any network call is attempted, which createOrder translates into this message.
        assertThatThrownBy(() -> service.createOrder(userId, experience.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("payments aren't configured correctly");

        verify(purchaseRepository, never()).save(any());
    }

    // --- confirmPayment ---

    @Test
    void confirmPaymentRejectsUnknownOrder() {
        when(purchaseRepository.findByRazorpayOrderId("order_missing")).thenReturn(Optional.empty());

        ConfirmPaymentRequest req = new ConfirmPaymentRequest("order_missing", "pay_1", "sig_1");
        assertThatThrownBy(() -> service.confirmPayment(UUID.randomUUID(), req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void confirmPaymentRejectsOrderBelongingToAnotherUser() {
        Purchase purchase = new Purchase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 19900, "order_1");
        when(purchaseRepository.findByRazorpayOrderId("order_1")).thenReturn(Optional.of(purchase));

        ConfirmPaymentRequest req = new ConfirmPaymentRequest("order_1", "pay_1", "sig_1");
        assertThatThrownBy(() -> service.confirmPayment(UUID.randomUUID(), req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void confirmPaymentIsIdempotentForAlreadyPaidOrder() {
        UUID userId = UUID.randomUUID();
        Purchase purchase = new Purchase(UUID.randomUUID(), userId, UUID.randomUUID(), 19900, "order_1");
        purchase.markPaid("pay_1");
        when(purchaseRepository.findByRazorpayOrderId("order_1")).thenReturn(Optional.of(purchase));

        ConfirmPaymentRequest req = new ConfirmPaymentRequest("order_1", "pay_1", "sig_1");
        PurchaseResponse response = service.confirmPayment(userId, req);

        assertThat(response.status()).isEqualTo(Purchase.Status.PAID);
        // No signature re-verification and no duplicate save needed — already settled.
        verify(purchaseRepository, never()).save(any());
    }

    @Test
    void confirmPaymentGrantsEntitlementWhenSignatureIsValid() {
        UUID userId = UUID.randomUUID();
        UUID experienceId = UUID.randomUUID();
        Purchase purchase = new Purchase(UUID.randomUUID(), userId, experienceId, 19900, "order_1");
        when(purchaseRepository.findByRazorpayOrderId("order_1")).thenReturn(Optional.of(purchase));
        when(entitlementRepository.existsByUserIdAndExperienceId(userId, experienceId)).thenReturn(false);

        ConfirmPaymentRequest req = new ConfirmPaymentRequest("order_1", "pay_1", "sig_1");
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(), any())).thenReturn(true);

            PurchaseResponse response = service.confirmPayment(userId, req);

            assertThat(response.status()).isEqualTo(Purchase.Status.PAID);
        }
        assertThat(purchase.getStatus()).isEqualTo(Purchase.Status.PAID);
        assertThat(purchase.getRazorpayPaymentId()).isEqualTo("pay_1");
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    @Test
    void confirmPaymentMarksFailedWhenSignatureIsInvalid() {
        UUID userId = UUID.randomUUID();
        Purchase purchase = new Purchase(UUID.randomUUID(), userId, UUID.randomUUID(), 19900, "order_1");
        when(purchaseRepository.findByRazorpayOrderId("order_1")).thenReturn(Optional.of(purchase));

        ConfirmPaymentRequest req = new ConfirmPaymentRequest("order_1", "pay_1", "bad_sig");
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(), any())).thenReturn(false);

            assertThatThrownBy(() -> service.confirmPayment(userId, req))
                    .isInstanceOf(InvalidStateException.class);
        }

        assertThat(purchase.getStatus()).isEqualTo(Purchase.Status.FAILED);
        verify(entitlementRepository, never()).save(any());
    }

    // --- handlePaymentCaptured (webhook backup path) ---

    @Test
    void handlePaymentCapturedGrantsEntitlementForKnownOrder() {
        UUID userId = UUID.randomUUID();
        UUID experienceId = UUID.randomUUID();
        Purchase purchase = new Purchase(UUID.randomUUID(), userId, experienceId, 19900, "order_1");
        when(purchaseRepository.findByRazorpayOrderId("order_1")).thenReturn(Optional.of(purchase));
        when(entitlementRepository.existsByUserIdAndExperienceId(userId, experienceId)).thenReturn(false);

        service.handlePaymentCaptured("order_1", "pay_1");

        assertThat(purchase.getStatus()).isEqualTo(Purchase.Status.PAID);
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    @Test
    void handlePaymentCapturedIsIdempotentForAlreadyPaidOrder() {
        Purchase purchase = new Purchase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 19900, "order_1");
        purchase.markPaid("pay_1");
        when(purchaseRepository.findByRazorpayOrderId("order_1")).thenReturn(Optional.of(purchase));

        service.handlePaymentCaptured("order_1", "pay_1_dup");

        // Already PAID — grantEntitlement must not run again (no re-save, no duplicate entitlement check).
        verify(entitlementRepository, never()).existsByUserIdAndExperienceId(any(), any());
    }

    @Test
    void handlePaymentCapturedIgnoresUnknownOrderWithoutThrowing() {
        when(purchaseRepository.findByRazorpayOrderId("order_ghost")).thenReturn(Optional.empty());

        service.handlePaymentCaptured("order_ghost", "pay_1");

        verify(purchaseRepository, never()).save(any());
    }

    // --- verifyWebhookSignature ---

    @Test
    void verifyWebhookSignatureRejectsWhenSecretNotConfigured() {
        PurchaseService noSecretService = new PurchaseService(
                experienceRepository, purchaseRepository, entitlementRepository, "", "", "");

        assertThat(noSecretService.verifyWebhookSignature("{}", "sig")).isFalse();
    }

    @Test
    void verifyWebhookSignatureDelegatesToRazorpayUtils() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyWebhookSignature("{}", "sig", "whsec_test")).thenReturn(true);

            assertThat(service.verifyWebhookSignature("{}", "sig")).isTrue();
        }
    }

    // --- listMine ---

    @Test
    void listMineReturnsUsersPurchasesOnly() {
        UUID userId = UUID.randomUUID();
        Purchase purchase = new Purchase(UUID.randomUUID(), userId, UUID.randomUUID(), 19900, "order_1");
        when(purchaseRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(purchase));

        List<PurchaseResponse> mine = service.listMine(userId);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).amountPaise()).isEqualTo(19900);
    }
}
