# BetterCloudDrive Desktop Client

PySide6 desktop client for the existing BetterCloudDrive backend.

## Run

```bash
cd desktop-client
uv sync
uv run betterclouddrive-desktop
```

Default endpoints:

- API: `http://127.0.0.1:8080/api/v1`
- Web share URL base: `http://127.0.0.1:3000`

Both can be changed on the login/settings screens.

## Scope

The first version is an active-operation desktop client. It does not implement a local sync folder, offline cache, or admin console. It reuses the existing `/api/v1` backend APIs.

Implemented user features:

- Login, registration code, registration, password reset
- File browsing, search, upload, download, folder ZIP download
- New folder, rename, move, copy, delete
- Image/text preview, file details, versions
- Share creation/edit/cancel/copy, manual and generated passwords
- Favorites, tags, recycle bin
- Public share access, download, and save-to-my-drive

