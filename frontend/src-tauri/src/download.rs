use crate::{
    settings::{self, DesktopSettings, TransferLimiters},
    transfer::{
        self, DesktopTransferState, TransferDirection, TransferNode, TransferNodeKind,
        TransferNodePatch, TransferStatus, TransferTask,
    },
};
use reqwest::{header::{CONTENT_LENGTH, CONTENT_RANGE, RANGE}, Client};
use serde::{Deserialize, Serialize};
use std::{
    env,
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::Arc,
};
use tauri::{AppHandle, Emitter, Runtime, State};
use tauri_plugin_dialog::DialogExt;

const AUTH_EXPIRED_ERROR: &str = "AUTH_EXPIRED";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopDownloadResult {
    pub saved: bool,
    pub path: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopFolderSelection {
    pub selected: bool,
    pub path: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartDesktopDownloadFileRequest {
    pub file_id: i64,
    pub file_name: String,
    pub file_size: u64,
    pub token: Option<String>,
    pub api_base_url: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartDesktopDownloadFolderRequest {
    pub root: DownloadTreeNode,
    pub token: Option<String>,
    pub api_base_url: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadTreeNode {
    pub id: i64,
    pub file_name: String,
    pub file_type: String,
    pub file_size: u64,
    pub children: Vec<DownloadTreeNode>,
}

#[tauri::command]
pub async fn download_desktop_file<R: Runtime>(
    app: AppHandle<R>,
    limiters: State<'_, TransferLimiters>,
    url: String,
    file_name: String,
    token: Option<String>,
) -> Result<DesktopDownloadResult, String> {
    if let Some(directory) = e2e_download_directory()? {
        let target = unique_file_path(&directory, &file_name);
        download_url_to_path(&app, &limiters, &url, token.as_deref(), &target).await?;
        return Ok(DesktopDownloadResult {
            saved: true,
            path: Some(target.to_string_lossy().to_string()),
        });
    }

    let settings = settings::read_desktop_settings(&app)?;
    let target = if let Some(directory) = default_download_directory(&settings)? {
        Some(unique_file_path(&directory, &file_name))
    } else {
        app.dialog()
            .file()
            .set_file_name(sanitize_path_segment(&file_name))
            .blocking_save_file()
            .and_then(|path| path.into_path().ok())
    };

    let Some(target) = target else {
        return Ok(DesktopDownloadResult {
            saved: false,
            path: None,
        });
    };

    download_url_to_path(&app, &limiters, &url, token.as_deref(), &target).await?;
    Ok(DesktopDownloadResult {
        saved: true,
        path: Some(target.to_string_lossy().to_string()),
    })
}

#[tauri::command]
pub async fn start_desktop_download_file<R: Runtime>(
    app: AppHandle<R>,
    transfer_state: State<'_, DesktopTransferState>,
    limiters: State<'_, TransferLimiters>,
    request: StartDesktopDownloadFileRequest,
) -> Result<DesktopDownloadResult, String> {
    let target = choose_file_target(&app, &request.file_name)?;
    let Some(target) = target else {
        return Ok(DesktopDownloadResult { saved: false, path: None });
    };
    let task_id = transfer::new_task_id("desktop-download");
    let node = download_file_node(
        &task_id,
        None,
        &request.file_name,
        vec![request.file_name.clone()],
        request.file_id,
        request.file_size,
        &target,
    );
    let task = download_task_from_nodes(
        &task_id,
        &request.file_name,
        request.api_base_url.trim_end_matches('/'),
        node.id.clone(),
        vec![node],
    );
    transfer_state.add_task(&app, task)?;
    if transfer_state.mark_running(&task_id) {
        spawn_resume_download_task(
            app.clone(),
            transfer_state.shared_inner(),
            limiters.inner().clone(),
            task_id,
            request.token.unwrap_or_default(),
            request.api_base_url,
        );
    }
    Ok(DesktopDownloadResult { saved: true, path: Some(target.to_string_lossy().to_string()) })
}

#[tauri::command]
pub async fn start_desktop_download_folder<R: Runtime>(
    app: AppHandle<R>,
    transfer_state: State<'_, DesktopTransferState>,
    limiters: State<'_, TransferLimiters>,
    request: StartDesktopDownloadFolderRequest,
) -> Result<DesktopDownloadResult, String> {
    let root = choose_folder_target(&app, &request.root.file_name)?;
    let Some(root) = root else {
        return Ok(DesktopDownloadResult { saved: false, path: None });
    };
    let task_id = transfer::new_task_id("desktop-download-folder");
    let mut nodes = Vec::new();
    build_download_nodes(&task_id, None, &request.root, &root, vec![request.root.file_name.clone()], &mut nodes);
    let root_node_id = transfer::upload_node_id(&task_id, &request.root.file_name);
    let task = download_task_from_nodes(
        &task_id,
        &request.root.file_name,
        request.api_base_url.trim_end_matches('/'),
        root_node_id,
        nodes,
    );
    transfer_state.add_task(&app, task)?;
    if transfer_state.mark_running(&task_id) {
        spawn_resume_download_task(
            app.clone(),
            transfer_state.shared_inner(),
            limiters.inner().clone(),
            task_id,
            request.token.unwrap_or_default(),
            request.api_base_url,
        );
    }
    Ok(DesktopDownloadResult { saved: true, path: Some(root.to_string_lossy().to_string()) })
}

#[tauri::command]
pub async fn choose_desktop_folder<R: Runtime>(
    app: AppHandle<R>,
    folder_name: String,
) -> Result<DesktopFolderSelection, String> {
    if let Some(directory) = e2e_download_directory()? {
        let root = create_unique_directory(&directory, &folder_name)?;
        return Ok(DesktopFolderSelection {
            selected: true,
            path: Some(root.to_string_lossy().to_string()),
        });
    }

    let settings = settings::read_desktop_settings(&app)?;
    let parent = if let Some(directory) = default_download_directory(&settings)? {
        Some(directory)
    } else {
        app.dialog()
            .file()
            .blocking_pick_folder()
            .and_then(|path| path.into_path().ok())
    };

    let Some(parent) = parent else {
        return Ok(DesktopFolderSelection {
            selected: false,
            path: None,
        });
    };

    let root = create_unique_directory(&parent, &folder_name)?;
    Ok(DesktopFolderSelection {
        selected: true,
        path: Some(root.to_string_lossy().to_string()),
    })
}

#[tauri::command]
pub async fn download_desktop_file_to_path<R: Runtime>(
    app: AppHandle<R>,
    limiters: State<'_, TransferLimiters>,
    url: String,
    file_name: String,
    directory_path: String,
    token: Option<String>,
) -> Result<DesktopDownloadResult, String> {
    let directory = PathBuf::from(directory_path);
    fs::create_dir_all(&directory).map_err(|e| format!("创建目录失败: {e}"))?;
    let target = unique_file_path(&directory, &file_name);

    download_url_to_path(&app, &limiters, &url, token.as_deref(), &target).await?;
    Ok(DesktopDownloadResult {
        saved: true,
        path: Some(target.to_string_lossy().to_string()),
    })
}

#[tauri::command]
pub fn create_desktop_subdirectory(
    directory_path: String,
    folder_name: String,
) -> Result<DesktopFolderSelection, String> {
    let parent = PathBuf::from(directory_path);
    let child = create_unique_directory(&parent, &folder_name)?;
    Ok(DesktopFolderSelection {
        selected: true,
        path: Some(child.to_string_lossy().to_string()),
    })
}

pub async fn download_url_to_path<R: Runtime>(
    app: &AppHandle<R>,
    limiters: &TransferLimiters,
    url: &str,
    token: Option<&str>,
    target: &Path,
) -> Result<(), String> {
    let part_path = part_path(target);
    if let Some(parent) = part_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
    }

    let settings = settings::read_desktop_settings(app)?;
    let _permit = limiters
        .acquire_download(settings.max_concurrent_downloads)
        .await;
    let limiter = limiters.download();
    let client = settings::build_client(&settings)?;
    let start = fs::metadata(&part_path).map(|m| m.len()).unwrap_or(0);
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    if start > 0 {
        request = request.header(RANGE, format!("bytes={start}-"));
    }

    let response = request.send().await.map_err(|e| format!("下载请求失败: {e}"))?;
    if start > 0 && response.status() == reqwest::StatusCode::RANGE_NOT_SATISFIABLE {
        if unsatisfied_total_size(&response).is_some_and(|total| total == start) {
            replace_file(&part_path, target)?;
            return Ok(());
        }
        fs::remove_file(&part_path).map_err(|e| format!("重置半成品失败: {e}"))?;
        return download_from_start(&client, &settings, &limiter, url, token, target).await;
    }
    if let Some(error) = download_status_error(response.status()) {
        return Err(error);
    }
    if start > 0 && response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        fs::remove_file(&part_path).map_err(|e| format!("重置半成品失败: {e}"))?;
        return download_from_start(&client, &settings, &limiter, url, token, target).await;
    }

    let expected_total = response_total_size(&response, start).unwrap_or(0);
    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&part_path)
        .map_err(|e| format!("打开半成品失败: {e}"))?;
    let mut response = response;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("读取下载数据失败: {e}"))? {
        limiter
            .throttle(chunk.len() as u64, settings.download_limit_bytes_per_sec)
            .await;
        file.write_all(&chunk).map_err(|e| format!("写入文件失败: {e}"))?;
    }
    file.flush().map_err(|e| format!("刷新文件失败: {e}"))?;

    let downloaded = fs::metadata(&part_path).map_err(|e| format!("读取半成品失败: {e}"))?.len();
    if expected_total > 0 && downloaded != expected_total {
        return Err("下载未完成，已保留 .part 文件".to_string());
    }

    replace_file(&part_path, target)?;
    Ok(())
}

