# Subscription Platform — API Documentation

Base URL (local): `http://localhost:8080`

All responses use this envelope:

```json
{
  "success": true,
  "message": "human readable message",
  "data": { }
}
```

`success: false` means something went wrong — read `message` and show it to
the user. `data` is `null` on errors.

---

## Flow overview

```
1. POST /api/auth/register           → account created (no password yet)
2. POST /api/payment/initialize      → get Paystack checkout link
3. (user pays on Paystack)
4. GET  /api/payment/verify/:ref     → get generated password (shown ONCE)
5. POST /api/auth/login              → get session token (timer starts now)
6. Use token on /api/protected/**    → access the paid feature
7. POST /api/auth/logout             → end session early (optional)
8. Session/account auto-expires when plan hours run out
```

Rules to build UI around:

- **One subscription at a time.** A user can't buy a second plan while a
  paid plan hasn't been fully used up yet. `/api/payment/initialize` will
  return `409` in that case.
- **One device at a time.** If the account is already logged in somewhere,
  a second `/api/auth/login` attempt returns `409`.
- **The password is shown exactly once**, in the response of
  `/api/payment/verify/:reference`. There is no "forgot password" — if it's
  lost, the account must consume/expire its current plan, then be paid for
  again to get a new password.
- **The timer starts at login, not at payment.** A user can pay and log in
  later; the clock only starts ticking the moment they first log in.

---

## 1. List plans

```
GET /api/payment/plans
```

No auth required.

**Response `200`**
```json
{
  "success": true,
  "message": "Available plans",
  "data": [
    { "plan": "TWO_HOUR",   "hours": 2, "amountCedis": 200 },
    { "plan": "THREE_HOUR", "hours": 3, "amountCedis": 350 },
    { "plan": "FIVE_HOUR",  "hours": 5, "amountCedis": 500 }
  ]
}
```

---

## 2. Register

```
POST /api/auth/register
Content-Type: application/json
```

**Body**
```json
{ "email": "user@example.com" }
```

**Response `200`**
```json
{
  "success": true,
  "message": "Registered. Now pay for a plan to receive your login password.",
  "data": { "username": "user@example.com", "status": "UNPAID" }
}
```

**Errors**
| Status | When |
|---|---|
| 400 | Invalid/missing email |
| 409 | Email already has an active, unused subscription |

Calling this again for an already-registered email with no active
subscription is safe (idempotent) — it just returns the existing account.

---

## 3. Initialize payment

```
POST /api/payment/initialize
Content-Type: application/json
```

**Body**
```json
{ "email": "user@example.com", "plan": "TWO_HOUR" }
```
`plan` is one of: `TWO_HOUR`, `THREE_HOUR`, `FIVE_HOUR`.

**Response `200`**
```json
{
  "success": true,
  "message": "Payment initialized. Redirect user to authorizationUrl.",
  "data": {
    "authorizationUrl": "https://checkout.paystack.com/xxxxxxx",
    "reference": "SUB_9f8a7b6c5d4e...",
    "plan": "TWO_HOUR",
    "amountCedis": 200
  }
}
```

**What the frontend does:** redirect the browser (or open a webview) to
`data.authorizationUrl`. Save `data.reference` — you'll need it in step 4.
Paystack will redirect back to the `paystack.callback-url` configured in
`application.properties` after payment.

**Errors**
| Status | When |
|---|---|
| 400 | Invalid email / unknown plan code |
| 409 | Email not registered yet, OR already has an active subscription |
| 502 | Paystack API error |

---

## 4. Verify payment

```
GET /api/payment/verify/{reference}
```

Call this when the user lands back on your callback page (or poll it).

**Response `200` (payment succeeded)**
```json
{
  "success": true,
  "message": "Payment verified. Account ready.",
  "data": {
    "username": "user@example.com",
    "password": "aB3xQ9kLmZ",
    "plan": "TWO_HOUR",
    "hours": 2,
    "message": "Save this password now - it will not be shown again. Use it to log in."
  }
}
```

⚠️ **Show `data.password` to the user immediately and prominently** — a
modal, a "copy password" button, etc. It is never retrievable again after
this response.

**Errors**
| Status | When |
|---|---|
| 402 | Paystack says the payment was not successful |
| 404 | Unknown reference |
| 409 | This reference was already verified before (prevents double-processing) |
| 502 | Paystack API error |

