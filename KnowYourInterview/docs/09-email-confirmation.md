# Email confirmation

Registration now sends a confirmation link, and an unconfirmed account can't submit an
experience or unlock one until it clicks through. Password-reset emails moved onto the same
sender at the same time, so nothing auth-related is a log line any more.

## Verification status

- **Frontend typecheck: passing** (`tsc -b`, tests included).
- **Frontend tests: not run here** (rollup's platform binary can't be installed in this
  environment). `cd web && npm test`.
- **Backend: not compiled here** (no JDK 21/Maven/Docker). `cd api && ./mvnw verify`.

New/changed constructors are the likeliest source of a compile error if something's off:
`AuthService` (+2 args), `ExperienceService` (+1), `PurchaseService` (+1). Their tests are
updated to match.

---

## What it does

**Only email/password signups need confirming.** A Google sign-in is verified at creation —
`GoogleSignInVerifier` already refuses an ID token whose `email_verified` claim isn't set, so
Google has done precisely the check a confirmation link does. Signing in with Google against
an existing unconfirmed account also clears it, which gives a user who never opened the email
a second route through.

**Confirmation gates what an account can do, not whether it can log in.** Registering issues a
session immediately and browsing stays fully open. What's blocked until confirmed:

| Action | Where it's enforced |
|---|---|
| Create a draft | `ExperienceService#createDraft` |
| Submit for review | `ExperienceService#submitForReview` |
| Open a checkout | `PurchaseService#createOrder` |

Everything else — logging in, browsing, reading anything already unlocked, confirming a
payment that's already gone through — is untouched. Blocking a *completed* payment's
confirmation would leave someone charged with nothing to show for it, so that path is
deliberately outside the gate.

The block is a **403, not a 401**. A 401 would tell the web client's api layer the session is
dead and send it into a token refresh and then a logout — the wrong answer to "confirm your
email first".

**Existing accounts were grandfathered.** `V11` backfills `email_verified = TRUE` for every row
that existed at migration time. Nobody currently using the app, including your admin account,
gets locked out on deploy. Only registrations from then on start unconfirmed.

## The user's path through it

1. Registers → logged in immediately, with a warning banner: *"Confirm your email to submit or
   unlock experiences"* and a **Resend link** button.
2. "New draft" and "Unlock" are disabled, each with a line explaining why. Disabled rather than
   hidden — a missing button reads as a bug, a disabled one with a reason doesn't.
3. Clicks the link → `/confirm-email?token=…`, which redeems on mount without asking for
   another click. Works signed out, since people open email on a different device.
4. On success, if they're signed in, the session is refreshed so the banner disappears and the
   buttons unlock immediately, rather than waiting up to 15 minutes for the access token to
   turn over.

Failure cases each say something actionable: expired, already used, unknown token, and a link
that arrived without a `?token=` at all.

## Tokens

Same design as password reset, and for the same reasons:

- 256 bits from a CSPRNG, url-safe encoded. The raw value exists **only** in the emailed link —
  the database stores a SHA-256 hash, so a database leak yields no working links.
