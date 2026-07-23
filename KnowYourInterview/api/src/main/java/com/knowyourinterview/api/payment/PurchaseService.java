package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.experience.ExperienceStatus;
import com.knowyourinterview.api.payment.dto.ConfirmPaymentRequest;
import com.knowyourinterview.api.payment.dto.CreateOrderResponse;
import com.knowyourinterview.api.payment.dto.PurchaseResponse;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;

@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);
    private static final String CURRENCY = "INR";

    private final ExperienceRepository experienceRepository;
    private final PurchaseRepository purchaseRepository;
    private final EntitlementRepository entitlementRepository;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;

    public PurchaseService(
            ExperienceRepository experienceRepository,
            PurchaseRepository purchaseRepository,
            EntitlementRepository entitlementRepository,
            @Value("${app.razorpay.key-id}") String keyId,
            @Value("${app.razorpay.key-secret}") String keySecret,
            @Value("${app.razorpay.webhook-secret}") String webhookSecret) {
        this.experienceRepository = experienceRepository;
        this.purchaseRepository = purchaseRepository;
        this.entitlementRepository = entitlementRepository;
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
    }

    @Transactional
    public CreateOrderResponse createOrder(UUID userId, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
        if (experience.getStatus() != ExperienceStatus.PUBLISHED) {
            throw new InvalidStateException("This experience isn't published yet");
        }
        if (entitlementRepository.existsByUserIdAndExperienceId(userId, experienceId)) {
            throw new InvalidStateException("You already have access to this experience");
        }

        try {
            // Auto-capture is an account-level Dashboard setting in current Razorpay API
            // versions, not a per-order field — don't set "payment_capture" here, it's not
            // a documented Orders API parameter and Razorpay may reject unrecognized fields.
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", experience.getPricePaise());
            orderRequest.put("currency", CURRENCY);
            orderRequest.put("receipt", "exp_" + experienceId);
            com.razorpay.Order order = client().orders.create(orderRequest);
            String razorpayOrderId = order.get("id");

            Purchase purchase = new Purchase(
                    UUID.randomUUID(), userId, experienceId, experience.getPricePaise(), razorpayOrderId);
            purchaseRepository.save(purchase);

            return new CreateOrderResponse(experienceId, razorpayOrderId, experience.getPricePaise(), CURRENCY, keyId);
        } catch (RazorpayException e) {
            log.error("Failed to create Razorpay order for experience {}", experienceId, e);
            throw new InvalidStateException("Could not start checkout — payments aren't configured correctly");
        }
    }

    /** Called by the frontend right after Razorpay Checkout reports success. */
    @Transactional
    public PurchaseResponse confirmPayment(UUID userId, ConfirmPaymentRequest req) {
        Purchase purchase = purchaseRepository.findByRazorpayOrderId(req.razorpayOrderId())
                .orElseThrow(() -> new NotFoundException("No matching order found"));
        if (!purchase.getUserId().equals(userId)) {
            throw new NotFoundException("No matching order found");
        }

        if (purchase.getStatus() == Purchase.Status.PAID) {
            // Idempotent: the webhook backup may have already confirmed this order.
            return PurchaseResponse.from(purchase, experienceRepository.findById(purchase.getExperienceId()).orElse(null));
        }

        boolean valid;
        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", req.razorpayOrderId());
            attributes.put("razorpay_payment_id", req.razorpayPaymentId());
            attributes.put("razorpay_signature", req.razorpaySignature());
            valid = Utils.verifyPaymentSignature(attributes, keySecret);
        } catch (RazorpayException e) {
            valid = false;
        }

        if (!valid) {
            purchase.markFailed();
            purchaseRepository.save(purchase);
            throw new InvalidStateException("Payment could not be verified");
        }

        grantEntitlement(purchase, req.razorpayPaymentId());
        return PurchaseResponse.from(purchase, experienceRepository.findById(purchase.getExperienceId()).orElse(null));
    }

    /**
     * Backup path: Razorpay calls this server-to-server when a payment is captured,
     * independent of whether the client's browser tab was still open to call
     * confirmPayment. Idempotent — safe to call whether or not confirmPayment already ran.
     */
    @Transactional
    public void handlePaymentCaptured(String razorpayOrderId, String razorpayPaymentId) {
        purchaseRepository.findByRazorpayOrderId(razorpayOrderId).ifPresentOrElse(purchase -> {
            if (purchase.getStatus() != Purchase.Status.PAID) {
                grantEntitlement(purchase, razorpayPaymentId);
            }
        }, () -> log.warn("Webhook payment.captured for unknown order {}", razorpayOrderId));
    }

    public boolean verifyWebhookSignature(String rawPayload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Received a Razorpay webhook but no RAZORPAY_WEBHOOK_SECRET is configured — rejecting");
            return false;
        }
        try {
            return Utils.verifyWebhookSignature(rawPayload, signatureHeader, webhookSecret);
        } catch (RazorpayException e) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<PurchaseResponse> listMine(UUID userId) {
        List<Purchase> purchases = purchaseRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, Experience> experiencesById = experienceRepository
                .findAllById(purchases.stream().map(Purchase::getExperienceId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Experience::getId, e -> e));
        return purchases.stream()
                .map(p -> PurchaseResponse.from(p, experiencesById.get(p.getExperienceId())))
                .toList();
    }

    private void grantEntitlement(Purchase purchase, String razorpayPaymentId) {
        purchase.markPaid(razorpayPaymentId);
        purchaseRepository.save(purchase);

        if (!entitlementRepository.existsByUserIdAndExperienceId(purchase.getUserId(), purchase.getExperienceId())) {
            entitlementRepository.save(new Entitlement(
                    UUID.randomUUID(), purchase.getUserId(), purchase.getExperienceId(), purchase.getId()));
        }
    }

    private RazorpayClient client() throws RazorpayException {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new RazorpayException(
                    "RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET are not set on the backend environment");
        }
        return new RazorpayClient(keyId, keySecret);
    }
}