---

## 5. Login

```
POST /api/auth/login
Content-Type: application/json
```

**Body**
```json
{ "username": "user@example.com", "password": "aB3xQ9kLmZ" }
```
(`"email"` also works in place of `"username"` as the JSON key, if that's more natural for your form field.)

**Response `200`**
```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "token": "9f8a7b6c...e4d3c2b1...",
    "expiresAt": "2026-07-11T18:30:00",
    "secondsRemaining": 7200
  }
}
```

**Save `data.token`.** Send it as `Authorization: Bearer <token>` on every
subsequent protected request. Use `secondsRemaining` to drive a countdown
timer in the UI.

**Errors**
| Status | When |
|---|---|
| 401 | Wrong username/password |
| 403 | Account registered but hasn't paid yet |
| 403 | Subscription's total time window already fully used |
| 409 | Already logged in on another device/session |

---

## 6. Access protected resources

Everything under `/api/protected/**` requires the header:
```
Authorization: Bearer <token>
```

### Session status (poll this to drive a live countdown)
```
GET /api/protected/status
```
**Response `200`**
```json
{
  "success": true,
  "message": "Session active",
  "data": {
    "username": "user@example.com",
    "loginAt": "2026-07-11T16:30:00",
    "expiresAt": "2026-07-11T18:30:00",
    "secondsRemaining": 6543
  }
}
```

### Example protected feature
```
GET /api/protected/dashboard
```
**Response `200`**
```json
{
  "success": true,
  "message": "Welcome user@example.com",
  "data": { "secondsRemaining": 6543 }
}
```
Replace/extend `ProtectedController` with your actual paid features — they
all get the same auth guard automatically as long as they live under
`/api/protected/**`.

**Errors (any protected route)**
| Status | When |
|---|---|
| 401 | Missing/invalid token |
| 401 | Time limit reached — user has been auto-logged-out |

When you get a `401` here, clear the stored token client-side and send the
user back to the login screen.

---

## 7. Logout

```
POST /api/auth/logout
Authorization: Bearer <token>
```

**Response `200`**
```json
{ "success": true, "message": "Logged out successfully", "data": null }
```

**Errors**
| Status | When |
|---|---|
| 401 | Missing Authorization header |
| 404 | Token not found/already invalidated |

---

## Error response shape (all endpoints)

```json
{
  "success": false,
  "message": "Invalid username or password",
  "data": null
}
```

Common HTTP status codes you'll see: `400` (bad input), `401` (auth
failed/expired), `403` (not allowed yet), `404` (not found), `409`
(conflict — already active session or subscription), `502` (Paystack
unreachable).

---

## Manual Payment (Mobile Money / Bank Transfer)

An alternative to Paystack: the user pays directly to a mobile money number
or bank account you've shared with them (outside the app), then submits
proof here. An admin manually reviews the screenshot and approves or
rejects it. Same rules apply as Paystack payments: the email must already
be registered, and can't already have an active/unused subscription.

### Submit payment proof

```
POST /api/payment/manual/submit
Content-Type: multipart/form-data
```

Form fields:
| Field | Type | Description |
|---|---|---|
| `email` | text | The registered email |
| `plan` | text | `TWO_HOUR`, `THREE_HOUR`, or `FIVE_HOUR` |
| `accountName` | text | Name on the account/number that sent the money |
| `accountNumber` | text | The phone number or account number used to pay |
| `networkOrBank` | text | e.g. `"MTN Mobile Money"`, `"Vodafone Cash"`, `"GCB Bank"` |
| `reference` | text | The transaction reference/ID the user was given |
| `screenshot` | file | Image of the payment confirmation (png/jpg/jpeg/webp/heic, max 3MB) |

**Response `200`**
```json
{
  "success": true,
  "message": "Payment proof submitted, awaiting admin review",
  "data": {
    "id": "e3f1a9d2-...",
    "status": "PENDING",
    "plan": "TWO_HOUR",
    "submittedAt": "2026-07-15T09:00:00",
    "message": "Submitted. Save this id - check its status with GET /api/payment/manual/status/{id}."
  }
}
```
Save `data.id` - that's how the user checks status and eventually retrieves their password.

