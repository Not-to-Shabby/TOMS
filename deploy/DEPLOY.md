# Deploying TOMS to the OVH VPS (toms.perchbuilds.me)

Plan: VPS-1 (2 vCPU, 4 GB RAM, 40 GB), Singapore, **Debian 13** or **Ubuntu 24.04**.
The server runs Docker with four containers: Postgres, the API, the web and Caddy (HTTPS). Only ports 80, 443 and
SSH are open to the internet.

Run every command over SSH on the server, as a normal user (not root). Nothing here has been run on the server yet.

## 1. Point the subdomain at the server (Namecheap)
1. In OVH, open the VPS and copy its **public IPv4 address**.
2. In Namecheap, **Domain List > perchbuilds.me > Advanced DNS**. Leave the existing records alone. The apex (`@`) and
   `www` already point to GitHub Pages, and the TOMS server does not use them.
3. Add one **A record**: Host `toms`, value = that IPv4 address. This makes `toms.perchbuilds.me`.
4. Wait until `dig +short toms.perchbuilds.me` on your PC returns the server's IP. This can take a few minutes.
   Caddy cannot get the HTTPS certificate until this is true.

## 2. Create a non-root user and secure SSH
```bash
adduser deploy                  # set a password
usermod -aG sudo deploy
# From your PC, check you can log in as deploy BEFORE disabling root:
#   ssh deploy@<server-ip>
sudo nano /etc/ssh/sshd_config  # set: PermitRootLogin no   and   PasswordAuthentication no  (after adding your SSH key)
sudo systemctl restart ssh
```

## 3. Firewall: only SSH, HTTP and HTTPS
```bash
sudo apt update && sudo apt install -y ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose        # port 3000 and 5173 must NOT appear
```

## 4. Install Docker
Follow Docker's official instructions for your OS: https://docs.docker.com/engine/install/
(Debian 13 or Ubuntu 24.04). Then let your user run Docker and check it:
```bash
sudo usermod -aG docker deploy   # log out and back in afterwards
docker run --rm hello-world
docker compose version
```

## 5. Get the code onto the server
```bash
mkdir -p ~/toms && cd ~/toms
git clone https://github.com/Not-to-Shabby/TOMS.git src
cd src && git checkout v2/web-application-phase1   # the branch with all the web and backend work
```
Note: the deploy folder and the Caddyfile are part of this branch once they are committed. Until then, copy
`deploy/` from your PC with `scp -r`.

## 6. Create the secrets file (on the server only)
```bash
cd ~/toms/src/deploy
cp .env.example .env
# Generate the two secrets and paste them into .env (replace CHANGE_ME):
openssl rand -hex 24     # POSTGRES_PASSWORD
openssl rand -hex 32     # JWT_SECRET
nano .env
chmod 600 .env
```
Use **new** values here. Do not reuse anything from your local test setup.

## 7. Start the stack
```bash
cd ~/toms/src/deploy
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps     # all four should be Up; db should be healthy
docker compose -f docker-compose.prod.yml logs api --tail 20
```
The API log should show "Applied migrations" and "listening on port 3000".

## 8. Check it from your PC
- https://toms.perchbuilds.me  opens the dashboard with a valid certificate (lock icon).
- https://toms.perchbuilds.me/api/config  returns **401** (the API answers, and it needs a login).
- If the certificate fails, run `docker compose -f docker-compose.prod.yml logs caddy --tail 50`. The usual cause is
  step 1 not having propagated yet.

## 9. Create the admin account and the conductor device
```bash
cd ~/toms/src/deploy
docker compose -f docker-compose.prod.yml exec -e ADMIN_PASSWORD='use-a-long-password-here' api \
  node dist/cli/createAdmin.js <your-admin-username>
```
Sign in at https://toms.perchbuilds.me with that account. Then, in the dashboard:
- **Fleet Manager**: add the vehicle.
- **Conductors Directory**: add the conductor and assign the vehicle.
- **Fare & LTFRB**: publish a fare matrix for the route. Until you do, passenger receipts show no rate.