pub fn spawn_resume_download_task<R: Runtime>(
    app: AppHandle<R>,
    transfer_state: Arc<transfer::TransferStateInner>,
    limiters: TransferLimiters,
    task_id: String,
    token: String,
    api_base_url: String,
) {
    let transfer_state = DesktopTransferState::from_inner(transfer_state);
    tauri::async_runtime::spawn(async move {
        let result = run_download_task(
            app.clone(),
            transfer_state.clone(),
            limiters,
            task_id.clone(),
            token,
            api_base_url,
        )
        .await;
        if let Err(error) = result {
            let _ = app.emit("transfer:error", serde_json::json!({ "taskId": task_id, "message": error }));
        }
        let _ = transfer_state.finish_task(&app, &task_id);
    });
}

async fn run_download_task<R: Runtime>(
    app: AppHandle<R>,
    transfer_state: DesktopTransferState,
    limiters: TransferLimiters,
    task_id: String,
    token: String,
    api_base_url: String,
) -> Result<(), String> {
    let queue = transfer_state.queue_snapshot();
    let Some(task) = queue.tasks.into_iter().find(|task| task.id == task_id) else {
        return Ok(());
    };
    let settings = settings::read_desktop_settings(&app)?;
    let client = settings::build_client(&settings)?;
    let files: Vec<_> = task
        .nodes
        .into_iter()
        .filter(|node| node.kind == TransferNodeKind::File)
        .filter(|node| !transfer::is_terminal(&node.status))
        .collect();

    let mut handles = Vec::new();
    for node in files {
        let app = app.clone();
        let transfer_state = transfer_state.clone();
        let limiters = limiters.clone();
        let client = client.clone();
        let settings = settings.clone();
        let token = token.clone();
        let api_base_url = api_base_url.trim_end_matches('/').to_string();
        handles.push(tauri::async_runtime::spawn(async move {
            let _permit = limiters
                .acquire_download(settings.max_concurrent_downloads)
                .await;
            download_transfer_node(
                &app,
                &transfer_state,
                &limiters,
                &client,
                &settings,
                &node,
                &api_base_url,
                if token.is_empty() { None } else { Some(token.as_str()) },
            )
            .await
        }));
    }

    for handle in handles {
        if let Err(error) = handle.await.map_err(|e| format!("下载任务失败: {e}"))? {
            let _ = app.emit("transfer:error", serde_json::json!({ "taskId": task_id, "message": error }));
        }
    }
    Ok(())
}

