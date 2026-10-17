# Deploying ReferBoard on Oracle Cloud (free)

This puts the whole app on one **Oracle Cloud Always Free** server: 2 Arm CPUs (Ampere A1), 12 GB of memory,
up to 200 GB of disk, free with no time limit. Everything runs there with Docker: the 13 Java services,
Postgres, Kafka, Redis, the web app, and Caddy for HTTPS.

**What it costs:** nothing for the server, as long as you stay inside the Always Free limits. You do need a
domain name (about ₹700–1,000 a year), because Razorpay and Google sign-in require one.

**Time:** about an hour the first time, much of it waiting for builds.

```
Browser ──HTTPS──> Caddy :443 ──> nginx (web app, /api) ──> api-gateway ──> services ──> Postgres / Kafka / Redis
                   (certificates)                                            all on one server, nothing else exposed
```

---

## 1. Create the Oracle Cloud account

1. Go to **oracle.com/cloud/free** and choose **Start for free**.
2. Sign up with your email. Oracle asks for a credit or debit card to verify your identity. You may see a small
   temporary authorisation on it that is reversed. Always Free resources aren't billed.
3. **Home region:** pick it carefully, because you can't change it later and free servers can only be created there.
   - **India West (Mumbai)** or **India South (Hyderabad)** give the lowest latency for Indian users, but their
     free Arm capacity is often used up.
   - If the server won't create there (step 2), you'd need a new account to try another region. Some people pick
     **Singapore** for better availability; it's still fast from India.

The account can take anywhere from a few minutes to a day to be fully provisioned. Wait until you can open
**Compute → Instances**.

## 2. Create the server

In the console menu choose **Compute → Instances → Create instance**.

| Setting | Choose |
|---|---|
| Name | `referboard` |
| Image | **Change image → Ubuntu → Canonical Ubuntu 24.04** |
| Shape | **Change shape → Ampere → VM.Standard.A1.Flex**, set **2 OCPUs** and **12 GB memory**. It should be labelled "Always Free-eligible". |
| Networking | Create a new virtual cloud network and a **public subnet**, and keep **Assign a public IPv4 address** on |
| SSH keys | **Generate a key pair for me**, then **Save private key**. You need this file to log in, and Oracle won't show it again. (Or paste your own public key.) |
| Boot volume | Specify a custom size: **100 GB** (still inside the free 200 GB) |

Choose **Create**. After a minute or two it shows **Running** and a **Public IP address**. Write the IP down.

> **"Out of capacity for shape VM.Standard.A1.Flex"** means Oracle has no free Arm capacity in that region right
> now. Try again later (early morning IST often works), or try another availability domain in the same form.
> Upgrading the account to **Pay As You Go** usually fixes it. Always Free resources stay free on that plan, but
> anything you create beyond the free limits is billed, so only create what this guide says.

## 3. Open ports 80 and 443 in Oracle's network

Oracle blocks web traffic at the network level until you allow it.

1. Open your instance. Under **Primary VNIC**, click the **Subnet** link, then open its **Security** tab (or
   **Security Lists**) and choose the **Default Security List**.
2. **Add Ingress Rules**, one for each port:
   - Source CIDR `0.0.0.0/0`, IP protocol **TCP**, destination port **80**
   - Source CIDR `0.0.0.0/0`, IP protocol **TCP**, destination port **443**

Leave the existing SSH (port 22) rule as it is. The setup script opens the same two ports in the server's own
firewall.

## 4. Point your domain at the server

Buy a domain from any registrar (GoDaddy, Namecheap, Hostinger, BigRock, Cloudflare, …). In its DNS settings
add:

| Type | Name | Value |
|---|---|---|
| A | `@` (the domain itself) | your server's public IP |

If your DNS is on **Cloudflare**, set the record to **DNS only** (grey cloud), not Proxied, so the server can get
its own certificate.

DNS changes usually show up within minutes. Check from your computer: `nslookup yourdomain.in` should print the
server's IP.

## 5. Log in and copy the code over

**Log in** from a terminal (macOS/Linux Terminal, or Windows PowerShell):

