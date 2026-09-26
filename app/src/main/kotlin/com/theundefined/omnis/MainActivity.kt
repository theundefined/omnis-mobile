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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.repository.OmnisRepository
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.components.MainScreen
import com.theundefined.omnis.ui.theme.OmnisTheme

class MainActivity : ComponentActivity() {

    private val repository by lazy { OmnisRepository(AccountManager(applicationContext)) }

    private val viewModel: OmnisViewModel by viewModels {
        OmnisViewModel.Factory(application, repository)
    }

    // Licznik żądań skanu ze skrótu aplikacji — każda zmiana wartości to jedno żądanie. Celowo
    // nie przetrwa rekreacji Activity (obrót ekranu), żeby nie odpalać skanera ponownie.
    private var scanRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Po rekreacji (savedInstanceState != null) Android oddaje ten sam Intent ze skrótu —
        // obsługujemy go tylko przy pierwszym uruchomieniu.
        if (savedInstanceState == null) handleShortcutIntent(intent)
        setContent {
            OmnisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel, scanRequests = scanRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_SCAN_ISBN) scanRequests++
    }

    companion object {
        // Musi odpowiadać akcji w res/xml/shortcuts.xml.
        const val ACTION_SCAN_ISBN = "com.theundefined.omnis.action.SCAN_ISBN"
    }
}
