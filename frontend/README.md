# BetterCloudDrive Frontend

Vue 3 + Vite web client for BetterCloudDrive. The same app is also used by the Tauri desktop shell.

## Web Development

```bash
npm install
npm run dev
```

The web app uses relative `/api/v1` requests. Vite proxies `/api` and `/webdav`
to `http://localhost:8080` during development.

## Tauri Desktop

The desktop client connects to an external backend. Defaults:

- API: `http://127.0.0.1:8080/api/v1`
- Web share URL base: `http://127.0.0.1:3000`

Run the desktop client:

```bash
npm run tauri:dev
```

Build a desktop bundle:

```bash
npm run tauri:build
```

The login page exposes API and Web share URL fields in desktop mode.

## Tauri Window E2E

The Tauri E2E smoke test drives the real desktop window through `tauri-driver`
and WebdriverIO. It reuses the web E2E API helpers, so the Docker backend stack
must be running on the usual local ports.

Prerequisites:

- `tauri-driver` is available on `PATH` or `TAURI_DRIVER_PATH` is set.
- Microsoft WebView2 Runtime is installed.
- `msedgedriver.exe` is on `PATH`, `EDGEWEBDRIVER_PATH` is set, or the
  `edgedriver` package can download a matching driver.

Run:

```bash
npm run tauri:e2e:build
npm run test:e2e:tauri
```

Set `TAURI_E2E_APP_PATH` to test a different built executable.
