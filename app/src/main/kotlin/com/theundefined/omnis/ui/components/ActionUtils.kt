package com.theundefined.omnis.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.theundefined.omnis.R

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