async fn download_transfer_node<R: Runtime>(
    app: &AppHandle<R>,
    transfer_state: &DesktopTransferState,
    limiters: &TransferLimiters,
    client: &Client,
    settings: &DesktopSettings,
    node: &TransferNode,
    api_base_url: &str,
    token: Option<&str>,
) -> Result<(), String> {
    transfer_state.wait_if_blocked(app, &node.id).await?;
    let Some(remote_file_id) = node.remote_file_id else {
        return Ok(());
    };
    let Some(target_path) = node.target_path.as_deref() else {
        return Ok(());
    };
    let target = PathBuf::from(target_path);
    let url = format!("{api_base_url}/download/{remote_file_id}");
    transfer_state.update_node(
        app,
        &node.id,
        TransferNodePatch {
            status: Some(TransferStatus::Transferring),
            error: Some(None),
            ..TransferNodePatch::default()
        },
    )?;

    match download_url_to_path_with_progress(
        app,
        transfer_state,
        limiters,
        client,
        settings,
        &node.id,
        &url,
        token,
        &target,
    )
    .await
    {
        Ok(()) => {
            transfer_state.update_node(
                app,
                &node.id,
                TransferNodePatch {
                    status: Some(TransferStatus::Done),
                    bytes_done: Some(node.bytes_total),
                    progress: Some(100.0),
                    error: Some(None),
                    ..TransferNodePatch::default()
                },
            )?;
            Ok(())
        }
        Err(error) if error == "transfer canceled" => {
            transfer_state.update_node(
                app,
                &node.id,
                TransferNodePatch {
                    status: Some(TransferStatus::Canceled),
                    error: Some(None),
                    ..TransferNodePatch::default()
                },
            )?;
            Ok(())
        }
        Err(error) => {
            transfer_state.update_node(
                app,
                &node.id,
                TransferNodePatch {
                    status: Some(TransferStatus::Error),
                    error: Some(Some(error.clone())),
                    ..TransferNodePatch::default()
                },
            )?;
            Err(error)
        }
    }
}

