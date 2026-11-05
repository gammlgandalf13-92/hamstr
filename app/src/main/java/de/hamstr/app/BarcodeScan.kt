package de.hamstr.app

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Barcode-Scanner von Google (läuft in den Google-Play-Diensten).
 * Braucht keine Kamera-Berechtigung in Hamstr – die Kamera-Ansicht stellt Google bereit.
 */
object BarcodeScan {
    fun start(context: Context, onCode: (String) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .enableAutoZoom()
            .build()
        try {
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    val code = Catalog.normalizeBarcode(barcode.rawValue.orEmpty())
                    if (code != null) onCode(code) else onError("Das war kein Produkt-Barcode (EAN).")
                }
                .addOnCanceledListener { /* Nutzer hat abgebrochen – nichts tun */ }
                .addOnFailureListener { e ->
                    onError(
                        if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE)
                            "Der Scanner wird gerade noch geladen – bitte gleich noch einmal versuchen."
                        else
                            "Scanner nicht verfügbar. Du kannst die Nummer unter dem Barcode auch ins Suchfeld tippen."
                    )
                }
        } catch (e: Exception) {
            onError("Scanner nicht verfügbar. Du kannst die Nummer unter dem Barcode auch ins Suchfeld tippen.")
        }
    }
}
