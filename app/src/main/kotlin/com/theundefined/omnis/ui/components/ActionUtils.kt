package com.theundefined.omnis.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri

fun sendShareIntent(context: Context, text: String) {
    val sendIntent =
        Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

fun openWebSearch(context: Context, title: String, author: String?) {
    val query = Uri.encode(listOfNotNull(title, author).joinToString(" "))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
    context.startActivity(intent)
}

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
