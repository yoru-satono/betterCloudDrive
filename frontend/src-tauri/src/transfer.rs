use crate::{download, settings::TransferLimiters, upload};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    fs,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tauri::{AppHandle, Emitter, Manager, Runtime, State};
use tokio::sync::Notify;

const TRANSFERS_FILE_NAME: &str = "transfers.json";

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum TransferDirection {
    Upload,
    Download,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum TransferNodeKind {
    File,
    Folder,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum TransferStatus {
    Pending,
    Hashing,
    Transferring,
    Paused,
    Done,
    Instant,
    Error,
    Canceled,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferNode {
    pub id: String,
    pub task_id: String,
    pub parent_id: Option<String>,
    pub direction: TransferDirection,
    pub kind: TransferNodeKind,
    pub name: String,
    pub display_path: String,
    pub path: Vec<String>,
    #[serde(default)]
    pub remote_path: Vec<String>,
    pub status: TransferStatus,
    pub progress: f64,
    pub bytes_done: u64,
    pub bytes_total: u64,
    pub error: Option<String>,
    pub local_path: Option<String>,
    pub target_path: Option<String>,
    pub remote_file_id: Option<i64>,
    pub remote_parent_id: Option<i64>,
    pub upload_id: Option<String>,
    pub md5_hash: Option<String>,
    pub total_chunks: u64,
    pub completed_chunks: u64,
    pub chunk_size: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferTask {
    pub id: String,
    pub direction: TransferDirection,
    pub root_node_id: String,
    pub name: String,
    pub status: TransferStatus,
    pub progress: f64,
    pub bytes_done: u64,
    pub bytes_total: u64,
    pub created_at: u64,
    pub updated_at: u64,
    pub api_base_url: String,
    pub base_parent_id: Option<i64>,
    pub nodes: Vec<TransferNode>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferQueue {
    pub tasks: Vec<TransferTask>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreTransfersRequest {
    pub token: String,
    pub api_base_url: String,
    pub resumable_uploads: Vec<upload::ResumableUploadRecord>,
    pub resumable_directories: Vec<upload::ResumableDirectoryRecord>,
}

#[derive(Clone, Debug, Default)]
pub struct DesktopTransferState {
    inner: Arc<TransferStateInner>,
}

#[derive(Debug, Default)]
pub struct TransferStateInner {
    queue: Mutex<TransferQueue>,
    loaded: Mutex<bool>,
    running_tasks: Mutex<HashSet<String>>,
    notify: Notify,
}

#[derive(Clone, Debug, Default)]
pub struct TransferNodePatch {
    pub status: Option<TransferStatus>,
    pub progress: Option<f64>,
    pub bytes_done: Option<u64>,
    pub bytes_total: Option<u64>,
    pub error: Option<Option<String>>,
    pub upload_id: Option<Option<String>>,
    pub md5_hash: Option<Option<String>>,
    pub total_chunks: Option<u64>,
    pub completed_chunks: Option<u64>,
    pub chunk_size: Option<u64>,
    pub remote_parent_id: Option<Option<i64>>,
    pub remote_path: Option<Vec<String>>,
}

#[tauri::command]
pub fn get_transfer_queue<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, DesktopTransferState>,
) -> Result<TransferQueue, String> {
    state.ensure_loaded(&app)?;
    Ok(state.queue_snapshot())
}

#[tauri::command]
pub async fn restore_desktop_transfers<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, DesktopTransferState>,
    upload_state: State<'_, upload::DesktopUploadState>,
    limiters: State<'_, TransferLimiters>,
    request: RestoreTransfersRequest,
) -> Result<TransferQueue, String> {
    state.ensure_loaded(&app)?;
    state.update_api_base_for_unfinished(&app, &request.api_base_url)?;
    let queue = state.queue_snapshot();
    for task in queue.tasks.iter().filter(|task| should_resume_task(task)) {
        match task.direction {
            TransferDirection::Upload => {
                if state.mark_running(&task.id) {
                    upload::spawn_resume_upload_task(
                        app.clone(),
                        upload_state.inner().clone(),
                        state.shared_inner(),
                        limiters.inner().clone(),
                        task.id.clone(),
                        request.token.clone(),
                        request.api_base_url.clone(),
                        request.resumable_uploads.clone(),
                        request.resumable_directories.clone(),
                    );
                }
            }
            TransferDirection::Download => {
                if state.mark_running(&task.id) {
                    download::spawn_resume_download_task(
                        app.clone(),
                        state.shared_inner(),
                        limiters.inner().clone(),
                        task.id.clone(),
                        request.token.clone(),
                        request.api_base_url.clone(),
                    );
                }
            }
        }
    }
    Ok(state.queue_snapshot())
}

#[tauri::command]
pub async fn pause_transfer_node<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, DesktopTransferState>,
    node_id: String,
) -> Result<TransferQueue, String> {
    state.ensure_loaded(&app)?;
    state.set_node_tree_status(&app, &node_id, TransferStatus::Paused)?;
    Ok(state.queue_snapshot())
}

#[tauri::command]
pub async fn resume_transfer_node<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, DesktopTransferState>,
    upload_state: State<'_, upload::DesktopUploadState>,
    limiters: State<'_, TransferLimiters>,
    node_id: String,
    token: String,
    api_base_url: String,
) -> Result<TransferQueue, String> {
    state.ensure_loaded(&app)?;
    let task = state.set_node_tree_status(&app, &node_id, TransferStatus::Pending)?;
    state.inner.notify.notify_waiters();
    if let Some(task) = task {
        if state.mark_running(&task.id) {
            match task.direction {
                TransferDirection::Upload => upload::spawn_resume_upload_task(
                    app.clone(),
                    upload_state.inner().clone(),
                    state.shared_inner(),
                    limiters.inner().clone(),
                    task.id,
                    token,
                    api_base_url,
                    Vec::new(),
                    Vec::new(),
                ),
                TransferDirection::Download => download::spawn_resume_download_task(
                    app.clone(),
                    state.shared_inner(),
                    limiters.inner().clone(),
                    task.id,
                    token,
                    api_base_url,
                ),
            }
        }
    }
    Ok(state.queue_snapshot())
}

#[tauri::command]
pub async fn cancel_transfer_node<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, DesktopTransferState>,
    node_id: String,
) -> Result<TransferQueue, String> {
    state.ensure_loaded(&app)?;
    state.set_node_tree_status(&app, &node_id, TransferStatus::Canceled)?;
    state.inner.notify.notify_waiters();
    Ok(state.queue_snapshot())
}

#[tauri::command]
pub fn clear_finished_transfers<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, DesktopTransferState>,
) -> Result<TransferQueue, String> {
    state.ensure_loaded(&app)?;
    state.clear_finished(&app)?;
    Ok(state.queue_snapshot())
}