**Enrol the phone** (the dashboard has no button for this yet). Use the phone's Device ID, which the app shows under
More > Server Sync. Replace `PHONE-ID` with it and `VEHICLE-ID` with the vehicle id:
```bash
TOKEN=$(curl -s -X POST https://toms.perchbuilds.me/api/admin/login -H 'Content-Type: application/json' \
  -d '{"username":"<your-admin-username>","password":"<your-password>"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -X POST https://toms.perchbuilds.me/api/devices -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"device_id":"PHONE-ID","vehicle_id":"VEHICLE-ID"}'
```
The reply contains the phone's token (`<device_id>.<secret>`). It is shown once, so copy it into the app at
More > Server Sync > Set Token. The server address is changed in the app at More > Server Sync > Server URL. Enter
`https://toms.perchbuilds.me`. The phone also needs the newer app build for the card refusal messages (Android branch
`v2/android-card-status`). Before that build is released, check `android:usesCleartextTraffic` in the manifest.

## 10. Backups (do this before real use)
```bash
mkdir -p ~/toms/src/deploy && chmod +x ~/toms/src/deploy/backup.sh
sudo mkdir -p /var/backups/toms && sudo chown deploy:deploy /var/backups/toms
cd ~/toms/src/deploy && bash backup.sh          # first backup now
ls -la /var/backups/toms                        # should show a toms-db-*.dump and a toms-uploads-*.tar.gz
crontab -e
#   30 2 * * * bash /home/deploy/toms/src/deploy/backup.sh >> /home/deploy/toms-backup.log 2>&1
```
**Test a restore once**, into a scratch database, so you know it works before you need it:
```bash
cd ~/toms/src/deploy
DUMP=$(ls -t /var/backups/toms/toms-db-*.dump | head -1)
docker compose -f docker-compose.prod.yml exec -T db createdb -U toms toms_restore_test
docker compose -f docker-compose.prod.yml exec -T db pg_restore -U toms -d toms_restore_test < "$DUMP"
docker compose -f docker-compose.prod.yml exec -T db psql -U toms -d toms_restore_test -c '\dt'
docker compose -f docker-compose.prod.yml exec -T db dropdb -U toms toms_restore_test
```
To keep a second copy off the server, add `OFFSITE` to the crontab line:
`30 2 * * * OFFSITE=user@your-pc:/backups/toms bash /home/deploy/toms/src/deploy/backup.sh >> /home/deploy/toms-backup.log 2>&1`
This needs `rsync` (`sudo apt install -y rsync`, it is not on a minimal Debian) and key-based SSH from the server to your PC. The script does not delete old copies on
your PC, so prune those yourself. The OVH automated backup is a server snapshot. It does not replace this database dump.

## 11. Point the Android app at the server
In the app, open More > Server Sync > Server URL and enter `https://toms.perchbuilds.me` (no trailing slash). The address
is a setting, so the URL alone needs no code change or rebuild. The conductor phone still needs the newer app build for
the card refusal messages (branch `v2/android-card-status`). Do not run a trip on the old build.

## 12. Before the first real trip
- [ ] Old test admin `tempadmin` deleted on the **local** database (its password was shared in chat).
- [ ] `PUBLIC_BASE_URL` in `.env` is `https://toms.perchbuilds.me` **before** any card is printed.
- [ ] Printed QR codes open `https://toms.perchbuilds.me/r/<uuid>` on a phone (test with one card first).
- [ ] A test trip on mobile data, then a backup restored once into a scratch database (step 10).
- [ ] The phone's fares and stops are still placeholders (`TestConfig.kt`). The app never downloads the server's fare matrix, so a paid trip is charged the placeholder fare. Do not start a real paid trip until this is fixed.

## Day-to-day
- Logs: `docker compose -f docker-compose.prod.yml logs -f api`
- Update: `cd ~/toms/src && git pull && cd deploy && docker compose -f docker-compose.prod.yml up -d --build`
- Disk: `df -h` (the uploads and backups grow over time; check monthly).
