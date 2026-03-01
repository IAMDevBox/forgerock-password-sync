#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ForgeRock IDM Password Sync Integration Tests
#
# Tests the complete password synchronization pipeline from IDM to IDCS.
# Requires curl and jq. Runs against a live IDM + IDCS environment.
#
# Tutorial: https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/
#
# Usage:
#   ./tests/test-password-sync.sh \
#     --idm-host http://localhost:8080 \
#     --idm-admin openidm-admin \
#     --idm-password openidm-admin \
#     --test-user testuser@example.com
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

# ─── Defaults ──────────────────────────────────────────────────────────────
IDM_HOST="http://localhost:8080"
IDM_ADMIN="openidm-admin"
IDM_PASSWORD="openidm-admin"
TEST_USER="testuser@example.com"
PASS_OLD="TestOldPassword123!"
PASS_NEW="TestNewPassword456@"

# ─── Argument parsing ───────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --idm-host)     IDM_HOST="$2";     shift 2 ;;
        --idm-admin)    IDM_ADMIN="$2";    shift 2 ;;
        --idm-password) IDM_PASSWORD="$2"; shift 2 ;;
        --test-user)    TEST_USER="$2";    shift 2 ;;
        *) echo "Unknown argument: $1"; exit 1 ;;
    esac
done

# ─── Helpers ─────────────────────────────────────────────────────────────────
IDM_AUTH_HEADERS=(-H "X-OpenIDM-Username: ${IDM_ADMIN}" -H "X-OpenIDM-Password: ${IDM_PASSWORD}")

pass() { echo "[PASS] $*"; }
fail() { echo "[FAIL] $*" >&2; }
info() { echo "[INFO] $*"; }

# ─── Tests ───────────────────────────────────────────────────────────────────

echo ""
echo "============================================================"
echo "  ForgeRock IDM Password Sync Integration Tests"
echo "  IDM: ${IDM_HOST} | Test user: ${TEST_USER}"
echo "============================================================"
echo ""

PASS_COUNT=0
FAIL_COUNT=0

# ─── Test 1: IDM health check ────────────────────────────────────────────────
info "Test 1: IDM health check"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "${IDM_HOST}/openidm/info/ping" "${IDM_AUTH_HEADERS[@]}")

if [[ "$HTTP_STATUS" == "200" ]]; then
    pass "IDM is reachable (HTTP 200)"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    fail "IDM health check failed (HTTP ${HTTP_STATUS})"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# ─── Test 2: Test user exists in IDM ─────────────────────────────────────────
info "Test 2: Verify test user exists in IDM"
USER_RESPONSE=$(curl -s \
    "${IDM_AUTH_HEADERS[@]}" \
    "${IDM_HOST}/openidm/managed/user?_queryFilter=userName+eq+%22${TEST_USER}%22&_fields=_id,userName")

USER_COUNT=$(echo "$USER_RESPONSE" | jq -r '.resultCount // 0')

if [[ "$USER_COUNT" -gt 0 ]]; then
    pass "Test user '${TEST_USER}' found in IDM"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    fail "Test user '${TEST_USER}' not found in IDM — create user before running tests"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo "  Create with: curl -X POST ${IDM_HOST}/openidm/managed/user ..."
fi

# ─── Test 3: Password change triggers sync script ─────────────────────────────
info "Test 3: Trigger password change (should invoke sync script)"

USER_ID=$(echo "$USER_RESPONSE" | jq -r '.result[0]._id // empty')

if [[ -z "$USER_ID" ]]; then
    fail "Cannot run password change test — test user not found"
    FAIL_COUNT=$((FAIL_COUNT + 1))
else
    PATCH_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH \
        "${IDM_AUTH_HEADERS[@]}" \
        -H "Content-Type: application/json" \
        "${IDM_HOST}/openidm/managed/user/${USER_ID}" \
        -d "[{\"operation\":\"replace\",\"field\":\"password\",\"value\":\"${PASS_NEW}\"}]")

    if [[ "$PATCH_RESPONSE" == "200" ]]; then
        pass "Password change accepted by IDM (HTTP 200)"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        fail "Password change PATCH failed (HTTP ${PATCH_RESPONSE})"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
fi

# ─── Test 4: Check audit log for sync record ──────────────────────────────────
info "Test 4: Check audit log for PASSWORD_SYNC record"
sleep 2  # Allow async processing

AUDIT_RESPONSE=$(curl -s \
    "${IDM_AUTH_HEADERS[@]}" \
    "${IDM_HOST}/openidm/audit/activity?_queryFilter=action+eq+%22PASSWORD_SYNC%22+and+objectId+sw+%22${TEST_USER}%22&_sortKeys=-timestamp&_pageSize=1")

AUDIT_COUNT=$(echo "$AUDIT_RESPONSE" | jq -r '.resultCount // 0')

if [[ "$AUDIT_COUNT" -gt 0 ]]; then
    AUDIT_STATUS=$(echo "$AUDIT_RESPONSE" | jq -r '.result[0].status // unknown')
    pass "Audit log contains PASSWORD_SYNC record (status: ${AUDIT_STATUS})"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    fail "No PASSWORD_SYNC audit record found — sync script may not have run"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo "  Check IDM logs: tail -f /opt/openidm/logs/openidm0.log.0 | grep -i 'password sync'"
fi

# ─── Test 5: Verify retry queue for failed syncs ──────────────────────────────
info "Test 5: Check retry queue is accessible"
QUEUE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
    "${IDM_AUTH_HEADERS[@]}" \
    "${IDM_HOST}/openidm/repo/passwordSyncFailures?_queryFilter=status+eq+%22PENDING_RETRY%22")

if [[ "$QUEUE_RESPONSE" == "200" ]]; then
    pass "Retry queue (repo/passwordSyncFailures) is accessible"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    fail "Retry queue not accessible (HTTP ${QUEUE_RESPONSE}) — check repo configuration"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# ─── Results ─────────────────────────────────────────────────────────────────
echo ""
echo "============================================================"
echo "  Results: ${PASS_COUNT} passed, ${FAIL_COUNT} failed"
echo "============================================================"
echo ""

if [[ "$FAIL_COUNT" -gt 0 ]]; then
    echo "Some tests failed. See troubleshooting guide:"
    echo "  https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/"
    exit 1
else
    echo "All tests passed!"
    exit 0
fi
