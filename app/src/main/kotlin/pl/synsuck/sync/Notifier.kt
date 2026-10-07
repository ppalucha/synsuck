package pl.synsuck.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.synsuck.MainActivity
import pl.synsuck.R
import pl.synsuck.data.SyncOutcome
import pl.synsuck.data.db.AccountDao
import pl.synsuck.data.db.AccountEntity
import pl.synsuck.data.db.AccountStatus
import pl.synsuck.data.db.MessageDao
import pl.synsuck.data.db.MessageEntity
import pl.synsuck.ui.theme.AccountColors

/** Where a tapped notification should lead. Carried as Intent extras; validated before use. */
object NotificationTarget {
    const val EXTRA_ACCOUNT_ID = "pl.synsuck.extra.ACCOUNT_ID"
    const val EXTRA_MESSAGE_ID = "pl.synsuck.extra.MESSAGE_ID"
    const val EXTRA_FIX_PASSWORD = "pl.synsuck.extra.FIX_PASSWORD"
}

class Notifier(
    private val context: Context,
    private val accounts: AccountDao,
    private val messages: MessageDao,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_NEW, context.getString(R.string.channel_new_messages), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_PROBLEMS, context.getString(R.string.channel_account_problems), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    suspend fun notifyOutcomes(outcomes: List<SyncOutcome>) {
        if (!manager.areNotificationsEnabled()) return
        val byId = accounts.getAll().associateBy { it.id }
        var postedNew = 0
        for (outcome in outcomes) {
            val account = byId[outcome.accountId] ?: continue
            when (outcome) {
                is SyncOutcome.Synced -> {
                    val fresh = outcome.newUnread.filterNot { it.notified }
                    if (fresh.isEmpty()) continue
                    postNewMessages(account, fresh.sortedByDescending { it.sentAt })
                    messages.markNotified(account.id, fresh.map { it.messageId })
                    postedNew++
                }
                // Syncer only reports these on the transition (afterwards the account is skipped),
                // so this fires once until the user fixes the password.
                is SyncOutcome.Failed -> if (outcome.status == AccountStatus.AUTH_FAILED ||
                    outcome.status == AccountStatus.CAPTCHA_REQUIRED
                ) {
                    postProblem(account, outcome.status)
                }
                else -> Unit
            }
        }
        if (postedNew > 0) postSummary()
    }

    fun cancelProblem(accountId: Long) = manager.cancel(PROBLEM_ID_BASE + accountId.toInt())

    @Suppress("MissingPermission") // checked via areNotificationsEnabled()
    private fun postNewMessages(account: AccountEntity, fresh: List<MessageEntity>) {
        val single = fresh.singleOrNull()
        val title = single?.senderName
            ?: context.resources.getQuantityString(R.plurals.new_messages_title, fresh.size, account.label, fresh.size)
        val text = single?.topic ?: fresh.joinToString(" · ") { it.topic }
        val tap = openIntent(account.id, messageId = single?.messageId)

        val builder = NotificationCompat.Builder(context, CHANNEL_NEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(AccountColors.of(account.color).toArgb())
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(account.label)
            .setWhen(fresh.first().sentAt.toEpochMilli())
            .setShowWhen(true)
            .setNumber(fresh.size)
            .setGroup(GROUP_NEW)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_NEW, account))
        if (single == null) {
            builder.setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    fresh.take(6).forEach { style.addLine("${it.senderName}: ${it.topic}") }
                    style.setSummaryText(account.label)
                },
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(single.topic))
        }
        manager.notify(NEW_ID_BASE + account.id.toInt(), builder.build())
    }

    @Suppress("MissingPermission")
    private fun postSummary() {
        val summary = NotificationCompat.Builder(context, CHANNEL_NEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setGroup(GROUP_NEW)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(accountId = null, messageId = null))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    @Suppress("MissingPermission")
    private fun postProblem(account: AccountEntity, status: AccountStatus) {
        val text = if (status == AccountStatus.CAPTCHA_REQUIRED) {
            context.getString(R.string.status_captcha, account.label)
        } else {
            context.getString(R.string.status_auth_failed, account.label)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(account.label)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(account.id, messageId = null, fixPassword = true))
            .build()
        manager.notify(PROBLEM_ID_BASE + account.id.toInt(), notification)
    }

    /** Lock-screen version without sender or subject. */
    private fun publicVersion(channel: String, account: AccountEntity) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(AccountColors.of(account.color).toArgb())
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_public_text))
            .build()

    private fun openIntent(accountId: Long?, messageId: String?, fixPassword: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            accountId?.let { putExtra(NotificationTarget.EXTRA_ACCOUNT_ID, it) }
            messageId?.let { putExtra(NotificationTarget.EXTRA_MESSAGE_ID, it) }
            if (fixPassword) putExtra(NotificationTarget.EXTRA_FIX_PASSWORD, true)
        }
        // Distinct request codes so each notification keeps its own extras.
        val requestCode = when {
            accountId == null -> 0
            fixPassword -> PROBLEM_ID_BASE + accountId.toInt()
            else -> NEW_ID_BASE + accountId.toInt()
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val CHANNEL_NEW = "new_messages"
        const val CHANNEL_PROBLEMS = "account_problems"
        const val GROUP_NEW = "pl.synsuck.NEW_MESSAGES"
        const val SUMMARY_ID = 1
        const val NEW_ID_BASE = 1_000
        const val PROBLEM_ID_BASE = 100_000
    }
}
