// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** Only a success flag crosses Activity results; credentials never enter Intent extras or saved state. */
object ScanInbox {
    private var value: String? = null
    @Synchronized fun put(text: String) { value = text }
    @Synchronized fun take(): String? = value.also { value = null }
}

class WgScanActivity : ComponentActivity() {
    private lateinit var scanner: DecoratedBarcodeView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) scanner.resume() else { setResult(Activity.RESULT_CANCELED); finish() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scanner = DecoratedBarcodeView(this).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("将 WireGuard 配置二维码放入框内")
            decodeSingle(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    val value = result.text
                    if (value.toByteArray().size <= WireGuardImport.MAX_BYTES) {
                        ScanInbox.put(value)
                        setResult(Activity.RESULT_OK)
                    } else setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            })
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(TextView(context).apply {
                text = "扫描 WireGuard 配置"; textSize = 20f; setTextColor(Color.WHITE)
                setPadding(24, 24, 24, 16)
            })
            addView(scanner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(Button(context).apply { text = "取消"; setOnClickListener { finish() } })
        }
        setContentView(layout)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.CAMERA)
    }
    override fun onResume() {
        super.onResume()
        if (::scanner.isInitialized && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner.resume()
    }
    override fun onPause() { if (::scanner.isInitialized) scanner.pause(); super.onPause() }
}