**Errors**
| Status | When |
|---|---|
| 400 | Missing/invalid field, unsupported image type, unknown plan |
| 409 | Email not registered, or already has an active subscription |
| 413 | File too large (over 3MB) |

### Check status (and retrieve password once approved)

```
GET /api/payment/manual/status/{id}
```

While pending:
```json
{
  "success": true,
  "message": "Manual payment status",
  "data": {
    "id": "e3f1a9d2-...",
    "status": "PENDING",
    "plan": "TWO_HOUR",
    "submittedAt": "2026-07-15T09:00:00",
    "reviewedAt": null
  }
}
```

Right after an admin approves it (password shown **once**):
```json
{
  "success": true,
  "message": "Manual payment status",
  "data": {
    "id": "e3f1a9d2-...",
    "status": "APPROVED",
    "plan": "TWO_HOUR",
    "submittedAt": "2026-07-15T09:00:00",
    "reviewedAt": "2026-07-15T09:20:00",
    "username": "user@example.com",
    "password": "xQ7mK2pL9",
    "message": "Save this password now - it will not be shown again. Use it to log in."
  }
}
```

If rejected:
```json
{
  "data": {
    "status": "REJECTED",
    "rejectionReason": "Screenshot doesn't match the amount"
  }
}
```

**Frontend tip:** poll this endpoint every few seconds after submission until
`status` is no longer `"PENDING"`.

---

### Admin: review manual payment submissions

All of these require an admin token, same as everything else under `/api/admin/**`.

```
GET /api/admin/manual-payments            # everything
GET /api/admin/manual-payments/pending    # just what needs review
GET /api/admin/manual-payments/{id}       # one submission's details
Authorization: Bearer <admin-token>
```
Each entry includes a `screenshotUrl` you can load directly (it's already
under `/api/admin/manual-payments/{id}/screenshot`).

### Admin: view the screenshot

```
GET /api/admin/manual-payments/{id}/screenshot
Authorization: Bearer <admin-token>
```
Returns the raw image bytes (not wrapped in the usual JSON envelope) - point
an `<img src="...">` tag at this with the auth header attached, or fetch it
as a blob.

### Admin: approve

```
POST /api/admin/manual-payments/{id}/approve
Authorization: Bearer <admin-token>
```
Attaches the plan and generates the password on the user's account (same as
a Paystack verification would), and records referral commission if the user
was referred. The user retrieves their password via
`GET /api/payment/manual/status/{id}` right after this.

Returns `409` if this submission was already approved or rejected.

### Admin: reject

```
POST /api/admin/manual-payments/{id}/reject
Authorization: Bearer <admin-token>
Content-Type: application/json
```
```json
{ "reason": "Screenshot doesn't match the amount" }
```
`reason` is optional. The account is left untouched - the user can submit a
new manual payment (or pay via Paystack instead).

---

## AI Betting-Slip Scan

A completely separate feature from the time-based access plans above. The
user pays for a **scan plan**, submits proof (manual payment only, same
review flow as above), and once approved gets **one** AI analysis of a
betting-slip image. The AI reads the slip, finds every pick/game printed on
it, and returns its own prediction for as many of those picks as the plan
covers.

| Plan | Code | Price (GHS) | Picks covered per slip |
|---|---|---|---|
| Basic | `SCAN_300` | 300 | 2 |
| Standard | `SCAN_500` | 500 | 5 |
| Premium | `SCAN_1000` | 1000 | Full coverage (every pick found) |

