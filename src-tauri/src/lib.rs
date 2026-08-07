use std::io::Write;
use tauri::{Emitter, Manager};

/// File name the downloaded rain loop is cached under, in the app data dir.
const AUDIO_FILE: &str = "rain-loop-long.mp3";

fn audio_path(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    Ok(app.path().app_data_dir().map_err(|e| e.to_string())?.join(AUDIO_FILE))
}

#[derive(serde::Serialize)]
struct AppInfo {
    name: String,
    version: String,
}

#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        name: "justrain".into(),
        version: env!("CARGO_PKG_VERSION").into(),
    }
}

/// Fast local check: is the rain audio already downloaded and non-empty?
#[tauri::command]
fn audio_cached(app: tauri::AppHandle) -> Result<bool, String> {
    let path = audio_path(&app)?;
    let bytes = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
    Ok(path.exists() && bytes > 0)
}

/// Size of the remote file (HEAD) so the UI can show "download ~N MB".
/// Returns an error (surfaced to the UI) rather than silently defaulting.
#[tauri::command]
async fn audio_remote_size(url: String) -> Result<u64, String> {
    let resp = reqwest::Client::new()
        .head(&url)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("server returned {}", resp.status()));
    }
    Ok(resp.content_length().unwrap_or(0))
}

#[derive(serde::Serialize)]
struct AudioDebug {
    path: String,
    exists: bool,
    size: u64,
}

/// Diagnostic: the resolved cache path + whether the file is present. Logged to
/// the webview console so it surfaces in `adb logcat`.
#[tauri::command]
fn audio_debug(app: tauri::AppHandle) -> Result<AudioDebug, String> {
    let path = audio_path(&app)?;
    let size = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
    Ok(AudioDebug {
        path: path.to_string_lossy().into_owned(),
        exists: path.exists() && size > 0,
        size,
    })
}

/// Download the rain audio to the app data dir, emitting `download-progress`
/// events `[downloaded, total]` as it streams. Downloads to a `.part` file and
/// atomically renames on success so a partial download never looks complete.
#[tauri::command]
async fn download_audio(app: tauri::AppHandle, url: String) -> Result<(), String> {
    use futures_util::StreamExt;

    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let tmp = dir.join(format!("{AUDIO_FILE}.part"));
    let final_path = dir.join(AUDIO_FILE);

    let resp = reqwest::get(&url).await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("server returned {}", resp.status()));
    }
    let total = resp.content_length().unwrap_or(0);

    let mut file = std::fs::File::create(&tmp).map_err(|e| e.to_string())?;
    let mut downloaded: u64 = 0;
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| e.to_string())?;
        file.write_all(&chunk).map_err(|e| e.to_string())?;
        downloaded += chunk.len() as u64;
        let _ = app.emit("download-progress", (downloaded, total));
    }
    file.flush().map_err(|e| e.to_string())?;
    drop(file);
    std::fs::rename(&tmp, &final_path).map_err(|e| e.to_string())?;
    Ok(())
}

/// Return the cached audio file as a `file://` URI for the native player.
#[tauri::command]
fn audio_file_uri(app: tauri::AppHandle) -> Result<String, String> {
    let path = audio_path(&app)?;
    if !path.exists() {
        return Err("audio not downloaded".into());
    }
    Ok(format!("file://{}", path.to_string_lossy()))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    #[allow(unused_mut)]
    let mut builder = tauri::Builder::default();

    #[cfg(mobile)]
    {
        builder = builder.plugin(tauri_plugin_native_player::init());
    }

    builder
        .invoke_handler(tauri::generate_handler![
            app_info,
            audio_cached,
            audio_remote_size,
            audio_debug,
            download_audio,
            audio_file_uri
        ])
        .run(tauri::generate_context!())
        .expect("error while running justrain");
}
