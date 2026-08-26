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

old1 = '''        Handler(Looper.getMainLooper()).post {
            onResult(resultCommand, resultError)
        }
    }.start()
}'''
new1 = old1 + '''

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
        val scriptFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "vicious_run.sh"
        )
        scriptFile.writeText("#!/data/data/com.termux/files/usr/bin/bash\\n$command\\n")
        scriptFile.setExecutable(true)

        val intent = Intent()
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.action = "com.termux.RUN_COMMAND"
        intent.putExtra(
            "com.termux.RUN_COMMAND_PATH",
            "/data/data/com.termux/files/home/storage/downloads/vicious_run.sh"
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
}'''
apply(old1, new1, "executeInTermux() function")

old2 = '''            val runCommandPermissionLauncher = rememberLauncherForActivityResult(
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
            }'''
new2 = old2 + '''

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
            }'''
apply(old2, new2, "runInTermux() wrapper + permission launcher")

old3 = '''                            if (gitRepoPath.isBlank()) {
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
                            }'''
new3 = '''                            if (gitRepoPath.isBlank()) {
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
                                runInTermux(command)
                            }'''
apply(old3, new3, "AUTO COMMIT runs in Termux")

old4 = '''                                        Text(
                                            "This will be saved as a taught command. Run it from the CLI (Termux), not from this app, since dev tools like git only exist there.",
                                            color = ViciousTextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )'''
new4 = '''                                        Text(
                                            "Tap Run Now to execute this in Termux right away, or Save as Command to reuse it later.",
                                            color = ViciousTextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )'''
apply(old4, new4, "Ask AI hint text")

old5 = '''                            } else {
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
                            }'''
new5 = '''                            } else {
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
                            }'''
apply(old5, new5, "Run Now button")

with open(path, "w") as f:
    f.write(content)
print("ALL PATCHES APPLIED")
