package com.example.rnshello

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat

class QrScanActivity : Activity() {

    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock to portrait
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Dark background
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            gravity = Gravity.CENTER
        }

        // Label
        root.addView(TextView(this).apply {
            text = "Scan Address QR Code"
            textSize = 16f
            setTextColor(Color.parseColor("#00d4ff"))
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 24)
        })

        // Scanner view — square, centred, ~300dp
        val sizePx = (resources.displayMetrics.density * 300).toInt()
        barcodeView = DecoratedBarcodeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).also {
                it.gravity = Gravity.CENTER
            }
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
        }
        root.addView(barcodeView)

        // Hint
        root.addView(TextView(this).apply {
            text = "Point camera at QR code"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        // Cancel button
        root.addView(android.widget.Button(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0f3460"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 32, 0, 0) }
            setOnClickListener { finish() }
        })

        setContentView(root)

        // Start scanning
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val scanned = result.text?.trim()?.replace("<", "")?.replace(">", "") ?: return
                val intent = Intent().putExtra("SCAN_RESULT", scanned)
                setResult(RESULT_OK, intent)
                finish()
            }
            override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>) {}
        })
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }
}
