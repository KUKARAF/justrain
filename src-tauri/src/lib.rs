use serde::Serialize;

#[derive(Serialize)]
struct AppInfo {
    name: String,
    version: String,
}

/// Exposed to the webview via `window.__TAURI__.core.invoke("app_info")`.
#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        name: "justrain".into(),
        version: env!("CARGO_PKG_VERSION").into(),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![app_info])
        .run(tauri::generate_context!())
        .expect("error while running justrain");
}
