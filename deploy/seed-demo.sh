#!/usr/bin/env bash
# Creates the shared demo account behind "Try the demo" and fills it with fictional sample data, through the public
# API (the same requests the web app makes).
#
#   1. In .env set DEMO_ACCOUNT_EMAIL (e.g. demo@referboard.example) and apply it:  ./deploy/rb up -d auth-service
#   2. ./deploy/seed-demo.sh                 (or: ./deploy/seed-demo.sh https://other-host  to seed another site)
#
# The account gets a random password that is printed nowhere and stored nowhere, so the only way in is the demo
# button. Safe to re-run: it stops once the account has referral contacts. The account must never be an admin;
# the auth service refuses demo sign-in for admin accounts anyway.
# Turn the demo off again by emptying DEMO_ACCOUNT_EMAIL and re-running ./deploy/rb up -d auth-service.
set -euo pipefail
cd "$(dirname "$0")/.."

env_value() { grep -E "^$1=" .env 2>/dev/null | tail -n1 | cut -d= -f2- || true; }

BASE_URL="${1:-}"
if [[ -z "$BASE_URL" ]]; then
  domain="$(env_value DOMAIN)"
  [[ -n "$domain" ]] || { echo "No DOMAIN in .env; pass the site URL: $0 https://your-site" >&2; exit 1; }
  BASE_URL="https://$domain"
fi
EMAIL="${DEMO_ACCOUNT_EMAIL:-$(env_value DEMO_ACCOUNT_EMAIL)}"
[[ -n "$EMAIL" ]] || { echo "Set DEMO_ACCOUNT_EMAIL in .env first (see the comment at the top of $0)." >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

exec python3 - "$BASE_URL" "$EMAIL" <<'PY'
import json, secrets, sys, urllib.error, urllib.request

base, email = sys.argv[1].rstrip("/"), sys.argv[2].strip().lower()
token = None


def call(method, path, body=None, ok=(200, 201, 204)):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "ReferBoard demo seeder")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = raw.decode(errors="replace")
        if e.code in ok:
            return e.code, payload
        raise SystemExit(f"{method} {path} failed with {e.code}: {payload}")


# --- account ---------------------------------------------------------------------------------------------------
status, res = call("POST", "/api/auth/signup",
                   {"email": email, "password": "Demo" + secrets.token_urlsafe(24) + "9", "fullName": "Riya Demo"},
                   ok=(200, 201, 409))
if status == 409:
    _, demo = call("GET", "/api/auth/demo")
    if not demo or not demo.get("enabled"):
        raise SystemExit(f"{email} already exists, but demo sign-in is off. Set DEMO_ACCOUNT_EMAIL={email} in .env "
                         "and run ./deploy/rb up -d auth-service, then run this again.")
    _, res = call("POST", "/api/auth/demo")
    print(f"Demo account {email} already exists; signed in through the demo.")
else:
    print(f"Created demo account {email}.")
token = res["accessToken"]
if res["user"].get("role") == "ADMIN":
    raise SystemExit("That account is an admin. Pick another DEMO_ACCOUNT_EMAIL: the demo is shared with everyone.")

_, board = call("GET", "/api/applications/board")
_, contacts = call("GET", "/api/referrals/contacts?page=0&size=1")
already_started = (board or {}).get("total", 0) > 0
if (contacts or {}).get("total", 0) > 0:
    print("The demo account is already filled; leaving its data alone.")
    sys.exit(0)