```bash
chmod 400 ~/Downloads/ssh-key-*.key        # macOS/Linux only: SSH refuses keys others can read
ssh -i ~/Downloads/ssh-key-*.key ubuntu@YOUR_SERVER_IP
```

**Get the code onto the server.** Option A makes future updates a single command, so it's recommended.

**Option A: a private GitHub repository.**
1. On your computer, unzip the milestone zip, then:
   ```bash
   cd referboard
   git init && git add -A && git commit -m "ReferBoard"
   ```
   Create an empty **private** repository on GitHub and push to it.
2. On the server:
   ```bash
   git clone https://github.com/YOUR_NAME/referboard.git
   ```
   GitHub asks for a username and password: use your username and a **personal access token** (GitHub →
   Settings → Developer settings → Fine-grained tokens, with read access to this repository only).

**Option B: copy the zip.** From your computer:
```bash
scp -i ~/Downloads/ssh-key-*.key referboard-milestone13.zip ubuntu@YOUR_SERVER_IP:~
```
Then on the server:
```bash
sudo apt-get update && sudo apt-get install -y unzip
unzip referboard-milestone13.zip -d referboard
```

## 6. Run the setup

On the server:

```bash
cd ~/referboard
sudo bash deploy/setup.sh yourdomain.in you@example.com
```

The email is only used by Let's Encrypt for certificate notices. The script:

1. Adds 4 GB of swap as a safety net.
2. Installs Docker.
3. Opens ports 80 and 443 in the server's firewall. Oracle's Ubuntu image blocks them by default.
4. Writes `.env` with a random password for every database account and a fresh sign-in key pair.
5. Checks that your domain points at this server. It stops here if it doesn't, so it never wastes a certificate
   attempt.
6. Downloads the base images and checks each one has an Arm build.
7. Builds all the services in one go. This takes **10–20 minutes the first time**; later builds are faster.
8. Starts everything behind HTTPS and waits until `https://yourdomain.in` answers. The first start takes **3–6
   minutes**.
9. Schedules nightly backups.

It's safe to run again: each step checks what's already done, and an existing `.env` is never overwritten.

Open `https://yourdomain.in`. You should see ReferBoard with a padlock.

## 7. Make yourself admin

Sign up on the site with your own email, then on the server:

```bash
sudo bash deploy/rb exec postgres psql -U postgres referboard \
  -c "update auth.users set role = 'ADMIN' where email = 'you@example.com';"
```

Sign out and back in. **Admin** now appears in the menu, with platform numbers but no personal data.

## 8. Turn on the optional parts

Everything below lives in `~/referboard/.env`. Edit it with `sudo nano .env`, then apply the change:

```bash
sudo bash deploy/rb up -d          # restarts only the services whose settings changed
```

| Feature | What to do |
|---|---|
| **Payments (Razorpay)** | 1. In the Razorpay Dashboard, get **test** API keys and set `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET`. <br>2. Under **Webhooks**, add `https://yourdomain.in/api/billing/webhooks/razorpay` with events `payment.captured`, `order.paid` and `payment.refunded`, choose a secret, and put the same value in `RAZORPAY_WEBHOOK_SECRET`. <br>3. Buy Pro on your site with a Razorpay test card. <br>4. To take real money, complete Razorpay's KYC. Activation needs privacy, terms, refund and contact pages on your site. Then swap in the live keys and add a webhook in live mode the same way. |
| **Google sign-in** | In Google Cloud Console, set up the OAuth consent screen, then create an **OAuth client ID** of type Web application, with authorised JavaScript origin `https://yourdomain.in`. Put it in `GOOGLE_CLIENT_ID`, then run `sudo bash deploy/update.sh`. The web app has the ID built in, so it needs a rebuild, not just a restart. |
| **AI** | Set `ANTHROPIC_API_KEY` and/or `OPENAI_API_KEY`. Without them the app still works, with simpler resume parsing and template drafts. |
| **Email** | Any SMTP relay. For example, Brevo's free plan: `SMTP_HOST=smtp-relay.brevo.com`, plus your SMTP login in `SMTP_USERNAME` and `SMTP_PASSWORD`. Use a `MAIL_FROM` address on a domain you've verified with the provider. Without `SMTP_HOST`, emails are recorded but not sent. |
| **More jobs** | Free Adzuna API keys (developer.adzuna.com) in `ADZUNA_APP_ID` and `ADZUNA_APP_KEY`. Company career boards are read on a schedule regardless. |

