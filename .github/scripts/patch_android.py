#!/usr/bin/env python3
"""Enable programmatic audio in the Android System WebView.

Android WebView defaults `mediaPlaybackRequiresUserGesture = true`, which
silently blocks audio output (even Web Audio) — the app is dead silent on
device while identical JS plays fine on desktop. Tauri exposes no config flag
for this; the supported hook is overriding `onWebViewCreate` on the generated
`MainActivity : TauriActivity()`. gen/android is regenerated each CI build, so
this patch runs every build (after `cargo tauri android init`)."""
import glob
import pathlib
import re
import sys

matches = glob.glob("src-tauri/gen/android/app/src/main/java/**/MainActivity.kt", recursive=True)
if not matches:
    sys.exit("MainActivity.kt not found under src-tauri/gen/android")
p = pathlib.Path(matches[0])
s = p.read_text()

if "onWebViewCreate" in s:
    print("already patched:", matches[0])
    sys.exit(0)

# WebView is code-generated into the app package; import the Android class.
if "import android.webkit.WebView" not in s:
    s = re.sub(r'(^\s*package[^\n]*\n)', r'\1\nimport android.webkit.WebView\n', s, count=1, flags=re.M)

method = (
    "    override fun onWebViewCreate(webView: WebView) {\n"
    "        webView.settings.mediaPlaybackRequiresUserGesture = false\n"
    "        super.onWebViewCreate(webView)\n"
    "    }\n"
)

if re.search(r'class\s+MainActivity\s*:\s*TauriActivity\(\)\s*\{', s):
    # class already has a body — inject the method after the opening brace
    s, n = re.subn(r'(class\s+MainActivity\s*:\s*TauriActivity\(\)\s*\{)', r'\1\n' + method, s, count=1)
else:
    # bare `class MainActivity : TauriActivity()` — add a body
    s, n = re.subn(r'(class\s+MainActivity\s*:\s*TauriActivity\(\))', r'\1 {\n' + method + '}\n', s, count=1)

if n != 1:
    sys.exit("could not locate `class MainActivity : TauriActivity()` to patch")

p.write_text(s)
print("patched", matches[0])
