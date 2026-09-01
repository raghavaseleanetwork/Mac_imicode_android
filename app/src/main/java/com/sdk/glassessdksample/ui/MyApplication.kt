package com.sdk.glassessdksample.ui

import android.app.Application
import android.content.Context
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import java.io.File
import kotlin.properties.Delegates

class MyApplication : Application() {

    var hardwareVersion: String = ""
    var firmwareVersion: String = ""

    override fun onCreate() {
        super.onCreate()
        CONTEXT = applicationContext
        instance = this
        // The glasses can call a browser tool before any screen has opened.
        com.sdk.glassessdksample.ui.web.GlassBrowserEngine.init(applicationContext)
        // Install a defensive uncaught-exception handler to prevent library parsing bugs
        // from crashing the app. We only swallow specific ArrayIndexOutOfBoundsException
        // instances originating from the glasses parser to keep the app running.
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
                try {
                    // If this is the known parser index issue, and stack mentions GlassModelControlResponse,
                    // swallow it quietly to avoid crashing the app and flooding logs (library bug).
                    if (ex is ArrayIndexOutOfBoundsException) {
                        val mentionsParser = ex.stackTrace?.any { it.className.contains("GlassModelControlResponse") || it.className.contains("LargeDataParser") } ?: false
                        if (mentionsParser) {
                            Log.w("MyApplication", "Swallowed Glass parser ArrayIndexOutOfBoundsException (suppressed stacktrace)")
                            return@setDefaultUncaughtExceptionHandler
                        }
                    }

                    // For all other exceptions, log and forward to previous handler
                    Log.e("MyApplication", "Uncaught exception on thread ${'$'}{thread.name}", ex)
                } catch (handlerEx: Throwable) {
                    // If our handler itself fails, fallthrough to previous handler below
                    Log.e("MyApplication", "Error in custom uncaught exception handler", handlerEx)
                }

                // Forward other exceptions to the previous handler (or let system handle)
                previous?.uncaughtException(thread, ex)
            }
        } catch (e: Exception) {
            Log.w("MyApplication", "Could not install custom uncaught exception handler", e)
        }

        initBle()
    }

    private fun initBle() {
        // Initialize Smart Glass BLE system
        LargeDataHandler.getInstance()
        BleOperateManager.getInstance(this)
        BleOperateManager.getInstance().setApplication(this)
        BleOperateManager.getInstance().init()

        initReceiver()

        val intentFilter = BleAction.getIntentFilter()
        val myBleReceiver = MyBluetoothReceiver()
        LocalBroadcastManager.getInstance(CONTEXT)
            .registerReceiver(myBleReceiver, intentFilter)

        BleBaseControl.getInstance(CONTEXT).setmContext(this)
    }

    private fun initReceiver() {
        // Reserved for any Bluetooth events we add later
    }

    fun getAlbumDirFile(): File {
        val dir = File(getAppRootFile(CONTEXT), "DCIM_1")

        if (!dir.exists()) {
            dir.mkdirs()   // **CRITICAL FIX**
        }
        return dir
    }

    fun getAppRootFile(context: Context): File {
        return if (context.getExternalFilesDir("") != null) {
            context.getExternalFilesDir("")!!
        } else {
            val externalSaveDir = context.externalCacheDir
            externalSaveDir ?: context.cacheDir
        }
    }

    companion object {
        var CONTEXT: Context by Delegates.notNull()
        lateinit var instance: MyApplication
    }
}
