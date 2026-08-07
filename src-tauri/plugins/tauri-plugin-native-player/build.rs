const COMMANDS: &[&str] = &["play", "pause", "set_volume"];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
