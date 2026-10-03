use tauri::{
    plugin::{Builder, TauriPlugin},
    Runtime,
};

/// One-time "tip" in-app purchase via Google Play Billing. The app is free;
/// this is an optional consumable "buy me a coffee" that can be bought again.
/// Commands are implemented on the Android side (`BillingPlugin.kt`) and
/// invoked from the webview as `plugin:billing|<command>`.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("billing")
        .setup(|_app, _api| {
            // Mirrors native-player: without this the Kotlin plugin class is
            // never instantiated and every invoke rejects with
            // "Plugin billing not initialized".
            #[cfg(target_os = "android")]
            _api.register_android_plugin("page.osmosis.billing", "BillingPlugin")?;
            Ok(())
        })
        .build()
}