## Day-to-day commands

Run these from `~/referboard`.

| Task | Command |
|---|---|
| What's running | `sudo bash deploy/rb ps` |
| A service's logs | `sudo bash deploy/rb logs -f --tail=100 auth-service` |
| Restart one service | `sudo bash deploy/rb restart job-service` |
| Memory per container | `sudo docker stats --no-stream` |
| Database shell | `sudo bash deploy/rb exec postgres psql -U postgres referboard` |
| Stop everything | `sudo bash deploy/rb down` (your data stays in Docker volumes) |
| Start again | `sudo bash deploy/rb up -d` |

Everything restarts by itself after a crash or a server reboot.

## Updating to a new version

- **Option A (GitHub):** push the new code to your repository, then on the server run
  `sudo bash deploy/update.sh`.
- **Option B (zip):** copy the new zip over and unzip it **on top of** `~/referboard` (keep `.env`!), then run
  `sudo bash deploy/update.sh`.

The update script backs up first, rebuilds, and restarts what changed. Database changes apply automatically
when each service starts.

## Backups

Every night at 02:30 UTC (08:00 IST) the server saves three things to `/var/backups/referboard/<date>/`:

- the database (`referboard.dump`);
- the database accounts (`roles.sql`);
- the uploaded resumes (`resumes.tar.gz`).

It keeps 14 days and checks each database dump is readable. The log is at `/var/log/referboard-backup.log`.
To run a backup now: `sudo bash deploy/backup.sh`.

**Keep a copy off the server.** A backup on the same disk doesn't survive losing the server. Oracle's free tier
includes Object Storage, which the backup can copy to automatically:

1. In the console, go to **Storage → Buckets → Create bucket**, named `referboard-backups`. Note the
   **namespace** shown on the bucket page.
