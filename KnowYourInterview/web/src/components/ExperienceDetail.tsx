import { useEffect, useState } from "react";
import type { ExperienceFull, ExperienceTeaser, ExperienceView, User } from "../../../shared/types";
import * as api from "../lib/api";
import { loadRazorpayCheckout } from "../lib/razorpay";
import { useAuth } from "../context/AuthContext";
import { OutcomeTag, RemoteTag } from "./tags";
import { ArrowLeftIcon, LockIcon } from "./icons";
import { interviewedLabel, roundCountLabel } from "../lib/format";

function levelLine(exp: { level?: string; location?: string }): string {
  return [exp.level, exp.location].filter(Boolean).join(" · ") || "—";
}

export function ExperienceDetail({
  experienceId,
  onClose,
  onLoginRequired,
}: {
  experienceId: string;
  onClose: () => void;
  onLoginRequired: () => void;
}) {
  const { accessToken, isAuthenticated, user } = useAuth();
  const [view, setView] = useState<ExperienceView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [purchasing, setPurchasing] = useState(false);
  const [unpublishing, setUnpublishing] = useState(false);

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

  const unpublish = async () => {
    if (!accessToken) return;
    setError(null);
    setUnpublishing(true);
    try {
      await api.unpublishExperience(accessToken, experienceId);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not unpublish");
    } finally {
      setUnpublishing(false);
    }
  };

  return (
    <div>
      <button type="button" onClick={onClose} className="btn-ghost row" style={{ gap: 6, fontSize: 14, fontWeight: 600, marginBottom: 20 }}>
        <ArrowLeftIcon />
        Back to browse
      </button>

      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading && <p className="muted">Loading…</p>}

      {view && !view.entitled && (
        <TeaserWithUnlock
          teaser={view.teaser}
          isAuthenticated={isAuthenticated}
          purchasing={purchasing}
          onUnlock={unlock}
          onLoginRequired={onLoginRequired}
        />
      )}
      {view && view.entitled && (
        <FullExperience full={view.full} user={user} unpublishing={unpublishing} onUnpublish={unpublish} />
      )}
    </div>
  );
}

function TeaserWithUnlock({
  teaser,
  isAuthenticated,
  purchasing,
  onUnlock,
  onLoginRequired,
}: {
  teaser: ExperienceTeaser;
  isAuthenticated: boolean;
  purchasing: boolean;
  onUnlock: () => void;
  onLoginRequired: () => void;
}) {
  const recency = interviewedLabel(teaser.interviewMonth, teaser.interviewYear);
  return (
    <div className="card card-pad-lg" style={{ maxWidth: 640, margin: "0 auto" }}>
      <div className="card-kicker" style={{ marginBottom: 6 }}>
        {levelLine(teaser)}
      </div>
      <h1 className="page-title" style={{ marginBottom: 14 }}>
        {teaser.company} — {teaser.roleTitle}
      </h1>
      <div className="row" style={{ gap: 8, marginBottom: 10 }}>
        <OutcomeTag outcome={teaser.outcome} />
        {teaser.isRemote && <RemoteTag />}
        <span className="tag tag-neutral">{roundCountLabel(teaser.roundCount)}</span>
      </div>
      {recency && (
        <div style={{ fontSize: 13, color: "var(--text-muted)", marginBottom: 8 }}>{recency}</div>
      )}
      <p style={{ color: "var(--text-secondary)", fontSize: 15, lineHeight: 1.6 }}>{teaser.teaser}</p>
      <div className="divider" />
      <p style={{ fontSize: 15, color: "var(--text-secondary-2)", lineHeight: 1.6 }}>
        <strong className="price-tag-lg">₹{(teaser.pricePaise / 100).toFixed(2)}</strong>
        &nbsp;to unlock the full write-up — every round, questions asked, prep advice, and outcome
        details.
      </p>
      {isAuthenticated ? (
        <button
          type="button"
          onClick={onUnlock}
          disabled={purchasing}
          className="btn btn-primary btn-block"
          style={{ padding: 13, fontSize: 15, marginTop: 8 }}
        >
          {purchasing ? "Opening checkout…" : `Unlock ₹${(teaser.pricePaise / 100).toFixed(2)}`}
          <LockIcon />
        </button>
      ) : (
        <p style={{ marginTop: 8 }}>
          <a
            href="#"
            onClick={(e) => {
              e.preventDefault();
              onLoginRequired();
            }}
          >
            Log in
          </a>{" "}
          to unlock this experience.
        </p>
      )}
    </div>
  );
}

