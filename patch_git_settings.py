path = "app/src/main/java/com/renaealisha/viciousability/MainActivity.kt"
with open(path) as f:
    content = f.read()

def apply(old, new, label):
    global content
    if old not in content:
        print(f"NO MATCH: {label}")
        raise SystemExit(1)
    if content.count(old) > 1:
        print(f"MULTIPLE MATCHES: {label}")
        raise SystemExit(1)
    content = content.replace(old, new, 1)
    print(f"OK: {label}")

old1 = '''fun saveGroqKey(context: android.content.Context, key: String) {
    val trimmed = key.trim()
    val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    prefs.edit().putString(PREF_GROQ_KEY, trimmed).apply()
    try {
        groqKeyBackupFile.writeText(trimmed)
    } catch (e: Exception) {
        // No "All files access" yet, or storage otherwise unwritable — ignore.
    }
}'''
new1 = old1 + '''

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
}'''
apply(old1, new1, "storage functions")

old2 = '''fun askGroqForCommand(phrase: String, apiKey: String, onResult: (String?, String?) -> Unit) {
    Thread {
        var resultCommand: String? = null
        var resultError: String? = null
        try {
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You translate a spoken/typed request into a single exact " +
                        "Linux/Termux shell command that would accomplish it. " +
                        "Respond with ONLY the raw command on one line - no explanation, " +
                        "no markdown, no backticks, no preamble. If the request is too " +
                        "vague or unsafe to turn into a command, respond with exactly: UNSURE"
                )
            })'''
new2 = '''fun askGroqForCommand(
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
                            "chained with && on one line. "
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
            })'''
apply(old2, new2, "askGroqForCommand signature + prompt")

old3 = '''            var askAiKeyInput by remember { mutableStateOf("") }'''
new3 = old3 + '''

            val savedGitConfig = remember { loadGitConfig(context) }
            var gitEmail by remember { mutableStateOf(savedGitConfig.first ?: "") }
            var gitRepoPath by remember { mutableStateOf(savedGitConfig.second ?: "") }
            var showGitSettingsDialog by remember { mutableStateOf(false) }
            var gitEmailInput by remember { mutableStateOf(gitEmail) }
            var gitRepoPathInput by remember { mutableStateOf(gitRepoPath) }'''
apply(old3, new3, "state vars")

old4 = '''                                            askGroqForCommand(phrase, groqKey!!) { command, error ->'''
new4 = '''                                            askGroqForCommand(
                                                phrase,
                                                groqKey!!,
                                                gitEmail.ifBlank { null },
                                                gitRepoPath.ifBlank { null }
                                            ) { command, error ->'''
apply(old4, new4, "Ask AI call site")

old5 = '''                        Text("🔄 SYNC")
                    }
                }

                // ---- My Commands ----'''
new5 = '''                        Text("🔄 SYNC")
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
                                    "git config user.email \\"$gitEmail\\" && "
                                } else ""
                                val command = "cd \\"$gitRepoPath\\" && $emailCmd" +
                                    "git add -A && git commit -m \\"Auto commit from Vicious AI\\" && git push"
                                chatHistory.add("Auto commit → $gitRepoPath" to true)
                                val result = commandManager.runExact(command)
                                chatHistory.add(result.output to false)
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

                // ---- My Commands ----'''
apply(old5, new5, "GIT SETUP / AUTO COMMIT row")

old6 = '''                // ---- Teach / Edit Dialog ----
                if (showTeachDialog) {'''
new6 = '''                // ---- Git Settings Dialog ----
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
                if (showTeachDialog) {'''
apply(old6, new6, "Git Settings dialog")

with open(path, "w") as f:
    f.write(content)
print("ALL PATCHES APPLIED")
