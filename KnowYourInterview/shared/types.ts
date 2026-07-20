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
  rounds: ExperienceRound[];
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

export interface Payout {
  id: UUID;
  experienceId: UUID;
  amountPaise: number;
  status: PayoutStatus;
  createdAt: ISODateTime;
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
