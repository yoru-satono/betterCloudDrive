use reqwest::{Client, Proxy};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::{Arc, Mutex as StdMutex},
    time::{Duration, Instant},
};
use tauri::{AppHandle, Manager, Runtime};
use tauri_plugin_dialog::DialogExt;
use tokio::sync::{Mutex, Notify};

const SETTINGS_FILE_NAME: &str = "settings.json";
const DEFAULT_CONCURRENCY: u8 = 3;
const MAX_CONCURRENCY: u8 = 16;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum ProxyMode {
    System,
    Manual,
    Disabled,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct DesktopSettings {
    pub upload_limit_bytes_per_sec: Option<u64>,
    pub download_limit_bytes_per_sec: Option<u64>,
    pub max_concurrent_uploads: u8,
    pub max_concurrent_downloads: u8,
    pub default_download_dir: Option<String>,
    pub proxy_mode: ProxyMode,
    pub proxy_url: String,
    pub proxy_username: String,
    pub proxy_password: String,
}

impl Default for DesktopSettings {
    fn default() -> Self {
        Self {
            upload_limit_bytes_per_sec: None,
            download_limit_bytes_per_sec: None,
            max_concurrent_uploads: DEFAULT_CONCURRENCY,
            max_concurrent_downloads: DEFAULT_CONCURRENCY,
            default_download_dir: None,
            proxy_mode: ProxyMode::System,
            proxy_url: String::new(),
            proxy_username: String::new(),
            proxy_password: String::new(),
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct TransferLimiters {
    upload: Arc<RateLimiter>,
    download: Arc<RateLimiter>,
    upload_slots: Arc<ConcurrencyLimiter>,
    download_slots: Arc<ConcurrencyLimiter>,
}

impl TransferLimiters {
    pub fn upload(&self) -> Arc<RateLimiter> {
        self.upload.clone()
    }

    pub fn download(&self) -> Arc<RateLimiter> {
        self.download.clone()
    }

    pub async fn acquire_upload(&self, limit: u8) -> ConcurrencyPermit {
        self.upload_slots.acquire(limit).await
    }

    pub async fn acquire_download(&self, limit: u8) -> ConcurrencyPermit {
        self.download_slots.acquire(limit).await
    }
}

#[derive(Debug, Default)]
pub struct RateLimiter {
    state: Mutex<RateLimiterState>,
}

#[derive(Debug)]
struct RateLimiterState {
    next_available_at: Instant,
}

impl Default for RateLimiterState {
    fn default() -> Self {
        Self {
            next_available_at: Instant::now(),
        }
    }
}

impl RateLimiter {
    pub async fn throttle(&self, bytes: u64, limit_bytes_per_sec: Option<u64>) {
        let Some(limit) = limit_bytes_per_sec.filter(|limit| *limit > 0) else {
            return;
        };
        if bytes == 0 {
            return;
        }

        let sleep_for = {
            let mut state = self.state.lock().await;
            let now = Instant::now();
            if state.next_available_at < now {
                state.next_available_at = now;
            }
            let duration = Duration::from_secs_f64(bytes as f64 / limit as f64);
            state.next_available_at += duration;
            state.next_available_at.saturating_duration_since(now)
        };

        if !sleep_for.is_zero() {
            tokio::time::sleep(sleep_for).await;
        }
    }
}

#[derive(Debug, Default)]
pub struct ConcurrencyLimiter {
    state: StdMutex<ConcurrencyLimiterState>,
    notify: Notify,
}

#[derive(Debug, Default)]
struct ConcurrencyLimiterState {
    active: u8,
}

pub struct ConcurrencyPermit {
    limiter: Arc<ConcurrencyLimiter>,
}

impl ConcurrencyLimiter {
    async fn acquire(self: &Arc<Self>, limit: u8) -> ConcurrencyPermit {
        let limit = limit.clamp(1, MAX_CONCURRENCY);
        loop {
            let notified = self.notify.notified();
            {
                let mut state = self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                if state.active < limit {
                    state.active += 1;
                    return ConcurrencyPermit {
                        limiter: self.clone(),
                    };
                }
            }
            notified.await;
        }
    }

    fn release(&self) {
        {
            let mut state = self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            state.active = state.active.saturating_sub(1);
        }
        self.notify.notify_waiters();
    }
}

impl Drop for ConcurrencyPermit {
    fn drop(&mut self) {
        self.limiter.release();
    }
}

#[tauri::command]
pub fn get_desktop_settings<R: Runtime>(app: AppHandle<R>) -> Result<DesktopSettings, String> {
    read_desktop_settings(&app)
}

#[tauri::command]
pub fn save_desktop_settings<R: Runtime>(
    app: AppHandle<R>,
    settings: DesktopSettings,
) -> Result<DesktopSettings, String> {
    let normalized = normalize_settings(settings)?;
    write_desktop_settings(&app, &normalized)?;
    Ok(normalized)
}

#[tauri::command]
pub fn choose_default_download_directory<R: Runtime>(
    app: AppHandle<R>,
) -> Result<Option<String>, String> {
    Ok(app
        .dialog()
        .file()
        .blocking_pick_folder()
        .and_then(|path| path.into_path().ok())
        .map(|path| path.to_string_lossy().to_string()))
}

pub fn read_desktop_settings<R: Runtime>(app: &AppHandle<R>) -> Result<DesktopSettings, String> {
    read_settings_file(&settings_path(app)?)
}

pub fn build_client(settings: &DesktopSettings) -> Result<Client, String> {
    let mut builder = Client::builder();
    match settings.proxy_mode {
        ProxyMode::System => {}
        ProxyMode::Disabled => {
            builder = builder.no_proxy();
        }
        ProxyMode::Manual => {
            let url = settings.proxy_url.trim();
            if url.is_empty() {
                return Err("请填写代理地址".to_string());
            }
            let mut proxy = Proxy::all(url).map_err(|e| format!("代理地址无效: {e}"))?;
            let username = settings.proxy_username.trim();
            if !username.is_empty() || !settings.proxy_password.is_empty() {
                proxy = proxy.basic_auth(username, &settings.proxy_password);
            }
            builder = builder.proxy(proxy);
        }
    }
    builder.build().map_err(|e| format!("创建网络客户端失败: {e}"))
}

pub fn normalize_settings(mut settings: DesktopSettings) -> Result<DesktopSettings, String> {
    settings.max_concurrent_uploads = settings.max_concurrent_uploads.clamp(1, MAX_CONCURRENCY);
    settings.max_concurrent_downloads = settings.max_concurrent_downloads.clamp(1, MAX_CONCURRENCY);
    settings.upload_limit_bytes_per_sec = normalize_limit(settings.upload_limit_bytes_per_sec);
    settings.download_limit_bytes_per_sec = normalize_limit(settings.download_limit_bytes_per_sec);
    settings.proxy_url = settings.proxy_url.trim().to_string();
    settings.proxy_username = settings.proxy_username.trim().to_string();
    settings.default_download_dir = settings
        .default_download_dir
        .and_then(|path| {
            let trimmed = path.trim();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed.to_string())
            }
        });

    if settings.proxy_mode == ProxyMode::Manual {
        let lower = settings.proxy_url.to_ascii_lowercase();
        let valid = lower.starts_with("http://")
            || lower.starts_with("https://")
            || lower.starts_with("socks5://");
        if !valid {
            return Err("代理地址必须以 http://、https:// 或 socks5:// 开头".to_string());
        }
    }

    Ok(settings)
}

fn normalize_limit(limit: Option<u64>) -> Option<u64> {
    limit.filter(|value| *value > 0)
}

fn read_settings_file(path: &Path) -> Result<DesktopSettings, String> {
    if !path.exists() {
        return Ok(DesktopSettings::default());
    }

    let text = fs::read_to_string(path).map_err(|e| format!("读取设置失败: {e}"))?;
    let settings: DesktopSettings =
        serde_json::from_str(&text).map_err(|e| format!("解析设置失败: {e}"))?;
    normalize_settings(settings)
}

fn write_desktop_settings<R: Runtime>(
    app: &AppHandle<R>,
    settings: &DesktopSettings,
) -> Result<(), String> {
    let path = settings_path(app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("创建设置目录失败: {e}"))?;
    }
    let text = serde_json::to_string_pretty(settings).map_err(|e| format!("序列化设置失败: {e}"))?;
    fs::write(path, text).map_err(|e| format!("保存设置失败: {e}"))
}

fn settings_path<R: Runtime>(app: &AppHandle<R>) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|dir| dir.join(SETTINGS_FILE_NAME))
        .map_err(|e| format!("获取设置目录失败: {e}"))
}