impl DesktopTransferState {
    pub fn from_inner(inner: Arc<TransferStateInner>) -> Self {
        Self { inner }
    }

    pub fn shared_inner(&self) -> Arc<TransferStateInner> {
        self.inner.clone()
    }

    pub fn ensure_loaded<R: Runtime>(&self, app: &AppHandle<R>) -> Result<(), String> {
        let mut loaded = self.inner.loaded.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if *loaded {
            return Ok(());
        }
        let queue = read_queue_file(&transfers_path(app)?)?;
        *self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = queue;
        *loaded = true;
        Ok(())
    }

    pub fn add_task<R: Runtime>(&self, app: &AppHandle<R>, task: TransferTask) -> Result<(), String> {
        self.ensure_loaded(app)?;
        {
            let mut queue = self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            queue.tasks.retain(|existing| existing.id != task.id);
            queue.tasks.push(task);
            normalize_queue(&mut queue);
            write_queue_file(&transfers_path(app)?, &queue)?;
        }
        self.emit_queue(app);
        Ok(())
    }

    pub fn update_node<R: Runtime>(
        &self,
        app: &AppHandle<R>,
        node_id: &str,
        patch: TransferNodePatch,
    ) -> Result<(), String> {
        self.ensure_loaded(app)?;
        let node = {
            let mut queue = self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            let node = apply_node_patch(&mut queue, node_id, patch);
            normalize_queue(&mut queue);
            write_queue_file(&transfers_path(app)?, &queue)?;
            node
        };
        if let Some(node) = node {
            let _ = app.emit("transfer:node-updated", node);
        }
        self.emit_queue(app);
        Ok(())
    }

