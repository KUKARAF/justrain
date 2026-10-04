// register_listener/remove_listener back the webview's addPluginListener()
// (Plugin.registerListener on the Kotlin side), used for the "state" event.
const COMMANDS: &[&str] = &[
    "play",
    "pause",
    "set_volume",
    "set_exclusive",
    "get_state",
    "register_listener",
    "remove_listener",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
