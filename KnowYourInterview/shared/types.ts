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
  publishedAt?: ISODateTime;
}

export interface ExperienceFull extends ExperienceTeaser {
  contributorId: UUID;
  status: ExperienceStatus;
  prepAdvice?: string;
  overallDifficulty?: number; // 1-5
  timeline?: string;
  compensation?: string;
  rejectionReason?: string;
  rounds: ExperienceRound[];
  proofDocuments: ProofDocument[];
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

export interface Purchase {
  id: UUID;
  experienceId: UUID;
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