async fn download_url_to_path_with_progress<R: Runtime>(
    app: &AppHandle<R>,
    transfer_state: &DesktopTransferState,
    limiters: &TransferLimiters,
    client: &Client,
    settings: &DesktopSettings,
    node_id: &str,
    url: &str,
    token: Option<&str>,
    target: &Path,
) -> Result<(), String> {
    let part_path = part_path(target);
    if let Some(parent) = part_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
    }

    let limiter = limiters.download();
    let start = fs::metadata(&part_path).map(|m| m.len()).unwrap_or(0);
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    if start > 0 {
        request = request.header(RANGE, format!("bytes={start}-"));
    }

    let response = request.send().await.map_err(|e| format!("下载请求失败: {e}"))?;
    if start > 0 && response.status() == reqwest::StatusCode::RANGE_NOT_SATISFIABLE {
        if unsatisfied_total_size(&response).is_some_and(|total| total == start) {
            replace_file(&part_path, target)?;
            return Ok(());
        }
        fs::remove_file(&part_path).map_err(|e| format!("重置半成品失败: {e}"))?;
        return download_from_start_with_progress(app, transfer_state, client, settings, &limiter, node_id, url, token, target).await;
    }
    if let Some(error) = download_status_error(response.status()) {
        return Err(error);
    }
    if start > 0 && response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        fs::remove_file(&part_path).map_err(|e| format!("重置半成品失败: {e}"))?;
        return download_from_start_with_progress(app, transfer_state, client, settings, &limiter, node_id, url, token, target).await;
    }

    let expected_total = response_total_size(&response, start).unwrap_or(0);
    transfer_state.update_node(
        app,
        node_id,
        TransferNodePatch {
            bytes_done: Some(start),
            bytes_total: if expected_total > 0 { Some(expected_total) } else { None },
            ..TransferNodePatch::default()
        },
    )?;
    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&part_path)
        .map_err(|e| format!("打开半成品失败: {e}"))?;
    let mut downloaded = start;
    let mut response = response;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("读取下载数据失败: {e}"))? {
        transfer_state.wait_if_blocked(app, node_id).await?;
        limiter
            .throttle(chunk.len() as u64, settings.download_limit_bytes_per_sec)
            .await;
        file.write_all(&chunk).map_err(|e| format!("写入文件失败: {e}"))?;
        downloaded += chunk.len() as u64;
        transfer_state.update_node(
            app,
            node_id,
            TransferNodePatch {
                status: Some(TransferStatus::Transferring),
                bytes_done: Some(downloaded),
                bytes_total: if expected_total > 0 { Some(expected_total) } else { None },
                ..TransferNodePatch::default()
            },
        )?;
    }
    file.flush().map_err(|e| format!("刷新文件失败: {e}"))?;

    let downloaded = fs::metadata(&part_path).map_err(|e| format!("读取半成品失败: {e}"))?.len();
    if expected_total > 0 && downloaded != expected_total {
        return Err("下载未完成，已保留 .part 文件".to_string());
    }

    replace_file(&part_path, target)?;
    Ok(())
}

