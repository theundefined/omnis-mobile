package com.theundefined.omnis.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.theundefined.omnis.MainActivity
import com.theundefined.omnis.R
import com.theundefined.omnis.data.local.AccountManager
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stan widżetu: albo dane z cache'u, albo prośba o otwarcie aplikacji. */
sealed interface WidgetState {
    /** Brak kont albo niedostępny Keystore/cache — widżet nie ma czego pokazać. */
    data object OpenApp : WidgetState

    data class Data(val model: WidgetModel) : WidgetState
}

private const val MAX_ITEMS = 5

/**
 * Widżet „Terminy zwrotu". Czyta WYŁĄCZNIE lokalny cache wypożyczeń włączonych kont — nigdy nie
 * robi sieci ani logowań. Żaden wyjątek (np. niedostępny Keystore) nie może wywrócić widżetu, więc
 * każda porażka odczytu daje stan „Otwórz aplikację".
 */
class LoansWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = withContext(Dispatchers.IO) { loadWidgetState(context, LocalDate.now()) }
        provideContent { GlanceTheme { Content(state) } }
    }

    @Composable
    private fun Content(state: WidgetState) {
        val context = LocalContext.current
        val root =
            GlanceModifier.fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(openLoansIntent(context)))
        when (state) {
            WidgetState.OpenApp ->
                Column(
                    modifier = root,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        context.getString(R.string.widget_title),
                        style = bold(GlanceTheme.colors.onSurface, 14)
                    )
                    Text(
                        context.getString(R.string.widget_open_app),
                        style = plain(GlanceTheme.colors.onSurfaceVariant, 12)
                    )
                }
            is WidgetState.Data -> {
                if (LocalSize.current.height >= LARGE.height)
                    ListContent(root, state.model, context)
                else SmallContent(root, state.model, context)
            }
        }
    }

    @Composable
    private fun SmallContent(modifier: GlanceModifier, model: WidgetModel, context: Context) {
        Column(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.widget_title),
                style = plain(GlanceTheme.colors.onSurfaceVariant, 11),
                maxLines = 1
            )
            Text(model.count.toString(), style = bold(GlanceTheme.colors.onSurface, 30))
            Text(
                countLabel(context, model.count),
                style = plain(GlanceTheme.colors.onSurfaceVariant, 12),
                maxLines = 1
            )
            val days = model.nearestDays
            if (days != null) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    context.getString(R.string.widget_nearest, dueLabel(context, days)),
                    style = bold(levelColor(dueLevel(days), GlanceTheme.colors.onSurface), 12),
                    maxLines = 2
                )
            }
        }
    }

    @Composable
    private fun ListContent(modifier: GlanceModifier, model: WidgetModel, context: Context) {
        // Nagłówek ~40dp, wiersz ~24dp — tyle pozycji, ile się zmieści (1..MAX_ITEMS).
        val rows = ((LocalSize.current.height.value - 48f) / 24f).toInt().coerceIn(1, MAX_ITEMS)
        Column(modifier = modifier) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    context.getString(R.string.widget_title),
                    style = bold(GlanceTheme.colors.onSurface, 14),
                    modifier = GlanceModifier.defaultWeight(),
                    maxLines = 1
                )
                Text(
                    "${model.count} · ${countLabel(context, model.count)}",
                    style = plain(GlanceTheme.colors.onSurfaceVariant, 11),
                    maxLines = 1
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            if (model.items.isEmpty()) {
                Text(
                    context.getString(R.string.widget_no_loans),
                    style = plain(GlanceTheme.colors.onSurfaceVariant, 12)
                )
            }
            model.items.take(rows).forEach { item ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.title,
                        style = plain(GlanceTheme.colors.onSurface, 12),
                        modifier = GlanceModifier.defaultWeight(),
                        maxLines = 1
                    )
                    item.days?.let {
                        Text(
                            dueLabel(context, it),
                            style = bold(levelColor(item.level, GlanceTheme.colors.onSurface), 12),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    private fun bold(color: ColorProvider, size: Int) =
        TextStyle(color = color, fontSize = size.sp, fontWeight = FontWeight.Bold)

    private fun plain(color: ColorProvider, size: Int) =
        TextStyle(color = color, fontSize = size.sp)

    companion object {
        private val SMALL = DpSize(100.dp, 100.dp)
        private val LARGE = DpSize(180.dp, 150.dp)
    }
}

class LoansWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LoansWidget()
}

/** Kolory jak getDueDateColor w aplikacji; nieczytelna data = kolor domyślny. */
private fun levelColor(level: DueLevel, default: ColorProvider): ColorProvider =
    when (level) {
        DueLevel.OVERDUE_OR_TODAY -> ColorProvider(Color(0xFFD32F2F))
        DueLevel.SOON -> ColorProvider(Color(0xFFFBC02D))
        DueLevel.LATER -> ColorProvider(Color(0xFF388E3C))
        DueLevel.UNKNOWN -> default
    }

private fun countLabel(context: Context, count: Int): String =
    context.resources.getQuantityString(R.plurals.widget_loans_count, count)

private fun dueLabel(context: Context, days: Long): String {
    val res = context.resources
    return when {
        days < -1 -> res.getQuantityString(R.plurals.days_overdue, (-days).toInt(), (-days).toInt())
        days == -1L -> context.getString(R.string.yesterday)
        days == 0L -> context.getString(R.string.today)
        days == 1L -> context.getString(R.string.tomorrow)
        else -> res.getQuantityString(R.plurals.in_days, days.toInt(), days.toInt())
    }
}

/** Jawny intent (nie eksportujemy nic nowego): jak kliknięcie powiadomienia o terminach. */
private fun openLoansIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_LOANS
        flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

/**
 * Odczyt wyłącznie z lokalnego cache'u (zero sieci). Wyjątek z Keystore/EncryptedSharedPreferences
 * albo brak włączonych kont = „Otwórz aplikację".
 */
fun loadWidgetState(context: Context, today: LocalDate): WidgetState =
    try {
        val manager = AccountManager(context.applicationContext)
        val accounts = manager.getAccounts().filter { it.isEnabled }
        if (accounts.isEmpty()) WidgetState.OpenApp
        else
            WidgetState.Data(
                buildWidgetModel(
                    accounts.flatMap { manager.getCachedLoans(it.id) },
                    today,
                    MAX_ITEMS
                )
            )
    } catch (e: Exception) {
        WidgetState.OpenApp
    }

/** Odświeża wszystkie umieszczone widżety; nigdy nie rzuca (widżet jest dodatkiem). */
suspend fun updateLoansWidgets(context: Context) {
    try {
        LoansWidget().updateAll(context.applicationContext)
    } catch (e: Exception) {
        // Brak widżetów / błąd Glance nie może psuć zapisu cache'u ani pracy workera.
    }
}

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Wersja „odpal i zapomnij" dla kodu bez korutyny (UI). */
fun requestLoansWidgetUpdate(context: Context) {
    val app = context.applicationContext
    widgetScope.launch { updateLoansWidgets(app) }
}
