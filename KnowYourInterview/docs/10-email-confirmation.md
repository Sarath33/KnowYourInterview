# Email confirmation (6-digit code)

Registration emails a **6-digit code**, and an unconfirmed account can't submit an experience or
unlock one until it's entered. Password-reset emails moved onto the same sender at the same
time, so nothing auth-related is a log line any more.

**Why a code rather than a link.** The code arrives on whatever device holds the inbox — usually
a phone — while the person is usually signing up on something else. A link is worst exactly
there: it opens the app in a second browser, on a second device, with no session. A code
crosses that gap by being read and retyped, which is also why it goes in the email's *subject
line* as well as the body: it's legible from a notification without opening anything.

The cost is that six digits is a million possibilities, which is nothing to a script. Three
controls together make that safe, and removing any one breaks it — see "Why a short code is
safe" below.

## Verification status

- **Frontend typecheck: passing** (`tsc -b`, tests included).
- **Frontend tests: not run here** (rollup's platform binary can't be installed in this
  environment). `cd web && npm test`.
- **Backend: not compiled here** (no JDK 21/Maven/Docker). `cd api && ./mvnw verify`.

New/changed constructors are the likeliest source of a compile error if something's off:
`AuthService` (+2 args), `ExperienceService` (+1), `PurchaseService` (+1). Their tests are
updated to match.

The gate touches the existing functional suite broadly — `registerUser()` now confirms the
address it creates, which is what keeps the other ~180 cases passing. If a large number of
functional tests fail with a `403` mentioning "Confirm your email", that fixture is the place
to look, not the individual tests.

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

1. Registers → logged in immediately, and taken **straight to the code screen**. The code is
   already in their inbox and entering it is the only thing between them and a usable account,
   so anywhere else would be the wrong place to land. Logging in isn't diverted — only
   registration.
2. Types or pastes the code into six boxes. Pasting fills all of them from wherever it lands;
   typing spills forward; backspace in an empty box steps back. `inputMode="numeric"` gets the
   numeric keypad on mobile, and `autocomplete="one-time-code"` lets the OS offer it directly.
3. Not ready? **"I'll do this later"** returns them to browsing, with a banner explaining why
   "New draft" and "Unlock" are disabled and an **Enter code** button back to the screen.
   Disabled rather than hidden — a missing button reads as a bug, a disabled one with a reason
   doesn't.
4. On success, if they're signed in, the session is refreshed so the banner disappears and the
   buttons unlock immediately, rather than waiting up to 15 minutes for the access token to
   turn over.

The screen works **signed out** too: someone who registered on a laptop can confirm from the
phone the code arrived on. In that case it asks for the address as well, because the code alone
doesn't identify an account.

A rejected code clears the boxes. Every guess costs one of five, and leaving the wrong digits
sitting there invites spending another on the same input.

## Codes

- **Six digits, ten minutes, five guesses.** Generated with `SecureRandom.nextInt(1_000_000)`
  and zero-padded — uniform across the range, no modulo bias, and `048192` stays six characters
  wide.
- **Stored as a SHA-256 hash**, for consistency with the reset token — but see the honest note
  in `SecureTokens`: hashing six digits protects essentially nothing, since a million candidates
  are exhausted instantly. It costs nothing and keeps one storage format, and it should not be
  mistaken for the defence. The defence is the expiry and the guess cap.
- **Looked up by user, not by code.** A 256-bit token is unique enough to be an identifier; six
  digits are not. Searching by code alone would mean a guess that collided with *some* account's
  live code would confirm that account — a few thousand tries against no particular target would
  eventually land. Scoping to one user first is what makes the per-code cap mean anything, and
  it's why `POST /auth/verify-email` takes `{email, code}`.
- **Resending invalidates the previous code.** More important here than it was for a link: each
  live code carries its own guess budget, so five outstanding codes would be five budgets.

### Why a short code is safe

Three controls, and the feature is only safe with all three:

| Control | Where | What it stops |
|---|---|---|
| 10-minute expiry | `app.email-verification.code-ttl-minutes` | A code sitting guessable indefinitely |
| 5 guesses **per code row** | `EmailVerificationToken.MAX_ATTEMPTS` | Brute force. Counted against the row, **not** per-IP — an attacker rotating IPs gets no extra guesses. FT-CONF-06 proves this: the harness sends each of its five guesses from a different address |
| 10 verify + 3 resend per minute per IP | `RateLimitingFilter` | Cycling rapidly through fresh codes to farm fresh budgets |

Once the budget is spent the code is **burned**, and the *correct* code stops working too. That
last part matters: if it still worked, the cap would only slow an attacker rather than stop one
who guesses right on their last attempt.

