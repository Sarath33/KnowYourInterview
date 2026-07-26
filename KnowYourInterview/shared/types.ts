// Know Your Interview — shared API contract types
// Consumed by both web (React) and mobile (Expo/React Native).
// Keep this in sync with api/src/main/resources/db/migration/V1__init.sql
// and the MVP API contract in docs/02-phase0-design.md §6.
//
// Amounts are always in paise (INR minor unit, 1 INR = 100 paise).

export type UUID = string;
export type ISODateTime = string;

export interface User {
  id: UUID;
  email: string;
  displayName: string;
  isAdmin: boolean;
  createdAt: ISODateTime;
}

export type ExperienceOutcome = "OFFER" | "REJECTED" | "WITHDRAWN";

export type ExperienceStatus =
  | "DRAFT"
  | "PENDING_REVIEW"
  | "APPROVED"
  | "REJECTED"
  | "PUBLISHED";

export interface ExperienceRound {
  id: UUID;
  roundNumber: number;
  roundType: string; // e.g. "PHONE_SCREEN", "ONSITE", "SYSTEM_DESIGN"
  durationMinutes?: number;
  questionsAsked?: string;
  topicsTags?: string[];
  approach?: string;
  interviewerBehavior?: string;
  difficulty?: number; // 1-5
}

export interface ExperienceTeaser {
  id: UUID;
  company: string;
  roleTitle: string;
  level?: string;
  location?: string;
  isRemote: boolean;
  interviewMonth?: number;
  interviewYear?: number;
  outcome: ExperienceOutcome;
  teaser: string;
  pricePaise: number;
  /** Safe to show pre-purchase — signals content depth without leaking round content. */
  roundCount: number;
  publishedAt?: ISODateTime;
  /** True if the current viewer already holds a paid entitlement for this experience.
   * Always false for a guest (no token sent). On ExperienceFull (which extends this) it's
   * always true — reaching a full response at all implies access. Also always true for a
   * free (isFree) experience — see the backend's getPublicView, which grants everyone
   * full access to those with no entitlement needed. */
  unlocked: boolean;
  /** Admin-authored "reference a public source" experiences are always free — no paywall,
   * pricePaise is 0/not meaningful. See ExperienceRequest.sourceUrl. Optional/undefined is
   * treated the same as false everywhere it's read. */
  isFree?: boolean;
  sourceUrl?: string;
  sourceName?: string;
}

export interface ExperienceFull extends ExperienceTeaser {
  contributorId: UUID;
  status: ExperienceStatus;
  prepAdvice?: string;
  overallDifficulty?: number; // 1-5
  timeline?: string;
  compensation?: string;
  rejectionReason?: string;
  /** How many people hold a real (paid) entitlement — visible to the owner, an admin, or
   * a purchaser (same audience as everything else on this type). Not on the public teaser. */
  unlockCount: number;
  rounds: ExperienceRound[];
  proofDocuments: ProofDocument[];
}

/** A prior version of an experience's top-level fields, captured right before an edit
 * overwrote them (see GET /experiences/:id/history). changedFields lists which of these
 * fields the edit right after this snapshot actually changed — computed server-side.
 * Scoped to the fields "Edit details" edits; rounds have their own edit-in-place UI and
 * aren't covered by this history. */
export interface ExperienceEditSnapshot {
  id: UUID;
  recordedAt: ISODateTime;
  company: string;
  roleTitle: string;
  level?: string;
  location?: string;
  isRemote: boolean;
  interviewMonth?: number;
  interviewYear?: number;
  outcome: ExperienceOutcome;
  teaser: string;
  prepAdvice?: string;
  overallDifficulty?: number;
  timeline?: string;
  compensation?: string;
  changedFields: string[];
}

/** Body for both POST /experiences (create draft) and PUT /experiences/:id (edit draft).
 * No price field — the platform sets it, contributors don't. */