    pub async fn wait_if_blocked<R: Runtime>(
        &self,
        app: &AppHandle<R>,
        node_id: &str,
    ) -> Result<(), String> {
        loop {
            self.ensure_loaded(app)?;
            let state = {
                let queue = self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                node_control_state(&queue, node_id)
            };
            match state {
                NodeControlState::Ready => return Ok(()),
                NodeControlState::Paused => self.inner.notify.notified().await,
                NodeControlState::Canceled => return Err("transfer canceled".to_string()),
            }
        }
    }

    pub fn finish_task<R: Runtime>(&self, app: &AppHandle<R>, task_id: &str) -> Result<(), String> {
        self.ensure_loaded(app)?;
        {
            let mut running = self.inner.running_tasks.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            running.remove(task_id);
        }
        {
            let mut queue = self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            normalize_queue(&mut queue);
            write_queue_file(&transfers_path(app)?, &queue)?;
        }
        let _ = app.emit("transfer:task-completed", serde_json::json!({ "taskId": task_id }));
        self.emit_queue(app);
        Ok(())
    }

    pub fn mark_running(&self, task_id: &str) -> bool {
        let mut running = self.inner.running_tasks.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        running.insert(task_id.to_string())
    }

    pub fn queue_snapshot(&self) -> TransferQueue {
        self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clone()
    }

    pub fn clear_finished<R: Runtime>(&self, app: &AppHandle<R>) -> Result<(), String> {
        self.ensure_loaded(app)?;
        {
            let mut queue = self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            queue.tasks.retain(|task| !is_terminal(&task.status));
            write_queue_file(&transfers_path(app)?, &queue)?;
        }
        self.emit_queue(app);
        Ok(())
    }

    fn update_api_base_for_unfinished<R: Runtime>(
        &self,
        app: &AppHandle<R>,
        api_base_url: &str,
    ) -> Result<(), String> {
        {
            let mut queue = self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            for task in &mut queue.tasks {
                if !is_terminal(&task.status) {
                    task.api_base_url = api_base_url.trim_end_matches('/').to_string();
                    task.updated_at = epoch_millis();
                }
            }
            write_queue_file(&transfers_path(app)?, &queue)?;
        }
        self.emit_queue(app);
        Ok(())
    }

    fn set_node_tree_status<R: Runtime>(
        &self,
        app: &AppHandle<R>,
        node_id: &str,
        status: TransferStatus,
    ) -> Result<Option<TransferTask>, String> {
        let task = {
            let mut queue = self.inner.queue.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            let Some((task_index, descendants)) = descendants_for_node(&queue, node_id) else {
                return Ok(None);
            };
            for descendant_id in descendants {
                if let Some(node) = queue.tasks[task_index].nodes.iter_mut().find(|node| node.id == descendant_id) {
                    if can_transition_by_user(&node.status) {
                        node.status = status.clone();
                        node.error = None;
                        node.progress = if status == TransferStatus::Canceled { 0.0 } else { node.progress };
                        node.updated_touch();
                    }
                }
            }
            normalize_queue(&mut queue);
            let task = queue.tasks.get(task_index).cloned();
            write_queue_file(&transfers_path(app)?, &queue)?;
            task
        };
        self.emit_queue(app);
        Ok(task)
    }

    fn emit_queue<R: Runtime>(&self, app: &AppHandle<R>) {
        let _ = app.emit("transfer:queue-updated", self.queue_snapshot());
    }
}

impl TransferNode {
    fn updated_touch(&mut self) {
        self.progress = normalize_progress(self.progress);
    }
}

pub fn new_task_id(prefix: &str) -> String {
    format!("{prefix}-{}", epoch_millis())
}

pub fn upload_node_id(task_id: &str, display_path: &str) -> String {
    format!("{task_id}:{}", display_path.replace('\\', "/"))
}

pub fn is_terminal(status: &TransferStatus) -> bool {
    matches!(
        status,
        TransferStatus::Done | TransferStatus::Instant | TransferStatus::Error | TransferStatus::Canceled
    )
}

