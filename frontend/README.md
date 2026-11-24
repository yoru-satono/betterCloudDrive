# BetterCloudDrive Frontend

Vue 3 + Vite web client for BetterCloudDrive. The Qt desktop build reuses the Vue source through its own adapters.

Current development documentation:

- [Web development](../docs/development/WEB.md)
- [Qt desktop development](../desktop/README.md)
- [Shared testing strategy](../docs/development/TESTING.md)

This module README keeps only the fastest local commands and compatibility notes. The documents above are authoritative for structure, debugging, and required validation.

## Web Development

```bash
pnpm install
pnpm run dev
```

The web app uses relative `/api/v1` requests. Vite proxies `/api` and `/webdav`
to `http://localhost:8080` during development.

## Localization

The backend returns semantic `messageKey` / `messageParams` values and never a
user-facing fallback message. Web and Qt resolve those keys through
`src/i18n.ts` and `src/locales/zh-Hans.ts`. Unknown keys are logged for diagnosis
and resolve to a local generic Chinese message; production UI must not render a
raw server key. Notification, task, activity, and permission objects follow the
same key/params convention. See [`docs/reference/LOCALIZATION.md`](../docs/reference/LOCALIZATION.md).

## Appearance

Web and Qt support `system`, `light`, and `dark` theme modes. New devices
default to the operating-system theme; users can select a fixed mode from the
Appearance section of the profile page. The device-local preference uses the
`bcd.themeMode` key, survives logout, and is not synchronized to the backend.
Qt also forwards the selection to the native application theme.

## Desktop boundary

Web and Qt load the same `dist/` output and share authentication, HTTP, preview and OnlyOffice implementations. `src/platform/desktop.ts` lazily loads the Qt transport only when the native host provides startup configuration. Browser native operations reject without registering bridge listeners. Qt keeps its channel in an isolated world; commands, transfer DTOs and events remain unchanged.

The old Rust shell, Tauri SDK/CLI and WebDriver tooling have been removed. Current desktop installation, startup, verification and trial commands are maintained in [desktop/README.md](../desktop/README.md). Browser development continues to use pnpm and Vite; it does not expose native commands.
