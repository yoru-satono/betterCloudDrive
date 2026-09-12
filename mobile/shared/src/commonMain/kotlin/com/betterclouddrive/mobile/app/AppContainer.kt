package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.database.MobileDatabase
import com.betterclouddrive.mobile.core.localization.PlatformLocaleProvider
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.platform.ApplicationLocaleController
import com.betterclouddrive.mobile.core.platform.ThemePreferenceStore
import com.betterclouddrive.mobile.core.platform.ClipboardService
import com.betterclouddrive.mobile.core.platform.LocalDateTimeService
import com.betterclouddrive.mobile.core.network.AuthenticatedHttpClient
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.core.platform.MediaPreviewRenderer
import com.betterclouddrive.mobile.feature.auth.AuthRepository
import com.betterclouddrive.mobile.feature.files.PersonalFilesRepository
import com.betterclouddrive.mobile.feature.files.FavoritesRepository
import com.betterclouddrive.mobile.feature.files.TagsRepository
import com.betterclouddrive.mobile.feature.files.PersonalSharesRepository
import com.betterclouddrive.mobile.feature.files.PreviewRepository
import com.betterclouddrive.mobile.feature.files.RecycleBinRepository
import com.betterclouddrive.mobile.feature.files.PersonalVersionsRepository
import com.betterclouddrive.mobile.feature.transfers.TransferEngine
import com.betterclouddrive.mobile.feature.transfers.TransferManager
import com.betterclouddrive.mobile.feature.transfers.TransferQueueStore
import com.betterclouddrive.mobile.feature.auth.AuthStore
import com.betterclouddrive.mobile.feature.files.PersonalBrowserStore
import com.betterclouddrive.mobile.feature.files.PersonalSearchStore
import com.betterclouddrive.mobile.feature.files.PersonalPreviewStore
import com.betterclouddrive.mobile.feature.organization.FavoritesStore
import com.betterclouddrive.mobile.feature.organization.TagsStore
import com.betterclouddrive.mobile.feature.shares.PersonalSharesStore
import com.betterclouddrive.mobile.feature.shares.TeamSharesRepository
import com.betterclouddrive.mobile.feature.shares.TeamSharesStore
import com.betterclouddrive.mobile.feature.diagnostics.DiagnosticsStore
import com.betterclouddrive.mobile.feature.recovery.PersonalRecycleBinStore
import com.betterclouddrive.mobile.feature.recovery.PersonalVersionsStore
import com.betterclouddrive.mobile.feature.recovery.TeamRecycleBinRepository
import com.betterclouddrive.mobile.feature.recovery.TeamRecycleBinStore
import com.betterclouddrive.mobile.feature.recovery.TeamVersionsRepository
import com.betterclouddrive.mobile.feature.recovery.TeamVersionsStore
import com.betterclouddrive.mobile.feature.teams.TeamBrowserStore
import com.betterclouddrive.mobile.feature.teams.TeamFileActionsStore
import com.betterclouddrive.mobile.feature.teams.TeamPreviewRepository
import com.betterclouddrive.mobile.feature.teams.TeamPreviewStore
import com.betterclouddrive.mobile.feature.teams.TeamSearchStore
import com.betterclouddrive.mobile.feature.teams.TeamWorkspaceRepository
import com.betterclouddrive.mobile.feature.teams.TeamsStore
import com.betterclouddrive.mobile.feature.teams.TeamMembersRepository
import com.betterclouddrive.mobile.feature.teams.TeamMembersStore
import com.betterclouddrive.mobile.feature.teams.TeamManagementRepository
import com.betterclouddrive.mobile.feature.teams.TeamManagementStore
import com.betterclouddrive.mobile.feature.teams.TeamAuditRepository
import com.betterclouddrive.mobile.feature.teams.TeamAuditStore
import com.betterclouddrive.mobile.feature.teams.TeamTasksRepository
import com.betterclouddrive.mobile.feature.teams.TeamTasksStore
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsRepository
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsStore
import com.betterclouddrive.mobile.feature.teams.TeamInvitesRepository
import com.betterclouddrive.mobile.feature.teams.NotificationsRepository
import com.betterclouddrive.mobile.feature.teams.TeamInvitesStore
import com.betterclouddrive.mobile.feature.teams.IncomingInviteStore
import com.betterclouddrive.mobile.feature.teams.NotificationsStore
import com.betterclouddrive.mobile.feature.teams.TeamOrganizationRepository
import com.betterclouddrive.mobile.feature.teams.TeamFavoritesStore
import com.betterclouddrive.mobile.feature.teams.TeamTagsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AppContainer(
    val http: AuthenticatedHttpClient,
    val auth: AuthRepository,
    val personalFiles: PersonalFilesRepository,
    val favorites: FavoritesRepository,
    val tags: TagsRepository,
    val shares: PersonalSharesRepository,
    val teamShares: TeamSharesRepository,
    val preview: PreviewRepository,
    val recycleBin: RecycleBinRepository,
    val personalVersions: PersonalVersionsRepository,
    val teamRecycleBin: TeamRecycleBinRepository,
    val teamVersions: TeamVersionsRepository,
    val teamWorkspace: TeamWorkspaceRepository,
    val teamPreview: TeamPreviewRepository,
    val teamOrganization: TeamOrganizationRepository,
    val teamMembers: TeamMembersRepository,
    val teamManagement: TeamManagementRepository,
    val teamAudit: TeamAuditRepository,
    val teamTasks: TeamTasksRepository,
    val teamPermissions: TeamPermissionsRepository,
    val teamInvites: TeamInvitesRepository,
    val notifications: NotificationsRepository,
    val database: MobileDatabase,
    val transfers: TransferManager,
    val transferEngine: TransferEngine,
    val documentPicker: DocumentPicker,
    val officeRenderer: OfficeRenderer,
    val mediaPreviewRenderer: MediaPreviewRenderer,
    val localeProvider: PlatformLocaleProvider,
    val localeController: ApplicationLocaleController,
    val themePreferences: ThemePreferenceStore,
    val clipboard: ClipboardService,
    val localDateTime: LocalDateTimeService,
    private val applicationScope: CoroutineScope,
) {
    val transferQueue = TransferQueueStore(transfers, applicationScope)
    val favoritesStore = FavoritesStore(favorites, applicationScope)
    val tagsStore = TagsStore(tags, applicationScope)
    val browserStore = PersonalBrowserStore(personalFiles, transferQueue, applicationScope) {
        favoritesStore.invalidate()
        tagsStore.invalidateFiles()
    }
    val searchStore = PersonalSearchStore(personalFiles, applicationScope)
    val previewStore = PersonalPreviewStore(preview, applicationScope)
    val sharesStore = PersonalSharesStore(shares, applicationScope)
    val diagnosticsStore = DiagnosticsStore(preview, applicationScope)
    val authStore = AuthStore(auth, applicationScope, localeController, themePreferences)
    val teamsStore = TeamsStore(teamWorkspace, applicationScope)
    val teamBrowserStore = TeamBrowserStore(teamWorkspace, applicationScope)
    val teamSearchStore = TeamSearchStore(teamWorkspace, applicationScope)
    val teamPreviewStore = TeamPreviewStore(teamPreview, applicationScope)
    val teamFavoritesStore = TeamFavoritesStore(teamOrganization, applicationScope)
    val teamTagsStore = TeamTagsStore(teamOrganization, applicationScope)
    val teamSharesStore = TeamSharesStore(teamShares, teamPermissions, applicationScope)
    private fun invalidateTeamContent(teamId: Long, folderIds: Set<Long?>) {
        teamBrowserStore.invalidate(teamId, folderIds)
        teamSearchStore.reset()
        teamPreviewStore.reset()
        teamFavoritesStore.invalidate(teamId)
        teamTagsStore.invalidateFiles(teamId)
        teamsStore.refresh()
    }
    val teamFileActionsStore = TeamFileActionsStore(teamWorkspace, teamPermissions, transferQueue, applicationScope, ::invalidateTeamContent)
    val teamRecycleBinStore = TeamRecycleBinStore(teamRecycleBin, teamPermissions, applicationScope) { teamId ->
        invalidateTeamContent(teamId, setOf(null))
    }
    val teamVersionsStore = TeamVersionsStore(teamVersions, teamPermissions, transferQueue, applicationScope) { teamId, parentId ->
        invalidateTeamContent(teamId, setOf(parentId))
    }
    val teamMembersStore = TeamMembersStore(teamMembers, applicationScope)
    val teamPermissionsStore = TeamPermissionsStore(teamPermissions, teamMembers, applicationScope) { teamId, fileId ->
        teamFileActionsStore.invalidatePermission(teamId, fileId)
        invalidateTeamContent(teamId, setOf(fileId))
    }
    val teamInvitesStore = TeamInvitesStore(teamInvites, applicationScope)
    val teamManagementStore = TeamManagementStore(
        teamManagement,
        applicationScope,
        onTeamUpdated = ::synchronizeTeamSummary,
        onTeamRemoved = ::clearTeamContext,
        onOwnershipTransferred = { team ->
            if (teamMembersStore.state.value.teamId == team.id) teamMembersStore.refresh()
        },
    )
    val teamTasksStore = TeamTasksStore(teamTasks, applicationScope)
    val teamAuditStore = TeamAuditStore(teamAudit, localDateTime, applicationScope, teamTasksStore::acceptQueued)
    val incomingInviteStore = IncomingInviteStore(teamInvites, applicationScope) { teamId ->
        teamsStore.refresh()
        teamMembersStore.reset()
    }
    val notificationsStore = NotificationsStore(notifications, applicationScope)
    private fun invalidatePersonalContent() {
        favoritesStore.invalidate()
        tagsStore.invalidateFiles()
        searchStore.reset()
        previewStore.reset()
        sharesStore.reset()
        browserStore.refresh()
    }
    val recycleBinStore = PersonalRecycleBinStore(recycleBin, applicationScope, ::invalidatePersonalContent)
    val versionsStore = PersonalVersionsStore(personalVersions, transferQueue, applicationScope, ::invalidatePersonalContent)
    val coordinator = AppCoordinator(
        authStore,
        auth,
        browserStore,
        transferQueue,
        listOf(
            browserStore, searchStore, favoritesStore, tagsStore, sharesStore, previewStore,
            recycleBinStore, versionsStore, diagnosticsStore, transferQueue,
            teamsStore, teamBrowserStore, teamSearchStore, teamPreviewStore,
            teamFavoritesStore, teamTagsStore, teamSharesStore,
            teamRecycleBinStore, teamVersionsStore,
            teamFileActionsStore, teamMembersStore, teamPermissionsStore,
            teamInvitesStore, teamManagementStore, teamAuditStore, teamTasksStore, notificationsStore,
        ),
        incomingInviteStore,
        notificationsStore,
        applicationScope,
    )

    fun reconcileTransfers() {
        applicationScope.launch { transfers.reconcile() }
    }

    private fun synchronizeTeamSummary(team: com.betterclouddrive.mobile.core.model.TeamSummary) {
        teamsStore.upsert(team)
        teamBrowserStore.replaceTeamSummary(team)
        teamMembersStore.updateActorRole(team.id, team.role)
        if (teamInvitesStore.state.value.teamId == team.id) teamInvitesStore.activate(team.id, team.role)
        if (teamFileActionsStore.state.value.teamId == team.id) {
            teamFileActionsStore.updateTeamRole(team)
        }
        teamTagsStore.updateRole(team.id, team.role)
        teamRecycleBinStore.updateRole(team.id, team.role)
        if (teamAuditStore.state.value.teamId == team.id) teamAuditStore.activate(team.id, team.role)
        if (teamTasksStore.state.value.teamId == team.id) teamTasksStore.activate(team.id, team.role)
        if (teamSharesStore.state.value.teamId == team.id) {
            teamSharesStore.bind(team.id, authStore.state.value.user?.id, team.role)
        }
    }

    private fun clearTeamContext(
        teamId: Long,
        feedback: UiMessage,
    ) {
        teamsStore.remove(teamId)
        teamsStore.showFeedback(feedback)
        teamBrowserStore.reset()
        teamSearchStore.reset()
        teamPreviewStore.reset()
        teamFavoritesStore.reset()
        teamTagsStore.reset()
        teamSharesStore.reset()
        teamRecycleBinStore.reset()
        teamVersionsStore.reset()
        teamFileActionsStore.reset()
        teamMembersStore.reset()
        teamPermissionsStore.reset()
        teamInvitesStore.reset()
        teamAuditStore.reset()
        teamTasksStore.reset()
    }
}