# --- profile (fictional person, fictional employers) -----------------------------------------------------------
PROFILE = {
    "fullName": "Riya Demo",
    "email": email,
    "headline": "Backend engineer: Java, Spring Boot, Kafka",
    "summary": "Fictional sample profile for the ReferBoard demo. Four years building payment and logistics "
               "backends; looking for an SDE II role with a strong platform team.",
    "currentTitle": "Software Engineer",
    "currentCompany": "Northwind Logistics (fictional)",
    "location": "Bengaluru",
    "college": "Example Institute of Technology",
    "highestDegree": "B.Tech, Computer Science",
    "links": [],
    "certifications": [],
    "skills": [{"name": n, "years": y} for n, y in [
        ("Java", 4), ("Spring Boot", 3.5), ("PostgreSQL", 3), ("Kafka", 2), ("Redis", 2), ("Docker", 2.5),
        ("Kubernetes", 1), ("REST APIs", 4), ("Microservices", 3), ("AWS", 1.5), ("React", 1), ("SQL", 4)]],
    "experiences": [
        {"company": "Northwind Logistics (fictional)", "title": "Software Engineer", "location": "Bengaluru",
         "startDate": "2023-04", "current": True,
         "description": "Shipment tracking platform.",
         "bullets": ["Built an event-driven shipment status service on Kafka handling 2M events a day",
                     "Cut p95 API latency from 480 ms to 120 ms with Redis caching and query tuning"]},
        {"company": "Lumen Pay (fictional)", "title": "Associate Software Engineer", "location": "Pune",
         "startDate": "2021-07", "endDate": "2023-03", "current": False,
         "description": "Merchant payouts.",
         "bullets": ["Wrote the reconciliation job for daily merchant payouts in Spring Batch",
                     "Moved five services from VMs to Docker on ECS"]},
    ],
    "educations": [{"institution": "Example Institute of Technology", "degree": "B.Tech",
                    "fieldOfStudy": "Computer Science", "startYear": 2017, "endYear": 2021}],
}
PREFERENCES = {
    "roles": ["Backend Engineer", "Software Engineer II", "Java Developer"],
    "locations": ["Bengaluru", "Hyderabad", "Pune"],
    "remoteOnly": False, "openToRemote": True, "minSalaryInr": 2400000, "experienceLevel": "MID",
}

if not already_started:   # a re-run after the first job import only adds what needed listings
    call("PUT", "/api/profile", PROFILE)
    call("PUT", "/api/profile/preferences", PREFERENCES)
    call("PUT", "/api/notifications/preferences", {"emailReminders": False, "emailDigest": False})
    # A job found elsewhere, so the board shows both kinds of cards even on a site with few listings.
    call("POST", "/api/applications", {
        "jobTitle": "Backend Engineer, Payments", "companyName": "Contoso Fintech (fictional)",
        "location": "Hyderabad", "stage": "APPLIED", "notes": "Sample card for a job found outside ReferBoard.",
    })
    print("Filled the profile and preferences.")

# --- tracker and referral contacts -----------------------------------------------------------------------------
# Prefer jobs that fit the sample profile, so the demo's board and referrals look like a real search.
_, page = call("GET", "/api/jobs/recommended?page=0&size=12", ok=(200, 400, 404, 409))
if not isinstance(page, dict) or not page.get("items"):
    _, page = call("GET", "/api/jobs?page=0&size=12")
jobs = [j for j in (page or {}).get("items", []) if not j.get("ghostJobSuspected")][:4]

stages = ["REFERRAL_REQUESTED", "APPLIED", "INTERVIEW", "SAVED"]
for job, stage in zip(jobs, stages):
    call("POST", "/api/applications", {"jobId": job["id"], "stage": stage}, ok=(200, 201, 409))

# Fictional people only, and no profile links: nobody real should be shown as having been asked for a referral.
people = [
    ("Kabir Example", "Senior Engineer (sample contact)", "TEAM_MEMBER", "REFERRAL_ASKED",
     "Sample contact. Met at a Bengaluru Java meetup; said to ping after applying."),
    ("Meera Sample", "Talent partner (sample contact)", "RECRUITER", "CONNECTED",
     "Sample contact. Accepted the connection; waiting on the role's hiring manager."),
    ("Dev Placeholder", "College senior (sample contact)", "ALUMNI", "REQUEST_SENT",
     "Sample contact. Same college, two batches above."),
]
for job, (name, headline, kind, contact_status, notes) in zip(jobs, people):
    _, contact = call("POST", f"/api/referrals/jobs/{job['id']}/contacts",
                      {"fullName": name, "headline": headline, "personType": kind, "notes": notes})
    if contact_status in (contact or {}).get("allowedNextStatuses", []):
        call("POST", f"/api/referrals/contacts/{contact['id']}/status", {"status": contact_status})

if jobs:
    print(f"Tracked {len(jobs)} listed jobs and added {min(len(jobs), len(people))} sample referral contacts.")
else:
    print("There are no job listings on this site yet, so no referral contacts were added. "
          "Run this again after the first job import to add them.")
print("Done. Visitors can now use \"Try the demo\" on the home and sign-in pages.")
PY
