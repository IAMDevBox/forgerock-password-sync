/**
 * ForgeRock IDM Managed Object Hook: onUserUpdate
 *
 * Detects password field changes and triggers IDCS password synchronization.
 * Deploy to /opt/openidm/script/ and reference in conf/managed.json.
 *
 * Full tutorial: https://www.iamdevbox.com/posts/complete-workflow-for-password-synchronization-from-forgerock-idm-to-identity-cloud/
 *
 * Note: This script receives the plaintext password before IDM hashes it.
 * This is the ONLY opportunity to capture it for cross-system sync.
 */

(function () {
    "use strict";

    // Only act on password field changes
    if (!request.value || !request.value.password) {
        return; // No password change — nothing to sync
    }

    // Verify the password actually changed (guard against no-op PATCH requests)
    if (request.value.password === oldObject.password) {
        logger.debug("onUserUpdate: password field present but unchanged for " + oldObject.userName);
        return;
    }

    logger.info("onUserUpdate: password change detected for user: " + oldObject.userName);

    var syncResult;

    try {
        // Invoke the Groovy sync script
        syncResult = openidm.action(
            "script",
            "eval",
            {
                "type"  : "groovy",
                "file"  : "script/idcs-password-sync.groovy",
                "source": {
                    "userName": oldObject.userName,
                    "password": request.value.password  // Plaintext — before IDM hashes it
                }
            }
        );

        if (syncResult && syncResult.success) {
            logger.info("onUserUpdate: IDCS password sync succeeded for " + oldObject.userName);
        } else {
            var errorMsg = (syncResult && syncResult.error) ? syncResult.error : "unknown error";
            logger.error("onUserUpdate: IDCS password sync failed for " + oldObject.userName + ": " + errorMsg);

            // LENIENT MODE (default — recommended):
            // Allow the local password change even if IDCS sync failed.
            // Prevents user lockout. Failed sync is queued for retry.
            //
            // To enable STRICT MODE (block password change on sync failure), uncomment:
            // throw { "code": 500, "message": "Password synchronization to IDCS failed: " + errorMsg };
        }

    } catch (e) {
        logger.error("onUserUpdate: unhandled exception during IDCS sync for "
            + oldObject.userName + ": " + JSON.stringify(e));

        // Same lenient-mode decision: log but don't block the password change
        // Uncomment to enable strict mode:
        // throw e;
    }
}());
