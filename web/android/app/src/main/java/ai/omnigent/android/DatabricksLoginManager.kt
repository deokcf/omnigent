package ai.omnigent.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

/** Starts system-browser OAuth and completes an App Link callback into durable credentials. */
internal class DatabricksLoginManager(
    context: Context,
    private val configuration: DatabricksOAuthConfiguration =
        DatabricksOAuthConfiguration.fromBuildConfig(),
    private val pending: DatabricksPendingAuthStore =
        DatabricksPendingAuthStore(context.applicationContext),
    private val client: DatabricksOAuthClient = DatabricksOAuthClient(),
    private val tokens: DatabricksTokenManager = DatabricksTokenManager.shared(context),
) {
    fun start(
        activity: Activity,
        workspaceUrl: URI,
    ): Boolean {
        val attempt = DatabricksOAuthAttempt.create(workspaceUrl, configuration)
        pending.begin(attempt)
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(attempt.authorizationUri.toString()))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            pending.clear()
            false
        }
    }

    fun complete(callback: URI): DatabricksOAuthTokens {
        val saved = pending.load() ?: throw DatabricksOAuthException.InvalidCallback()
        val authorization = saved.attempt.authorizationResponse(callback)
        val claimed =
            pending.consume(saved.id) ?: throw DatabricksOAuthException.InvalidCallback()
        val result =
            client.exchange(
                authorization.code,
                claimed.attempt,
                authorization.issuer,
            )
        tokens.save(claimed.attempt.credentialScope, result)
        return result
    }

    fun cancel() {
        pending.clear()
    }
}
