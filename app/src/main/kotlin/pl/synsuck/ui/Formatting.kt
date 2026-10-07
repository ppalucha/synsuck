package pl.synsuck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import pl.synsuck.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** "10:42" today, "Yesterday", "6 Oct" this year, "Jun 4, 2025" otherwise — in the app's language. */
@Composable
fun rememberMessageDateFormatter(): (Instant) -> String {
    val locale: Locale = LocalConfiguration.current.locales[0]
    val yesterday = stringResource(R.string.yesterday)
    return remember(locale, yesterday) {
        val zone = ZoneId.systemDefault()
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val full = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        ({ instant: Instant ->
            val dateTime = instant.atZone(zone)
            val today = LocalDate.now(zone)
            when (dateTime.toLocalDate()) {
                today -> time.format(dateTime)
                today.minusDays(1) -> yesterday
                else -> if (dateTime.year == today.year) dayMonth.format(dateTime) else full.format(dateTime)
            }
        })
    }
}
