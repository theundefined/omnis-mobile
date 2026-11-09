package com.theundefined.omnis

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.repository.OmnisRepository
import com.theundefined.omnis.guard.GuardScheduler
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.components.MainScreen
import com.theundefined.omnis.ui.components.NavRequest
import com.theundefined.omnis.ui.components.SearchRequest
import com.theundefined.omnis.ui.queryFromSharedText
import com.theundefined.omnis.ui.theme.OmnisTheme

class MainActivity : ComponentActivity() {

    private val repository by lazy { OmnisRepository(AccountManager(applicationContext)) }

    private val viewModel: OmnisViewModel by viewModels {
        OmnisViewModel.Factory(application, repository)
    }

    // Licznik żądań skanu ze skrótu aplikacji — każda zmiana wartości to jedno żądanie. Celowo
    // nie przetrwa rekreacji Activity (obrót ekranu), żeby nie odpalać skanera ponownie.
    private var scanRequests by mutableIntStateOf(0)

    // Ekran do otwarcia po kliknięciu powiadomienia strażnika — nowy obiekt = nowe żądanie.
    private var navRequest by mutableStateOf<NavRequest?>(null)
    private var navSeq = 0

    // Zapytanie przekazane z innej aplikacji przez ShareReceiverActivity.
    private var searchRequest by mutableStateOf<SearchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Po rekreacji (savedInstanceState != null) Android oddaje ten sam Intent ze skrótu —
        // obsługujemy go tylko przy pierwszym uruchomieniu.
        if (savedInstanceState == null) handleShortcutIntent(intent)
        // KEEP — nie przesuwa już zaplanowanego przebiegu; odtwarza go np. po wyczyszczeniu
        // danych WorkManagera.
        repository.getGuardSettings().let { guard ->
            if (guard.enabled) GuardScheduler.schedule(applicationContext, guard.runHour)
        }
        setContent {
            OmnisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel,
                        scanRequests = scanRequests,
                        navRequest = navRequest,
                        searchRequest = searchRequest
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForeground()
    }

    private fun handleShortcutIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SCAN_ISBN -> scanRequests++
            ACTION_OPEN_LOANS -> navRequest = NavRequest("main", ++navSeq)
            ACTION_OPEN_HOLDS -> navRequest = NavRequest("holds", ++navSeq)
            ACTION_OPEN_WATCHLIST -> navRequest = NavRequest("watchlist", ++navSeq)
            ACTION_OPEN_SETTINGS -> navRequest = NavRequest("settings", ++navSeq)
            ACTION_OPEN_CARD -> navRequest = NavRequest("card", ++navSeq)
            // Zapytanie to wyłącznie tekst — ShareReceiverActivity już je oczyściła, ale ta akcja
            // jest osiągalna dla każdej aplikacji, więc traktujemy je jak dane, nie polecenie.
            ACTION_SEARCH_TEXT ->
                searchRequest =
                    SearchRequest(
                        queryFromSharedText(intent?.getStringExtra(EXTRA_SEARCH_QUERY)),
                        ++navSeq
                    )
        }
    }

    companion object {
        // Musi odpowiadać akcji w res/xml/shortcuts.xml.
        const val ACTION_SCAN_ISBN = "com.theundefined.omnis.action.SCAN_ISBN"

        // Skrót „Karta biblioteczna" — musi odpowiadać akcji w res/xml/shortcuts.xml.
        const val ACTION_OPEN_CARD = "com.theundefined.omnis.action.OPEN_CARD"

        // Kliknięcia powiadomień strażnika terminów (guard/GuardNotifier).
        const val ACTION_OPEN_LOANS = "com.theundefined.omnis.action.OPEN_LOANS"
        const val ACTION_OPEN_HOLDS = "com.theundefined.omnis.action.OPEN_HOLDS"
        const val ACTION_OPEN_WATCHLIST = "com.theundefined.omnis.action.OPEN_WATCHLIST"
        const val ACTION_OPEN_SETTINGS = "com.theundefined.omnis.action.OPEN_SETTINGS"

        // „Szukaj w Omnis" z innych aplikacji (ShareReceiverActivity → MainActivity).
        const val ACTION_SEARCH_TEXT = "com.theundefined.omnis.action.SEARCH_TEXT"
        const val EXTRA_SEARCH_QUERY = "com.theundefined.omnis.extra.SEARCH_QUERY"
    }
}
