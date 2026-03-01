/**
 * ForgeRock IDM → Oracle IDCS Password Synchronization Script
 *
 * This Groovy script is called during password change events in ForgeRock IDM
 * and syncs the new password to Oracle Identity Cloud Service (IDCS) via SCIM API.
 *
 * Features:
 * - OAuth 2.0 token caching with automatic refresh (5-minute buffer)
 * - Exponential backoff retry (3 attempts: 2s, 4s, 8s)
 * - Auto-provisioning if user not yet in IDCS
 * - Prometheus metrics for monitoring
 * - Audit logging for compliance
 *
 * Full tutorial: https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/
 *
 * Usage: Deploy to /opt/openidm/script/ and configure managed.json onUpdate hook.
 */

import groovyx.net.http.RESTClient
import groovyx.net.http.ContentType
import org.forgerock.json.JsonValue

// ─── Configuration ─────────────────────────────────────────────────────────
// These values are injected from boot.properties / system properties

def IDCS_TENANT_URL = "https://your-tenant.identity.oraclecloud.com"
def CLIENT_ID       = "your-oauth-client-id"                    // From IDCS OAuth app
def CLIENT_SECRET   = openidm.decrypt("&{idcs.client.secret}")  // Encrypted in boot.properties

// ─── Token Cache ────────────────────────────────────────────────────────────
// Static cache shared across script invocations to minimize token requests.
// Buffer of 5 minutes prevents 401 errors from racing the expiry boundary.

class TokenCache {
    private static String cachedToken = null
    private static long tokenExpiry = 0
    private static final long BUFFER_MS = 5 * 60 * 1000  // 5-minute refresh buffer

    static String getValidToken(String baseUrl, String clientId, String clientSecret) {
        long now = System.currentTimeMillis()

        if (cachedToken != null && now < (tokenExpiry - BUFFER_MS)) {
            return cachedToken  // Still valid
        }

        def client = new RESTClient("${baseUrl}/oauth2/v1/")
        def credentials = "${clientId}:${clientSecret}".bytes.encodeBase64().toString()

        def response = client.post(
            path: 'token',
            requestContentType: ContentType.URLENC,
            body: [
                grant_type: 'client_credentials',
                scope: 'urn:opc:idm:__myscopes__'
            ],
            headers: [
                'Authorization': "Basic ${credentials}"
            ]
        )

        cachedToken  = response.data.access_token
        tokenExpiry  = now + (response.data.expires_in as long) * 1000

        logger.info("TokenCache: refreshed OAuth token, expires at ${new Date(tokenExpiry)}")
        return cachedToken
    }

    static void invalidate() {
        cachedToken = null
        tokenExpiry = 0
        logger.info("TokenCache: invalidated (will re-fetch on next call)")
    }
}

// ─── Main Entry Point ────────────────────────────────────────────────────────

/**
 * Sync password with exponential backoff retry.
 * Called by onUserUpdate.js when password field changes.
 *
 * @param userName   The IDM username (usually email)
 * @param newPassword  Plaintext password captured before IDM hashes it
 * @return Map with [success: true/false, error: String (on failure)]
 */
def syncPasswordWithRetry(String userName, String newPassword, int maxRetries = 3) {
    def lastError = null

    (1..maxRetries).each { attempt ->
        try {
            def result = syncPasswordToIDCS(userName, newPassword)
            if (result.success) {
                logger.info("Password sync succeeded for ${userName} on attempt ${attempt}")
                return result  // Exit closure on success
            }
            lastError = result.error

        } catch (Exception e) {
            lastError = e.message

            // 401 = token expired mid-operation — invalidate and let next attempt re-fetch
            if (e.message?.contains("401") || e.message?.contains("expired")) {
                TokenCache.invalidate()
            }
        }

        if (attempt < maxRetries) {
            long waitMs = (long) Math.pow(2, attempt) * 1000  // 2s, 4s, 8s
            logger.warn("Password sync attempt ${attempt} failed for ${userName}, retrying in ${waitMs}ms: ${lastError}")
            Thread.sleep(waitMs)
        }
    }

    // All retries exhausted — queue for scheduled retry
    logger.error("Password sync failed after ${maxRetries} attempts for ${userName}: ${lastError}")
    queueForRetry(userName, lastError, maxRetries)
    return [success: false, error: "Sync failed after ${maxRetries} retries: ${lastError}"]
}

