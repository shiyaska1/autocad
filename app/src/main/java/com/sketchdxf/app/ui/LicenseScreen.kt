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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.AppPrefs
import com.sketchdxf.app.data.License

/** Blocking screen shown after the trial ends (or a renewal falls due) until a valid key is entered. */
@Composable
fun LicenseScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val deviceId = remember { License.deviceId(context) }
    val due = remember { License.dueMilestone(prefs.installDateMillis).coerceAtLeast(1) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun open(uri: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (due <= 1) "Trial ended" else "Renewal due",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
        )
        Text(
            if (due <= 1)
                "Your ${License.TRIAL_DAYS}-day trial has finished. Send us your Device ID below to get an activation key, then enter it here."
            else
                "This licence covers up to $due months. Send us your Device ID and tell us it is the $due-month renewal, then enter the key you get back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Text("Your Device ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(deviceId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            if (due <= 1) "First activation" else "$due-month renewal",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(deviceId)) },
            modifier = Modifier.padding(top = 4.dp)
        ) { Text("Copy Device ID") }

        OutlinedTextField(
            value = key,
            onValueChange = { key = it; error = null },
            label = { Text("Activation key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = {
                if (License.isValid(deviceId, key, due)) {
                    prefs.licensed = true
                    prefs.licensedMilestone = due
                    onActivated()
                } else error = "Invalid activation key for the $due-month renewal"
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text("Activate") }
        Button(
            onClick = { open(License.buyUrlFor(deviceId)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Buy app on WhatsApp") }

        Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Text(
                "To buy or renew, contact us for a licence key:",
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
            Text(
                License.SUPPORT_EMAIL,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { open(License.buyUrlFor(deviceId)) },
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
