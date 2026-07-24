# Subscription Platform Backend (Spring Boot + Maven, in-memory)

Pay-per-time-slot access platform:

| Plan | Price (GHS) | Duration |
|------|-------------|----------|
| TWO_HOUR | 200 | 2 hours |
| THREE_HOUR | 350 | 3 hours |
| FIVE_HOUR | 500 | 5 hours |

## How it works

1. **Register** — `POST /api/auth/register` with just an email. No password
   yet; the account sits "unpaid".
2. **Pay** — `POST /api/payment/initialize` with the same email + a plan.
   Only allowed if the email is registered AND has no active/unused
   subscription already (**one subscription at a time — no topping up**).
3. **Verify** — `GET /api/payment/verify/{reference}` confirms with Paystack.
   Once confirmed, the backend **generates a random password** and returns
   it once in that response.
4. User logs in with that username (email) / password → gets a session
   **token**.
5. The timer (2/3/5 hrs) **starts at first login**, not at payment.
6. While a session is active, **no second login is allowed** for that
   account (single active session per account) — so the password can't be
   shared with someone else while in use.
7. When the time limit is reached, the session is auto-invalidated:
   - immediately, the next time the user hits any `/api/protected/**` route
   - and also proactively, by a background job that runs every 30s
     (`session.cleanup.interval-ms`) and force logs-out anyone whose time is up.
   - once consumed, the account goes back to "no active subscription" — the
     same email can now register/pay for a brand new plan.
8. Everything is stored **in memory** (`ConcurrentHashMap`s) — restarting the
   app wipes all data. Swap the repository classes for real DB repositories
   later without touching controllers/services.

## Project structure

```
src/main/java/com/example/subscription/
├── SubscriptionApplication.java
├── config/
│   ├── WebConfig.java                    CORS
│   └── SuperAdminBootstrap.java          creates the first super admin on startup
├── controller/
│   ├── PaymentController.java            /api/payment/**
│   ├── AuthController.java               /api/auth/** (user register/login/logout)
│   ├── ProtectedController.java          /api/protected/** (sample guarded routes)
│   ├── AdminAuthController.java          /api/admin/auth/** (admin OR super-admin login/logout)
│   ├── SuperAdminAuthController.java     /api/superadmin/auth/** (SUPER_ADMIN-only dedicated login)
│   ├── AdminController.java              /api/admin/** (referral link, stats, users, commissions)
│   ├── SuperAdminController.java         /api/superadmin/** (manage admins, platform stats, payouts)
│   ├── ManualPaymentController.java      /api/payment/manual/** (user submits mobile money/bank proof)
│   └── AdminManualPaymentController.java /api/admin/manual-payments/** (admin reviews/approves/rejects)
├── dto/                                  request/response payloads
├── exception/                            ApiException + global handler
├── filter/
│   ├── SessionAuthFilter.java            validates user Bearer token on /api/protected/**
│   └── AdminAuthFilter.java              validates admin Bearer token on /api/admin/**, /api/superadmin/**
├── model/
│   ├── Plan, UserAccount, Session, PaymentTransaction   (user-side)
│   └── Admin, AdminRole, AdminSession, CommissionRecord, PayoutRecord   (admin-side)
├── repository/                           in-memory ConcurrentHashMap "repositories"
├── scheduler/SessionExpiryScheduler.java auto logout background job
├── service/
│   ├── PaystackService.java              calls Paystack REST API
│   ├── AccountService.java               register/pay flow, referral attribution
│   ├── SessionService.java               user login/logout/validate, single-session rule
│   ├── AdminService.java                 create admins, referral codes
│   ├── AdminAuthService.java             admin login/logout/validate
│   ├── CommissionService.java            referral commission split + payouts
│   ├── StatsService.java                 platform-wide daily/weekly revenue (Paystack + manual)
│   └── ManualPaymentService.java         mobile money/bank transfer proof submission + review
└── util/CodeGenerator.java               password/reference/token/referral-code generation
```

## Manual Payment (Mobile Money / Bank Transfer)

An alternative to Paystack for users who pay via MTN Mobile Money,
Vodafone Cash, AirtelTigo Money, or a direct bank transfer:

1. You share your payment details (number/account) with the user outside the app.
2. They pay, then submit proof via `POST /api/payment/manual/submit` -
   name, account number, network/bank, transaction reference, and a
   screenshot (multipart upload, held **in memory**, same as everything
   else - not written to disk).
3. It shows up in an admin's review queue
   (`GET /api/admin/manual-payments/pending`), screenshot included.