#[cfg(test)]
mod tests {
    use super::{normalize_settings, ConcurrencyLimiter, DesktopSettings, ProxyMode, RateLimiter};
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    #[test]
    fn normalizes_empty_and_out_of_range_values() {
        let settings = DesktopSettings {
            upload_limit_bytes_per_sec: Some(0),
            download_limit_bytes_per_sec: Some(42),
            max_concurrent_uploads: 0,
            max_concurrent_downloads: 99,
            default_download_dir: Some("  ".into()),
            proxy_mode: ProxyMode::System,
            proxy_url: "  ".into(),
            proxy_username: " user ".into(),
            proxy_password: "pass".into(),
        };

        let normalized = normalize_settings(settings).expect("settings should normalize");
        assert_eq!(normalized.upload_limit_bytes_per_sec, None);
        assert_eq!(normalized.download_limit_bytes_per_sec, Some(42));
        assert_eq!(normalized.max_concurrent_uploads, 1);
        assert_eq!(normalized.max_concurrent_downloads, 16);
        assert_eq!(normalized.default_download_dir, None);
        assert_eq!(normalized.proxy_username, "user");
    }

    #[test]
    fn rejects_manual_proxy_without_supported_scheme() {
        let settings = DesktopSettings {
            proxy_mode: ProxyMode::Manual,
            proxy_url: "localhost:8080".into(),
            ..DesktopSettings::default()
        };

        assert!(normalize_settings(settings).is_err());
    }

    #[tokio::test]
    async fn limiter_does_not_sleep_without_limit() {
        let limiter = RateLimiter::default();
        let started = Instant::now();
        limiter.throttle(1024, None).await;
        assert!(started.elapsed() < Duration::from_millis(50));
    }

    #[tokio::test]
    async fn limiter_allows_chunks_larger_than_limit() {
        let limiter = RateLimiter::default();
        let started = Instant::now();
        limiter.throttle(2048, Some(u64::MAX)).await;
        assert!(started.elapsed() < Duration::from_millis(50));
    }

    #[tokio::test]
    async fn concurrency_limiter_releases_slots_on_drop() {
        let limiter = Arc::new(ConcurrencyLimiter::default());
        let first = limiter.acquire(1).await;
        let second = {
            let limiter = limiter.clone();
            tokio::spawn(async move { limiter.acquire(1).await })
        };

        tokio::time::sleep(Duration::from_millis(20)).await;
        assert!(!second.is_finished());

        drop(first);
        let permit = tokio::time::timeout(Duration::from_secs(1), second)
            .await
            .expect("waiting task should acquire after release")
            .expect("task should not panic");
        drop(permit);
    }
}