async fn download_from_start_with_progress<R: Runtime>(
    app: &AppHandle<R>,
    transfer_state: &DesktopTransferState,
    client: &Client,
    settings: &DesktopSettings,
    limiter: &settings::RateLimiter,
    node_id: &str,
    url: &str,
    token: Option<&str>,
    target: &Path,
) -> Result<(), String> {
    let part_path = part_path(target);
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    let mut response = request.send().await.map_err(|e| format!("下载请求失败: {e}"))?;
    if let Some(error) = download_status_error(response.status()) {
        return Err(error);
    }
    let expected_total = response_total_size(&response, 0).unwrap_or(0);
    let mut file = fs::File::create(&part_path).map_err(|e| format!("创建半成品失败: {e}"))?;
    let mut downloaded = 0;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("读取下载数据失败: {e}"))? {
        transfer_state.wait_if_blocked(app, node_id).await?;
        limiter
            .throttle(chunk.len() as u64, settings.download_limit_bytes_per_sec)
            .await;
        file.write_all(&chunk).map_err(|e| format!("写入文件失败: {e}"))?;
        downloaded += chunk.len() as u64;
        transfer_state.update_node(
            app,
            node_id,
            TransferNodePatch {
                status: Some(TransferStatus::Transferring),
                bytes_done: Some(downloaded),
                bytes_total: if expected_total > 0 { Some(expected_total) } else { None },
                ..TransferNodePatch::default()
            },
        )?;
    }
    file.flush().map_err(|e| format!("刷新文件失败: {e}"))?;
    replace_file(&part_path, target)
}

async fn download_from_start(
    client: &Client,
    settings: &DesktopSettings,
    limiter: &settings::RateLimiter,
    url: &str,
    token: Option<&str>,
    target: &Path,
) -> Result<(), String> {
    let part_path = part_path(target);
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    let mut response = request.send().await.map_err(|e| format!("下载请求失败: {e}"))?;
    if let Some(error) = download_status_error(response.status()) {
        return Err(error);
    }
    let mut file = fs::File::create(&part_path).map_err(|e| format!("创建半成品失败: {e}"))?;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("读取下载数据失败: {e}"))? {
        limiter
            .throttle(chunk.len() as u64, settings.download_limit_bytes_per_sec)
            .await;
        file.write_all(&chunk).map_err(|e| format!("写入文件失败: {e}"))?;
    }
    file.flush().map_err(|e| format!("刷新文件失败: {e}"))?;
    replace_file(&part_path, target)
}

fn default_download_directory(settings: &DesktopSettings) -> Result<Option<PathBuf>, String> {
    let Some(path) = settings.default_download_dir.as_deref() else {
        return Ok(None);
    };
    let directory = PathBuf::from(path);
    fs::create_dir_all(&directory).map_err(|e| format!("创建默认下载目录失败: {e}"))?;
    Ok(Some(directory))
}

fn choose_file_target<R: Runtime>(app: &AppHandle<R>, file_name: &str) -> Result<Option<PathBuf>, String> {
    if let Some(directory) = e2e_download_directory()? {
        return Ok(Some(unique_file_path(&directory, file_name)));
    }

    let settings = settings::read_desktop_settings(app)?;
    if let Some(directory) = default_download_directory(&settings)? {
        return Ok(Some(unique_file_path(&directory, file_name)));
    }

    Ok(app
        .dialog()
        .file()
        .set_file_name(sanitize_path_segment(file_name))
        .blocking_save_file()
        .and_then(|path| path.into_path().ok()))
}

fn choose_folder_target<R: Runtime>(app: &AppHandle<R>, folder_name: &str) -> Result<Option<PathBuf>, String> {
    if let Some(directory) = e2e_download_directory()? {
        return create_unique_directory(&directory, folder_name).map(Some);
    }

    let settings = settings::read_desktop_settings(app)?;
    let parent = if let Some(directory) = default_download_directory(&settings)? {
        Some(directory)
    } else {
        app.dialog()
            .file()
            .blocking_pick_folder()
            .and_then(|path| path.into_path().ok())
    };

    parent
        .map(|parent| create_unique_directory(&parent, folder_name))
        .transpose()
}

