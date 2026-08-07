#!/usr/bin/env python3
"""Patch the Tauri-generated Android Gradle project to:
  1. read versionCode / versionName from env vars (arbitrary non-semver versionName),
  2. add a release signingConfig that reads the keystore from env vars.
Runs every build because `cargo tauri android init` regenerates gen/android."""
import re
import pathlib
import sys

p = pathlib.Path("src-tauri/gen/android/app/build.gradle.kts")
if not p.exists():
    sys.exit(f"not found: {p}")
s = p.read_text()

# 1) versionCode / versionName -> prefer env vars, fall back to tauri.properties
s = s.replace(
    'versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()',
    'versionCode = (System.getenv("TAURI_ANDROID_VERSION_CODE")?.toInt()) '
    '?: tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()',
)
s = s.replace(
    'versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")',
    'versionName = System.getenv("TAURI_ANDROID_VERSION_NAME") '
    '?: tauriProperties.getProperty("tauri.android.versionName", "1.0")',
)

# 2) signingConfigs block reading env vars (injected right after `android {`)
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
    s = re.sub(r'(\nandroid\s*\{)', r'\1' + signing_block, s, count=1)

# 3) attach the release signingConfig to the release buildType
if 'signingConfig = signingConfigs.getByName("release")' not in s:
    s = re.sub(
        r'(getByName\("release"\)\s*\{)',
        r'\1\n            signingConfig = signingConfigs.getByName("release")',
        s, count=1,
    )

p.write_text(s)
print("Patched", p)
