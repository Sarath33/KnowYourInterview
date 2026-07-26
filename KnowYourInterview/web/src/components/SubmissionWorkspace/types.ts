import type { ExperienceFull, ExperienceOutcome, ExperienceRequest } from "../../../../shared/types";

export const OUTCOMES: ExperienceOutcome[] = ["OFFER", "REJECTED", "WITHDRAWN"];

export const ROUND_TYPES: { value: string; label: string }[] = [
  { value: "PHONE_SCREEN", label: "Phone screen" },
  { value: "ONSITE", label: "Onsite" },
  { value: "SYSTEM_DESIGN", label: "System design" },
  { value: "CODING", label: "Coding" },
  { value: "TAKE_HOME", label: "Take-home" },
  { value: "LIVE_DEBUGGING", label: "Live debugging" },
  { value: "PRODUCT_SENSE", label: "Product sense" },
  { value: "CASE_STUDY", label: "Case study" },
  { value: "LEADERSHIP", label: "Leadership / behavioral" },
  { value: "ONSITE_BAR_RAISER", label: "Bar raiser" },
];

export function roundTypeLabel(roundType: string): string {
  return ROUND_TYPES.find((t) => t.value === roundType)?.label ?? roundType;
}

export interface RoundFormState {
  roundType: string;
  durationMinutes: string;
  difficulty: string;
  topicsTags: string;
  questionsAsked: string;
  approach: string;
  interviewerBehavior: string;
}

export const emptyRoundForm: RoundFormState = {
  roundType: "",
  durationMinutes: "",
  difficulty: "",
  topicsTags: "",
  questionsAsked: "",
  approach: "",
  interviewerBehavior: "",
};

export interface RoundLike {
  roundType: string;
  durationMinutes?: number;
  difficulty?: number;
  topicsTags?: string[];
  questionsAsked?: string;
  approach?: string;
  interviewerBehavior?: string;
}

export function toRoundFormState(round: RoundLike): RoundFormState {
  return {
    roundType: round.roundType,
    durationMinutes: round.durationMinutes ? String(round.durationMinutes) : "",
    difficulty: round.difficulty ? String(round.difficulty) : "",
    topicsTags: round.topicsTags ? round.topicsTags.join(", ") : "",
    questionsAsked: round.questionsAsked ?? "",
    approach: round.approach ?? "",
    interviewerBehavior: round.interviewerBehavior ?? "",
  };
}

export interface DetailsFormState {
  company: string;
  roleTitle: string;
  level: string;
  location: string;
  isRemote: boolean;
  interviewMonth: string;
  interviewYear: string;
  outcome: ExperienceOutcome;
  teaser: string;
  prepAdvice: string;
  overallDifficulty: string;
  timeline: string;
  compensation: string;
}

export const emptyDetailsForm: DetailsFormState = {
  company: "",
  roleTitle: "",
  level: "",
  location: "",
  isRemote: false,
  interviewMonth: "",
  interviewYear: "",
  outcome: "OFFER",
  teaser: "",
  prepAdvice: "",
  overallDifficulty: "",
  timeline: "",
  compensation: "",
};

export function toDetailsForm(exp: ExperienceFull): DetailsFormState {
  return {
    company: exp.company,
    roleTitle: exp.roleTitle,
    level: exp.level ?? "",
    location: exp.location ?? "",
    isRemote: exp.isRemote,
    interviewMonth: exp.interviewMonth ? String(exp.interviewMonth) : "",
    interviewYear: exp.interviewYear ? String(exp.interviewYear) : "",
    outcome: exp.outcome,
    teaser: exp.teaser,
    prepAdvice: exp.prepAdvice ?? "",
    overallDifficulty: exp.overallDifficulty ? String(exp.overallDifficulty) : "",
    timeline: exp.timeline ?? "",
    compensation: exp.compensation ?? "",
  };
}

export function toExperienceRequest(f: DetailsFormState): ExperienceRequest {
  return {
    company: f.company,
    roleTitle: f.roleTitle,
    level: f.level || undefined,
    location: f.location || undefined,
    isRemote: f.isRemote,
    interviewMonth: f.interviewMonth ? Number(f.interviewMonth) : undefined,
    interviewYear: f.interviewYear ? Number(f.interviewYear) : undefined,
    outcome: f.outcome,
    teaser: f.teaser,
    prepAdvice: f.prepAdvice || undefined,
    overallDifficulty: f.overallDifficulty ? Number(f.overallDifficulty) : undefined,
    timeline: f.timeline || undefined,
    compensation: f.compensation || undefined,
  };
}

/** Shared required-field validation for the create and edit detail forms. */
export function validateDetails(form: DetailsFormState): string | null {
  if (!form.company.trim() || !form.roleTitle.trim() || !form.teaser.trim()) {
    return "Company, role, and teaser are required.";
  }
  return null;
}
