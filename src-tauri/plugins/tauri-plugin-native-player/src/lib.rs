use tauri::{
    plugin::{Builder, TauriPlugin},
    Runtime,
};

/// Native audio playback via an Android Media3 (ExoPlayer) MediaSessionService.
/// Commands are implemented on the Android side (`NativePlayerPlugin.kt`) and
/// invoked from the webview as `plugin:native-player|<command>`.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("native-player")
        .setup(|_app, _api| Ok(()))
        .build()
}
