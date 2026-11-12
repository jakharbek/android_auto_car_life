package com.jakharbek.monzaauto

import android.app.Activity
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * Receives "head unit attached" from the system, starts the bridge and closes at once, without
 * disturbing whatever screen of the app is open (the setup wizard, for example).
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.dir = getExternalFilesDir("logs")
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            @Suppress("DEPRECATION")
            val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            Log.i("UI: accessory attached")
            LinkService.attach(this, acc)
        }
        finish()
    }
}
