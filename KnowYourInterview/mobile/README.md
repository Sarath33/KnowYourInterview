# Know Your Interview — Mobile

## 1. Generate the app

From the **repo root** (not inside `mobile/`):

```bash
npx create-expo-app@latest mobile
```

If it warns the `mobile/` directory isn't empty (it already has this README), let it proceed / choose to keep existing files — it won't touch this file.

This uses the default Expo Router template (SDK 56). If you'd rather start from a blank template instead, use `npx create-expo-app@latest mobile --template blank-typescript` and adjust step 4 below (there'll be a single `App.tsx` instead of `app/`).

## 2. Point it at the API

Create `mobile/.env`:

```
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080
```

**On a physical phone, `localhost` is the phone itself.** Use your computer's LAN IP instead, e.g. `EXPO_PUBLIC_API_BASE_URL=http://192.168.1.x:8080` (find it with `ipconfig getifaddr en0` on macOS), or run `npx expo start --tunnel`.

## 3. Add the API client

Create `mobile/lib/api.ts`:

```ts
import type { HealthResponse } from "../../shared/types";

const BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL as string;

export async function getHealth(): Promise<HealthResponse> {
  const res = await fetch(`${BASE_URL}/api/v1/health`);
  if (!res.ok) throw new Error(`Health check failed: ${res.status}`);
  return res.json();
}
```

## 4. Wire it into a screen

If you used the default Expo Router template, edit `mobile/app/(tabs)/index.tsx`; if you used `blank-typescript`, edit `mobile/App.tsx`. Either way, the body is the same:

```tsx
import { useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { getHealth } from "../../lib/api"; // adjust relative path to wherever lib/ ends up
import type { HealthResponse } from "../../shared/types";

export default function HealthScreen() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getHealth().then(setHealth).catch((e) => setError(e.message));
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Know Your Interview</Text>
      {error && <Text style={{ color: "red" }}>API unreachable: {error}</Text>}
      {!error && !health && <Text>Checking API…</Text>}
      {health && (
        <Text>
          API status: {health.status} ({health.service})
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24 },
  title: { fontSize: 20, fontWeight: "600", marginBottom: 12 },
});
```

## 5. Run it

```bash
npx expo start
```

Scan the QR code with Expo Go, or press `i` / `a` for a simulator. You should see "API status: UP".

## Conventions

- TypeScript, strict mode on.
- Import shared API types from `shared/types.ts` via a relative path for now; adjust the exact `../../shared/types` depth to wherever your file actually lives.
- API base URL comes from `EXPO_PUBLIC_API_BASE_URL`, never hardcoded.
