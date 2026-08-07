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

#[derive(serde::Serialize)]
struct AudioStatus {
    cached: bool,
    bytes: u64,
    remote_bytes: u64,
}

/// Report whether the rain audio is already cached locally, and — if not — the
/// size of the remote file (via a HEAD request) so the UI can ask the user to
/// confirm the download.
#[tauri::command]
async fn audio_status(app: tauri::AppHandle, url: String) -> Result<AudioStatus, String> {
    let path = audio_path(&app)?;
    let bytes = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
    let cached = path.exists() && bytes > 0;
    let remote_bytes = if cached {
        bytes
    } else {
        reqwest::Client::new()
            .head(&url)
            .send()
            .await
            .ok()
            .and_then(|r| r.content_length())
            .unwrap_or(0)
    };
    Ok(AudioStatus { cached, bytes, remote_bytes })
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

/// Return the cached audio bytes to the webview as an ArrayBuffer.
#[tauri::command]
fn load_audio(app: tauri::AppHandle) -> Result<tauri::ipc::Response, String> {
    let path = audio_path(&app)?;
    let bytes = std::fs::read(&path).map_err(|e| e.to_string())?;
    Ok(tauri::ipc::Response::new(bytes))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            app_info,
            audio_status,
            download_audio,
            load_audio
        ])
        .run(tauri::generate_context!())
        .expect("error while running justrain");
}
