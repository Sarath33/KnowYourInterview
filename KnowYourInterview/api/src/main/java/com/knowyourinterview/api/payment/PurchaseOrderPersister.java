package com.knowyourinterview.api.payment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tiny transactional seam for persisting a freshly-created Purchase. Kept as its own bean
 * rather than a method on PurchaseService because Spring's @Transactional is proxy-based:
 * a self-invoked method inside PurchaseService would bypass the proxy and run with no
 * transaction at all. PurchaseService.createOrder makes its remote Razorpay call OUTSIDE
 * any transaction (so a slow/failed network call never holds a DB connection open), then
 * calls this to open a short-lived transaction just for the insert (H1).
 */
@Component
class PurchaseOrderPersister {

    private final PurchaseRepository purchaseRepository;

    PurchaseOrderPersister(PurchaseRepository purchaseRepository) {
        this.purchaseRepository = purchaseRepository;
    }

    @Transactional
    public Purchase persist(Purchase purchase) {
        return purchaseRepository.save(purchase);
    }
}
