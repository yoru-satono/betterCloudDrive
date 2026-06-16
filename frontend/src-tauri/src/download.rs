use reqwest::header::{CONTENT_LENGTH, CONTENT_RANGE, RANGE};
use serde::Serialize;
use std::{
    env,
    fs,
    io::Write,
    path::{Path, PathBuf},
};
use tauri::{AppHandle, Runtime};
use tauri_plugin_dialog::DialogExt;

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

#[tauri::command]
pub async fn download_desktop_file<R: Runtime>(
    app: AppHandle<R>,
    url: String,
    file_name: String,
    token: Option<String>,
) -> Result<DesktopDownloadResult, String> {
    if let Some(directory) = e2e_download_directory()? {
        let target = unique_file_path(&directory, &file_name);
        download_url_to_path(&url, token.as_deref(), &target).await?;
        return Ok(DesktopDownloadResult {
            saved: true,
            path: Some(target.to_string_lossy().to_string()),
        });
    }

    let target = app
        .dialog()
        .file()
        .set_file_name(sanitize_path_segment(&file_name))
        .blocking_save_file()
        .and_then(|path| path.into_path().ok());

    let Some(target) = target else {
        return Ok(DesktopDownloadResult {
            saved: false,
            path: None,
        });
    };

    download_url_to_path(&url, token.as_deref(), &target).await?;
    Ok(DesktopDownloadResult {
        saved: true,
        path: Some(target.to_string_lossy().to_string()),
    })
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

    let parent = app
        .dialog()
        .file()
        .blocking_pick_folder()
        .and_then(|path| path.into_path().ok());

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
pub async fn download_desktop_file_to_path(
    url: String,
    file_name: String,
    directory_path: String,
    token: Option<String>,
) -> Result<DesktopDownloadResult, String> {
    let directory = PathBuf::from(directory_path);
    fs::create_dir_all(&directory).map_err(|e| format!("创建目录失败: {e}"))?;
    let target = unique_file_path(&directory, &file_name);

    download_url_to_path(&url, token.as_deref(), &target).await?;
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

pub async fn download_url_to_path(url: &str, token: Option<&str>, target: &Path) -> Result<(), String> {
    let part_path = part_path(target);
    if let Some(parent) = part_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
    }

    let start = fs::metadata(&part_path).map(|m| m.len()).unwrap_or(0);
    let client = reqwest::Client::new();
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
        return download_from_start(url, token, target).await;
    }
    if !response.status().is_success() {
        return Err(format!("下载失败 ({})", response.status()));
    }
    if start > 0 && response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        fs::remove_file(&part_path).map_err(|e| format!("重置半成品失败: {e}"))?;
        return download_from_start(url, token, target).await;
    }

    let expected_total = response_total_size(&response, start).unwrap_or(0);
    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&part_path)
        .map_err(|e| format!("打开半成品失败: {e}"))?;
    let mut response = response;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("读取下载数据失败: {e}"))? {
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

async fn download_from_start(url: &str, token: Option<&str>, target: &Path) -> Result<(), String> {
    let part_path = part_path(target);
    let client = reqwest::Client::new();
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    let mut response = request.send().await.map_err(|e| format!("下载请求失败: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("下载失败 ({})", response.status()));
    }
    let mut file = fs::File::create(&part_path).map_err(|e| format!("创建半成品失败: {e}"))?;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("读取下载数据失败: {e}"))? {
        file.write_all(&chunk).map_err(|e| format!("写入文件失败: {e}"))?;
    }
    file.flush().map_err(|e| format!("刷新文件失败: {e}"))?;
    replace_file(&part_path, target)
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
        if !candidate.exists() || part.exists() {
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
    use super::{build_unique_path_name, part_path, sanitize_path_segment};
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
}
