use reqwest::multipart::{Form, Part};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    env, fs,
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};
use tauri::{AppHandle, Emitter, Runtime};
use tauri_plugin_dialog::DialogExt;

const CHUNK_SIZE: u64 = 5 * 1024 * 1024;
const FILE_NAME_CONFLICT_CODE: i64 = 409001;
const INSTANT_UPLOAD_NOT_FOUND_CODE: i64 = 419010;

#[derive(Clone, Debug, Default)]
pub struct DesktopUploadState {
    canceled: Arc<Mutex<HashSet<String>>>,
}

impl DesktopUploadState {
    fn cancel(&self, item_id: &str) {
        if let Ok(mut canceled) = self.canceled.lock() {
            canceled.insert(item_id.to_string());
        }
    }

    fn is_canceled(&self, item_id: &str) -> bool {
        self.canceled
            .lock()
            .map(|canceled| canceled.contains(item_id))
            .unwrap_or(false)
    }

    fn clear(&self, item_id: &str) {
        if let Ok(mut canceled) = self.canceled.lock() {
            canceled.remove(item_id);
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopFolderUploadRequest {
    pub parent_id: Option<i64>,
    pub token: String,
    pub api_base_url: String,
    pub resumable_uploads: Vec<ResumableUploadRecord>,
    pub resumable_directories: Vec<ResumableDirectoryRecord>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopFolderUploadStart {
    pub selected: bool,
    pub batch_id: Option<String>,
    pub root_name: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResumableUploadRecord {
    pub session_id: String,
    pub parent_id: Option<i64>,
    pub file_name: String,
    pub display_name: String,
    pub file_size: u64,
    pub md5_hash: String,
    pub total_chunks: u64,
    pub chunk_size: u64,
    pub created_at: u64,
    pub updated_at: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResumableDirectoryRecord {
    pub path_key: String,
    pub parent_path_key: Option<String>,
    pub base_parent_id: Option<i64>,
    pub remote_id: i64,
    pub remote_name: String,
    pub created_at: u64,
    pub updated_at: u64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopUploadItemEvent {
    id: String,
    batch_id: String,
    file_name: String,
    display_name: String,
    parent_id: Option<i64>,
    status: String,
    progress: f64,
    chunk_progress: String,
    error: Option<String>,
    upload_id: Option<String>,
    file_size: u64,
    md5_hash: Option<String>,
    total_chunks: u64,
    resumable: bool,
    resumable_record: Option<ResumableUploadRecord>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopUploadDirectoryEvent {
    record: ResumableDirectoryRecord,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopUploadBatchEvent {
    batch_id: String,
    root_name: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiEnvelope<T> {
    code: i64,
    message: String,
    data: Option<T>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FileResponse {
    id: i64,
    file_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InitUploadResponse {
    session_id: String,
    chunk_size: Option<u64>,
    total_chunks: Option<u64>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UploadStatusResponse {
    total_chunks: Option<u64>,
    uploaded_chunks: Option<u64>,
    missing_chunks: Option<Vec<u64>>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstantUploadResponse {
    instant: Option<bool>,
}

#[derive(Debug)]
struct LocalUploadFile {
    path: PathBuf,
    file_name: String,
    display_name: String,
    directory_path: Vec<String>,
    file_size: u64,
}

struct UploadContext<R: Runtime> {
    app: AppHandle<R>,
    client: reqwest::Client,
    state: DesktopUploadState,
    batch_id: String,
    root_name: String,
    parent_id: Option<i64>,
    token: String,
    api_base_url: String,
    resumable_uploads: Vec<ResumableUploadRecord>,
    resumable_directories: Vec<ResumableDirectoryRecord>,
}

#[tauri::command]
pub async fn upload_desktop_folder<R: Runtime>(
    app: AppHandle<R>,
    state: tauri::State<'_, DesktopUploadState>,
    request: DesktopFolderUploadRequest,
) -> Result<DesktopFolderUploadStart, String> {
    let root = if let Some(path) = e2e_upload_directory() {
        Some(path)
    } else {
        app.dialog()
            .file()
            .blocking_pick_folder()
            .and_then(|path| path.into_path().ok())
    };

    let Some(root) = root else {
        return Ok(DesktopFolderUploadStart {
            selected: false,
            batch_id: None,
            root_name: None,
        });
    };

    let root_name = root
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("folder")
        .to_string();
    let batch_id = format!("desktop-folder-{}", epoch_millis());
    let ctx = UploadContext {
        app: app.clone(),
        client: reqwest::Client::new(),
        state: state.inner().clone(),
        batch_id: batch_id.clone(),
        root_name: root_name.clone(),
        parent_id: request.parent_id,
        token: request.token,
        api_base_url: request.api_base_url.trim_end_matches('/').to_string(),
        resumable_uploads: request.resumable_uploads,
        resumable_directories: request.resumable_directories,
    };

    tauri::async_runtime::spawn(async move {
        if let Err(error) = upload_folder(ctx, root).await {
            let _ = app.emit("desktop-upload:error", serde_json::json!({ "message": error }));
        }
    });

    Ok(DesktopFolderUploadStart {
        selected: true,
        batch_id: Some(batch_id),
        root_name: Some(root_name),
    })
}

#[tauri::command]
pub fn cancel_desktop_upload(
    state: tauri::State<'_, DesktopUploadState>,
    upload_item_id: String,
) -> Result<(), String> {
    state.cancel(&upload_item_id);
    Ok(())
}

async fn upload_folder<R: Runtime>(mut ctx: UploadContext<R>, root: PathBuf) -> Result<(), String> {
    let plan = collect_upload_plan(&root)?;
    let directories = collect_directory_paths(&ctx.root_name, &plan);
    let directory_ids = create_remote_directories(&mut ctx, directories).await?;

    if plan.files.is_empty() {
        let _ = ctx.app.emit(
            "desktop-upload:batch-completed",
            DesktopUploadBatchEvent {
                batch_id: ctx.batch_id.clone(),
                root_name: ctx.root_name.clone(),
            },
        );
        return Ok(());
    }

    for file in plan.files {
        let parent_id = directory_ids
            .get(&directory_path_key(&file.directory_path, ctx.parent_id))
            .copied()
            .flatten()
            .or(ctx.parent_id);
        upload_file(&ctx, file, parent_id).await?;
    }

    let _ = ctx.app.emit(
        "desktop-upload:batch-completed",
        DesktopUploadBatchEvent {
            batch_id: ctx.batch_id,
            root_name: ctx.root_name,
        },
    );
    Ok(())
}

async fn upload_file<R: Runtime>(
    ctx: &UploadContext<R>,
    file: LocalUploadFile,
    parent_id: Option<i64>,
) -> Result<(), String> {
    let id = format!("{}:{}", ctx.batch_id, file.display_name);
    let total_chunks = total_chunks(file.file_size);
    emit_item(ctx, &file, &id, parent_id, "hashing", 0.0, "", None, None, None, total_chunks, false);

    let md5_hash = compute_md5(&file.path).map_err(|e| format!("计算 MD5 失败: {e}"))?;
    emit_item(ctx, &file, &id, parent_id, "hashing", 30.0, "", None, None, Some(md5_hash.clone()), total_chunks, false);

    if try_instant_upload(ctx, parent_id, &file, &md5_hash).await? {
        emit_item(ctx, &file, &id, parent_id, "instant", 100.0, "", None, None, Some(md5_hash), total_chunks, false);
        return Ok(());
    }

    let session = if let Some(record) = find_resumable_upload(ctx, parent_id, &file, &md5_hash) {
        validate_upload_session(ctx, &record)
            .await
            .unwrap_or(init_upload(ctx, parent_id, &file, &md5_hash, total_chunks).await?)
    } else {
        init_upload(ctx, parent_id, &file, &md5_hash, total_chunks).await?
    };
    let upload_id = session.session_id.clone();
    let record = ResumableUploadRecord {
        session_id: upload_id.clone(),
        parent_id,
        file_name: file.file_name.clone(),
        display_name: file.display_name.clone(),
        file_size: file.file_size,
        md5_hash: md5_hash.clone(),
        total_chunks: session.total_chunks,
        chunk_size: session.chunk_size,
        created_at: session.created_at,
        updated_at: epoch_millis(),
    };
    emit_item(ctx, &file, &id, parent_id, "uploading", 30.0, "0/0", Some(upload_id.clone()), None, Some(md5_hash.clone()), session.total_chunks, true);
    emit_record(ctx, record.clone());

    let missing_chunks = missing_chunks(ctx, &upload_id, session.total_chunks).await?;
    let completed_before = session.total_chunks.saturating_sub(missing_chunks.len() as u64);
    emit_item(
        ctx,
        &file,
        &id,
        parent_id,
        "uploading",
        upload_progress(session.total_chunks, completed_before),
        &format!("{completed_before}/{}", session.total_chunks),
        Some(upload_id.clone()),
        None,
        Some(md5_hash.clone()),
        session.total_chunks,
        true,
    );

    for (index, chunk_number) in missing_chunks.iter().enumerate() {
        if ctx.state.is_canceled(&id) {
            ctx.state.clear(&id);
            emit_item(ctx, &file, &id, parent_id, "canceled", 0.0, "", Some(upload_id), None, Some(md5_hash), session.total_chunks, false);
            return Ok(());
        }
        upload_chunk(ctx, &upload_id, &file.path, *chunk_number, session.chunk_size).await?;
        let uploaded = completed_before + index as u64 + 1;
        emit_item(
            ctx,
            &file,
            &id,
            parent_id,
            "uploading",
            upload_progress(session.total_chunks, uploaded),
            &format!("{uploaded}/{}", session.total_chunks),
            Some(upload_id.clone()),
            None,
            Some(md5_hash.clone()),
            session.total_chunks,
            true,
        );
    }

    complete_upload(ctx, &upload_id).await?;
    emit_item(ctx, &file, &id, parent_id, "done", 100.0, "", Some(upload_id), None, Some(md5_hash), session.total_chunks, false);
    Ok(())
}

#[derive(Clone)]
struct UploadSession {
    session_id: String,
    total_chunks: u64,
    chunk_size: u64,
    created_at: u64,
}

async fn validate_upload_session<R: Runtime>(
    ctx: &UploadContext<R>,
    record: &ResumableUploadRecord,
) -> Option<UploadSession> {
    let status = get_upload_status(ctx, &record.session_id).await.ok()?;
    Some(UploadSession {
        session_id: record.session_id.clone(),
        total_chunks: status.total_chunks.unwrap_or(record.total_chunks),
        chunk_size: record.chunk_size,
        created_at: record.created_at,
    })
}

async fn init_upload<R: Runtime>(
    ctx: &UploadContext<R>,
    parent_id: Option<i64>,
    file: &LocalUploadFile,
    md5_hash: &str,
    total_chunks: u64,
) -> Result<UploadSession, String> {
    let data = post_json::<_, InitUploadResponse>(
        ctx,
        "/upload/init",
        serde_json::json!({
            "parentId": parent_id,
            "fileName": file.file_name,
            "fileSize": file.file_size,
            "md5Hash": md5_hash,
            "totalChunks": total_chunks,
        }),
    )
    .await?;
    Ok(UploadSession {
        session_id: data.session_id,
        total_chunks: data.total_chunks.unwrap_or(total_chunks),
        chunk_size: data.chunk_size.unwrap_or(CHUNK_SIZE),
        created_at: epoch_millis(),
    })
}

async fn try_instant_upload<R: Runtime>(
    ctx: &UploadContext<R>,
    parent_id: Option<i64>,
    file: &LocalUploadFile,
    md5_hash: &str,
) -> Result<bool, String> {
    match post_json::<_, InstantUploadResponse>(
        ctx,
        "/upload/instant",
        serde_json::json!({
            "parentId": parent_id,
            "fileName": file.file_name,
            "fileSize": file.file_size,
            "md5Hash": md5_hash,
        }),
    )
    .await
    {
        Ok(data) => Ok(data.instant.unwrap_or(false)),
        Err(error) if error.contains(&INSTANT_UPLOAD_NOT_FOUND_CODE.to_string()) => Ok(false),
        Err(error) => Err(error),
    }
}

async fn missing_chunks<R: Runtime>(ctx: &UploadContext<R>, session_id: &str, total_chunks: u64) -> Result<Vec<u64>, String> {
    let status = get_upload_status(ctx, session_id).await?;
    if let Some(missing) = status.missing_chunks {
        return Ok(missing);
    }
    let uploaded = status.uploaded_chunks.unwrap_or(0);
    Ok((uploaded..total_chunks).collect())
}

async fn get_upload_status<R: Runtime>(ctx: &UploadContext<R>, session_id: &str) -> Result<UploadStatusResponse, String> {
    let url = format!("{}/upload/{}/status", ctx.api_base_url, session_id);
    let response = ctx
        .client
        .get(url)
        .bearer_auth(&ctx.token)
        .send()
        .await
        .map_err(|e| format!("读取上传状态失败: {e}"))?;
    parse_response(response).await
}

async fn upload_chunk<R: Runtime>(
    ctx: &UploadContext<R>,
    session_id: &str,
    path: &Path,
    chunk_number: u64,
    chunk_size: u64,
) -> Result<(), String> {
    let bytes = read_chunk(path, chunk_number, chunk_size).map_err(|e| format!("读取分片失败: {e}"))?;
    let file_name = path.file_name().and_then(|name| name.to_str()).unwrap_or("chunk").to_string();
    let part = Part::bytes(bytes).file_name(file_name);
    let form = Form::new().part("file", part);
    let url = format!("{}/upload/{}/chunk?chunkNumber={}", ctx.api_base_url, session_id, chunk_number);
    let response = ctx
        .client
        .post(url)
        .bearer_auth(&ctx.token)
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("上传分片失败: {e}"))?;
    parse_response::<serde_json::Value>(response).await.map(|_| ())
}

async fn complete_upload<R: Runtime>(ctx: &UploadContext<R>, session_id: &str) -> Result<(), String> {
    post_json::<_, serde_json::Value>(ctx, &format!("/upload/{session_id}/complete"), serde_json::json!({})).await.map(|_| ())
}

async fn create_remote_directories<R: Runtime>(
    ctx: &mut UploadContext<R>,
    paths: Vec<Vec<String>>,
) -> Result<HashMap<String, Option<i64>>, String> {
    let mut ids = HashMap::new();
    ids.insert(directory_path_key(&[], ctx.parent_id), ctx.parent_id);

    for path in paths {
        let key = directory_path_key(&path, ctx.parent_id);
        if let Some(record) = ctx.resumable_directories.iter().find(|record| record.path_key == key).cloned() {
            ids.insert(key, Some(record.remote_id));
            continue;
        }

        let parent_path = &path[..path.len().saturating_sub(1)];
        let parent_key = directory_path_key(parent_path, ctx.parent_id);
        let parent_id = ids.get(&parent_key).copied().flatten().or(ctx.parent_id);
        let remote = create_unique_remote_folder(ctx, parent_id, path.last().map(String::as_str).unwrap_or("folder")).await?;
        ids.insert(key.clone(), Some(remote.id));
        let record = ResumableDirectoryRecord {
            path_key: key,
            parent_path_key: Some(parent_key),
            base_parent_id: ctx.parent_id,
            remote_id: remote.id,
            remote_name: remote.file_name,
            created_at: epoch_millis(),
            updated_at: epoch_millis(),
        };
        ctx.resumable_directories.push(record.clone());
        emit_directory_record(ctx, record);
    }

    Ok(ids)
}

async fn create_unique_remote_folder<R: Runtime>(
    ctx: &UploadContext<R>,
    parent_id: Option<i64>,
    folder_name: &str,
) -> Result<FileResponse, String> {
    for index in 0..10_000 {
        let candidate = if index == 0 {
            folder_name.to_string()
        } else {
            format!("{folder_name} ({index})")
        };
        match post_json::<_, FileResponse>(
            ctx,
            "/files/folder",
            serde_json::json!({
                "parentId": parent_id,
                "folderName": candidate,
            }),
        )
        .await
        {
            Ok(file) => return Ok(file),
            Err(error) if error.contains(&FILE_NAME_CONFLICT_CODE.to_string()) => continue,
            Err(error) => return Err(error),
        }
    }
    Err("无法创建唯一远端文件夹名称".to_string())
}

async fn post_json<R: Runtime, T: for<'de> Deserialize<'de>>(
    ctx: &UploadContext<R>,
    path: &str,
    body: serde_json::Value,
) -> Result<T, String> {
    let url = format!("{}{}", ctx.api_base_url, path);
    let response = ctx
        .client
        .post(url)
        .bearer_auth(&ctx.token)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("请求失败: {e}"))?;
    parse_response(response).await
}

async fn parse_response<T: for<'de> Deserialize<'de>>(response: reqwest::Response) -> Result<T, String> {
    let status = response.status();
    let text = response.text().await.map_err(|e| format!("读取响应失败: {e}"))?;
    if !status.is_success() {
        return Err(format!("请求失败 ({status}): {text}"));
    }
    let envelope: ApiEnvelope<T> = serde_json::from_str(&text).map_err(|e| format!("解析响应失败: {e}: {text}"))?;
    if envelope.code != 200 {
        return Err(format!("{}: {}", envelope.code, envelope.message));
    }
    envelope.data.ok_or_else(|| "响应缺少 data".to_string())
}

fn find_resumable_upload<R: Runtime>(
    ctx: &UploadContext<R>,
    parent_id: Option<i64>,
    file: &LocalUploadFile,
    md5_hash: &str,
) -> Option<ResumableUploadRecord> {
    ctx.resumable_uploads
        .iter()
        .find(|record| {
            record.parent_id == parent_id
                && record.file_name == file.file_name
                && record.display_name == file.display_name
                && record.file_size == file.file_size
                && record.md5_hash == md5_hash
        })
        .cloned()
}

struct LocalUploadPlan {
    files: Vec<LocalUploadFile>,
    directories: Vec<Vec<String>>,
}

fn collect_upload_plan(root: &Path) -> Result<LocalUploadPlan, String> {
    let root_name = root.file_name().and_then(|name| name.to_str()).unwrap_or("folder").to_string();
    let mut files = Vec::new();
    let mut directories = Vec::new();
    collect_files_inner(root, root, vec![root_name], &mut files, &mut directories)?;
    Ok(LocalUploadPlan { files, directories })
}

fn collect_files_inner(
    root: &Path,
    current: &Path,
    directory_path: Vec<String>,
    files: &mut Vec<LocalUploadFile>,
    directories: &mut Vec<Vec<String>>,
) -> Result<(), String> {
    directories.push(directory_path.clone());
    let entries = fs::read_dir(current).map_err(|e| format!("读取文件夹失败: {e}"))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("读取文件夹项失败: {e}"))?;
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();
        let metadata = entry.metadata().map_err(|e| format!("读取文件信息失败: {e}"))?;
        if metadata.is_dir() {
            let mut child_path = directory_path.clone();
            child_path.push(name);
            collect_files_inner(root, &path, child_path, files, directories)?;
        } else if metadata.is_file() {
            let mut display_parts = directory_path.clone();
            display_parts.push(name.clone());
            let relative = path.strip_prefix(root).unwrap_or(&path);
            let file_name = relative.file_name().and_then(|name| name.to_str()).unwrap_or(&name).to_string();
            files.push(LocalUploadFile {
                path,
                file_name,
                display_name: display_parts.join("/"),
                directory_path: directory_path.clone(),
                file_size: metadata.len(),
            });
        }
    }
    Ok(())
}

fn collect_directory_paths(root_name: &str, plan: &LocalUploadPlan) -> Vec<Vec<String>> {
    let mut keys = HashMap::<String, Vec<String>>::new();
    keys.insert(root_name.to_string(), vec![root_name.to_string()]);
    for directory in &plan.directories {
        keys.insert(directory.join("/"), directory.clone());
    }
    for file in &plan.files {
        for depth in 1..=file.directory_path.len() {
            let path = file.directory_path[..depth].to_vec();
            keys.insert(path.join("/"), path);
        }
    }
    let mut paths: Vec<Vec<String>> = keys.into_values().collect();
    paths.sort_by_key(|path| path.len());
    paths
}

fn directory_path_key(path: &[String], base_parent_id: Option<i64>) -> String {
    format!("{}:{}", base_parent_id.map(|id| id.to_string()).unwrap_or_else(|| "root".to_string()), path.join("/"))
}

fn compute_md5(path: &Path) -> std::io::Result<String> {
    let mut file = fs::File::open(path)?;
    let mut context = md5::Context::new();
    let mut buffer = [0u8; 1024 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        context.consume(&buffer[..read]);
    }
    Ok(format!("{:x}", context.finalize()))
}

fn read_chunk(path: &Path, chunk_number: u64, chunk_size: u64) -> std::io::Result<Vec<u8>> {
    let mut file = fs::File::open(path)?;
    file.seek(SeekFrom::Start(chunk_number * chunk_size))?;
    let mut buffer = vec![0; chunk_size as usize];
    let read = file.read(&mut buffer)?;
    buffer.truncate(read);
    Ok(buffer)
}

fn total_chunks(file_size: u64) -> u64 {
    std::cmp::max(1, (file_size + CHUNK_SIZE - 1) / CHUNK_SIZE)
}

fn upload_progress(total_chunks: u64, uploaded_chunks: u64) -> f64 {
    if total_chunks == 0 {
        return 100.0;
    }
    let progress = 30.0 + (uploaded_chunks as f64 / total_chunks as f64) * 65.0;
    (progress * 100.0).round() / 100.0
}

fn emit_item<R: Runtime>(
    ctx: &UploadContext<R>,
    file: &LocalUploadFile,
    id: &str,
    parent_id: Option<i64>,
    status: &str,
    progress: f64,
    chunk_progress: &str,
    upload_id: Option<String>,
    error: Option<String>,
    md5_hash: Option<String>,
    total_chunks: u64,
    resumable: bool,
) {
    let event = DesktopUploadItemEvent {
        id: id.to_string(),
        batch_id: ctx.batch_id.clone(),
        file_name: file.file_name.clone(),
        display_name: file.display_name.clone(),
        parent_id,
        status: status.to_string(),
        progress,
        chunk_progress: chunk_progress.to_string(),
        error,
        upload_id,
        file_size: file.file_size,
        md5_hash,
        total_chunks,
        resumable,
        resumable_record: None,
    };
    let _ = ctx.app.emit("desktop-upload:item-updated", event);
}

fn emit_record<R: Runtime>(ctx: &UploadContext<R>, record: ResumableUploadRecord) {
    let _ = ctx.app.emit("desktop-upload:resumable-record", record);
}

fn emit_directory_record<R: Runtime>(ctx: &UploadContext<R>, record: ResumableDirectoryRecord) {
    let _ = ctx.app.emit("desktop-upload:directory-record", DesktopUploadDirectoryEvent { record });
}

fn e2e_upload_directory() -> Option<PathBuf> {
    let value = env::var_os("BCD_E2E_UPLOAD_DIR")?;
    if value.is_empty() {
        return None;
    }
    Some(PathBuf::from(value))
}

fn epoch_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::{collect_directory_paths, directory_path_key, total_chunks, LocalUploadFile, LocalUploadPlan, CHUNK_SIZE};
    use std::path::PathBuf;

    #[test]
    fn builds_directory_path_keys() {
        assert_eq!(directory_path_key(&["Project".into(), "src".into()], None), "root:Project/src");
        assert_eq!(directory_path_key(&["Project".into()], Some(10)), "10:Project");
    }

    #[test]
    fn calculates_total_chunks() {
        assert_eq!(total_chunks(0), 1);
        assert_eq!(total_chunks(1), 1);
        assert_eq!(total_chunks(CHUNK_SIZE), 1);
        assert_eq!(total_chunks(CHUNK_SIZE + 1), 2);
    }

    #[test]
    fn collects_parent_directories_in_depth_order() {
        let files = vec![LocalUploadFile {
            path: PathBuf::from("Project/src/main.rs"),
            file_name: "main.rs".into(),
            display_name: "Project/src/main.rs".into(),
            directory_path: vec!["Project".into(), "src".into()],
            file_size: 1,
        }];
        let plan = LocalUploadPlan {
            files,
            directories: vec![vec!["Project".into()], vec!["Project".into(), "src".into()]],
        };
        let paths = collect_directory_paths("Project", &plan);
        assert_eq!(paths[0], vec!["Project"]);
        assert_eq!(paths[1], vec!["Project", "src"]);
    }
}
