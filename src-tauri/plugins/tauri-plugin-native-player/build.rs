const COMMANDS: &[&str] = &["load", "play", "pause", "stop", "set_volume"];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