**One purchase = one scan.** Approving a payment doesn't run the AI — it
just unlocks a single call to `/api/scan/analyze`. Once that call runs
(success or failure of parsing, doesn't matter), the purchase is marked
`USED` and can't be reused. A user also can't have two purchases "in
flight" at once — one pending-or-approved-and-unused purchase per email at
a time.

```
Flow:
1. GET  /api/scan/payment/plans           → show the 3 cards
2. POST /api/scan/payment/submit          → user submits screenshot link + plan → PENDING
3. (admin reviews via /api/admin/scan-purchases/**)
4. GET  /api/scan/payment/status/{id}     → user polls until APPROVED
5. POST /api/scan/analyze                 → user uploads the slip image → AI runs → predictions returned
6. GET  /api/scan/result/{id}             → re-fetch that result later if needed
7. GET  /api/scan/history?email=...       → all of a user's past scan results
```

### List scan plans

```
GET /api/scan/payment/plans
```
No auth required.

**Response `200`**
```json
{
  "success": true,
  "message": "Available AI scan plans",
  "data": [
    { "code": "SCAN_300",  "name": "BASIC",    "amountCedis": 300,  "maxPicks": 2 },
    { "code": "SCAN_500",  "name": "STANDARD", "amountCedis": 500,  "maxPicks": 5 },
    { "code": "SCAN_1000", "name": "PREMIUM",  "amountCedis": 1000, "maxPicks": "FULL" }
  ]
}
```

### Submit scan payment proof

```
POST /api/scan/payment/submit
Content-Type: multipart/form-data (or regular form fields)
```

| Field | Type | Description |
|---|---|---|
| `email` | text | The user's email (does **not** need to be registered on the time-plan side) |
| `plan` | text | `SCAN_300`, `SCAN_500`, or `SCAN_1000` (bare `"300"`/`"500"`/`"1000"` also accepted) |
| `accountName` | text | Name on the account/number that sent the money |
| `accountNumber` | text | Phone number or account number used to pay |
| `networkOrBank` | text | e.g. `"MTN Mobile Money"`, `"Vodafone Cash"`, `"GCB Bank"` |
| `reference` | text | Transaction reference/ID the user was given |
| `screenshotUrl` | text | Link to the uploaded payment-proof screenshot (host it yourself — an image host/bucket — same as the time-plan manual payment flow; this endpoint stores the link, not the file) |

**Response `200`**
```json
{
  "success": true,
  "message": "Scan payment proof submitted",
  "data": {
    "id": "3b1a9d2e-...",
    "status": "PENDING",
    "scanPlan": "STANDARD",
    "maxPicks": 5,
    "submittedAt": "2026-08-07T09:00:00",
    "message": "Submitted, awaiting admin review. Check status with GET /api/scan/payment/status/{id}."
  }
}
```
Save `data.id` — it's the `purchaseId` used for every step after this.

**Errors**
| Status | When |
|---|---|
| 400 | Missing/invalid field, unknown plan code, `screenshotUrl` not a valid http(s) URL |
| 409 | This email already has a submission that's still `PENDING` or `APPROVED`-but-unused |

### Check scan payment status

```
GET /api/scan/payment/status/{id}
```

While pending:
```json
{ "data": { "id": "3b1a9d2e-...", "status": "PENDING", "scanPlan": "STANDARD", "maxPicks": 5 } }
```

Once approved:
```json
{
  "data": {
    "id": "3b1a9d2e-...",
    "status": "APPROVED",
    "scanPlan": "STANDARD",
    "maxPicks": 5,
    "message": "Approved! Submit your slip image to POST /api/scan/analyze with this purchase id."
  }
}
```

If rejected: `"status": "REJECTED"` + `"rejectionReason"`.
After it's been used: `"status": "USED"` + `"usedAt"`.

**Frontend tip:** poll every few seconds until `status` leaves `PENDING`.

---

### Run the scan

```
POST /api/scan/analyze
Content-Type: multipart/form-data
```

| Field | Type | Description |
|---|---|---|
| `purchaseId` | text | The `id` from the submit step, must currently be `APPROVED` |
| `email` | text | Must match the email on that purchase |
| `image` | file | The betting-slip image — png/jpeg/webp |

Under the hood this base64-encodes the image and sends it, along with a
plan-aware prompt, to an NVIDIA-hosted vision model. The model is told to
find every pick/game on the slip and only return predictions for as many as
the plan covers (2 / 5 / all), then the response is parsed into structured
picks.

**Response `200`**
```json
{
  "success": true,
  "message": "Scan complete",
  "data": {
    "id": "9f2c-...",
    "purchaseId": "3b1a9d2e-...",
    "email": "user@example.com",
    "scanPlan": "STANDARD",
    "totalPicksDetected": 8,
    "picksAnalyzed": 5,
    "coverageNote": "5 of 8 picks covered on the STANDARD plan. Upgrade to a higher tier for more picks per slip.",
    "predictions": [
      {
        "sectionIndex": 1,
        "matchLabel": "Arsenal vs Chelsea",
        "originalPick": "Over 2.5 goals",
        "prediction": "Over 2.5 goals",
        "confidence": "Medium",
        "analysis": "Both sides have leaky defenses this season and Arsenal's last 5 home games average 3.1 goals."
      }
    ],
    "createdAt": "2026-08-07T09:22:11"
  }
}
```

If the model doesn't return clean JSON (rare, but possible), `predictions`
will be empty and a `rawModelOutput` + `note` field are included instead so
nothing is lost — show that as plain text as a fallback.

**Errors**
| Status | When |
|---|---|
| 400 | Missing image, unsupported image type |
| 403 | `email` doesn't match the email on that purchase |
| 404 | `purchaseId` doesn't exist |
| 409 | Purchase isn't `APPROVED` (still pending, rejected, or already `USED`) |
| 502 | The AI provider errored or timed out |
| 503 | Server isn't configured with an NVIDIA API key |

### Fetch a past result

```
GET /api/scan/result/{id}
GET /api/scan/history?email=user@example.com
```
Same response shape as above (history returns an array).

---

### Admin: review scan purchase submissions

Same auth as everything else under `/api/admin/**` — admin or super admin token required.

```
GET  /api/admin/scan-purchases            # everything
GET  /api/admin/scan-purchases/pending    # just what needs review
GET  /api/admin/scan-purchases/{id}       # one submission's details
POST /api/admin/scan-purchases/{id}/approve
POST /api/admin/scan-purchases/{id}/reject
Authorization: Bearer <admin-token>
```

`approve` just flips the purchase to `APPROVED` — it does **not** call the
AI. The user triggers the actual scan themselves via `POST
/api/scan/analyze` once they see `APPROVED`.

`reject` body (optional):
```json
{ "reason": "Screenshot doesn't match the amount" }
```

Both return `409` if the submission was already approved/rejected.

---

## Admin & Super Admin

Separate auth system from regular users — admins log in at `/api/admin/auth/login`
and get their own Bearer token, used on `/api/admin/**` and `/api/superadmin/**`.

- `/api/admin/**` → requires role `ADMIN` or `SUPER_ADMIN`
- `/api/superadmin/**` → requires role `SUPER_ADMIN` only

There's a bootstrap Super Admin created automatically on first startup
(`superadmin.bootstrap.username` / `superadmin.bootstrap.password` in
`application.properties`). Log in as that first, then create real admins.

### Admin login (ADMIN or SUPER_ADMIN)

```
POST /api/admin/auth/login
Content-Type: application/json
```
```json
{ "username": "agent1@example.com", "password": "SomeStrongPassword1" }
```
(`"email"` also works in place of `"username"` as the JSON key.)

**Response `200`**
```json
{
  "success": true,
  "message": "Admin login successful",
  "data": {
    "token": "8f7e6d5c...",
    "role": "ADMIN",
    "expiresAt": "2026-07-14T02:00:00"
  }
}
```
This endpoint accepts login for **either** role - a super admin can also log
in here and will get `"role": "SUPER_ADMIN"` back. Use this `token` as
`Authorization: Bearer <token>` on `/api/admin/**` calls.

### Admin logout
```
POST /api/admin/auth/logout
Authorization: Bearer <admin-token>
```

### Super admin login (SUPER_ADMIN only, dedicated endpoint)

```
POST /api/superadmin/auth/login
Content-Type: application/json
```
```json
{ "username": "superadmin@platform.com", "password": "ChangeMe123!" }
```
(`"email"` also works in place of `"username"` as the JSON key.)

**Response `200`**
```json
{
  "success": true,
  "message": "Super admin login successful",
  "data": {
    "token": "8f7e6d5c...",
    "role": "SUPER_ADMIN",
    "expiresAt": "2026-07-14T02:00:00"
  }
}
```
This is a **separate, dedicated door** for super admins. A regular `ADMIN`
account gets `403 Forbidden` here even with the correct password — they
must use `/api/admin/auth/login` instead. Use the returned `token` as
`Authorization: Bearer <token>` on `/api/superadmin/**` calls (also works
on `/api/admin/**` calls, since a super admin can do everything an admin
can).

### Super admin logout
```
POST /api/superadmin/auth/logout
Authorization: Bearer <super-admin-token>
```

---

### Super Admin: create an admin (with a referral link)

```
POST /api/superadmin/admins
Authorization: Bearer <super-admin-token>
Content-Type: application/json
```
```json
{
  "email": "agent1@example.com",
  "password": "SomeStrongPassword1",
  "role": "ADMIN",
  "adminSharePercent": 30
}
```
`role` defaults to `"ADMIN"` if omitted. `adminSharePercent` defaults to the
platform default (30) if omitted — platform automatically gets `100 - adminSharePercent`.

**Response `200`**
```json
{
  "success": true,
  "message": "Admin created",
  "data": {
    "username": "agent1@example.com",
    "role": "ADMIN",
    "referralCode": "REF-8K3PQZ",
    "adminSharePercent": 30,
    "platformSharePercent": 70,
    "active": true,
    "createdAt": "2026-07-13T10:00:00"
  }
}
```

### Super Admin: list all admins (with commission summary)
```
GET /api/superadmin/admins
Authorization: Bearer <super-admin-token>
```
Returns each admin plus `totalCommissionEarned`, `totalCommissionPending`, `totalCommissionPaidOut`.

### Super Admin: activate / deactivate an admin
```
PUT /api/superadmin/admins/{username}/deactivate
PUT /api/superadmin/admins/{username}/activate
Authorization: Bearer <super-admin-token>
```
A deactivated admin's referral links stop working for new registrations,
their existing admin session is invalidated on next use, and they can no
longer log into the admin panel.

### Super Admin: platform-wide stats
```
GET /api/superadmin/stats
Authorization: Bearer <super-admin-token>
```
**Response `200`**
```json
{
  "success": true,
  "message": "Platform-wide stats",
  "data": {
    "depositsToday": 1450,
    "paymentsToday": 6,
    "depositsThisWeek": 8300,
    "depositsAllTime": 45200,
    "successfulPaymentsAllTime": 187,
    "totalCommissionsOwed": 320.5,
    "totalCommissionsPaid": 1200.0,
    "totalAdmins": 4
  }
}
```
`depositsThisWeek` is a rolling 7-day window, not calendar-week.

### Super Admin: all commission records
```
GET /api/superadmin/commissions
Authorization: Bearer <super-admin-token>
```

### Super Admin: pay out an admin's pending commission
```
POST /api/superadmin/payouts/{adminUsername}
Authorization: Bearer <super-admin-token>
```
Marks **all** of that admin's currently pending commission records as paid
in one batch and returns a receipt:
```json
{
  "success": true,
  "message": "Payout recorded",
  "data": {
    "id": "a1b2c3d4-...",
    "adminUsername": "agent1@example.com",
    "totalAmountCedis": 210.0,
    "commissionCount": 7,
    "paidAt": "2026-07-13T11:00:00",
    "paidByUsername": "superadmin@platform.com"
  }
}
```
Returns `400` if that admin has nothing pending.

### Super Admin: all payout receipts
```
GET /api/superadmin/payouts
Authorization: Bearer <super-admin-token>
```

---

### Admin: my profile + referral link
```
GET /api/admin/me
Authorization: Bearer <admin-token>
```
```json
{
  "success": true,
  "message": "Admin profile",
  "data": {
    "username": "agent1@example.com",
    "role": "ADMIN",
    "referralCode": "REF-8K3PQZ",
    "referralLink": "http://localhost:3000/register?ref=REF-8K3PQZ",
    "adminSharePercent": 30,
    "platformSharePercent": 70,
    "active": true,
    "createdAt": "2026-07-13T10:00:00"
  }
}
```
Give `referralLink` straight to your agent/marketer — when someone
registers through it, they're tagged, and any payment they make earns
this admin their cut automatically.

### Admin: my referral stats (daily/weekly/commission)
```
GET /api/admin/stats
Authorization: Bearer <admin-token>
```
```json
{
  "success": true,
  "message": "Your referral stats",
  "data": {
    "referralCode": "REF-8K3PQZ",
    "split": "30/70",
    "referredUserCount": 12,
    "commissionEarnedToday": 60.0,
    "commissionEarnedThisWeek": 210.0,
    "totalCommissionEarned": 540.0,
    "totalCommissionPending": 210.0,
    "totalCommissionPaidOut": 330.0
  }
}
```

### Admin: users I've referred
```
GET /api/admin/users/referred
Authorization: Bearer <admin-token>
```

### Admin: all platform users (general oversight)
```
GET /api/admin/users
Authorization: Bearer <admin-token>
```
Each entry: `username`, `paid`, `plan`, `hasActiveSession`, `subscriptionConsumed`, `referredByAdminCode`, `createdAt`.

### Admin: my commission history
```
GET /api/admin/commissions
Authorization: Bearer <admin-token>
```
Itemized list — one entry per referred payment, showing `amountCedis`,
`adminShareCedis`, `platformShareCedis`, `paidOut`.

### Admin: my payout history
```
GET /api/admin/payouts
Authorization: Bearer <admin-token>
```

---

### Registering with a referral code (regular user side)

```
POST /api/auth/register
Content-Type: application/json
```
```json
{ "email": "user@example.com", "referralCode": "REF-8K3PQZ" }
```
`referralCode` is optional. If provided, it must belong to an active admin
or the request fails with `400`. From then on, whenever this email pays,
that admin's commission is recorded automatically — no extra step needed
at payment time.

---

## Frontend integration checklist

**User-facing app:**
- [ ] Registration screen → `POST /api/auth/register` (support `?ref=` query param → `referralCode` field)
- [ ] Pricing screen → `GET /api/payment/plans` to render the 3 cards
- [ ] "Subscribe" button → `POST /api/payment/initialize` → redirect to
      `authorizationUrl`
- [ ] Payment callback/return page → `GET /api/payment/verify/:reference` →
      **show the password once, force the user to acknowledge they saved it**
- [ ] Login screen → `POST /api/auth/login` → store `token` (memory or
      secure storage, not `localStorage` for anything sensitive if avoidable)
- [ ] After login, show a live countdown using `secondsRemaining`, refresh
      via `GET /api/protected/status` periodically
- [ ] On any `401` from a protected route → clear token, redirect to login,
      show "your time is up, please subscribe again" if the message says so
- [ ] Handle `409` on login gracefully: "This account is already logged in
      elsewhere"
- [ ] Handle `409` on initialize/register gracefully: "You already have an
      active subscription"
- [ ] Manual payment option → show your mobile money/bank details, then a
      form (`accountName`, `accountNumber`, `networkOrBank`, `reference`,
      `screenshot` file) → `POST /api/payment/manual/submit`
- [ ] After manual submit, poll `GET /api/payment/manual/status/{id}` every
      few seconds until status leaves `PENDING`, then show the password once approved
- [ ] AI scan upsell screen → `GET /api/scan/payment/plans` to render the 3 scan cards
- [ ] Scan payment form (same shape as manual payment, but plan is
      `SCAN_300`/`SCAN_500`/`SCAN_1000`) → `POST /api/scan/payment/submit`
- [ ] Poll `GET /api/scan/payment/status/{id}` until `APPROVED`
- [ ] Slip upload screen → `POST /api/scan/analyze` (`purchaseId`, `email`, `image` file)
- [ ] Results screen → render `predictions[]`; fall back to `rawModelOutput`
      if `predictions` is empty
- [ ] Scan history screen → `GET /api/scan/history?email=...`

**Admin panel (separate app/section, admin auth):**
- [ ] Admin login screen → `POST /api/admin/auth/login` → store admin token separately from user token
- [ ] Dashboard → `GET /api/admin/me` (referral link) + `GET /api/admin/stats` (earnings)
- [ ] "Copy referral link" button using `data.referralLink`
- [ ] Referred users table → `GET /api/admin/users/referred`
- [ ] Commission history table → `GET /api/admin/commissions`
- [ ] Payout history table → `GET /api/admin/payouts`
- [ ] Manual payments review queue → `GET /api/admin/manual-payments/pending`,
      show the screenshot inline, Approve/Reject buttons
- [ ] AI scan purchases review queue → `GET /api/admin/scan-purchases/pending`,
      open the `screenshotUrl` link, Approve/Reject buttons

**Super admin panel (SUPER_ADMIN only):**
- [ ] Admin management screen → `GET /api/superadmin/admins`, create via
      `POST /api/superadmin/admins`, activate/deactivate via the `PUT` routes
- [ ] Platform stats dashboard → `GET /api/superadmin/stats`
- [ ] All commissions table → `GET /api/superadmin/commissions`
- [ ] "Pay out" button per admin → `POST /api/superadmin/payouts/{adminUsername}`,
      show the returned receipt, refresh the admin's pending total to 0

