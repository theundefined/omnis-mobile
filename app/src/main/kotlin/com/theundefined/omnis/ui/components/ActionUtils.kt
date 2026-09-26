package com.theundefined.omnis.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Row
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

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    // Od Androida 13 system sam pokazuje podgląd skopiowanego tekstu — własny Toast by go dublował.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}

/** Para przycisków "kopiuj do schowka" + "udostępnij" dla tego samego tekstu. */
@Composable
fun CopyShareButtons(text: () -> String) {
    val context = LocalContext.current
    Row {
        IconButton(onClick = { copyToClipboard(context, text()) }) {
            Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.cd_copy))
        }
        IconButton(onClick = { sendShareIntent(context, text()) }) {
            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.cd_share))
        }
    }
}

fun openWebSearch(context: Context, title: String, author: String?) {
    val query = Uri.encode(listOfNotNull(title, author).joinToString(" "))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
    context.startActivity(intent)
}

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
