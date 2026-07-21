import { useEffect, useState } from "react";
import type { ExperienceFull, ExperienceTeaser, ExperienceView } from "../../../shared/types";
import * as api from "../lib/api";
import { loadRazorpayCheckout } from "../lib/razorpay";
import { useAuth } from "../context/AuthContext";

export function ExperienceDetail({
  experienceId,
  onClose,
}: {
  experienceId: string;
  onClose: () => void;
}) {
  const { accessToken, isAuthenticated, user } = useAuth();
  const [view, setView] = useState<ExperienceView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [purchasing, setPurchasing] = useState(false);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.getExperience(experienceId, accessToken ?? undefined);
      setView(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experienceId]);

  const unlock = async () => {
    if (!accessToken) return;
    setError(null);
    setPurchasing(true);
    try {
      await loadRazorpayCheckout();
      const order = await api.createPurchaseOrder(accessToken, experienceId);

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
        handler: async (response: unknown) => {
          const r = response as {
            razorpay_order_id: string;
            razorpay_payment_id: string;
            razorpay_signature: string;
          };
          try {
            await api.confirmPurchase(accessToken, {
              razorpayOrderId: r.razorpay_order_id,
              razorpayPaymentId: r.razorpay_payment_id,
              razorpaySignature: r.razorpay_signature,
            });
            await load();
          } catch (err) {
            // The charge went through on Razorpay's side even if our confirm call
            // failed — the webhook backup path (see PurchaseService) will still grant
            // the entitlement shortly, so this isn't a lost payment.
            setError(
              err instanceof Error
                ? `Payment received but confirmation failed: ${err.message}. Refresh in a moment — it should unlock automatically.`
                : "Payment received but confirmation failed. Refresh in a moment.",
            );
          } finally {
            setPurchasing(false);
          }
        },
        modal: {
          ondismiss: () => setPurchasing(false),
        },
      });
      checkout.on("payment.failed", () => {
        setError("Payment failed — you weren't charged. You can try again.");
        setPurchasing(false);
      });
      checkout.open();
    } catch (err) {
      setPurchasing(false);
      setError(err instanceof Error ? err.message : "Could not start checkout");
    }
  };

  return (
    <div style={{ marginTop: "1.5rem", border: "1px solid #ccc", padding: "1rem", borderRadius: "6px" }}>
      <button type="button" onClick={onClose}>
        ← Back to browse
      </button>

      {error && <p style={{ color: "red" }}>{error}</p>}
      {loading && <p>Loading…</p>}

      {view && !view.entitled && (
        <TeaserWithUnlock
          teaser={view.teaser}
          isAuthenticated={isAuthenticated}
          purchasing={purchasing}
          onUnlock={unlock}
        />
      )}
      {view && view.entitled && <FullExperience full={view.full} />}
    </div>
  );
}

function TeaserWithUnlock({
  teaser,
  isAuthenticated,
  purchasing,
  onUnlock,
}: {
  teaser: ExperienceTeaser;
  isAuthenticated: boolean;
  purchasing: boolean;
  onUnlock: () => void;
}) {
  return (
    <div>
      <h2>
        {teaser.company} — {teaser.roleTitle}
      </h2>
      {teaser.level && <p>Level: {teaser.level}</p>}
      <p>{teaser.teaser}</p>
      <p>
        <strong>₹{(teaser.pricePaise / 100).toFixed(2)}</strong> to unlock the full write-up: every
        round, questions asked, prep advice, and outcome details.
      </p>
      {isAuthenticated ? (
        <button type="button" onClick={onUnlock} disabled={purchasing}>
          {purchasing ? "Opening checkout…" : `Unlock ₹${(teaser.pricePaise / 100).toFixed(2)}`}
        </button>
      ) : (
        <p>Log in to unlock this experience.</p>
      )}
    </div>
  );
}

function FullExperience({ full }: { full: ExperienceFull }) {
  return (
    <div>
      <h2>
        {full.company} — {full.roleTitle}
      </h2>
      {full.level && <p>Level: {full.level}</p>}
      {full.location && <p>Location: {full.location} {full.isRemote && "(remote)"}</p>}
      <p>Outcome: {full.outcome}</p>
      {full.timeline && <p>Timeline: {full.timeline}</p>}
      {full.compensation && <p>Compensation: {full.compensation}</p>}
      {full.overallDifficulty && <p>Overall difficulty: {full.overallDifficulty}/5</p>}
      {full.prepAdvice && (
        <>
          <h3>Prep advice</h3>
          <p>{full.prepAdvice}</p>
        </>
      )}
      <h3>Rounds</h3>
      {full.rounds.length === 0 ? (
        <p>No rounds recorded.</p>
      ) : (
        <ol>
          {full.rounds.map((round) => (
            <li key={round.id} style={{ marginBottom: "0.75rem" }}>
              <strong>{round.roundType}</strong>
              {round.durationMinutes && <span> — {round.durationMinutes} min</span>}
              {round.difficulty && <span> — difficulty {round.difficulty}/5</span>}
              {round.topicsTags && round.topicsTags.length > 0 && (
                <p>Topics: {round.topicsTags.join(", ")}</p>
              )}
              {round.questionsAsked && <p>Questions: {round.questionsAsked}</p>}
              {round.approach && <p>Approach: {round.approach}</p>}
              {round.interviewerBehavior && <p>Interviewer: {round.interviewerBehavior}</p>}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