pub fn epoch_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn should_resume_task(task: &TransferTask) -> bool {
    !is_terminal(&task.status) && task.status != TransferStatus::Paused
}

fn can_transition_by_user(status: &TransferStatus) -> bool {
    !matches!(status, TransferStatus::Done | TransferStatus::Instant)
}

fn read_queue_file(path: &PathBuf) -> Result<TransferQueue, String> {
    if !path.exists() {
        return Ok(TransferQueue::default());
    }
    let text = fs::read_to_string(path).map_err(|e| format!("读取传输队列失败: {e}"))?;
    serde_json::from_str(&text).map_err(|e| format!("解析传输队列失败: {e}"))
}

fn write_queue_file(path: &PathBuf, queue: &TransferQueue) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("创建传输队列目录失败: {e}"))?;
    }
    let text = serde_json::to_string_pretty(queue).map_err(|e| format!("序列化传输队列失败: {e}"))?;
    fs::write(path, text).map_err(|e| format!("保存传输队列失败: {e}"))
}

fn transfers_path<R: Runtime>(app: &AppHandle<R>) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|dir| dir.join(TRANSFERS_FILE_NAME))
        .map_err(|e| format!("获取传输队列目录失败: {e}"))
}

fn apply_node_patch(
    queue: &mut TransferQueue,
    node_id: &str,
    patch: TransferNodePatch,
) -> Option<TransferNode> {
    for task in &mut queue.tasks {
        if let Some(node) = task.nodes.iter_mut().find(|node| node.id == node_id) {
            if let Some(status) = patch.status {
                node.status = status;
            }
            if let Some(progress) = patch.progress {
                node.progress = normalize_progress(progress);
            }
            if let Some(bytes_done) = patch.bytes_done {
                node.bytes_done = bytes_done.min(node.bytes_total.max(bytes_done));
                if node.bytes_total > 0 {
                    node.progress = normalize_progress(node.bytes_done as f64 / node.bytes_total as f64 * 100.0);
                }
            }
            if let Some(bytes_total) = patch.bytes_total {
                node.bytes_total = bytes_total;
                if node.bytes_total > 0 {
                    node.progress = normalize_progress(node.bytes_done as f64 / node.bytes_total as f64 * 100.0);
                }
            }
            if let Some(error) = patch.error {
                node.error = error;
            }
            if let Some(upload_id) = patch.upload_id {
                node.upload_id = upload_id;
            }
            if let Some(md5_hash) = patch.md5_hash {
                node.md5_hash = md5_hash;
            }
            if let Some(total_chunks) = patch.total_chunks {
                node.total_chunks = total_chunks;
            }
            if let Some(completed_chunks) = patch.completed_chunks {
                node.completed_chunks = completed_chunks;
            }
            if let Some(chunk_size) = patch.chunk_size {
                node.chunk_size = chunk_size;
            }
            if let Some(remote_parent_id) = patch.remote_parent_id {
                node.remote_parent_id = remote_parent_id;
            }
            if let Some(remote_path) = patch.remote_path {
                node.remote_path = remote_path;
            }
            return Some(node.clone());
        }
    }
    None
}

fn normalize_queue(queue: &mut TransferQueue) {
    for task in &mut queue.tasks {
        normalize_task(task);
    }
}

fn normalize_task(task: &mut TransferTask) {
    let child_map = build_child_map(&task.nodes);
    let mut by_id: HashMap<String, TransferNode> = task
        .nodes
        .iter()
        .cloned()
        .map(|node| (node.id.clone(), node))
        .collect();
    normalize_node(&task.root_node_id, &child_map, &mut by_id);
    task.nodes = task
        .nodes
        .iter()
        .filter_map(|node| by_id.get(&node.id).cloned())
        .collect();
    if let Some(root) = by_id.get(&task.root_node_id) {
        task.status = root.status.clone();
        task.progress = root.progress;
        task.bytes_done = root.bytes_done;
        task.bytes_total = root.bytes_total;
    }
    task.updated_at = epoch_millis();
}

