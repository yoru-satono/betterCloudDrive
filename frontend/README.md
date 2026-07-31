# BetterCloudDrive Frontend

Vue 3 + Vite web client for BetterCloudDrive. The same app is also used by the Tauri desktop shell.

## Web Development

```bash
pnpm install
pnpm run dev
```

The web app uses relative `/api/v1` requests. Vite proxies `/api` and `/webdav`
to `http://localhost:8080` during development.

## Localization

The backend returns semantic `messageKey` / `messageParams` values and never a
user-facing fallback message. Web and Tauri resolve those keys through
`src/i18n.ts` and `src/locales/zh-Hans.ts`. Unknown keys are logged for diagnosis
and resolve to a local generic Chinese message; production UI must not render a
raw server key. Notification, task, activity, and permission objects follow the
same key/params convention. See [`docs/reference/LOCALIZATION.md`](../docs/reference/LOCALIZATION.md).

## Appearance

Web and Tauri support `system`, `light`, and `dark` theme modes. New devices
default to the operating-system theme; users can select a fixed mode from the
Appearance section of the profile page. The device-local preference uses the
`bcd.themeMode` key, survives logout, and is not synchronized to the backend.
Tauri also forwards the selection to the native application theme.

## Tauri Desktop

The desktop client connects to an external backend. Defaults:

- API: `http://127.0.0.1:8080/api/v1`
- Web share URL base: `http://127.0.0.1:3000`

Run the desktop client:

```bash
pnpm run tauri:dev
```

Build a desktop bundle:

```bash
pnpm run tauri:build
```

The login page exposes API and Web share URL fields in desktop mode.

## Tauri Window E2E

The Tauri E2E smoke test drives the real desktop window through WebdriverIO. It
reuses the web E2E API helpers, so the Docker backend stack must be running on
the usual local ports.

Windows prerequisites:

- `tauri-driver` is available on `PATH` or `TAURI_DRIVER_PATH` is set.
- Microsoft WebView2 Runtime is installed.
- `msedgedriver.exe` is on `PATH`, `EDGEWEBDRIVER_PATH` is set, or the
  `edgedriver` package can download a matching driver.

Linux prerequisites:

- The standard Tauri 2 development dependencies for WebKitGTK 4.1 are installed.
- A graphical display is available, or `xvfb-run` is installed for headless use.
- The Linux E2E build uses an instrumented embedded WebDriver. It does not
  require `tauri-driver` or `WebKitWebDriver`, and the instrumentation is not
  included in a normal `tauri:build`.

Run:

```bash
pnpm run tauri:e2e:build
pnpm run test:e2e:tauri
```

Set `TAURI_E2E_APP_PATH` to test a different built executable.
For the complete Linux flow, including temporary E2E rate-limit configuration
and restoration, run `../scripts/verify-all.sh --module tauri`.

The Rust desktop upload client also consumes the semantic API envelope. Keep
its `ApiEnvelope` aligned with `code/messageKey/messageParams/data`; restoring
the removed `message` field will break folder uploads at runtime.
