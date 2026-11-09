package com.theundefined.omnis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.theundefined.omnis.ui.queryFromSharedText

/**
 * Niewidoczna trampolina dla „Udostępnij" (ACTION_SEND) i „Szukaj w Omnis" na zaznaczonym tekście
 * (ACTION_PROCESS_TEXT). Jest wyeksportowana, więc dostaje intenty od dowolnej aplikacji — tekst
 * traktujemy wyłącznie jako zapytanie (queryFromSharedText), a dalej przekazujemy go do
 * MainActivity wewnętrzną akcją i od razu kończymy, żeby MainActivity została w swoim zadaniu.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Intent od obcej aplikacji: wadliwy Parcelable w extras na API < 33 rzuca przy
        // odpakowywaniu — bez runCatching Activity by się wywróciła zamiast po cichu zakończyć.
        val raw =
            runCatching {
                    when (intent?.action) {
                        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                        Intent.ACTION_PROCESS_TEXT ->
                            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                        else -> null
                    }
                }
                .getOrNull()
        // Przy cudzym, zbyt dużym ładunku nie ma sensu parsować całości — zapytanie to jedna linia.
        val query = queryFromSharedText(raw?.toString()?.take(MAX_SHARED_CHARS))
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_SEARCH_TEXT
                    if (query != null) putExtra(MainActivity.EXTRA_SEARCH_QUERY, query)
                    // Jak kliknięcie powiadomienia strażnika: otwarta aplikacja dostaje
                    // onNewIntent.
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        } finally {
            finish()
        }
    }

    private companion object {
        const val MAX_SHARED_CHARS = 10_000
    }
}
