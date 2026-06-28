package com.blissless.subsplease

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ExtensionBeaconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Do nothing. This only exists to be discoverable by the Main App.
    }
}