fn normalize_node(
    node_id: &str,
    child_map: &HashMap<Option<String>, Vec<String>>,
    by_id: &mut HashMap<String, TransferNode>,
) -> Option<TransferNode> {
    let children = child_map.get(&Some(node_id.to_string())).cloned().unwrap_or_default();
    if children.is_empty() {
        if let Some(node) = by_id.get_mut(node_id) {
            if node.bytes_total > 0 {
                node.progress = normalize_progress(node.bytes_done as f64 / node.bytes_total as f64 * 100.0);
            }
            if matches!(node.status, TransferStatus::Done | TransferStatus::Instant) {
                node.bytes_done = node.bytes_total;
                node.progress = 100.0;
            }
            return Some(node.clone());
        }
        return None;
    }

    let mut child_nodes = Vec::new();
    for child_id in children {
        if let Some(child) = normalize_node(&child_id, child_map, by_id) {
            child_nodes.push(child);
        }
    }

    let bytes_total = child_nodes.iter().map(|node| node.bytes_total).sum();
    let bytes_done = child_nodes.iter().map(|node| node.bytes_done).sum();
    if let Some(node) = by_id.get_mut(node_id) {
        node.bytes_total = bytes_total;
        node.bytes_done = bytes_done;
        if node.remote_path.is_empty() {
            node.remote_path = node.path.clone();
        }
        node.progress = if bytes_total > 0 {
            normalize_progress(bytes_done as f64 / bytes_total as f64 * 100.0)
        } else if child_nodes.iter().all(|child| matches!(child.status, TransferStatus::Done | TransferStatus::Instant)) {
            100.0
        } else {
            node.progress
        };
        if node.status != TransferStatus::Paused && node.status != TransferStatus::Canceled {
            node.status = aggregate_status(&child_nodes);
        }
        return Some(node.clone());
    }
    None
}

fn aggregate_status(children: &[TransferNode]) -> TransferStatus {
    if children.is_empty() {
        return TransferStatus::Pending;
    }
    if children.iter().all(|node| matches!(node.status, TransferStatus::Done | TransferStatus::Instant)) {
        return TransferStatus::Done;
    }
    if children.iter().any(|node| matches!(node.status, TransferStatus::Hashing | TransferStatus::Transferring)) {
        return TransferStatus::Transferring;
    }
    if children.iter().any(|node| node.status == TransferStatus::Pending) {
        return TransferStatus::Pending;
    }
    if children.iter().any(|node| node.status == TransferStatus::Paused) {
        return TransferStatus::Paused;
    }
    if children.iter().any(|node| node.status == TransferStatus::Error) {
        return TransferStatus::Error;
    }
    if children.iter().all(|node| node.status == TransferStatus::Canceled) {
        return TransferStatus::Canceled;
    }
    TransferStatus::Pending
}

fn build_child_map(nodes: &[TransferNode]) -> HashMap<Option<String>, Vec<String>> {
    let mut map: HashMap<Option<String>, Vec<String>> = HashMap::new();
    for node in nodes {
        map.entry(node.parent_id.clone()).or_default().push(node.id.clone());
    }
    map
}

fn descendants_for_node(queue: &TransferQueue, node_id: &str) -> Option<(usize, Vec<String>)> {
    for (task_index, task) in queue.tasks.iter().enumerate() {
        if !task.nodes.iter().any(|node| node.id == node_id) {
            continue;
        }
        let child_map = build_child_map(&task.nodes);
        let mut ids = Vec::new();
        collect_descendants(node_id, &child_map, &mut ids);
        return Some((task_index, ids));
    }
    None
}

fn collect_descendants(
    node_id: &str,
    child_map: &HashMap<Option<String>, Vec<String>>,
    ids: &mut Vec<String>,
) {
    ids.push(node_id.to_string());
    if let Some(children) = child_map.get(&Some(node_id.to_string())) {
        for child in children {
            collect_descendants(child, child_map, ids);
        }
    }
}

enum NodeControlState {
    Ready,
    Paused,
    Canceled,
}