2. Open your **Profile** (top right) → **Customer secret keys** → **Generate secret key**. Copy the secret
   right away (it's shown once) and the **Access key**.
3. On the server:
   ```bash
   sudo apt-get install -y rclone
   sudo mkdir -p /root/.config/rclone
   sudo nano /root/.config/rclone/rclone.conf
   ```
   Paste this, filling in your values. The region identifier is shown under **Regions** in the console, e.g.
   `ap-mumbai-1` or `ap-hyderabad-1`.
   ```ini
   [oci]
   type = s3
   provider = Other
   access_key_id = YOUR_ACCESS_KEY
   secret_access_key = YOUR_SECRET
   endpoint = https://YOUR_NAMESPACE.compat.objectstorage.YOUR_REGION.oraclecloud.com
   region = YOUR_REGION
   ```
4. Test it with `sudo rclone lsd oci:`. It should list `referboard-backups`.
5. In `.env`, set `BACKUP_REMOTE=oci:referboard-backups`, then run `sudo bash deploy/backup.sh` once to check.

### Restoring a backup

On the same server, or a fresh one where setup has run with the **same `.env`** (it holds the database
passwords):

```bash
B=/var/backups/referboard/2026-10-20T023000Z          # the backup to restore
cd ~/referboard
sudo bash deploy/rb stop $(sudo bash deploy/rb config --services | grep -E 'service$')
sudo bash deploy/rb exec -T postgres dropdb -U postgres referboard
sudo bash deploy/rb exec -T postgres createdb -U postgres referboard
sudo bash deploy/rb exec -T postgres pg_restore -U postgres -d referboard --exit-on-error < $B/referboard.dump
sudo bash deploy/rb up -d
sudo bash deploy/rb exec -T profile-service tar -xzf - -C /data/resumes < $B/resumes.tar.gz
```

The dump keeps each service's ownership of its own schema and the grants that keep services out of each other's
tables. This restore was tested that way. The database accounts already exist on any server where setup has run.
`roles.sql` is only needed for restoring into a completely separate Postgres, and is run first with
`psql -U postgres -f roles.sql`.

## Keeping it healthy (and free)

- **Uptime alerts:** a free UptimeRobot (or similar) monitor on `https://yourdomain.in/healthz` emails you if
  the site goes down.
- **Security updates:** Ubuntu installs them automatically. Once a month, run
  `sudo apt-get update && sudo apt-get upgrade -y` and reboot if it asks.
- **Logs** are capped (10 MB × 3 per container), and Kafka keeps two weeks of events, so the disk doesn't fill.
  Check with `df -h`.
- **Stay free:** in the console, check **Billing → Cost analysis** now and then. It should say ₹0. Don't create
  extra servers, volumes or load balancers.
- **Idle reclamation:** Oracle may reclaim Always Free servers that sit almost unused for 7 days. ReferBoard keeps
  enough memory in use that it shouldn't count as idle, but keep the uptime monitor on so you'd notice.

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| Setup stops at "resolves to …" | DNS isn't pointing at this server yet, or Cloudflare is proxying (orange cloud). Fix the A record, wait, and rerun setup. |
| Browser can't connect at all | Port 80/443 rule missing in Oracle's security list (step 3). From your computer, `curl -I http://yourdomain.in` should get a response. |
| Certificate error in the browser | Caddy couldn't get a certificate: `sudo bash deploy/rb logs caddy`. Usually DNS or ports. It retries by itself once fixed. |
| Site loads but says it can't reach the server | Services are still starting (allow 6 minutes after a start), or one failed: `sudo bash deploy/rb ps` and look for "Restarting". |
| A service keeps restarting | `sudo bash deploy/rb logs --tail=80 <service>`. Then `sudo docker inspect --format '{{.State.OOMKilled}}' referboard-<service>-1`; if it says `true`, raise its `mem_limit` in `deploy/compose.prod.yml` (and its `-Xmx` by the same amount). |
| `no matching manifest for linux/arm64` | One of the images has no Arm build. Tell me which one; it can be swapped for an Arm-ready equivalent. |
| Setup says Compose is too old | `sudo apt-get install --only-upgrade docker-compose-plugin`, then rerun. |
| Sign-ups fail with 403 | `.env` `DOMAIN` must exactly match the address in the browser (no `www.` unless you set it that way). After changing it: `sudo bash deploy/rb up -d`. |
| Want `www.yourdomain.in` too | Add an A record for `www`, then add `www.{$DOMAIN} { redir https://{$DOMAIN}{uri} permanent }` to `deploy/Caddyfile` and run `sudo bash deploy/rb restart caddy`. |

## What the deployment files do

| File | Purpose |
|---|---|
| `deploy/setup.sh` | One-time server setup (above) |
| `deploy/compose.prod.yml` | Production settings on top of `docker-compose.yml`: only Caddy is published, memory caps, real passwords, no Mailpit or sample jobs, log rotation, restart policies |
| `deploy/rb` | `docker compose` with both files, so you don't have to type them |
| `deploy/build-jars.sh` | Builds all services in one Maven run inside a container |
| `docker/service.prebuilt.Dockerfile` | Packages those jars into images (no compiling inside each image) |
| `deploy/Caddyfile` | HTTPS and automatic certificates |
| `deploy/nginx-real-ip.conf` | Makes rate limits see each visitor's real address behind Caddy |
| `deploy/postgres/02-set-passwords.sh` | Sets the database accounts' passwords from `.env` when the database is first created |
| `deploy/backup.sh`, `deploy/update.sh` | Backups and updates |
| `deploy/env.template` | The template `.env` is made from |

**Memory budget** (12 GB server):

| Part | Memory |
|---|---|
| 13 Java services | about 4 GB in use at peak in testing; caps total 6.1 GB |
| Postgres | up to 1.5 GB |
| Kafka | up to 1 GB |
| Redis, nginx, Caddy | about 0.5 GB |
| **Total of all caps** | **about 9.1 GB**, leaving room for Ubuntu and Docker, plus the 4 GB of swap |
