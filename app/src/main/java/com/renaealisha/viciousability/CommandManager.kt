// Copyright (c) 2026 Alisha Bevis (renaealisha54-debug)
// Vicious Ability - Local Command Manager
//
// Stores taught trigger -> exact command/path pairs in the app's own
// private internal storage (context.filesDir), completely independent
// of Termux. If Termux's files/data get wiped, this is untouched.

package com.renaealisha.viciousability

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class CommandResult(
    val success: Boolean,
    val output: String
)

class CommandManager(context: Context) {

    private val file: File = File(context.filesDir, "commands.json")

    // Mirror of commands.json written to the public Downloads folder so
    // Termux (a separate app/sandbox) can read taught commands. Requires
    // "All files access" to be granted (see MainActivity's permission check).
    private val publicMirror: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "vicious_commands.json"
    )

    // In-memory map, kept in sync with the JSON file on disk.
    private var commands: MutableMap<String, String> = loadFromDisk()

    // ---- Load / Save ----

    private fun loadFromDisk(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val text = file.readText()
            val json = JSONObject(text)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.getString(key)
            }
            map
        } catch (e: Exception) {
            // Corrupt or unreadable file: don't crash, just start fresh in memory.
            // The bad file on disk is left alone in case it needs recovery later.
            mutableMapOf()
        }
    }

    private fun saveToDisk() {
        try {
            val json = JSONObject()
            commands.forEach { (key, value) -> json.put(key, value) }
            file.writeText(json.toString(2))
        } catch (e: IOException) {
            // Swallow: teaching should not crash the app even if disk write fails.
        }
        exportPublicMirror()
    }

    /**
     * Writes the current commands to the public Downloads folder so Termux
     * can read them. Safe to call any time — no-ops quietly if "All files
     * access" hasn't been granted yet. Also callable directly (e.g. right
     * after the user grants the permission) to push an immediate sync.
     */
    fun exportPublicMirror() {
        try {
            val json = JSONObject()
            commands.forEach { (key, value) -> json.put(key, value) }
            publicMirror.writeText(json.toString(2))
        } catch (e: IOException) {
            // No "All files access" yet, or storage otherwise unwritable — ignore.
        }
    }

    // ---- Public API ----

    /** Teach a new trigger -> exact command/path. Overwrites if trigger already exists. */
    fun teach(trigger: String, command: String) {
        val key = trigger.trim().lowercase()
        commands[key] = command.trim()
        saveToDisk()
    }

    /** Remove a taught command. */
    fun forget(trigger: String) {
        commands.remove(trigger.trim().lowercase())
        saveToDisk()
    }

    /**
     * Edit an existing taught command. Handles the case where the trigger
     * name itself changed (e.g. "messages" -> "open messages") by removing
     * the old key and saving under the new one, so it doesn't leave a
     * duplicate/stale entry behind.
     */
    fun edit(originalTrigger: String, newTrigger: String, newCommand: String) {
        val oldKey = originalTrigger.trim().lowercase()
        val newKey = newTrigger.trim().lowercase()
        if (oldKey != newKey) {
            commands.remove(oldKey)
        }
        commands[newKey] = newCommand.trim()
        saveToDisk()
    }

    /** Returns all taught commands, trigger -> command, for display in a list screen. */
    fun getAll(): Map<String, String> = commands.toMap()

    /** Look up the exact command for a trigger, if it exists. */
    fun lookup(trigger: String): String? = commands[trigger.trim().lowercase()]

    /**
     * Find a taught trigger contained within a longer spoken/typed phrase.
     * E.g. trigger "open messages" matches phrase "hey open messages please".
     * Returns the trigger key that matched, or null.
     */
    fun findMatchingTrigger(phrase: String): String? {
        val lower = phrase.trim().lowercase()
        return commands.keys.firstOrNull { trigger -> lower.contains(trigger) }
    }

    /**
     * Execute the exact command/path stored for a trigger.
     * No validation, no permission pre-checks, no safety filtering —
     * it runs exactly what was taught. If it's restricted by Android
     * or the command doesn't exist, that will surface in the output/error.
     */
    fun execute(trigger: String): CommandResult {
        val command = lookup(trigger)
            ?: return CommandResult(false, "No command taught for '$trigger'")

        return runExact(command)
    }

    /** Run an arbitrary command string exactly as given. */
    fun runExact(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            CommandResult(true, output.ifBlank { "Done." })
        } catch (e: Exception) {
            CommandResult(false, "Error: ${e.message}")
        }
    }
}
