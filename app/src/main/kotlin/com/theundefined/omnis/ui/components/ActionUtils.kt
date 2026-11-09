package com.theundefined.omnis.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.ui.ReturnEventLabels
import com.theundefined.omnis.ui.buildReturnEvent

fun sendShareIntent(context: Context, text: String) {
    val sendIntent =
        Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

/** Przycisk "udostępnij" dla tekstu (systemowy arkusz udostępniania ma też "Kopiuj"). */
@Composable
fun ShareButton(text: () -> String) {
    val context = LocalContext.current
    IconButton(onClick = { sendShareIntent(context, text()) }) {
        Icon(painterResource(R.drawable.ic_share), stringResource(R.string.cd_share))
    }
}

fun openWebSearch(context: Context, title: String, author: String?) {
    val query = Uri.encode(listOfNotNull(title, author).joinToString(" "))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
    context.startActivity(intent)
}

/** Link niedający się otworzyć (brak aplikacji, pusty/niepoprawny adres) kończy się komunikatem. */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Otwiera kalendarz z wypełnionym wydarzeniem „zwrot" (`ACTION_INSERT`, bez uprawnień — zapis robi
 * sama aplikacja kalendarza). Brak takiej aplikacji kończy się komunikatem, jak w [openUrl].
 */
fun addReturnToCalendar(context: Context, loans: List<Loan>) {
    val event =
        buildReturnEvent(
            loans,
            ReturnEventLabels(
                single = { context.getString(R.string.calendar_event_single, it) },
                several = { context.getString(R.string.calendar_event_several, it) },
                line = { title, date ->
                    context.getString(R.string.calendar_event_line, title, date)
                }
            )
        )
    if (event == null) {
        Toast.makeText(context, R.string.calendar_no_date, Toast.LENGTH_SHORT).show()
        return
    }
    val intent =
        Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, event.description)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.calendar_unavailable, Toast.LENGTH_SHORT).show()
    }
}

/** Przycisk „Dodaj do kalendarza" dla jednego lub kilku wypożyczeń. */
@Composable
fun AddToCalendarButton(loans: () -> List<Loan>) {
    val context = LocalContext.current
    IconButton(onClick = { addReturnToCalendar(context, loans()) }) {
        Icon(painterResource(R.drawable.ic_event), stringResource(R.string.calendar_add))
    }
}
