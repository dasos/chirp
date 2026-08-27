package com.chirp.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Permissions needed to run a hands-free session (mic + BT routing on 31+). */
val conversationPermissions: Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
}.toTypedArray()

fun Context.hasMicPermission(): Boolean = hasPermission(Manifest.permission.RECORD_AUDIO)

/** Shows an on-device STT error message for the user. */
fun Context.bluetoothPermissionNeeded(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

/**
 * If we are on Android 12+ and BLUETOOTH_CONNECT is not yet granted, show a
 * one-time rationale dialog before the system permission prompt.
 */
@Composable
fun RequestBluetoothRationale(onConfirmed: () -> Unit) {
    val context = LocalContext.current
    if (!context.bluetoothPermissionNeeded()) {
        onConfirmed()
        return
    }
    var showDialog by remember { mutableStateOf(true) }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Bluetooth permission needed") },
            text = {
                Text(
                    "Chirp uses Bluetooth to route audio through your headset for " +
                        "hands-free conversations. The \"Nearby Devices\" permission is " +
                        "required to connect to Bluetooth headsets on this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false; onConfirmed() }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Not now")
                }
            },
        )
    }
}

/** Requests POST_NOTIFICATIONS once at startup (Android 13+). */
@Composable
fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored; the notification is best-effort */ }
    LaunchedEffect(Unit) {
        if (!context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
