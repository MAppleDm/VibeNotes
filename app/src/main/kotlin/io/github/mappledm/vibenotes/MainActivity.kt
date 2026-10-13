package io.github.mappledm.vibenotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.mappledm.vibenotes.ui.VibeNotesRoot

class MainActivity : ComponentActivity() {
    private val container get() = (application as VibeNotesApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) consumeShare(intent)
        setContent { VibeNotesRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeShare(intent)
    }

    private fun consumeShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val combined = listOf(subject, text).filter { it.isNotEmpty() }.distinct().joinToString("\n")
        if (combined.isNotEmpty()) container.pendingShare.value = combined
    }
}