export interface ExperienceRequest {
  company: string;
  roleTitle: string;
  level?: string;
  location?: string;
  isRemote: boolean;
  interviewMonth?: number; // 1-12
  interviewYear?: number;
  outcome: ExperienceOutcome;
  teaser: string;
  prepAdvice?: string;
  overallDifficulty?: number; // 1-5
  timeline?: string;
  compensation?: string;
  /** Admin-only — see backend ExperienceService#createDraft. Setting sourceUrl marks this
   * as a "reference a public source" submission: forced free, no platform price. Ignored
   * (not persisted) on an edit — only meaningful at creation. sourceName is required
   * whenever sourceUrl is set. */
  sourceUrl?: string;
  sourceName?: string;
  /** Open to any contributor, unlike sourceUrl above. Marks this as the contributor's own
   * free submission: forced free (no platform price) AND skips admin review entirely —
   * it publishes as soon as it's submitted, needing only at least one round (no proof
   * document required, since nobody reviews it). Ignored on an edit, same as sourceUrl/
   * sourceName. Mutually exclusive with sourceUrl — a sourceUrl always wins if both are set. */
  freeContribution?: boolean;
}

export interface RoundRequest {
  roundType: string;
  durationMinutes?: number;
  questionsAsked?: string;
  topicsTags?: string[];
  approach?: string;
  interviewerBehavior?: string;
  difficulty?: number; // 1-5
}

export interface RejectRequest {
  reason: string;
}

export interface PagedResponse<T> {
  items: T[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

/** Returned by GET /experiences/:id when the caller doesn't hold an entitlement. */
export type ExperienceView =
  | { entitled: false; teaser: ExperienceTeaser }
  | { entitled: true; full: ExperienceFull };

export interface ProofDocument {
  id: UUID;
  fileName: string;
  contentType: string;
  uploadedAt: ISODateTime;
}

export type PurchaseStatus = "CREATED" | "PAID" | "FAILED";

/** company/roleTitle/level ride along so My Library can show what was actually bought
 * instead of just a price and a date. level is optional — Experience.level itself is. */
export interface Purchase {
  id: UUID;
  experienceId: UUID;
  company: string;
  roleTitle: string;
  level?: string;
  amountPaise: number;
  status: PurchaseStatus;
  createdAt: ISODateTime;
}

/** Response for POST /experiences/:id/purchase — everything the client needs to
 * open Razorpay Checkout. */
export interface CreateOrderResponse {
  experienceId: UUID;
  razorpayOrderId: string;
  amountPaise: number;
  currency: string;
  razorpayKeyId: string;
}

/** Body for POST /purchases/confirm — the three fields Razorpay Checkout's success
 * handler hands back, forwarded as-is for server-side signature verification. */
export interface ConfirmPaymentRequest {
  razorpayOrderId: string;
  razorpayPaymentId: string;
  razorpaySignature: string;
}

export interface Entitlement {
  id: UUID;
  experienceId: UUID;
  grantedAt: ISODateTime;
}

export interface PayoutAccount {
  id: UUID;
  accountHolderName: string;
  hasRazorpayxFundAccount: boolean;
}

export type PayoutStatus = "PENDING" | "PROCESSING" | "PAID" | "FAILED";

/** Money movement is currently a manual batch process, not a live RazorpayX transfer
 * (RazorpayX needs a separate Current Account with its own business KYC approval) —
 * see docs/04-handoff.md. An admin wires the flat fee themselves and marks it paid. */
export interface Payout {
  id: UUID;
  experienceId: UUID;
  company: string;
  roleTitle: string;
  amountPaise: number;
  status: PayoutStatus;
  payoutReference?: string;
  paidAt?: ISODateTime;
  createdAt: ISODateTime;
}

/** Same as Payout, plus who the contributor is — only returned from the admin queue. */
export interface PayoutAdminView extends Payout {
  contributorId: UUID;
  contributorEmail: string;
  contributorDisplayName: string;
}

export interface MarkPayoutPaidRequest {
  reference?: string;
}

export interface HealthResponse {
  status: "UP" | "DOWN";
  service: string;
  timestamp: ISODateTime;
}

// --- Auth ---

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface GoogleLoginRequest {
  idToken: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: User;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface LogoutRequest {
  refreshToken: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  fieldErrors?: Record<string, string>;
}
