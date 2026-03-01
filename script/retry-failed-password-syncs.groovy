/**
 * ForgeRock IDM Scheduled Task: Retry Failed Password Syncs
 *
 * Runs every 15 minutes (see conf/schedule-passwordSyncRetry.json).
 * Queries the repo for PENDING_RETRY records and attempts to re-trigger
 * a password reset email for users whose sync failed too many times.
 *
 * After MAX_SYNC_RETRIES failures, user receives a self-service reset email
 * prompting them to set a new password in IDCS.
 *
 * Full tutorial: https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/
 */

def MAX_SYNC_RETRIES = 5

// Query all pending retry records
def failures = openidm.query("repo/passwordSyncFailures", [
    _queryFilter: "status eq \"PENDING_RETRY\" and retryCount lt ${MAX_SYNC_RETRIES}"
])

if (!failures || !failures.result) {
    logger.info("retry-failed-password-syncs: no pending failures found")
    return
}

logger.info("retry-failed-password-syncs: processing ${failures.result.size()} failed password syncs")

failures.result.each { failure ->
    logger.info("retry-failed-password-syncs: handling failed sync for ${failure.userName} (attempt ${failure.retryCount})")

    // Send self-service password reset email so user can set a new password in IDCS
    try {
        def resetResult = openidm.action(
            "selfservice/reset",
            "submitRequirements",
            [
                userId          : failure.userName,
                notificationType: "email"
            ]
        )

        if (resetResult && resetResult.success) {
            failure.status             = "RESET_EMAIL_SENT"
            failure.lastRetryTimestamp = new Date().toInstant().toString()
            failure.retryCount         = (failure.retryCount as int) + 1

            openidm.update("repo/passwordSyncFailures/${failure._id}", null, failure)
            logger.info("retry-failed-password-syncs: password reset email sent to ${failure.userName}")

        } else {
            logger.error("retry-failed-password-syncs: failed to send reset email to ${failure.userName}")
            failure.retryCount = (failure.retryCount as int) + 1
            openidm.update("repo/passwordSyncFailures/${failure._id}", null, failure)
        }

    } catch (Exception e) {
        logger.error("retry-failed-password-syncs: exception processing ${failure.userName}: ${e.message}")
    }
}

// Clean up records that are fully resolved or too old (> 7 days)
def sevenDaysAgo = new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
    .toInstant().toString()

def resolved = openidm.query("repo/passwordSyncFailures", [
    _queryFilter: "status eq \"RESET_EMAIL_SENT\" and timestamp lt \"${sevenDaysAgo}\""
])

resolved?.result?.each { record ->
    openidm.delete("repo/passwordSyncFailures/${record._id}", null)
    logger.info("retry-failed-password-syncs: cleaned up resolved record for ${record.userName}")
}

logger.info("retry-failed-password-syncs: completed")
