package com.mohamed.dev.ui.imorpher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseScreen
import com.mohamed.dev.ui.imorpher.ui.theme.IMorpherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IMorpherTheme {
                ShowcaseScreen()
            }
        }
    }
}