- Single-use, 24-hour expiry (vs. one hour for a reset — a confirmation competes with
  everything else in an inbox, whereas a reset is something you're actively waiting on).
- **Resending invalidates the previous link.** Without that, a user who pressed resend five
  times would have five live credentials sitting in their inbox. Marked used rather than
  deleted, so the rows stay as a record of how many attempts an address needed.

Token generation and hashing moved to `common/SecureTokens` — this was the second feature
needing exactly that, and three copies is where a subtly weaker one eventually appears.

## Email sending

`EmailSender` is a two-method interface with two implementations, chosen at startup by
`EmailConfig` based on whether `spring.mail.host` is set:

- **`SmtpEmailSender`** — Spring's `JavaMailSender`. SMTP rather than a vendor HTTP API so the
  provider is a config choice: Postmark, SendGrid, Resend and Mailgun all speak it, and
  switching is four env vars and no code.
- **`LoggingEmailSender`** — writes the message to the log. Same graceful-degradation pattern
  as Razorpay/Google/Sentry: an unconfigured integration must never stop the app booting or
  break unrelated flows. This is the local-dev path, and it's what password reset effectively
  did before.

**Failures are logged, never thrown.** Every caller is an auth flow where the surrounding
operation has already succeeded — a registration that committed must not 500 because a mail
server hiccupped, and forgot-password must return the same generic response either way or it
becomes a user-enumeration oracle. The visible cost of a swallowed failure is a user who
presses "resend"; the alternative is a broken signup.

Messages are built in `email/AuthEmails` — inline HTML plus a plain-text alternative, no
template engine. Two short emails don't justify one; the moment there's a fourth, or someone
non-technical needs to edit copy without a redeploy, that's when to move them out. The HTML is
deliberately crude (inline styles, no images, no web fonts) because email clients aren't
browsers. Display names are HTML-escaped — they're user-controlled and end up in a message
body.

## Rate limits

Two new entries in `RateLimitingFilter`:

- `POST /auth/resend-verification` — **3/min/IP**, the tightest limit in the app. The abuse here
  doesn't land on us, it lands in a stranger's inbox.
- `POST /auth/verify-email` — 10/min/IP. The token is 256 bits, so guessing isn't the threat
  model; this is just a backstop against someone pointing a script at it.

Both respect `TRUST_FORWARDED_FOR` like everything else — make sure that's set in production
or these are shared across all users at once.

## To set up sending

1. Create an account with an SMTP provider (Postmark and Resend both have usable free tiers;
   Postmark's is 100/month, Resend's 3,000).
2. **Verify a sending domain.** Until this is done most providers only let you mail yourself,
   and messages from an unverified domain get filtered regardless of configuration.
3. Set on the API service: `MAIL_HOST`, `MAIL_PORT` (587), `MAIL_USERNAME`, `MAIL_PASSWORD`,
   `MAIL_FROM_ADDRESS`, and `WEB_BASE_URL`. Full table in `06-deployment.md`.
4. Register a throwaway account and check the email arrives and the link works.

Until step 3, everything still functions — the links just come out in the API log, which is
exactly how the app behaved before this existed.

## Files

**Backend, new:** `email/{EmailSender,SmtpEmailSender,LoggingEmailSender,EmailConfig,AuthEmails}`,
`auth/{EmailVerificationService,EmailVerificationGuard,EmailNotVerifiedException}`,
`auth/dto/{VerifyEmailRequest,ResendVerificationRequest}`,
`user/{EmailVerificationToken,EmailVerificationTokenRepository}`, `common/SecureTokens`,
`V11__email_verification.sql`.

**Backend, changed:** `AuthService` (sends on register, reset moved to EmailSender, token
helpers extracted), `AuthController` (+2 routes), `User`, `UserResponse`, `ExperienceService`,
`PurchaseService`, `RateLimitingFilter`, `ApiExceptionHandler`, `application.yml`, `pom.xml`.

**Frontend, new:** `components/ConfirmEmail.tsx` (screen + banner).

**Frontend, changed:** `App.tsx` (route + banner), `AuthContext` (`refreshSession`),
`lib/api.ts`, `App.css`, `ExperienceDetail.tsx`, `SubmissionWorkspace.tsx`, `shared/types.ts`.

**Tests:** new `EmailVerificationServiceTest`, `EmailVerificationGuardTest`,
`ConfirmEmail.test.tsx`; extended `AuthServiceTest`, `AuthFlowIT` (the full
register → intercept email → redeem link loop against real Postgres/Redis),
`PurchaseFlowIT` (the gate, as a real 403), `ExperienceServiceTest`, `PurchaseServiceTest`,
`ExperienceDetail.test.tsx`, `SubmissionWorkspace.test.tsx`.

## Known gaps

- **No "change email address" flow.** An account is stuck with the address it registered
  with — which now matters more, since a typo'd address means an account that can never be
  confirmed. The resend endpoint can't help there. Worth adding before real signup volume.
- **Resetting a password still doesn't revoke existing sessions** (carried over from the
  previous pass — refresh tokens issued before a reset stay valid for up to 30 days).
- **No bounce handling.** A hard bounce is invisible to the app; the user just never receives
  anything and the provider's dashboard is the only place it shows up.
- **Unconfirmed accounts are never cleaned up.** They accumulate indefinitely, holding their
  email address against re-registration.
