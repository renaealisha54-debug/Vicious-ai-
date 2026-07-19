// Copyright (c) 2026 Alisha Bevis (renaealisha54-debug)
// Vicious Ability - Android APK

package com.renaealisha.viciousability

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ---- Vicious Ability dark theme ----
private val ViciousBackground = Color(0xFF0B0E14)
private val ViciousSurface = Color(0xFF161B22)
private val ViciousSurfaceVariant = Color(0xFF1F2630)
private val ViciousAccent = Color(0xFF00E5C7)
private val ViciousAccentDim = Color(0xFF0A8F7F)
private val ViciousTextPrimary = Color(0xFFE6EDF3)
private val ViciousTextSecondary = Color(0xFF8B949E)

private val ViciousColorScheme = darkColorScheme(
    background = ViciousBackground,
    surface = ViciousSurface,
    surfaceVariant = ViciousSurfaceVariant,
    primary = ViciousAccent,
    onPrimary = Color(0xFF002420),
    secondary = ViciousAccentDim,
    onBackground = ViciousTextPrimary,
    onSurface = ViciousTextPrimary,
    onSurfaceVariant = ViciousTextSecondary
)

private val ViciousShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ViciousAbilityApp(CommandManager(applicationContext))
        }
    }
}

@Composable
fun ViciousAbilityApp(commandManager: CommandManager) {
    MaterialTheme(colorScheme = ViciousColorScheme, shapes = ViciousShapes) {
        Surface(modifier = Modifier.fillMaxSize(), color = ViciousBackground) {
            val context = LocalContext.current
            var message by remember { mutableStateOf("") }
            val chatHistory = remember { mutableStateListOf<Pair<String, Boolean>>() } // text, isUser

            var taughtCommands by remember { mutableStateOf(commandManager.getAll()) }
            var showTeachDialog by remember { mutableStateOf(false) }
            var editingTrigger by remember { mutableStateOf<String?>(null) }

            fun launchVoiceMode() {
                try {
                    val intent = Intent()
                    intent.setClassName("com.termux", "com.termux.app.RunCommandService")
                    intent.action = "com.termux.RUN_COMMAND"
                    intent.putExtra(
                        "com.termux.RUN_COMMAND_PATH",
                        "/data/data/com.termux/files/home/ollama/vicious-projects/vicious-ability/run_vicious.sh"
                    )
                    intent.putExtra(
                        "com.termux.RUN_COMMAND_WORKDIR",
                        "/data/data/com.termux/files/home/ollama/vicious-projects/vicious-ability"
                    )
                    intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                    intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                    context.startService(intent)
                } catch (e: SecurityException) {
                    Toast.makeText(
                        context,
                        "Permission denied by Termux — check 'allow-external-apps=true' in ~/.termux/termux.properties",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Voice Mode failed: ${e.javaClass.simpleName} - ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            val runCommandPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    launchVoiceMode()
                } else {
                    Toast.makeText(
                        context,
                        "RUN_COMMAND permission denied — Voice Mode can't start Termux",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // ---- Header ----
                Column(modifier = Modifier.padding(bottom = 20.dp)) {
                    Text(
                        "🤖 VICIOUS ABILITY",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ViciousAccent
                    )
                    Text(
                        "Voice-first assistant",
                        style = MaterialTheme.typography.bodySmall,
                        color = ViciousTextSecondary
                    )
                }

                // ---- Action buttons ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                "com.termux.permission.RUN_COMMAND"
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                launchVoiceMode()
                            } else {
                                runCommandPermissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ViciousAccent,
                            contentColor = Color(0xFF002420)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🚀 VOICE MODE")
                    }
                    OutlinedButton(
                        onClick = {
                            editingTrigger = null
                            showTeachDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ViciousAccent
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("+ TEACH")
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            !Environment.isExternalStorageManager()
                        ) {
                            Toast.makeText(
                                context,
                                "Grant 'Allow access to manage all files' for Vicious Ability, then tap Sync again",
                                Toast.LENGTH_LONG
                            ).show()
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            commandManager.exportPublicMirror()
                            Toast.makeText(
                                context,
                                "Synced ${taughtCommands.size} command(s) to ~/storage/downloads/vicious_commands.json",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ViciousAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    Text("🔄 SYNC TO TERMUX")
                }

                // ---- My Commands ----
                Text(
                    "COMMANDS (${taughtCommands.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = ViciousTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(taughtCommands.entries.toList()) { (trigger, command) ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ViciousSurface),
                            shape = ViciousShapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(trigger, color = ViciousTextPrimary, style = MaterialTheme.typography.bodyLarge)
                                    Text(command, color = ViciousTextSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                                Row {
                                    TextButton(onClick = {
                                        editingTrigger = trigger
                                        showTeachDialog = true
                                    }) {
                                        Text("Edit", color = ViciousAccent)
                                    }
                                    TextButton(onClick = {
                                        commandManager.forget(trigger)
                                        taughtCommands = commandManager.getAll()
                                    }) {
                                        Text("Delete", color = Color(0xFFFF6B6B))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ---- Chat History (bubble style) ----
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chatHistory.forEach { (msg, isUser) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUser) ViciousAccentDim else ViciousSurfaceVariant
                                ),
                                shape = ViciousShapes.medium,
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Text(
                                    msg,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    color = if (isUser) Color(0xFF002420) else ViciousTextPrimary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ---- Input Area ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Speak or type command...", color = ViciousTextSecondary) },
                        shape = ViciousShapes.medium,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = ViciousSurface,
                            unfocusedContainerColor = ViciousSurface,
                            focusedIndicatorColor = ViciousAccent,
                            unfocusedIndicatorColor = ViciousSurfaceVariant,
                            focusedTextColor = ViciousTextPrimary,
                            unfocusedTextColor = ViciousTextPrimary,
                            cursorColor = ViciousAccent
                        )
                    )
                    Button(
                        onClick = {
                            if (message.isNotBlank()) {
                                chatHistory.add(message to true)

                                val matchedTrigger = commandManager.findMatchingTrigger(message)
                                if (matchedTrigger != null) {
                                    chatHistory.add("Running '$matchedTrigger'..." to false)
                                    val result = commandManager.execute(matchedTrigger)
                                    chatHistory.add(result.output to false)
                                } else {
                                    chatHistory.add("I don't know that command yet. Tap + Teach to add it." to false)
                                }

                                message = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ViciousAccent,
                            contentColor = Color(0xFF002420)
                        ),
                        shape = ViciousShapes.medium
                    ) {
                        Text("Send")
                    }
                }

                // ---- Teach / Edit Dialog ----
                if (showTeachDialog) {
                    val isEditing = editingTrigger != null

                    var triggerInput by remember(editingTrigger) {
                        mutableStateOf(editingTrigger ?: "")
                    }
                    var commandInput by remember(editingTrigger) {
                        mutableStateOf(editingTrigger?.let { commandManager.lookup(it) } ?: "")
                    }

                    AlertDialog(
                        containerColor = ViciousSurface,
                        onDismissRequest = {
                            showTeachDialog = false
                            editingTrigger = null
                        },
                        title = {
                            Text(
                                if (isEditing) "Edit command" else "Teach a command",
                                color = ViciousTextPrimary
                            )
                        },
                        text = {
                            Column {
                                TextField(
                                    value = triggerInput,
                                    onValueChange = { triggerInput = it },
                                    label = { Text("Keyword / trigger phrase") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = ViciousSurfaceVariant,
                                        unfocusedContainerColor = ViciousSurfaceVariant,
                                        focusedIndicatorColor = ViciousAccent,
                                        cursorColor = ViciousAccent
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextField(
                                    value = commandInput,
                                    onValueChange = { commandInput = it },
                                    label = { Text("Exact command or file path") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = ViciousSurfaceVariant,
                                        unfocusedContainerColor = ViciousSurfaceVariant,
                                        focusedIndicatorColor = ViciousAccent,
                                        cursorColor = ViciousAccent
                                    )
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (triggerInput.isNotBlank() && commandInput.isNotBlank()) {
                                    if (isEditing) {
                                        commandManager.edit(editingTrigger!!, triggerInput, commandInput)
                                    } else {
                                        commandManager.teach(triggerInput, commandInput)
                                    }
                                    taughtCommands = commandManager.getAll()
                                    showTeachDialog = false
                                    editingTrigger = null
                                }
                            }) {
                                Text("Save", color = ViciousAccent)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showTeachDialog = false
                                editingTrigger = null
                            }) {
                                Text("Cancel", color = ViciousTextSecondary)
                            }
                        }
                    )
                }
            }
        }
    }
}