fn download_task_from_nodes(
    task_id: &str,
    name: &str,
    api_base_url: &str,
    root_node_id: String,
    nodes: Vec<TransferNode>,
) -> TransferTask {
    let now = transfer::epoch_millis();
    TransferTask {
        id: task_id.to_string(),
        direction: TransferDirection::Download,
        root_node_id,
        name: name.to_string(),
        status: TransferStatus::Pending,
        progress: 0.0,
        bytes_done: 0,
        bytes_total: nodes
            .iter()
            .filter(|node| node.kind == TransferNodeKind::File)
            .map(|node| node.bytes_total)
            .sum(),
        created_at: now,
        updated_at: now,
        api_base_url: api_base_url.to_string(),
        base_parent_id: None,
        nodes,
    }
}

fn build_download_nodes(
    task_id: &str,
    parent_id: Option<String>,
    tree: &DownloadTreeNode,
    target: &Path,
    path: Vec<String>,
    nodes: &mut Vec<TransferNode>,
) {
    let display_path = path.join("/");
    let node_id = transfer::upload_node_id(task_id, &display_path);
    if tree.file_type == "folder" {
        nodes.push(TransferNode {
            id: node_id.clone(),
            task_id: task_id.to_string(),
            parent_id,
            direction: TransferDirection::Download,
            kind: TransferNodeKind::Folder,
            name: tree.file_name.clone(),
            display_path: display_path.clone(),
            path: path.clone(),
            remote_path: path.clone(),
            status: TransferStatus::Pending,
            progress: 0.0,
            bytes_done: 0,
            bytes_total: 0,
            error: None,
            local_path: None,
            target_path: Some(target.to_string_lossy().to_string()),
            remote_file_id: Some(tree.id),
            remote_parent_id: None,
            upload_id: None,
            md5_hash: None,
            total_chunks: 0,
            completed_chunks: 0,
            chunk_size: 0,
        });
        for child in &tree.children {
            let child_target = target.join(sanitize_path_segment(&child.file_name));
            let mut child_path = path.clone();
            child_path.push(child.file_name.clone());
            build_download_nodes(task_id, Some(node_id.clone()), child, &child_target, child_path, nodes);
        }
        return;
    }

    nodes.push(download_file_node(
        task_id,
        parent_id,
        &tree.file_name,
        path,
        tree.id,
        tree.file_size,
        target,
    ));
}

fn download_file_node(
    task_id: &str,
    parent_id: Option<String>,
    file_name: &str,
    path: Vec<String>,
    remote_file_id: i64,
    file_size: u64,
    target: &Path,
) -> TransferNode {
    let display_path = path.join("/");
    TransferNode {
        id: transfer::upload_node_id(task_id, &display_path),
        task_id: task_id.to_string(),
        parent_id,
        direction: TransferDirection::Download,
        kind: TransferNodeKind::File,
        name: file_name.to_string(),
        display_path,
        remote_path: path.clone(),
        path,
        status: TransferStatus::Pending,
        progress: 0.0,
        bytes_done: fs::metadata(part_path(target)).map(|metadata| metadata.len()).unwrap_or(0),
        bytes_total: file_size,
        error: None,
        local_path: None,
        target_path: Some(target.to_string_lossy().to_string()),
        remote_file_id: Some(remote_file_id),
        remote_parent_id: None,
        upload_id: None,
        md5_hash: None,
        total_chunks: 0,
        completed_chunks: 0,
        chunk_size: 0,
    }
}

fn unsatisfied_total_size(response: &reqwest::Response) -> Option<u64> {
    response
        .headers()
        .get(CONTENT_RANGE)
        .and_then(|value| value.to_str().ok())
        .and_then(|range| range.strip_prefix("bytes */"))
        .and_then(|total| total.parse::<u64>().ok())
}

