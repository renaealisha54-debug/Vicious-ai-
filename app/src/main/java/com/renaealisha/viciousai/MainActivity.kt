// app/src/main/java/com/renaealisha/viciousai/MainActivity.kt
// Full updated file - overwrite the existing one at this path in your repo.
// Changes: auto-detects a git repo under ~/ollama/vicious-projects/ so
// GIT SETUP no longer has to be filled in manually, and the AI now
// defaults 'update repo' / 'sync my code' to stage+commit+push instead
// of returning UNSURE.

// Copyright (c) 2026 Alisha Bevis (renaealisha54-debug)
// Vicious AI - Android APK

package com.renaealisha.viciousai

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ---- Groq API key storage (app-private SharedPreferences, never in git) ----
private const val PREFS_NAME = "vicious_ai_prefs"
private const val PREF_GROQ_KEY = "groq_api_key"

// Backup copy in public storage so the key survives an app uninstall/reinstall
// (e.g. after a debug signing key change forces a fresh install, which wipes
// private SharedPreferences). Requires "All files access"; silently no-ops
// otherwise, same as CommandManager's exportPublicMirror.
private val groqKeyBackupFile: File
    get() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        ".vicious_groq_key"
    )

fun loadGroqKey(context: android.content.Context): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    prefs.getString(PREF_GROQ_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }

    // Private prefs are empty — fall back to the public backup copy and
    // restore it into prefs so future launches skip this check.
    return try {
        groqKeyBackupFile.takeIf { it.exists() }
            ?.readText()?.trim()?.takeIf { it.isNotBlank() }
            ?.also { restored -> prefs.edit().putString(PREF_GROQ_KEY, restored).apply() }
    } catch (e: Exception) {
        null
    }
}

fun saveGroqKey(context: android.content.Context, key: String) {
    val trimmed = key.trim()
    val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    prefs.edit().putString(PREF_GROQ_KEY, trimmed).apply()
    try {
        groqKeyBackupFile.writeText(trimmed)
    } catch (e: Exception) {
        // No "All files access" yet, or storage otherwise unwritable — ignore.
    }
}

// ---- Git identity + repo path (used to build commit/push commands) ----
private const val PREF_GIT_EMAIL = "git_email"
private const val PREF_GIT_REPO_PATH = "git_repo_path"

fun loadGitConfig(context: android.content.Context): Pair<String?, String?> {
    val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val email = prefs.getString(PREF_GIT_EMAIL, null)?.takeIf { it.isNotBlank() }
    val repoPath = prefs.getString(PREF_GIT_REPO_PATH, null)?.takeIf { it.isNotBlank() }
    return email to repoPath
}

fun saveGitConfig(context: android.content.Context, email: String, repoPath: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    prefs.edit()
        .putString(PREF_GIT_EMAIL, email.trim())
        .putString(PREF_GIT_REPO_PATH, repoPath.trim())
        .apply()
}

/**
 * Scans the known Termux projects directory for git repos (folders
 * containing a .git subfolder) so Ask AI has a repo path to work with
 * without the user having to open GIT SETUP and type it in manually.
 * Returns the first repo found, or null if the projects directory
 * doesn't exist yet or has no git repos in it.
 * If more than one repo is found, all candidates are returned so the
 * caller can decide (e.g. prefer the previously-saved one).
 */
fun autoDetectGitRepos(): List<String> {
    val projectsRoot = java.io.File(
        "/data/data/com.termux/files/home/ollama/vicious-projects"
    )
    if (!projectsRoot.isDirectory) return emptyList()
    return projectsRoot.listFiles { f -> f.isDirectory }
        ?.filter { java.io.File(it, ".git").isDirectory }
        ?.map { it.absolutePath }
        ?: emptyList()
}

/**
 * Resolves the repo path to use for Ask AI: the saved one if present,
 * otherwise the first auto-detected repo. Also persists an
 * auto-detected path so future launches don't need to re-scan or ask.
 */
fun resolveGitRepoPath(context: android.content.Context, savedPath: String?): String? {
    if (!savedPath.isNullOrBlank()) return savedPath
    val detected = autoDetectGitRepos().firstOrNull() ?: return null
    val (email, _) = loadGitConfig(context)
    saveGitConfig(context, email ?: "", detected)
    return detected
}