function FullExperience({
  full,
  user,
  unpublishing,
  onUnpublish,
}: {
  full: ExperienceFull;
  user: User | null;
  unpublishing: boolean;
  onUnpublish: () => void;
}) {
  const hasStats = full.timeline || full.compensation || full.overallDifficulty;
  const canUnpublish =
    full.status === "PUBLISHED" && !!user && (user.id === full.contributorId || user.isAdmin);
  const recency = interviewedLabel(full.interviewMonth, full.interviewYear);
  return (
    <div className="card card-pad-lg" style={{ maxWidth: 720, margin: "0 auto" }}>
      <div className="card-kicker" style={{ marginBottom: 6 }}>
        {levelLine(full)}
      </div>
      <h1 className="page-title" style={{ marginBottom: 14 }}>
        {full.company} — {full.roleTitle}
      </h1>
      <div className="row" style={{ gap: 8, marginBottom: recency ? 8 : 24 }}>
        <OutcomeTag outcome={full.outcome} />
        {full.isRemote && <RemoteTag />}
      </div>
      {(recency || full.publishedAt) && (
        <div style={{ fontSize: 13, color: "var(--text-muted)", marginBottom: 24 }}>
          {recency}
          {recency && full.publishedAt && " · "}
          {full.publishedAt &&
            `Unlocked by ${full.unlockCount} ${full.unlockCount === 1 ? "person" : "people"}`}
        </div>
      )}

      {canUnpublish && (
        <p style={{ fontSize: 13, color: "var(--text-muted)", marginBottom: 20 }}>
          <button type="button" onClick={onUnpublish} disabled={unpublishing} className="btn-danger-text" style={{ padding: 0 }}>
            {unpublishing ? "Unpublishing…" : "Unpublish"}
          </button>{" "}
          — pulls this from Browse and sends it back through review before it's live
          again. Anyone who already unlocked it keeps their access.
        </p>
      )}

      {hasStats && (
        <div className="detail-stat-grid">
          {full.timeline && (
            <div>
              <div className="detail-stat-label">Timeline</div>
              <p style={{ margin: 0, fontSize: 14, color: "var(--text-secondary-2)" }}>{full.timeline}</p>
            </div>
          )}
          {full.compensation && (
            <div>
              <div className="detail-stat-label">Compensation</div>
              <p style={{ margin: 0, fontSize: 14, color: "var(--text-secondary-2)" }}>{full.compensation}</p>
            </div>
          )}
          {full.overallDifficulty && (
            <div>
              <div className="detail-stat-label">Overall difficulty</div>
              <p style={{ margin: 0, fontSize: 14, color: "var(--text-secondary-2)" }}>{full.overallDifficulty}/5</p>
            </div>
          )}
        </div>
      )}

      {full.prepAdvice && (
        <>
          <div className="section-title">Prep advice</div>
          <p style={{ color: "var(--text-secondary)", fontSize: 14, lineHeight: 1.6, marginTop: 0 }}>
            {full.prepAdvice}
          </p>
          <div className="divider" />
        </>
      )}

      <div className="section-title">Rounds</div>
      {full.rounds.length === 0 ? (
        <p className="muted">No rounds recorded.</p>
      ) : (
        <div className="stack-md">
          {full.rounds.map((round) => (
            <div key={round.id} className="round-card">
              <div className="round-title">
                Round {round.roundNumber} — {round.roundType}
              </div>
              <div className="round-meta">
                {round.durationMinutes && <span>{round.durationMinutes} min</span>}
                {round.difficulty && <span>Difficulty {round.difficulty}/5</span>}
              </div>
              {round.topicsTags && round.topicsTags.length > 0 && (
                <p className="round-field">
                  <strong>Topics:</strong> {round.topicsTags.join(", ")}
                </p>
              )}
              {round.questionsAsked && (
                <p className="round-field">
                  <strong>Questions:</strong> {round.questionsAsked}
                </p>
              )}
              {round.approach && (
                <p className="round-field">
                  <strong>Approach:</strong> {round.approach}
                </p>
              )}
              {round.interviewerBehavior && (
                <p className="round-field">
                  <strong>Interviewer:</strong> {round.interviewerBehavior}
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