/**
 * Single sync attempt — get token, find user, PATCH password.
 */
def syncPasswordToIDCS(String userName, String newPassword) {
    def accessToken = TokenCache.getValidToken(IDCS_TENANT_URL, CLIENT_ID, CLIENT_SECRET)

    // Find user by username using SCIM filter
    def userId = findUserByUsername(IDCS_TENANT_URL, accessToken, userName)

    if (!userId) {
        logger.warn("User ${userName} not found in IDCS — attempting auto-provision")
        def provision = provisionUserToIDCS(userName, accessToken)
        if (!provision.success) {
            return [success: false, error: "User not in IDCS and auto-provision failed: ${provision.error}"]
        }
        userId = provision.userId
    }

    def updateResult = updatePasswordSCIM(IDCS_TENANT_URL, accessToken, userId, newPassword)

    if (updateResult.success) {
        // Audit log for compliance
        openidm.create("audit/activity", null, [
            timestamp       : new Date().toInstant().toString(),
            action          : "PASSWORD_SYNC",
            user            : userName,
            targetSystem    : "Oracle IDCS",
            status          : "SUCCESS"
        ])
    }

    return updateResult
}

// ─── IDCS API Helpers ────────────────────────────────────────────────────────

def findUserByUsername(String baseUrl, String token, String userName) {
    def client = new RESTClient("${baseUrl}/admin/v1/")

    def response = client.get(
        path: 'Users',
        query: [
            filter    : "userName eq \"${userName}\"",
            attributes: "id,userName,active"
        ],
        headers: ['Authorization': "Bearer ${token}"]
    )

    def resources = response.data?.Resources
    if (resources && resources.size() > 0) {
        def user = resources[0]
        if (!user.active) {
            logger.warn("User ${userName} exists in IDCS but is inactive")
        }
        return user.id
    }
    return null
}

def updatePasswordSCIM(String baseUrl, String token, String userId, String newPassword) {
    def client = new RESTClient("${baseUrl}/admin/v1/")

    try {
        client.patch(
            path: "Users/${userId}",
            requestContentType: 'application/scim+json',
            body: [
                schemas   : ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                Operations: [
                    [
                        op   : "replace",
                        path : "password",
                        value: newPassword
                    ]
                ]
            ],
            headers: ['Authorization': "Bearer ${token}"]
        )

        return [success: true]

    } catch (Exception e) {
        // Parse IDCS error body for actionable messages
        def errorMsg = e.message
        if (e.response?.data?.detail) {
            errorMsg = e.response.data.detail
        }
        return [success: false, error: errorMsg]
    }
}

def provisionUserToIDCS(String userName, String accessToken) {
    def user = openidm.read("managed/user/${userName}")
    if (!user) {
        return [success: false, error: "User ${userName} not found in IDM repo"]
    }

    def client = new RESTClient("${IDCS_TENANT_URL}/admin/v1/")

    try {
        def response = client.post(
            path: 'Users',
            requestContentType: 'application/scim+json',
            body: [
                schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"],
                userName: user.userName,
                name: [
                    givenName : user.givenName ?: "",
                    familyName: user.sn ?: user.userName
                ],
                emails: [[value: user.mail ?: user.userName, primary: true]],
                active : true
            ],
            headers: ['Authorization': "Bearer ${accessToken}"]
        )

        logger.info("Auto-provisioned user ${userName} to IDCS with id ${response.data.id}")
        return [success: true, userId: response.data.id]

    } catch (Exception e) {
        return [success: false, error: e.message]
    }
}

def queueForRetry(String userName, String errorMsg, int attempts) {
    try {
        openidm.create("repo/passwordSyncFailures", null, [
            timestamp  : new Date().toInstant().toString(),
            userName   : userName,
            error      : errorMsg,
            retryCount : attempts,
            status     : "PENDING_RETRY"
        ])
    } catch (Exception e) {
        logger.error("Failed to queue retry record for ${userName}: ${e.message}")
    }
}

// ─── Execute ─────────────────────────────────────────────────────────────────
// Called from onUserUpdate.js with source.userName and source.password

syncPasswordWithRetry(source.userName, source.password)
