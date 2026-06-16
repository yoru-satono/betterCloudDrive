mod download;
mod settings;
mod transfer;
mod upload;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(settings::TransferLimiters::default())
        .manage(transfer::DesktopTransferState::default())
        .manage(upload::DesktopUploadState::default())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            settings::get_desktop_settings,
            settings::save_desktop_settings,
            settings::choose_default_download_directory,
            transfer::get_transfer_queue,
            transfer::restore_desktop_transfers,
            transfer::pause_transfer_node,
            transfer::resume_transfer_node,
            transfer::cancel_transfer_node,
            transfer::clear_finished_transfers,
            download::start_desktop_download_file,
            download::start_desktop_download_folder,
            download::download_desktop_file,
            download::choose_desktop_folder,
            download::download_desktop_file_to_path,
            download::create_desktop_subdirectory,
            upload::upload_desktop_files,
            upload::upload_desktop_folder,
            upload::cancel_desktop_upload,
        ])
        .run(tauri::generate_context!())
        .expect("error while running BetterCloudDrive desktop app");
}