fn response_total_size(response: &reqwest::Response, start: u64) -> Option<u64> {
    if let Some(range) = response.headers().get(CONTENT_RANGE).and_then(|value| value.to_str().ok()) {
        return range.rsplit_once('/').and_then(|(_, total)| total.parse::<u64>().ok());
    }
    response
        .headers()
        .get(CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .map(|length| start + length)
}

fn download_status_error(status: reqwest::StatusCode) -> Option<String> {
    if status == reqwest::StatusCode::UNAUTHORIZED {
        return Some(AUTH_EXPIRED_ERROR.to_string());
    }
    if !status.is_success() {
        return Some(format!("下载失败 ({status})"));
    }
    None
}

fn part_path(target: &Path) -> PathBuf {
    let file_name = target
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("download");
    target.with_file_name(format!("{file_name}.part"))
}

fn replace_file(source: &Path, target: &Path) -> Result<(), String> {
    if target.exists() {
        fs::remove_file(target).map_err(|e| format!("替换目标文件失败: {e}"))?;
    }
    fs::rename(source, target).map_err(|e| format!("完成下载失败: {e}"))
}

fn create_unique_directory(parent: &Path, directory_name: &str) -> Result<PathBuf, String> {
    for index in 0..10_000 {
        let candidate = parent.join(build_unique_path_name(directory_name, index));
        if !candidate.exists() {
            fs::create_dir_all(&candidate).map_err(|e| format!("创建文件夹失败: {e}"))?;
            return Ok(candidate);
        }
    }
    Err("无法创建唯一文件夹名称".to_string())
}

fn unique_file_path(directory: &Path, file_name: &str) -> PathBuf {
    for index in 0..10_000 {
        let candidate = directory.join(build_unique_path_name(file_name, index));
        let part = part_path(&candidate);
        if part.exists() || !candidate.exists() {
            return candidate;
        }
    }
    directory.join(sanitize_path_segment(file_name))
}

fn e2e_download_directory() -> Result<Option<PathBuf>, String> {
    let Some(value) = env::var_os("BCD_E2E_DOWNLOAD_DIR") else {
        return Ok(None);
    };
    if value.is_empty() {
        return Ok(None);
    }

    let directory = PathBuf::from(value);
    fs::create_dir_all(&directory).map_err(|e| format!("创建测试下载目录失败: {e}"))?;
    Ok(Some(directory))
}

fn build_unique_path_name(file_name: &str, index: usize) -> String {
    let sanitized = sanitize_path_segment(file_name);
    if index == 0 {
        return sanitized;
    }
    if let Some(dot) = sanitized.rfind('.') {
        if dot > 0 {
            return format!("{} ({}){}", &sanitized[..dot], index, &sanitized[dot..]);
        }
    }
    format!("{sanitized} ({index})")
}

fn sanitize_path_segment(value: &str) -> String {
    let sanitized = value
        .chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect::<String>()
        .trim()
        .trim_matches('.')
        .to_string();
    if sanitized.is_empty() {
        "download".to_string()
    } else {
        sanitized
    }
}

#[cfg(test)]
mod tests {
    use super::{build_unique_path_name, download_status_error, part_path, sanitize_path_segment, AUTH_EXPIRED_ERROR};
    use reqwest::StatusCode;
    use std::path::Path;

    #[test]
    fn sanitizes_file_names() {
        assert_eq!(sanitize_path_segment("a<b>:c?.txt"), "a_b__c_.txt");
        assert_eq!(sanitize_path_segment("..."), "download");
    }

    #[test]
    fn builds_unique_file_names() {
        assert_eq!(build_unique_path_name("root.txt", 1), "root (1).txt");
        assert_eq!(build_unique_path_name("docs", 2), "docs (2)");
    }

    #[test]
    fn appends_part_suffix_to_full_file_name() {
        assert_eq!(part_path(Path::new("demo.tar.gz")).to_string_lossy(), "demo.tar.gz.part");
    }

    #[test]
    fn maps_download_auth_status_to_refresh_signal() {
        assert_eq!(
            download_status_error(StatusCode::UNAUTHORIZED),
            Some(AUTH_EXPIRED_ERROR.to_string())
        );
        assert_eq!(download_status_error(StatusCode::OK), None);
        assert_eq!(download_status_error(StatusCode::PARTIAL_CONTENT), None);
        assert!(download_status_error(StatusCode::FORBIDDEN)
            .expect("forbidden should return a download error")
            .contains("403"));
    }
}
