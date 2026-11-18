# SpindleTrack

Laundry-shop registration and operations foundation using Spring Boot, React, Firebase Authentication, and MySQL.

## Stack and prerequisites

The backend uses Java 21, Spring Boot 3.5, Maven, Spring Security, JDBC/JPA, Flyway, MySQL 8, and Firebase Admin Java. The frontend uses React, Vite, Firebase Authentication, Axios, Tailwind CSS, and lucide-react. Java 21, Node.js, npm, and Git are expected on the development PC. MySQL is configured for port 3307 in the local example.

## Local configuration

Keep both local `.env` files and the Firebase service-account JSON out of Git. The repository ignores local `.env` files. Do not replace an existing `.env` with an example file. For a new checkout, copy the checked-in examples and fill in values locally.

The backend `.env` uses `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT`, `FIREBASE_SERVICE_ACCOUNT_PATH`, `ADMIN_EMAIL`, and `FILE_STORAGE_PATH`. Set `FIREBASE_SERVICE_ACCOUNT_PATH` to the local service-account file path; do not put the JSON contents in `.env`. Set `FILE_STORAGE_PATH` to an absolute private folder outside the repository, for example `C:/Users/yourname/AppData/Local/SpindleTrack/private-documents`; do not use a web-served folder. The verified account matching `ADMIN_EMAIL` is created or promoted to `PLATFORM_ADMIN` on its first successful session.

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

## Step 2 foundation

The backend verifies Firebase ID tokens, rejects unverified email claims, creates a local session at `POST /api/auth/session`, bootstraps the configured platform administrator, and applies role checks. A customer account may register a shop only if it is not already a shop user and has no attached laundry orders. New shops start as `PENDING`, and the database limits registration attempts to three per IP address per UTC day.

## Step 3: verification documents and shop-status enforcement

Registration requires 1–5 JPG, PNG, or PDF verification documents, each no larger than 5 MiB. The backend checks file signatures (not client MIME or filename), stores files under random UUID filenames in the configured external private folder, and never exposes storage paths or public file URLs. Authenticated downloads are allowed only for `PLATFORM_ADMIN` and the owner who uploaded that document.

Only the owner of a `REJECTED` shop may edit details/documents and resubmit; resubmission returns the shop to `PENDING`. At least one document must remain and no more than five may be attached. Owners and staff of shops that are `PENDING`, `REJECTED`, or `DEACTIVATED` are blocked centrally from shop operations with a `403` response and code `SHOP_NOT_ACTIVE`. Registration status and authorized document workflows remain available. Orders, services, staff invitations, and reports are not implemented in this scaffold yet; the central guard will reject inactive-shop requests before route handling and allows requests for `ACTIVE` shops to continue normally.

V5 adds database constraints for the 5 MiB size limit and allowed document content types. The real `backend/.env` must contain an absolute `FILE_STORAGE_PATH` outside the repository; for this PC, use:

```dotenv
FILE_STORAGE_PATH=C:/Users/mikag/AppData/Local/SpindleTrack/private-documents
```

Add that line yourself to `backend/.env`; this project setup does not edit or inspect the real `.env` file.

## Manual checks

After setting `FILE_STORAGE_PATH` in the real `backend/.env` and starting both applications:

1. Sign in with Google; confirm a successful session and expected role. Sign in with the verified account configured by `ADMIN_EMAIL` and confirm `PLATFORM_ADMIN`. Also verify an unverified Email/Password account is rejected.
2. As an eligible customer without attached laundry orders, submit a shop registration with one valid JPG, PNG, or PDF. Confirm it becomes `PENDING` and the details/document metadata appear on the status page.
3. Confirm uploads are rejected when no document is supplied, more than five documents are included, a document exceeds 5 MiB, or a file's content does not match an allowed magic-byte signature even if its name/MIME claim looks valid.
4. Confirm stored files appear only under `FILE_STORAGE_PATH`, have UUID filenames, and are not available through a public/static URL.
5. Confirm the uploading owner and `PLATFORM_ADMIN` can download a document through the authenticated endpoint, while another owner and staff cannot.
6. For a `REJECTED` shop, edit details, remove/add documents, and resubmit. Confirm it returns to `PENDING`, requires at least one remaining document, and enforces the five-file total. Confirm `PENDING`, `ACTIVE`, and `DEACTIVATED` shops cannot use rejected-only editing/resubmission.
7. For owner/staff accounts linked to `PENDING`, `REJECTED`, and `DEACTIVATED` shops, try orders, services, staff-invitation, and report API paths; confirm `403` with code `SHOP_NOT_ACTIVE`. Confirm an `ACTIVE` shop is not blocked by the central guard. The business endpoints themselves are not implemented yet.
8. Confirm an owner, staff member, platform administrator, or customer with attached orders cannot register another shop. Confirm the per-IP daily rate limit rejects further attempts after three.

V1 and V2 remain immutable. V3 adds tenancy and an active demo shop; V4 adds durable rate-limit counters; V5 enforces verification-document size and content-type constraints. Existing demo users have no Firebase UIDs and cannot sign in.