**Every rejection returns the same message** — unknown address, no code outstanding, expired,
out of attempts, or simply wrong. Distinguishing them would make the endpoint an oracle for
which addresses are registered and which have a code in flight, and the user's next step is
identical in every case anyway. The distinctions are logged server-side, where they help support
without helping an attacker.

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
  doesn't land on us, it lands in a stranger's inbox — and each resend hands out a fresh guess
  budget.
- `POST /auth/verify-email` — 10/min/IP. The third of the three controls above; it's the only
  one a distributed attacker can't sidestep by resending, but it's also the weakest against one,
  which is why the per-code cap carries the real weight.

Both respect `TRUST_FORWARDED_FOR` like everything else — make sure that's set in production
or these are shared across all users at once.

## To set up sending

1. Create an account with an SMTP provider (Postmark and Resend both have usable free tiers;
   Postmark's is 100/month, Resend's 3,000).
2. **Verify a sending domain.** Until this is done most providers only let you mail yourself,
   and messages from an unverified domain get filtered regardless of configuration.
3. Set on the API service: `MAIL_HOST`, `MAIL_PORT` (587), `MAIL_USERNAME`, `MAIL_PASSWORD`,
   `MAIL_FROM_ADDRESS`, and `WEB_BASE_URL`. Full table in `06-deployment.md`.
4. Register a throwaway account and check the email arrives and the code works.

Note `WEB_BASE_URL` is still needed even though the confirmation email no longer contains a
link — the password-reset email does.

Until step 3, everything still functions — the codes and reset links just come out in the API
log, which is exactly how the app behaved before this existed.

## Files

**Backend, new:** `email/{EmailSender,SmtpEmailSender,LoggingEmailSender,EmailConfig,AuthEmails}`,
`auth/{EmailVerificationService,EmailVerificationGuard,EmailNotVerifiedException}`,
`auth/dto/{VerifyEmailRequest,ResendVerificationRequest}`,
`user/{EmailVerificationToken,EmailVerificationTokenRepository}`, `common/SecureTokens`,
`V11__email_verification.sql`, `V12__email_verification_attempts.sql`.

**On the two migrations.** V11 creates the column and the token table; V12 adds the `attempts`
counter the code flow needs. They're separate because V11 may already have been applied
somewhere, and editing an applied migration changes its checksum and makes Flyway refuse to
start. If V11 has *not* been applied in your environment they'll simply run back to back.

**Backend, changed:** `AuthService` (sends on register, reset moved to EmailSender, token
helpers extracted), `AuthController` (+2 routes), `User`, `UserResponse`, `ExperienceService`,
`PurchaseService`, `RateLimitingFilter`, `ApiExceptionHandler`, `application.yml`, `pom.xml`.

**Frontend, new:** `components/ConfirmEmail.tsx` (screen + banner).

**Frontend, changed:** `App.tsx` (route + banner), `AuthContext` (`refreshSession`),
`lib/api.ts`, `App.css`, `ExperienceDetail.tsx`, `SubmissionWorkspace.tsx`, `shared/types.ts`.

**Tests:** new `EmailConfirmationFunctionalIT` (**FT-CONF**, 15 cases — see `09-test-plan.md`
§7.14: the full register → intercept the real message → enter the code loop, the guess-limit
cases, and every gate case, against real Postgres and Redis), `EmailVerificationServiceTest`,
`EmailVerificationGuardTest`, `EmailConfigTest`, `AuthEmailsTest`, `ConfirmEmail.test.tsx`;
extended `AuthServiceTest`, `PurchaseFlowIT`, `ExperienceServiceTest`, `PurchaseServiceTest`,
`ExperienceDetail.test.tsx`, `SubmissionWorkspace.test.tsx`.

**Test-harness changes worth knowing about:**

- `FunctionalTestBase.registerUser()` now confirms the address it registers, so the ~180
  existing functional cases keep testing what they're about rather than all failing on the new
  gate. `registerUnconfirmedUser()` is the opt-out, used by the FT-CONF gate cases.
- `PasswordResetFunctionalIT` captured the reset link from `AuthService`'s logger; reset mail
  now goes through `EmailSender`, so the capture point moved to `LoggingEmailSender`. Same
  channel, same assertions — with no SMTP host configured, that logger *is* the delivery path,
  so the real `EmailConfig` selection stays under test rather than being replaced by a fake.
- `spring.mail.host` is pinned blank in every IT's property source. Without that, a developer
  with `MAIL_HOST` exported would have the suite try to mail its fixture addresses.
- `email_verification_tokens` added to the functional suite's `TRUNCATE_ALL`.

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
