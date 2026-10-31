# Deploying TOMS to the OVH VPS (perchbuilds.me)

Plan: VPS-1 (2 vCPU, 4 GB RAM, 40 GB), Singapore, **Debian 13** or **Ubuntu 24.04**.
The server runs Docker with four containers: Postgres, the API, the web and Caddy (HTTPS). Only ports 80, 443 and
SSH are open to the internet.

Run every command over SSH on the server, as a normal user (not root). Nothing here has been run on the server yet.

## 1. Point the domain at the server (Namecheap)
1. In OVH, open the VPS and copy its **public IPv4 address**.
2. In Namecheap, **Domain List > perchbuilds.me > Advanced DNS**.
3. Add two **A records**, both pointing to that IPv4 address:
   - Host `@`  (for perchbuilds.me)
   - Host `www`  (Caddy redirects www to the main name)
4. Wait until `dig +short perchbuilds.me` on your PC returns the server's IP. This can take a few minutes.
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
- https://perchbuilds.me  opens the dashboard with a valid certificate (lock icon).
- https://perchbuilds.me/api/config  returns **401** (the API answers, and it needs a login).
- If the certificate fails, run `docker compose -f docker-compose.prod.yml logs caddy --tail 50`. The usual cause is
  step 1 not having propagated yet.

## 9. Create the admin account and the conductor device
```bash
cd ~/toms/src/deploy
docker compose -f docker-compose.prod.yml exec -e ADMIN_PASSWORD='use-a-long-password-here' api \
  node dist/cli/createAdmin.js <your-admin-username>
```
Sign in at https://perchbuilds.me with that account. Create the conductor accounts and enrol the phone from the dashboard.

## 10. Backups (do this before real use)
```bash
mkdir -p ~/toms/src/deploy && chmod +x ~/toms/src/deploy/backup.sh
sudo mkdir -p /var/backups/toms && sudo chown deploy:deploy /var/backups/toms
cd ~/toms/src/deploy && bash backup.sh          # first backup now
ls -la /var/backups/toms                        # should show a .dump and a .tar.gz
crontab -e
#   30 2 * * * bash /home/deploy/toms/src/deploy/backup.sh >> /home/deploy/toms-backup.log 2>&1
```
**Test a restore once**, so you know it works before you need it:
```bash
docker compose -f docker-compose.prod.yml exec -T db pg_restore --list < /var/backups/toms/<latest>.dump | head
```
To copy backups off the server as well, set `OFFSITE=user@your-pc:/backups/toms` in the crontab line.

## 11. Point the Android app at the server
The phone app must use `https://perchbuilds.me`, not the old `http://<your-PC-IP>:3000`. That is a code change and
needs a new build. Do not install the old build on the conductor phone for the real trip.

## 12. Before the first real trip
- [ ] Old test admin `tempadmin` deleted on the **local** database (its password was shared in chat).
- [ ] `PUBLIC_BASE_URL` in `.env` is `https://perchbuilds.me` **before** any card is printed.
- [ ] Printed QR codes open `https://perchbuilds.me/r/<uuid>` on a phone (test with one card first).
- [ ] A test trip on mobile data, then a backup restored once.

## Day-to-day
- Logs: `docker compose -f docker-compose.prod.yml logs -f api`
- Update: `cd ~/toms/src && git pull && cd deploy && docker compose -f docker-compose.prod.yml up -d --build`
- Disk: `df -h` (the uploads and backups grow over time; check monthly).
