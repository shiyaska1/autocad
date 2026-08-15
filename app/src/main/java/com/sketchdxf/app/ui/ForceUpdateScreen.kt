package com.sketchdxf.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.License

/**
 * Blocking screen shown when BootScreen's UpdateChecker finds the installed build below
 * update-config.json's minVersionCode — no back navigation, no way through except updating (this
 * is the whole point of a FORCED update, unlike a dismissible "update available" nag). There's no
 * Play Store listing yet (the app is sold device-locked over WhatsApp, see License), so the
 * primary button opens whatever URL update-config.json shipped (a direct APK link, a release
 * page, etc.) with the same WhatsApp contact as a fallback if that link doesn't work for them.
 */
@Composable
fun ForceUpdateScreen(message: String, updateUrl: String) {
    val context = LocalContext.current
    fun open(uri: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Update required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            message.ifBlank { "A new version is available. Please update to continue." },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Button(
            onClick = { open(updateUrl.ifBlank { License.BUY_URL }) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Update now") }

        Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Text(
                "If that link doesn't work, contact us for the latest version:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                License.SUPPORT_PHONE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { open(License.BUY_URL) },
                    modifier = Modifier.weight(1f)
                ) { Text("WhatsApp") }
                OutlinedButton(
                    onClick = { open("tel:${License.SUPPORT_PHONE}") },
                    modifier = Modifier.weight(1f)
                ) { Text("Call") }
            }
        }
    }
}