/**
 * Asks Groq (Llama 3.3 70B) to translate a phrase into an exact shell
 * command, same system prompt/behavior as the Python CLI's version so the
 * two stay consistent. Runs the network call on a background thread and
 * delivers the result back on the main thread via onResult.
 */
fun askGroqForCommand(
    phrase: String,
    apiKey: String,
    gitEmail: String? = null,
    gitRepoPath: String? = null,
    onResult: (String?, String?) -> Unit
) {
    Thread {
        var resultCommand: String? = null
        var resultError: String? = null
        try {
            val systemPrompt = buildString {
                append(
                    "You translate a spoken/typed request into a single exact " +
                        "Linux/Termux shell command that would accomplish it. "
                )
                if (!gitRepoPath.isNullOrBlank()) {
                    append("The user's active git repo is at $gitRepoPath")
                    if (!gitEmail.isNullOrBlank()) {
                        append(", and their git commit email is $gitEmail")
                    }
                    append(
                        ". For any git commit or push request, cd into that repo, " +
                            "set the commit identity with 'git config user.email' " +
                            "if an email is given, then run the requested git command(s), " +
                            "chained with && on one line. " +
                            "If the request is a generic phrase like 'update repo', " +
                            "'update the repo', or 'sync my code' with no further detail, " +
                            "default to: stage all changes, commit with a short generic " +
                            "message such as 'update', then push - do not respond UNSURE " +
                            "just because no commit message was given. "
                    )
                }
                append(
                    "Respond with ONLY the raw command on one line - no explanation, " +
                        "no markdown, no backticks, no preamble. If the request is too " +
                        "vague or unsafe to turn into a command, respond with exactly: UNSURE"
                )
            }
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", phrase)
            })
            val body = JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("max_tokens", 200)
                put("messages", messages)
            }

            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                resultCommand = if (content == "UNSURE") null else content
                if (content == "UNSURE") resultError = "Couldn't come up with a safe command for that."
            } else {
                val errText = conn.errorStream?.bufferedReader()?.readText() ?: ""
                resultError = "API error ${conn.responseCode}: ${errText.take(200)}"
            }
        } catch (e: Exception) {
            resultError = "Couldn't reach the API: ${e.message}"
        }
        Handler(Looper.getMainLooper()).post {
            onResult(resultCommand, resultError)
        }
    }.start()
}

/**
 * Hands a command off to Termux to actually run it (git, python, and other
 * dev tools only exist in Termux's own filesystem, not this app's sandbox).
 * Writes the command to a script in the public Downloads folder — the one
 * location both this app and Termux can both reach — then fires Termux's
 * RUN_COMMAND intent to execute it there in the background. Requires the
 * com.termux.permission.RUN_COMMAND permission, same as Voice Mode.
 */
fun executeInTermux(context: android.content.Context, command: String) {
    try {
        // Public storage (FUSE-emulated on modern Android) doesn't support the
        // Unix executable bit, so a script written there can never be marked
        // executable. Instead, launch Termux's own bash (which IS executable,
        // since it lives in Termux's private storage) and pass the script as
        // an argument — bash doesn't care whether its argument is executable.
        val scriptFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "vicious_run.sh"
        )
        scriptFile.writeText("$command\n")

        val intent = Intent()
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.action = "com.termux.RUN_COMMAND"
        intent.putExtra(
            "com.termux.RUN_COMMAND_PATH",
            "/data/data/com.termux/files/usr/bin/bash"
        )
        intent.putExtra(
            "com.termux.RUN_COMMAND_ARGUMENTS",
            arrayOf("/data/data/com.termux/files/home/storage/downloads/vicious_run.sh")
        )
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        context.startService(intent)
        Toast.makeText(context, "Running in Termux…", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(
            context,
            "Permission denied by Termux — check 'allow-external-apps=true' in ~/.termux/termux.properties",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't run in Termux: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ---- Vicious AI dark theme ----
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
            ViciousAiApp(CommandManager(applicationContext))
        }
    }
}

