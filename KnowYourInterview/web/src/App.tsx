import { useEffect, useState } from "react";
import { getHealth } from "./lib/api";
import type { HealthResponse } from "../../shared/types";
import "./App.css";

function App() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getHealth()
      .then(setHealth)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  return (
    <div style={{ fontFamily: "sans-serif", padding: "2rem" }}>
      <h1>Know Your Interview</h1>
      {error && <p style={{ color: "red" }}>API unreachable: {error}</p>}
      {!error && !health && <p>Checking API…</p>}
      {health && (
        <p>
          API status: <strong>{health.status}</strong> ({health.service})
        </p>
      )}
    </div>
  );
}

export default App;
