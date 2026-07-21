import type { ExperienceOutcome, ExperienceStatus } from "../../../shared/types";

export function OutcomeTag({ outcome }: { outcome: ExperienceOutcome }) {
  if (outcome === "OFFER") return <span className="tag tag-success">Offer</span>;
  if (outcome === "REJECTED") return <span className="tag tag-danger">Rejected</span>;
  return <span className="tag tag-neutral">Withdrawn</span>;
}

export function RemoteTag() {
  return <span className="tag tag-neutral">Remote</span>;
}

const STATUS_LABEL: Record<ExperienceStatus, string> = {
  DRAFT: "Draft",
  PENDING_REVIEW: "Pending review",
  APPROVED: "Approved",
  REJECTED: "Rejected",
  PUBLISHED: "Published",
};

export function StatusTag({ status, small = false }: { status: ExperienceStatus; small?: boolean }) {
  const cls = small ? "tag tag-sm" : "tag";
  if (status === "PUBLISHED" || status === "APPROVED") return <span className={`${cls} tag-success`}>{STATUS_LABEL[status]}</span>;
  if (status === "PENDING_REVIEW") return <span className={`${cls} tag-warning`}>{STATUS_LABEL[status]}</span>;
  if (status === "REJECTED") return <span className={`${cls} tag-danger`}>{STATUS_LABEL[status]}</span>;
  return <span className={`${cls} tag-neutral`}>{STATUS_LABEL[status]}</span>;
}
