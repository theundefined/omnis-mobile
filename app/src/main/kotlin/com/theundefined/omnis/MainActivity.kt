package com.theundefined.omnis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmnisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}
