# SpindleTrack

Initial project scaffold for a laundry-shop order management, payment tracking, and customer notification system.

## Stack

- Backend: Java 21, Spring Boot 3.5, Maven, Spring Web/Security/Data JPA/Validation/Scheduling, Flyway, MySQL 8
- Frontend: React, Vite, React Router, Axios, Tailwind CSS, Recharts, lucide-react
- Database choice: MySQL (confirmed)

## Prerequisites

- Java 17 or newer (Java 21 was detected)
- Node.js and npm (Node.js 24 and npm 11 were detected)
- Git
- MySQL 8 server reachable from the backend

The machine reports a running `MySQL80` Windows service, but the local connection check to `127.0.0.1:3306` did not respond. Confirm the server is configured to listen on that port, or update `DB_URL` to its configured port. The MySQL command-line client was not found in PATH. No system software or MySQL settings were changed by this setup.

Maven was not found in PATH. `backend/mvnw.cmd` bootstraps Apache Maven 3.9.11 into `backend/.mvn/wrapper/dists` and stores Maven dependencies in `backend/.mvn/repository`; this keeps the tool and dependencies project-local rather than installing Maven system-wide.

## Database setup

Create a database and a least-privilege local application user using MySQL Workbench or another MySQL administration client. For example, run the following SQL in your local MySQL administrator session, replacing the example password locally:

```sql
CREATE DATABASE spindletrack CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'spindletrack'@'localhost' IDENTIFIED BY 'replace-with-a-local-password';
GRANT ALL PRIVILEGES ON spindletrack.* TO 'spindletrack'@'localhost';
```

Do not commit credentials. From the project root, create local environment files from the checked-in examples (skip any copy whose `.env` already exists):

```powershell
if (-not (Test-Path backend/.env)) { Copy-Item backend/.env.example backend/.env }
if (-not (Test-Path frontend/.env)) { Copy-Item frontend/.env.example frontend/.env }
```

Edit `backend/.env` with the local MySQL URL, username, and password. The `.env` files are ignored by Git. Flyway applies the schema and seed migrations automatically when the backend connects for the first time.

Seeded test identities include `owner@spindletrack.test`, `staff1@spindletrack.test`, and `staff2@spindletrack.test`. These are database sample rows only; Google/Firebase authentication has not been connected, and these are not usable login accounts.

## Run the backend (PowerShell)

From the project root, in the backend terminal:

```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

The API health endpoint is `http://localhost:8080/api/health`. The application cannot complete startup unless the MySQL server, database, and local `.env` connection values are ready.

## Run the frontend (PowerShell)

In a second terminal, from the project root:

```powershell
Set-Location frontend
npm.cmd install --cache .npm-cache
npm.cmd run dev
```

Vite serves the starter UI at `http://127.0.0.1:5173`. `npm.cmd` is used because this computer's PowerShell execution policy blocks the `npm.ps1` shim.

## Initial scope

This step establishes the project structure, configuration, schema, seed data, and starter screens. The six laundry stages are ordered in the backend domain enum and covered by unit tests. Customer/staff/owner workflows, Firebase token verification, PayMongo test-mode payments and signed webhook processing, Brevo email delivery, scheduled unclaimed-order handling, reports, and production role authorization remain for subsequent implementation steps. No live payment integration is configured.
