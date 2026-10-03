const COMMANDS: &[&str] = &["tip", "get_price"];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