@Composable
fun ViciousAiApp(commandManager: CommandManager) {
    MaterialTheme(colorScheme = ViciousColorScheme, shapes = ViciousShapes) {
        Surface(modifier = Modifier.fillMaxSize(), color = ViciousBackground) {
            val context = LocalContext.current
            var message by remember { mutableStateOf("") }
            val chatHistory = remember { mutableStateListOf<Pair<String, Boolean>>() } // text, isUser

            var taughtCommands by remember { mutableStateOf(commandManager.getAll()) }
            var showTeachDialog by remember { mutableStateOf(false) }
            var editingTrigger by remember { mutableStateOf<String?>(null) }

            var groqKey by remember { mutableStateOf(loadGroqKey(context)) }
            var showAskAiDialog by remember { mutableStateOf(false) }
            var askAiPhrase by remember { mutableStateOf("") }
            var askAiSuggestion by remember { mutableStateOf<String?>(null) }
            var askAiError by remember { mutableStateOf<String?>(null) }
            var askAiLoading by remember { mutableStateOf(false) }
            var askAiKeyInput by remember { mutableStateOf("") }

            val savedGitConfig = remember { loadGitConfig(context) }
            var gitEmail by remember { mutableStateOf(savedGitConfig.first ?: "") }
            // Falls back to auto-detecting a repo under vicious-projects/ so
            // Ask AI works without a manual GIT SETUP visit; the detected
            // path is persisted so this only scans once.
            var gitRepoPath by remember {
                mutableStateOf(resolveGitRepoPath(context, savedGitConfig.second) ?: "")
            }
            var showGitSettingsDialog by remember { mutableStateOf(false) }
            var gitEmailInput by remember { mutableStateOf(gitEmail) }
            var gitRepoPathInput by remember { mutableStateOf(gitRepoPath) }

            fun launchVoiceMode() {
                try {
                    val intent = Intent()
                    intent.setClassName("com.termux", "com.termux.app.RunCommandService")
                    intent.action = "com.termux.RUN_COMMAND"
                    intent.putExtra(
                        "com.termux.RUN_COMMAND_PATH",
                        "/data/data/com.termux/files/home/ollama/vicious-projects/vicious-ai/run_vicious.sh"
                    )
                    intent.putExtra(
                        "com.termux.RUN_COMMAND_WORKDIR",
                        "/data/data/com.termux/files/home/ollama/vicious-projects/vicious-ai"
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

            var pendingTermuxCommand by remember { mutableStateOf<String?>(null) }
            val termuxRunPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                val cmd = pendingTermuxCommand
                pendingTermuxCommand = null
                if (granted && cmd != null) {
                    executeInTermux(context, cmd)
                } else if (!granted) {
                    Toast.makeText(
                        context,
                        "RUN_COMMAND permission denied — can't run in Termux",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            fun runInTermux(command: String) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    "com.termux.permission.RUN_COMMAND"
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    executeInTermux(context, command)
                } else {
                    pendingTermuxCommand = command
                    termuxRunPermissionLauncher.launch("com.termux.permission.RUN_COMMAND")
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
                        "🤖 VICIOUS AI",
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            askAiPhrase = ""
                            askAiSuggestion = null
                            askAiError = null
                            askAiKeyInput = groqKey ?: ""
                            showAskAiDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ViciousAccent
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🧠 ASK AI")
                    }
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                !Environment.isExternalStorageManager()
                            ) {
                                Toast.makeText(
                                    context,
                                    "Grant 'Allow access to manage all files' for Vicious AI, then tap Sync again",
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
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔄 SYNC")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            gitEmailInput = gitEmail
                            gitRepoPathInput = gitRepoPath
                            showGitSettingsDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ViciousAccent
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⚙️ GIT SETUP")
                    }
                    OutlinedButton(
                        onClick = {
                            if (gitRepoPath.isBlank()) {
                                Toast.makeText(
                                    context,
                                    "Set a repo path in GIT SETUP first",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                val emailCmd = if (gitEmail.isNotBlank()) {
                                    "git config user.email \"$gitEmail\" && "
                                } else ""
                                val command = "cd \"$gitRepoPath\" && $emailCmd" +
                                    "git add -A && git commit -m \"Auto commit from Vicious AI\" && git push"
                                runInTermux(command)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ViciousAccent
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📤 AUTO COMMIT")
                    }
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

                // ---- Git Settings Dialog ----
                if (showGitSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showGitSettingsDialog = false },
                        title = { Text("Git Setup", color = ViciousAccent) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    "Used to build commit/push commands from Ask AI and the Auto Commit button.",
                                    color = ViciousTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                OutlinedTextField(
                                    value = gitRepoPathInput,
                                    onValueChange = { gitRepoPathInput = it },
                                    label = { Text("Repo path") },
                                    placeholder = { Text("/data/data/com.termux/files/home/Vicious-ai-") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = gitEmailInput,
                                    onValueChange = { gitEmailInput = it },
                                    label = { Text("Git commit email") },
                                    placeholder = { Text("you@example.com") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                gitEmail = gitEmailInput.trim()
                                gitRepoPath = gitRepoPathInput.trim()
                                saveGitConfig(context, gitEmail, gitRepoPath)
                                showGitSettingsDialog = false
                            }) {
                                Text("Save", color = ViciousAccent)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showGitSettingsDialog = false }) {
                                Text("Cancel", color = ViciousTextSecondary)
                            }
                        }
                    )
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
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

                if (showAskAiDialog) {
                    AlertDialog(
                        onDismissRequest = { showAskAiDialog = false },
                        containerColor = ViciousSurface,
                        title = {
                            Text(
                                if (groqKey == null) "Set Groq API Key" else "🧠 Ask AI",
                                color = ViciousAccent
                            )
                        },
                        text = {
                            Column {
                                if (groqKey == null) {
                                    Text(
                                        "Paste your Groq API key (starts with gsk_). Stored only on this device.",
                                        color = ViciousTextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    OutlinedTextField(
                                        value = askAiKeyInput,
                                        onValueChange = { askAiKeyInput = it },
                                        label = { Text("gsk_...") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = askAiPhrase,
                                        onValueChange = { askAiPhrase = it },
                                        label = { Text("What do you want to do?") },
                                        placeholder = { Text("push all termux work to github") },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !askAiLoading
                                    )
                                    if (askAiLoading) {
                                        Text(
                                            "Thinking...",
                                            color = ViciousTextSecondary,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    askAiSuggestion?.let { suggestion ->
                                        Text(
                                            "Suggested: $suggestion",
                                            color = ViciousAccent,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp)
                                        )
                                        Text(
                                            "Tap Run Now to execute this in Termux right away, or Save as Command to reuse it later.",
                                            color = ViciousTextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    askAiError?.let { err ->
                                        Text(
                                            err,
                                            color = Color(0xFFFF6B6B),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            if (groqKey == null) {
                                TextButton(onClick = {
                                    if (askAiKeyInput.isNotBlank()) {
                                        saveGroqKey(context, askAiKeyInput)
                                        groqKey = askAiKeyInput.trim()
                                        askAiKeyInput = ""
                                    }
                                }) {
                                    Text("Save Key", color = ViciousAccent)
                                }
                            } else if (askAiSuggestion == null) {
                                TextButton(
                                    onClick = {
                                        val phrase = askAiPhrase.trim()
                                        if (phrase.isNotBlank()) {
                                            askAiLoading = true
                                            askAiError = null
                                            askGroqForCommand(
                                                phrase,
                                                groqKey!!,
                                                gitEmail.ifBlank { null },
                                                gitRepoPath.ifBlank { null }
                                            ) { command, error ->
                                                askAiLoading = false
                                                askAiSuggestion = command
                                                askAiError = error
                                            }
                                        }
                                    },
                                    enabled = !askAiLoading && askAiPhrase.isNotBlank()
                                ) {
                                    Text("Ask", color = ViciousAccent)
                                }
                            } else {
                                Row {
                                    TextButton(onClick = {
                                        runInTermux(askAiSuggestion!!)
                                        showAskAiDialog = false
                                    }) {
                                        Text("▶ Run Now", color = ViciousAccent)
                                    }
                                    TextButton(onClick = {
                                        commandManager.teach(askAiPhrase.trim(), askAiSuggestion!!)
                                        taughtCommands = commandManager.getAll()
                                        Toast.makeText(
                                            context,
                                            "Saved. Say it from the CLI or tap Sync to push it to Termux now.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        showAskAiDialog = false
                                    }) {
                                        Text("Save as Command", color = ViciousAccent)
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAskAiDialog = false }) {
                                Text("Cancel", color = ViciousTextSecondary)
                            }
                        }
                    )
                }
            }
        }
    }
}

