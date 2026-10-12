package com.wristview.wear

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.wristview.wear.ui.WatchCameraScreen
import com.wristview.wear.ui.WatchCameraViewModel

class WatchMainActivity : ComponentActivity() {

    private val viewModel: WatchCameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the watch display awake while using the viewfinder
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            WatchCameraScreen(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.connectToTargetIp()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnectAndSleep()
    }
}
