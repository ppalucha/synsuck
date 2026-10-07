@file:OptIn(ExperimentalMaterial3Api::class)

package pl.synsuck.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.synsuck.R
import pl.synsuck.data.db.AttachmentEntity
import pl.synsuck.ui.forwardAsEmail
import pl.synsuck.ui.openAttachment
import pl.synsuck.ui.theme.AccountColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun MessageScreen(viewModel: MessageViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val problem = when (event) {
                is MessageEvent.Open ->
                    if (context.openAttachment(event.file)) null else R.string.no_app_to_open
                is MessageEvent.Forward ->
                    if (context.forwardAsEmail(event.message, event.account, event.files, event.to)) null else R.string.no_mail_app
                is MessageEvent.Failed -> event.error.messageRes()
            }
            problem?.let { snackbar.showSnackbar(context.getString(it)) }
        }
    }

    val canForward = state.message?.body != null && !state.forwarding
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::forward, enabled = canForward) {
                        Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.forward_as_email))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val message = state.message ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Text(message.topic, style = MaterialTheme.typography.titleLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.account?.let { account ->
                    val color = AccountColors.of(account.color)
                    Text(
                        account.label,
                        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    message.senderGroup?.let { "${message.senderName} · $it" } ?: message.senderName,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                rememberFullDate(message.sentAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            when {
                message.body != null -> SelectionContainer { Text(linkify(message.body)) }
                state.loadingBody -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.loading_message))
                }
                state.bodyError != null -> Column {
                    Text(stringResource(state.bodyError!!.messageRes()), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::loadBody) { Text(stringResource(R.string.retry)) }
                }
            }

            message.attachmentInfo?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleSmall)
                state.attachments.forEach { attachment ->
                    AttachmentRow(
                        attachment,
                        downloading = attachment.attachmentId in state.downloading,
                        onOpen = { viewModel.open(attachment) },
                    )
                }
            }

            if (message.body != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::forward, enabled = canForward, modifier = Modifier.fillMaxWidth()) {
                    if (state.forwarding) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.preparing_forward))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.forward_as_email))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(attachment: AttachmentEntity, downloading: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !downloading, onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(attachment.filename, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (downloading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.open_attachment),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun MessageError.messageRes(): Int = when (this) {
    MessageError.NETWORK -> R.string.network_error
    MessageError.LIBRUS -> R.string.librus_error
}

@Composable
private fun rememberFullDate(instant: java.time.Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.atZone(ZoneId.systemDefault()))
    }
}

private val URL = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""")

/** Plain text with http(s) links made tappable. No HTML is interpreted. */
@Composable
private fun linkify(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        buildAnnotatedString {
            var last = 0
            for (match in URL.findAll(text)) {
                append(text, last, match.range.first)
                withLink(
                    LinkAnnotation.Url(
                        match.value,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(match.value) }
                last = match.range.last + 1
            }
            append(text, last, text.length)
        }
    }
}
