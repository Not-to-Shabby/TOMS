TITLE: Security fixes: vehicle card scoping, enforcing CSP, URL validation, and dependency hygiene

BASE: main

Resolves backend, web dashboard, and deployment security findings identified in the security audit:

### 1. Backend (`toms_backend`)
- **S3 (Vulnerability - CVSS-B 5.3):** Constrain `GET /api/cards/approved` strictly to `boundVehicleId` when called by device tokens. Disregards `?vehicle_id=` parameter for devices, eliminating horizontal enumeration of card registries belonging to other transit vehicles. Admin JWT callers retain fleet-wide querying capability.
- **C3 (Code hygiene):** Remove deprecated unscoped `POST /api/routes/:id/stops` and `GET /api/routes/:id/stops` in `src/app.ts` which unintentionally erased path-based stop geofences.
- **D4 (Dependency hygiene):** Pin `multer` to exact `2.4.0` without caret `^` in `package.json`.

### 2. Deployment & Infrastructure (`deploy/`)
- **S4 (Hardening):** Promote `Content-Security-Policy-Report-Only` to enforcing `Content-Security-Policy` in `deploy/Caddyfile` with verified external origins (Google Fonts, CartoDB basemap tiles, OSRM routing, and WebSockets).
- **S5 (Hardening):** Enable `server_tokens off;` in `toms_web/nginx.conf.template` to suppress Nginx version disclosure (`Server: nginx/1.31.8`) in HTTP response headers.
- **S7 (Hardening):** Update `deploy/DEPLOY.md` to instruct operators to use `read -s -p "Admin password: " ADMIN_PASS` interactive input, avoiding logging plaintext administrator credentials in `~/.bash_history`.

### 3. Web Dashboard (`toms_web`)
- **S6 (Hardening):** Add `isValidBaseUrl()` validation in `src/pages/CardDesigner.tsx` requiring `https://` prefix and valid hostname syntax for manual base URL overrides. Disables ZIP and PDF sheet exports and displays an inline alert on invalid input.
- **C2 (Code hygiene):** Remove redundant `<Route path="/settings" />` and duplicate sidebar link in `src/App.tsx`, standardizing on canonical `/fares`.
- **D1, D2, D3 (Dependency hygiene):** Move `@vitejs/plugin-basic-ssl` and `@types/maplibre-gl` to `devDependencies`, and pin `jsqr` to exact `1.4.0`.

### Tests Run
- **Backend:** 308 tests pass across 15 files, TypeScript clean (`npx tsc --noEmit`). Includes new BOLA vehicle isolation unit test in `test/cards.test.ts`.
- **Web:** 313 tests pass across 20 files, TypeScript clean (`npx tsc -b`). Includes new CardDesigner base URL validation tests in `src/pages/CardDesigner.test.tsx`.
- **Scripts:** 29 unit tests pass in `scripts/setup-pilot.test.mjs`, syntax clean on `deploy/verify.sh`.
