# Deploying TOMS on Railway

Railway does not run `docker-compose.yml` directly. Each compose service becomes its own Railway
service in one project (see https://docs.railway.com/guides/docker-compose). TOMS needs three:
a Postgres database, the API (`toms_backend`) and the web dashboard (`toms_web`).

Everything below follows the Railway docs and the repository files. It has not been run on Railway yet,
so check the Deploy Logs after each step.

## 1. Create the project
1. Railway dashboard, New Project, **Empty project**.
2. Add **Postgres** from the database list (use the managed service, not a raw image).

## 2. API service (`toms_backend`)
1. New Service, GitHub repo, set **Root Directory** to `toms_backend`. Railway builds its `Dockerfile`.
2. Variables:

| Variable | Value |
| --- | --- |
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` (reference variable) |
| `JWT_SECRET` | output of `openssl rand -hex 32` |
| `PUBLIC_BASE_URL` | the final public address of the **web** service, for example `https://toms.example.com` |
| `CORS_ORIGINS` | the same public address (only needed if the browser calls the API from another origin; with the web proxy it can match `PUBLIC_BASE_URL`) |
| `REPORT_TIMEZONE` | `Asia/Manila` |
| `PORT` | `3000` (set it explicitly so the web service's `API_UPSTREAM` port always matches) |
| `RAILWAY_RUN_UID` | `0` (needed so the API can write to the volume, see below) |

3. Settings, Volumes, **Add Volume**, mount path `/app/uploads`. LTFRB fare documents are saved there;
   without a volume they vanish on every redeploy. A service can have only one volume, and redeploying a
   service with a volume causes a short downtime.
4. Do not generate a public domain for the API. The web service reaches it privately.
5. Migrations run automatically at start. Create the first admin from a shell on the service:
   `ADMIN_PASSWORD='at least 12 characters' npm run create-admin -- <username>` (or the compiled
   `node dist/cli/createAdmin.js <username>`).

## 3. Web service (`toms_web`)
1. New Service, same repo, **Root Directory** `toms_web`.
2. Variables:

| Variable | Value |
| --- | --- |
| `TLS_MODE` | `off` (Railway already provides HTTPS; nginx serves plain HTTP) |
| `API_UPSTREAM` | `http://${{api.RAILWAY_PRIVATE_DOMAIN}}:3000` (use your API service's name and the port it listens on) |

3. Settings, Networking, **Generate Domain**, then add your custom domain. Print QR codes only after the
   final domain is settled, and put that same address into `PUBLIC_BASE_URL` on the API service.

`PORT` is read from the environment (default 8080). The Docker health check and routing depend on Railway
passing the port your app listens on; confirm in the Networking settings that the domain points at it.

## 4. Check it
- API Deploy Logs show `Applied migrations` and `TOMS backend listening on port ...`.
- Open the web domain, sign in, open Card Registry. The designer warning about `PUBLIC_BASE_URL` should be gone.
- `https://<domain>/api/config` without a token returns 401 (proves the proxy reaches the API).
- Create two test cards, scan one on a phone, pair it, then delete the test cards.

## 5. Android app
Point the app's server address at the web domain with `https://`. It currently uses `http://<LAN address>:3000`.

## Local Docker is unchanged
`docker compose up` still serves HTTPS on https://localhost:5173 with a self-signed certificate, because
`TLS_MODE` defaults to self-signed and `API_UPSTREAM` defaults to `http://api:3000`.
