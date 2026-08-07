#!/usr/bin/env python3
"""Patch the Tauri-generated Android Gradle project to:
  1. read versionCode / versionName from env vars (arbitrary non-semver versionName),
  2. add a release signingConfig that reads the keystore from env vars.
Runs every build because `cargo tauri android init` regenerates gen/android.
Reports match counts (non-fatal) so a template drift is visible in the log."""
import re
import pathlib
import sys

p = pathlib.Path("src-tauri/gen/android/app/build.gradle.kts")
if not p.exists():
    sys.exit(f"not found: {p}")
s = p.read_text()


def patch(pattern, repl, label, regex=False):
    global s
    if regex:
        s, n = re.subn(pattern, repl, s)
    else:
        n = s.count(pattern)
        s = s.replace(pattern, repl)
    print(f"[patch_gradle] {label}: {n} match(es)", file=sys.stderr)
    return n


# 1) versionCode — exact, then regex fallback
vc = patch(
    'versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()',
    'versionCode = (System.getenv("TAURI_ANDROID_VERSION_CODE")?.toInt()) '
    '?: tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()',
    "versionCode(exact)",
)
if vc == 0:
    patch(
        r'versionCode\s*=\s*tauriProperties\.getProperty\(\s*"tauri\.android\.versionCode"\s*,\s*"1"\s*\)\.toInt\(\)',
        'versionCode = (System.getenv("TAURI_ANDROID_VERSION_CODE")?.toInt()) '
        '?: tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()',
        "versionCode(regex)", regex=True,
    )

# 2) versionName — exact, then regex fallback
vn = patch(
    'versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")',
    'versionName = System.getenv("TAURI_ANDROID_VERSION_NAME") '
    '?: tauriProperties.getProperty("tauri.android.versionName", "1.0")',
    "versionName(exact)",
)
if vn == 0:
    patch(
        r'versionName\s*=\s*tauriProperties\.getProperty\(\s*"tauri\.android\.versionName"\s*,\s*"1\.0"\s*\)',
        'versionName = System.getenv("TAURI_ANDROID_VERSION_NAME") '
        '?: tauriProperties.getProperty("tauri.android.versionName", "1.0")',
        "versionName(regex)", regex=True,
    )

# 3) signingConfigs block reading env vars (injected right after `android {`)
signing_block = '''
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
            }
        }
    }
'''
if "signingConfigs {" not in s:
    patch(r'(\nandroid\s*\{)', r'\1' + signing_block, "signingConfigs", regex=True)

# 4) attach the release signingConfig to the release buildType
if 'signingConfig = signingConfigs.getByName("release")' not in s:
    patch(
        r'(getByName\("release"\)\s*\{)',
        r'\1\n            signingConfig = signingConfigs.getByName("release")',
        "attach-signing", regex=True,
    )

p.write_text(s)
print("Patched", p)
