package dev.astra.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

val Bg = Color(0xFF0D0F13)
val CardBg = Color(0xFF181B21)
val Line = Color(0xFF2A2F38)
val Muted = Color(0xFF9AA1AD)
val Accent = Color(0xFF4F7CFF)
val Danger = Color(0xFFE5484D)
val Ok = Color(0xFF3FBF7F)

@Composable
fun AstraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.White, background = Bg, surface = CardBg,
            onBackground = Color(0xFFE8EAEE), onSurface = Color(0xFFE8EAEE), error = Danger,
            surfaceVariant = CardBg, outline = Line,
        ),
        content = content,
    )
}

/** A screen with an optional "< back" and one column of content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstraScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
        bottomBar = bottom,
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            content = content,
        )
    }
}

/** Centered message card used by the E-states and B4: plain words + one recovery action. */
@Composable
fun MessageState(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    top: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            top()
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(body, color = Muted, textAlign = TextAlign.Center)
            if (primary != null) {
                Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(primary.first) }
            }
            if (secondary != null) {
                OutlinedButton(
                    onClick = secondary.second, modifier = Modifier.fillMaxWidth().height(50.dp),
                    border = BorderStroke(1.dp, Line),
                ) { Text(secondary.first) }
            }
        }
    }
}

fun lastSeenText(iso: String?): String {
    if (iso == null) return "Never seen"
    return try {
        val seen = java.time.Instant.parse(iso)
        val secs = java.time.Duration.between(seen, java.time.Instant.now()).seconds
        when {
            secs < 60 -> "Last seen just now"
            secs < 3600 -> "Last seen ${secs / 60} min ago"
            secs < 86400 -> "Last seen ${secs / 3600} h ago"
            else -> "Last seen ${secs / 86400} d ago"
        }
    } catch (e: Exception) {
        "Last seen $iso"
    }
}

/** A labelled button action for MessageState; the lambda's result is ignored. */
fun action(label: String, onClick: () -> Unit): Pair<String, () -> Unit> = label to onClick
