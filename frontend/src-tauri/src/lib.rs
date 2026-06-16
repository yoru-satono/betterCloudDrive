mod download;
mod upload;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(upload::DesktopUploadState::default())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            download::download_desktop_file,
            download::choose_desktop_folder,
            download::download_desktop_file_to_path,
            download::create_desktop_subdirectory,
            upload::upload_desktop_folder,
            upload::cancel_desktop_upload,
        ])
        .run(tauri::generate_context!())
        .expect("error while running BetterCloudDrive desktop app");
}
