package com.kjkao.contextautomator.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScanResultReceiver(
    private val onScanResult: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
            onScanResult.invoke()
        }
    }
}

