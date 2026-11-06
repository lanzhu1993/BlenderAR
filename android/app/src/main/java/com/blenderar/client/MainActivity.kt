package com.blenderar.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.blenderar.client.ui.AppRoot
import com.blenderar.client.ui.theme.BlenderARTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlenderARTheme {
                AppRoot()
            }
        }
    }
}
