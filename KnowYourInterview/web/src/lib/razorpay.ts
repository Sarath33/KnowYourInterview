// Lazy-loads Razorpay's Checkout script on demand rather than in index.html, so pages
// that never need payments (browse without buying, admin review, etc.) don't pay for it.

import type { CreateOrderResponse, User } from "../../../shared/types";

/** The success payload Razorpay Checkout hands back to the `handler` callback. */
export interface RazorpaySuccessResponse {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

export interface RazorpayPrefill {
  name?: string;
  email?: string;
  contact?: string;
}

/** The subset of Razorpay Checkout options this app actually sets. */
export interface RazorpayOptions {
  key: string;
  amount: number;
  currency: string;
  order_id: string;
  name: string;
  description?: string;
  prefill?: RazorpayPrefill;
  handler: (response: RazorpaySuccessResponse) => void;
  modal?: { ondismiss?: () => void };
}

interface RazorpayInstance {
  open: () => void;
  on: (event: string, handler: (...args: unknown[]) => void) => void;
}

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => RazorpayInstance;
  }
}

let loadPromise: Promise<void> | null = null;

export function loadRazorpayCheckout(): Promise<void> {
  if (typeof window !== "undefined" && window.Razorpay) return Promise.resolve();
  if (loadPromise) return loadPromise;

  loadPromise = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://checkout.razorpay.com/v1/checkout.js";
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      loadPromise = null;
      reject(new Error("Could not load the payment popup — check your connection and try again"));
    };
    document.body.appendChild(script);
  });
  return loadPromise;
}

/** Loads the checkout script (if needed), constructs the Razorpay instance from a
 * create-order response, wires the success/failure/dismiss callbacks, and opens the
 * popup. Keeps the ~45-line inline checkout wiring out of the component. */
export async function startCheckout(
  order: CreateOrderResponse,
  user: User | null,
  handlers: {
    onSuccess: (response: RazorpaySuccessResponse) => void;
    onFailure: () => void;
    onDismiss: () => void;
  },
): Promise<void> {
  await loadRazorpayCheckout();
  if (!window.Razorpay) {
    throw new Error("Payment popup failed to load");
  }
  const checkout = new window.Razorpay({
    key: order.razorpayKeyId,
    amount: order.amountPaise,
    currency: order.currency,
    order_id: order.razorpayOrderId,
    name: "Know Your Interview",
    description: "Unlock full interview experience",
    prefill: user ? { email: user.email, name: user.displayName } : undefined,
    handler: handlers.onSuccess,
    modal: { ondismiss: handlers.onDismiss },
  });
  checkout.on("payment.failed", () => handlers.onFailure());
  checkout.open();
}
