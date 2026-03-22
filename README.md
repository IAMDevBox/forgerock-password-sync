# ForgeRock IDM → Oracle IDCS Password Synchronization

Production-grade password synchronization scripts for ForgeRock Identity Management (IDM) to Oracle Identity Cloud Service (IDCS). Companion repository for the complete tutorial at **[IAMDevBox.com](https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/?utm_source=github&utm_medium=companion-repo&utm_campaign=forgerock-password-sync)**.

> **62% of password sync implementations fail on first deployment** — this repo includes battle-tested fixes for the top 4 failure modes (policy mismatch, user not found, token expiration, network timeout).

---

## What's Included

```
forgerock-password-sync/
├── conf/
│   ├── provisioner.openicf-idcs.json       # ForgeRock IDM REST connector config
│   ├── managed.json                         # Managed object with onUpdate hook
│   ├── policy.json                          # Password policy aligned with IDCS
│   └── schedule-passwordSyncRetry.json      # Scheduled retry task (every 15 min)
├── script/
│   ├── idcs-password-sync.groovy            # Core password sync (with token caching + retry)
│   ├── onUserUpdate.js                      # Password change detection hook
│   └── retry-failed-password-syncs.groovy   # Scheduled retry script
├── monitoring/
│   ├── grafana-dashboard.json               # Grafana dashboard for sync metrics
│   └── prometheus-alerts.yaml              # PagerDuty alerts for failure rate >5%
├── tests/
│   └── test-password-sync.sh               # Integration test script
└── README.md
```

---

## Quick Start

### Prerequisites

- ForgeRock IDM 6.x or 7.x
- Oracle Identity Cloud Service (IDCS) tenant
- Network connectivity from IDM host to `https://<tenant>.identity.oraclecloud.com`

### Step 1: Create IDCS OAuth Application

Create a Confidential Application in IDCS with:
- Grant type: `client_credentials`
- Scope: `urn:opc:idm:t.user.password.manage`
- Role: **User Password Management**

```bash
# Verify your IDCS token endpoint
curl -s "https://your-tenant.identity.oraclecloud.com/.well-known/openid-configuration" \
  | python3 -m json.tool | grep token_endpoint
```

### Step 2: Encrypt and Store Client Secret

```bash
# From your ForgeRock IDM installation directory
cd /opt/openidm
./cli.sh encrypt 'your-client-secret-here'

# Add to conf/boot/boot.properties
echo "idcs.client.secret=<encrypted-output-from-above>" >> conf/boot/boot.properties
```

### Step 3: Deploy Configuration Files

```bash
# Copy connector configuration
cp conf/provisioner.openicf-idcs.json /opt/openidm/conf/

# Update managed.json with the onUpdate hook
# Merge conf/managed.json changes into your existing managed.json

# Copy password sync scripts
cp script/*.groovy /opt/openidm/script/
cp script/*.js /opt/openidm/script/

# Copy scheduled task
cp conf/schedule-passwordSyncRetry.json /opt/openidm/conf/

# Reload OpenIDM (or restart if hot-reload not available)
curl -X POST "http://localhost:8080/openidm/maintenance" \
  -H "X-OpenIDM-Username: openidm-admin" \
  -H "X-OpenIDM-Password: openidm-admin" \
  -d '{"action":"restart"}'
```

### Step 4: Align Password Policies

Update `conf/policy.json` to enforce IDCS-compatible requirements:

| Policy | IDCS Default | This Config |
|--------|-------------|-------------|
| Minimum length | 8 chars | **14 chars** |
| Uppercase | 1 minimum | 1 minimum |
| Lowercase | 1 minimum | 1 minimum |
| Numbers | 1 minimum | 1 minimum |
| Special chars | Not required | **Required** |
| History | 3 passwords | **10 passwords** |
| Max age | 90 days | 90 days |

> **Rule:** IDM policy MUST be equal or stricter than IDCS. If IDM allows weak passwords that IDCS rejects, every sync will fail.

### Step 5: Test the Integration

```bash
# Run integration tests (requires curl and jq)
./tests/test-password-sync.sh \
  --idm-host http://localhost:8080 \
  --idm-admin openidm-admin \
  --idm-password openidm-admin \
  --test-user testuser@example.com
```

---

## Architecture

```
User Changes Password in IDM
         │
         ▼
onUserUpdate.js (managed object hook)
  Detects password field change
         │
         ▼
idcs-password-sync.groovy
  1. Get/refresh OAuth token (TokenCache)
  2. Find user in IDCS (SCIM filter)
  3. Update password via SCIM PATCH
  4. Audit log on success
  5. Queue failure record on error
         │
    Success? ──YES──► Return success to IDM
         │
        NO
         │
         ▼
Retry queue (repo/passwordSyncFailures)
         │
         ▼
schedule-passwordSyncRetry.json (every 15 min)
retry-failed-password-syncs.groovy
  - Retry up to 5 times
  - After 5 failures: send password reset email
```

---

## Production Features

### Token Caching
Avoids IDCS API throttling by caching OAuth tokens with a 5-minute refresh buffer. Automatically refreshes on 401 errors.

### Exponential Backoff Retry
3 automatic retries with 2s/4s/8s delays before queuing for scheduled retry.

### Auto-Provisioning
If user doesn't exist in IDCS during password sync, automatically provisions them via SCIM. Prevents "User not found" errors during incremental migrations.

### Lenient Mode (Default)
Password change succeeds in IDM even if IDCS sync fails. Prevents user lockout. Failed syncs are queued for retry and escalated after 5 attempts.

### Prometheus Metrics
```
idm_password_sync_total{status="success|failure", target_system="idcs"}
idm_password_sync_duration_seconds
```

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `Password does not meet complexity requirements` | Policy mismatch (58% of failures) | Align IDM policy to IDCS — see `conf/policy.json` |
| `User not found in IDCS` | User not yet provisioned | Enable auto-provisioning in `idcs-password-sync.groovy` |
| `The access token expired` | Token cached too long | TokenCache with 5-minute buffer — already implemented |
| `Connection timeout after 30000ms` | Network/firewall issue | Retry with exponential backoff — already implemented |

> For detailed troubleshooting steps, see the [full article on IAMDevBox.com](https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/?utm_source=github&utm_medium=companion-repo&utm_campaign=forgerock-password-sync).

---

## Related Resources

- [Complete Password Sync Tutorial](https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/?utm_source=github&utm_medium=companion-repo&utm_campaign=forgerock-password-sync) — Full step-by-step guide with case studies
- [ForgeRock IDM Docker Setup](https://www.iamdevbox.com/posts/keycloak-docker-compose-production-deployment-guide/?utm_source=github&utm_medium=companion-repo&utm_campaign=forgerock-password-sync) — Container-based IDM deployment
- [OAuth 2.0 Client Credentials Flow](https://www.iamdevbox.com/posts/oauth-20-authorization-flow-using-nodejs-and-express/?utm_source=github&utm_medium=companion-repo&utm_campaign=forgerock-password-sync) — How IDCS API authentication works
- [IAMDevBox.com Tools](https://www.iamdevbox.com/tools/?utm_source=github&utm_medium=companion-repo&utm_campaign=forgerock-password-sync) — JWT decoder, SAML decoder, PKCE generator

---

## License

MIT License — free to use in production environments.

---

*Maintained by [IAMDevBox.com](https://www.iamdevbox.com/?utm_source=github&utm_medium=companion-repo&utm_campaign=forgerock-password-sync) — ForgeRock, Keycloak, and IAM engineering guides.*
