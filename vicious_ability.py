# Copyright (c) 2026 Alisha Bevis (renaealisha54-debug)
# Vicious Ability - Voice AI Assistant (Termux:API version)

import json
import subprocess
import os

# Known project shortcuts -> real paths.
# Add more entries here as you create new Vicious Series projects.
PROJECTS = {
    "ability": os.path.expanduser("~/ollama/vicious-projects/vicious-ability"),
    "aether": os.path.expanduser("~/vicious-aether-apk"),
    "script": os.path.expanduser("~/vicious-script-apk"),
    "spark": os.path.expanduser("~/vicious-spark-apk"),
    "suite": os.path.expanduser("~/vicious-suite-apk"),
    "vex": os.path.expanduser("~/vicious-vex"),
}

# Taught commands written by the Vicious Ability app's "+ TEACH" / "SYNC"
# buttons. Kept in the public Downloads folder since the app and Termux
# are separate sandboxes and can't see each other's private storage.
COMMANDS_FILE = os.path.expanduser("~/storage/downloads/vicious_commands.json")


def load_taught_commands():
    """Reload taught trigger -> exact command/path pairs from disk every call,
    so edits made in the app (after a SYNC) show up without restarting the CLI."""
    if not os.path.exists(COMMANDS_FILE):
        return {}
    try:
        with open(COMMANDS_FILE, "r") as f:
            return json.load(f)
    except Exception:
        return {}


def find_taught_match(phrase, taught):
    """Same matching rule as the app's CommandManager.findMatchingTrigger:
    a taught trigger is a match if it appears anywhere in the phrase."""
    lower = phrase.strip().lower()
    for trigger in taught:
        if trigger in lower:
            return trigger
    return None


def run_taught_command(command):
    """Run an exact taught command/path, same as CommandManager.runExact."""
    try:
        result = subprocess.run(
            ["sh", "-c", command],
            capture_output=True,
            text=True,
            timeout=30,
        )
        output = (result.stdout + result.stderr).strip()
        return output if output else "Done."
    except Exception as e:
        return f"Error: {e}"


def speak(text):
    """Use Termux TTS"""
    print("🤖 Vicious:", text)
    subprocess.run(["termux-tts-speak", text])


def listen():
    """Use Termux microphone"""
    print("🎤 Listening... (Speak now)")
    try:
        subprocess.check_output(["termux-microphone-record", "-d"], stderr=subprocess.STDOUT)
        subprocess.run(["termux-microphone-record", "-q"])
        return input("Type what you said for testing: ").lower()
    except Exception:
        return input("🎤 Type command: ").lower()


def handle_cd(command):
    """
    Matches 'cd <name>' or 'go to <name>' against known project shortcuts
    and changes the process's working directory if found.
    """
    for name, path in PROJECTS.items():
        if name in command:
            if os.path.isdir(path):
                os.chdir(path)
                speak(f"Now working in {name}.")
                print(f"📂 Current directory: {os.getcwd()}")
            else:
                speak(f"I know {name}, but its folder doesn't exist at {path}.")
            return True
    speak("I don't recognize that project. Say 'list projects' to hear known ones.")
    return True


def handle_teach(command, taught):
    """
    Handles 'teach <trigger> to <command>' spoken/typed here in the CLI.
    Writes back to the same file the app reads/writes, so it stays in sync
    both directions without needing to open the app.
    """
    rest = command.split("teach", 1)[1].strip()
    if " to " not in rest:
        speak("To teach a command, say: teach <trigger> to <exact command>")
        return
    trigger, exact_command = rest.split(" to ", 1)
    trigger = trigger.strip().lower()
    exact_command = exact_command.strip()
    if not trigger or not exact_command:
        speak("I need both a trigger and a command to teach that.")
        return
    taught[trigger] = exact_command
    try:
        with open(COMMANDS_FILE, "w") as f:
            json.dump(taught, f, indent=2)
        speak(f"Learned it. Say '{trigger}' and I'll run it.")
    except Exception as e:
        speak(f"Couldn't save that: {e}")


speak("Vicious Ability is now online. How can I help you?")

while True:
    command = listen()

    taught = load_taught_commands()
    matched_trigger = find_taught_match(command, taught)

    if matched_trigger:
        speak(f"Running '{matched_trigger}'.")
        output = run_taught_command(taught[matched_trigger])
        print(output)

    elif command.startswith("teach"):
        handle_teach(command, taught)

    elif "hello" in command or "hi" in command:
        speak("Hello Alisha, I'm here.")

    elif "open" in command and "github" in command:
        speak("Opening your repository")
        subprocess.run(["termux-open-url", "https://github.com/renaealisha54-debug/Vicious-ability-"])

    elif "list projects" in command:
        speak("Known projects: " + ", ".join(PROJECTS.keys()))

    elif "list commands" in command:
        if taught:
            speak("Taught commands: " + ", ".join(taught.keys()))
        else:
            speak("No taught commands yet. Say 'teach' followed by a trigger and command.")

    elif command.startswith("cd") or "go to" in command:
        handle_cd(command)

    elif "where am i" in command or "current directory" in command:
        speak(f"You are in {os.getcwd()}")

    elif "exit" in command or "stop" in command or "goodbye" in command:
        speak("Goodbye Alisha. Shutting down.")
        break

    else:
        speak("I heard: " + command)
        print("💡 You can say things like 'open github', 'cd ability', 'teach <trigger> to <command>', 'where am i', or 'hello'")

