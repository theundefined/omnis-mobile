package com.theundefined.omnis.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.theundefined.omnis.ui.normalizeIsbn

enum class IsbnScanError {
    NotIsbn,
    // Brak Google Play services albo moduł skanera (barcode_ui) jeszcze się nie pobrał — typowe
    // tuż po świeżej instalacji, stąd komunikat "spróbuj za chwilę".
    Unavailable
}

/**
 * Zwraca funkcję uruchamiającą skaner kodów Google (UI dostarcza Google Play services, więc
 * aplikacja nie potrzebuje uprawnienia CAMERA). Anulowanie skanowania przez użytkownika jest ciche
 * — nie wywołuje żadnego z callbacków.
 */
@Composable
fun rememberIsbnScanner(onIsbn: (String) -> Unit, onError: (IsbnScanError) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnIsbn by rememberUpdatedState(onIsbn)
    val currentOnError by rememberUpdatedState(onError)
    return remember(context) {
        val options =
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13)
                .allowManualInput()
                .enableAutoZoom()
                .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        val startScan: () -> Unit = {
            scanner
                .startScan()
                .addOnSuccessListener { barcode ->
                    val isbn = barcode.rawValue?.let(::normalizeIsbn)
                    if (isbn != null) currentOnIsbn(isbn) else currentOnError(IsbnScanError.NotIsbn)
                }
                .addOnFailureListener { currentOnError(IsbnScanError.Unavailable) }
        }
        startScan
    }
}
