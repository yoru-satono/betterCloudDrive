# Android Admin Scope Decision

## Decision

Android will not carry the full admin team console in this phase.

The Android client remains focused on end-user and team-member workflows: team files, members, invites, shares, tags, favorites, recycle bin, audit viewing/export, notifications, and document preview/edit where permissions allow. Full platform administration stays in the Web/Tauri admin console.

## Rationale

- The admin team console contains destructive and high-risk operations such as archive, restore, purge, quota override, member inspection, file inspection, and audit export across teams.
- These workflows need dense tables, multi-column filtering, confirmation context, and reviewable state. The current Web/Tauri admin UI is a better fit than a phone layout.
- Keeping admin operations out of Android reduces accidental destructive actions and avoids duplicating a second admin surface before the core Android team workflows and E2E coverage are complete.

## Android Scope

Android should continue to support:

- Team owner/admin settings inside teams the user belongs to.
- Team member and invite management for authorized team users.
- Team share, tag, favorite, recycle bin, audit list/filter/export, and quota visibility where already exposed by team APIs.
- Notification handling for team events.

Android should not implement in this phase:

- Global admin team list.
- Admin archive, restore, purge, or cross-team recovery.
- Admin quota override for arbitrary teams.
- Cross-team member/file/audit inspection.
- System log or admin audit center.

## Future Minimum Admin Entry

If product requirements later require Android admin support, add it as a separate admin-only module with:

- Explicit admin role gate before navigation.
- Read-only team list and team detail as the first milestone.
- Separate confirmations for archive, restore, purge, and quota changes.
- Dedicated E2E tests on an Android emulator before enabling destructive actions.

## Impact

- Existing Android team features remain in scope.
- Admin team backend and Web/Tauri coverage remain authoritative for platform administration.
- ROADMAP Android work can proceed to deep-link E2E, OnlyOffice E2E, and permission matrix coverage without blocking on a mobile admin console.
