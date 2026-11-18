# SpindleTrack

Laundry-shop registration and operations foundation using Spring Boot, React, Firebase Authentication, and MySQL.

## Stack and prerequisites

The backend uses Java 21, Spring Boot 3.5, Maven, Spring Security, JDBC/JPA, Flyway, MySQL 8, and Firebase Admin Java. The frontend uses React, Vite, Firebase Authentication, Axios, Tailwind CSS, and lucide-react. Java 21, Node.js, npm, and Git are expected on the development PC. MySQL is configured for port 3307 in the local example.

## Local configuration

Keep both local `.env` files and the Firebase service-account JSON out of Git. The repository ignores local `.env` files. Do not replace an existing `.env` with an example file. For a new checkout, copy the checked-in examples and fill in values locally.

The backend `.env` uses `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT`, `FIREBASE_SERVICE_ACCOUNT_PATH`, and `ADMIN_EMAIL`. Set `FIREBASE_SERVICE_ACCOUNT_PATH` to the local service-account file path; do not put the JSON contents in `.env`. The verified account matching `ADMIN_EMAIL` is created or promoted to `PLATFORM_ADMIN` on its first successful session.

The frontend `.env` uses `VITE_API_BASE_URL`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, and `VITE_FIREBASE_APP_ID`. Firebase Google and Email/Password providers must be enabled. Email/Password accounts must verify their email before the backend will create a session. Firebase Analytics is not used. The backend allows local browser origins at `http://localhost:5173` and `http://127.0.0.1:5173`.

## Run and test

From PowerShell, start the backend:

```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Flyway applies new migrations at startup. The backend listens at `http://localhost:8080`; the health endpoint is `http://localhost:8080/api/health`.

In another PowerShell terminal, build or start the frontend:

```powershell
Set-Location frontend
npm.cmd run build
npm.cmd run dev
```

The frontend is served at `http://localhost:5173` (Vite is bound to the local loopback interface). `npm.cmd` is used because PowerShell may block the `npm.ps1` shim under the local execution policy.

## Step 2 scope and manual checks

The backend verifies Firebase ID tokens, rejects unverified email claims, creates a local session at `POST /api/auth/session`, bootstraps the configured platform administrator, and applies role checks. A customer account may register a shop only if it is not already a shop user and has no attached laundry orders. Shop registration collects the shop name, address, and contact number only; new shops start as `PENDING`. The database limits registration attempts to three per IP address per UTC day. Document upload is deferred to the next step.

Manual checks after starting both applications:

1. Sign in with Google and confirm a successful session and the expected local role.
2. Sign in with the verified account configured by `ADMIN_EMAIL` and confirm the returned role is `PLATFORM_ADMIN`.
3. Create an Email/Password account, verify its email, then sign in and confirm an unverified account is rejected.
4. As an eligible customer with no attached orders, submit a shop registration and confirm it becomes `PENDING` and the status page shows the submitted details.
5. Confirm an owner, staff member, platform administrator, or customer with attached orders is blocked from registering another shop. Confirm the daily per-IP rate limit rejects further attempts after three.

The legacy V1 and V2 migrations remain immutable. V3 adds tenancy and the demo shop; V4 adds durable rate-limit counters. Existing demo rows are sample data without Firebase UIDs and cannot be claimed by ordinary Firebase sign-ins.