fn node_control_state(queue: &TransferQueue, node_id: &str) -> NodeControlState {
    let Some((task, node)) = find_task_and_node(queue, node_id) else {
        return NodeControlState::Canceled;
    };
    if node.status == TransferStatus::Canceled || task.status == TransferStatus::Canceled {
        return NodeControlState::Canceled;
    }
    if node.status == TransferStatus::Paused || task.status == TransferStatus::Paused {
        return NodeControlState::Paused;
    }
    let mut current_parent = node.parent_id.as_deref();
    while let Some(parent_id) = current_parent {
        if let Some(parent) = task.nodes.iter().find(|candidate| candidate.id == parent_id) {
            if parent.status == TransferStatus::Canceled {
                return NodeControlState::Canceled;
            }
            if parent.status == TransferStatus::Paused {
                return NodeControlState::Paused;
            }
            current_parent = parent.parent_id.as_deref();
        } else {
            break;
        }
    }
    NodeControlState::Ready
}

fn find_task_and_node<'a>(
    queue: &'a TransferQueue,
    node_id: &str,
) -> Option<(&'a TransferTask, &'a TransferNode)> {
    for task in &queue.tasks {
        if let Some(node) = task.nodes.iter().find(|node| node.id == node_id) {
            return Some((task, node));
        }
    }
    None
}

fn normalize_progress(progress: f64) -> f64 {
    let clamped = progress.clamp(0.0, 100.0);
    (clamped * 100.0).round() / 100.0
}

#[cfg(test)]
mod tests {
    use super::{
        aggregate_status, normalize_task, TransferDirection, TransferNode, TransferNodeKind,
        TransferStatus, TransferTask,
    };

    #[test]
    fn aggregates_folder_progress_from_children() {
        let mut task = task_with_nodes(vec![
            folder("root", None),
            file("a", Some("root"), 50, 100, TransferStatus::Transferring),
            file("b", Some("root"), 100, 100, TransferStatus::Done),
        ]);

        normalize_task(&mut task);

        assert_eq!(task.bytes_done, 150);
        assert_eq!(task.bytes_total, 200);
        assert_eq!(task.progress, 75.0);
        assert_eq!(task.status, TransferStatus::Transferring);
    }

    #[test]
    fn aggregates_paused_when_no_children_are_active() {
        let status = aggregate_status(&[
            node("a", TransferNodeKind::File, TransferStatus::Paused),
            node("b", TransferNodeKind::File, TransferStatus::Pending),
        ]);

        assert_eq!(status, TransferStatus::Pending);
    }

    fn task_with_nodes(nodes: Vec<TransferNode>) -> TransferTask {
        TransferTask {
            id: "task".into(),
            direction: TransferDirection::Download,
            root_node_id: "root".into(),
            name: "root".into(),
            status: TransferStatus::Pending,
            progress: 0.0,
            bytes_done: 0,
            bytes_total: 0,
            created_at: 1,
            updated_at: 1,
            api_base_url: "http://127.0.0.1/api/v1".into(),
            base_parent_id: None,
            nodes,
        }
    }

    fn folder(id: &str, parent_id: Option<&str>) -> TransferNode {
        let mut node = node(id, TransferNodeKind::Folder, TransferStatus::Pending);
        node.parent_id = parent_id.map(str::to_string);
        node
    }

    fn file(id: &str, parent_id: Option<&str>, done: u64, total: u64, status: TransferStatus) -> TransferNode {
        let mut node = node(id, TransferNodeKind::File, status);
        node.parent_id = parent_id.map(str::to_string);
        node.bytes_done = done;
        node.bytes_total = total;
        node
    }

    fn node(id: &str, kind: TransferNodeKind, status: TransferStatus) -> TransferNode {
        TransferNode {
            id: id.into(),
            task_id: "task".into(),
            parent_id: None,
            direction: TransferDirection::Download,
            kind,
            name: id.into(),
            display_path: id.into(),
            path: vec![id.into()],
            remote_path: Vec::new(),
            status,
            progress: 0.0,
            bytes_done: 0,
            bytes_total: 0,
            error: None,
            local_path: None,
            target_path: None,
            remote_file_id: None,
            remote_parent_id: None,
            upload_id: None,
            md5_hash: None,
            total_chunks: 0,
            completed_chunks: 0,
            chunk_size: 0,
        }
    }
}