4. Admin approves (`POST /api/admin/manual-payments/{id}/approve`) or
   rejects it. Approving generates the password and attaches the plan
   exactly like a Paystack verification would, and records referral
   commission if applicable.
5. The user retrieves their password by polling
   `GET /api/payment/manual/status/{id}` - shown once, same rule as
   everywhere else.

Same guardrails apply as the Paystack flow: the email must be registered
first, and can't already have an active/unused subscription.

## Admin & Super Admin

- **Super Admin** creates Admins (`POST /api/superadmin/admins`), each with a
  unique **referral code/link** and a commission split (default **30% admin
  / 70% platform**, configurable per admin).
- Users who register via `?ref=REF-XXXXXX` are attributed to that admin.
  When they pay, a `CommissionRecord` is created splitting that payment
  70/30 automatically.
- **Admins** can see: their referral link, everyone they've referred, their
  commission earned today/this week/all-time, pending vs paid-out
  commission, and a general list of all platform users.
- **Super Admins** can see platform-wide daily/weekly deposit totals,
  manage (activate/deactivate) admins, view every admin's commission
  standing, and **pay out** an admin's pending commission in one batch
  (creates a `PayoutRecord` receipt).
- A bootstrap Super Admin is created automatically on first startup from
  `superadmin.bootstrap.username` / `superadmin.bootstrap.password` in
  `application.properties` — **change these before deploying anywhere
  real.**

See `API_DOCS.md` for every admin/super-admin endpoint with example
requests and responses.

## Configure

Edit `src/main/resources/application.properties`:

```properties
paystack.secret-key=sk_test_xxxxxxxxxxxx
paystack.callback-url=http://localhost:3000/payment/callback

# change these before deploying anywhere real
superadmin.bootstrap.username=superadmin@platform.com
superadmin.bootstrap.password=ChangeMe123!

app.frontend-base-url=http://localhost:3000
```

Get your Paystack keys from https://dashboard.paystack.com/#/settings/developer

## Run

```bash
mvn spring-boot:run
```

or build a jar:

```bash
mvn clean package
java -jar target/subscription-platform.jar
```

Server starts on `http://localhost:8080`.

## API Walkthrough

### 1. List plans
```bash
curl http://localhost:8080/api/payment/plans
```

### 2. Register (email only, no password yet)
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com"}'
```

### 3. Initialize payment
```bash
curl -X POST http://localhost:8080/api/payment/initialize \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","plan":"TWO_HOUR"}'
```
Response gives `authorizationUrl` — redirect the user's browser there to pay
on Paystack, and `reference` — save it. This fails with `409 Conflict` if the
email isn't registered yet, or if it already has an active/unused
subscription (one at a time — no topping up).

### 4. Verify payment (after user completes payment / is redirected back)
```bash
curl http://localhost:8080/api/payment/verify/SUB_xxxxxxxxxxxxxxxx
```
Response (only shown once):
```json
{
  "success": true,
  "message": "Payment verified. Account ready.",
  "data": {
    "username": "user@example.com",
    "password": "aB3xQ9kLmZ",
    "plan": "TWO_HOUR",
    "hours": 2
  }
}
```

### 5. Login
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user@example.com","password":"aB3xQ9kLmZ"}'
```
Response gives a `token`. The 2-hour countdown starts now.

If someone else tries to log in with the same username/password while this
session is still active, they get **409 Conflict**: "This account is already
logged in on another device."

### 6. Access protected resources
```bash
curl http://localhost:8080/api/protected/dashboard \
  -H "Authorization: Bearer <token>"
```
Once the time limit passes, this returns **401 Unauthorized**: "Your time
limit has been reached. You have been logged out." Any further login attempt
fails with "subscription time has expired" — and the account is now free to
register/pay for a brand new plan (one subscription at a time).

### 7. Logout
```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <token>"
```

## Notes / next steps

- Storage is 100% in-memory — plug in JPA/Postgres/Mongo later by
  reimplementing the three repository classes with the same method
  signatures.
- Add a Paystack **webhook** endpoint (`POST /api/payment/webhook`) if you'd
  rather rely on server-to-server confirmation instead of (or in addition
  to) the `verify` endpoint — recommended for production, and you'd want to
  verify the `x-paystack-signature` header against your secret key.
- Consider hashing the generated password (e.g. BCrypt) before storing it,
  even in memory, once you move past prototyping.
- The single-active-session rule currently keys off one `activeSessionToken`
  per account; if you need "kick the old device out" behavior instead of
  "reject the new login", flip the logic in `SessionService.login()`.
