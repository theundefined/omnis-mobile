package com.theundefined.omnis.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.theundefined.omnis.R
import com.theundefined.omnis.ui.ReadingStats
import com.theundefined.omnis.ui.StatsSummaryLabels
import com.theundefined.omnis.ui.buildStatsSummary
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Podkatalog `cacheDir` jedyny wystawiony przez FileProvider (`res/xml/file_paths.xml`). */
private const val SHARED_DIR = "shared"
private const val SHARED_IMAGE = "omnis-podsumowanie.png"

fun statsSummaryLabels(context: Context) =
    StatsSummaryLabels(
        heading = { year ->
            if (year != null) context.getString(R.string.stats_summary_heading_year, year)
            else context.getString(R.string.stats_summary_heading_all)
        },
        total = { context.getString(R.string.stats_summary_total, it) },
        vsPrevious = { delta, year -> context.getString(R.string.stats_vs_previous, delta, year) },
        averagePerMonth = { context.getString(R.string.stats_summary_average, it) },
        bestMonth = { month, count ->
            context.getString(R.string.stats_summary_best_month, month, count)
        },
        topAuthors = { context.getString(R.string.stats_summary_authors, it) },
        averageDuration = { days ->
            context.getString(
                R.string.stats_summary_duration,
                context.getString(
                    R.string.stats_days_decimal,
                    String.format(Locale.getDefault(), "%.1f", days)
                )
            )
        },
        footer = context.getString(R.string.stats_summary_footer)
    )

fun shareStatsText(context: Context, stats: ReadingStats, year: Int?) {
    sendShareIntent(
        context,
        buildStatsSummary(stats, year, statsSummaryLabels(context), Locale.getDefault())
    )
}

/**
 * Udostępnia obraz karty podsumowania. PNG ląduje w `cacheDir/shared` (system może go sprzątnąć),
 * odbiorca dostaje jednorazowe prawo odczytu tylko tego URI — FileProvider nie jest eksportowany.
 * Tekst podsumowania dołączamy jako EXTRA_TEXT (część aplikacji użyje go jako opisu).
 */
suspend fun shareStatsImage(context: Context, bitmap: Bitmap, text: String) {
    val uri =
        withContext(Dispatchers.IO) {
            runCatching {
                    val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
                    val file = File(dir, SHARED_IMAGE)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                .getOrNull()
        }
    if (uri == null) {
        Toast.makeText(context, R.string.stats_share_image_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.stats_share_image_failed, Toast.LENGTH_SHORT).show()
    }
}
