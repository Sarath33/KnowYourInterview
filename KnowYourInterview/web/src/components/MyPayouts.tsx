import type { Payout } from "../../../shared/types";
import * as api from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { formatPaise } from "../lib/format";

function payoutStatusLabel(status: Payout["status"]) {
  if (status === "PAID") return <span className="tag tag-success">Paid</span>;
  if (status === "PROCESSING") return <span className="tag tag-warning">Processing</span>;
  if (status === "FAILED") return <span className="tag tag-danger">Failed</span>;
  return <span className="tag tag-neutral">Pending</span>;
}

export function MyPayouts() {
  const { data, loading, error } = useAsync(() => api.listMyPayouts(), []);
  const payouts = data ?? [];

  return (
    <div>
      <h1 className="page-title" style={{ marginBottom: 6 }}>
        My payouts
      </h1>
      <p className="page-subtext" style={{ marginBottom: 24 }}>
        Flat-fee payouts for published experiences. Paid manually for now — you'll be contacted for
        bank/UPI details when one's owed to you.
      </p>
      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading ? (
        <p className="muted" aria-busy="true" aria-live="polite">
          Loading…
        </p>
      ) : payouts.length === 0 ? (
        <p className="muted">Nothing here yet — payouts show up once one of your experiences is published.</p>
      ) : (
        <div className="stack-sm">
          {payouts.map((p) => (
            <div key={p.id} className="card card-pad-sm row" style={{ justifyContent: "space-between" }}>
              <div>
                <div className="card-title" style={{ fontSize: 15 }}>
                  {p.company} — {p.roleTitle}
                </div>
                <span className="price-tag">{formatPaise(p.amountPaise)}</span>
                {p.paidAt && (
                  <span className="muted" style={{ marginLeft: 8, fontSize: 13 }}>
                    paid {new Date(p.paidAt).toLocaleDateString()}
                  </span>
                )}
              </div>
              {payoutStatusLabel(p.status)}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
