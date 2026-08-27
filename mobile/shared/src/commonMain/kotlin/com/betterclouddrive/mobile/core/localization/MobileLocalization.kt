package com.betterclouddrive.mobile.core.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import betterclouddrivemobile.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

enum class MobileLocale(val languageTag: String) {
    ENGLISH_US("en-US"),
    SIMPLIFIED_CHINESE_CN("zh-CN"),
    SIMPLIFIED_CHINESE_SG("zh-SG"),
}

fun resolveMobileLocale(languageTags: List<String>): MobileLocale {
    languageTags.forEach { raw ->
        val parts = raw.replace('_', '-').split('-').filter(String::isNotBlank)
        val language = parts.firstOrNull()?.lowercase() ?: return@forEach
        if (language == "en") return MobileLocale.ENGLISH_US
        if (language == "zh") {
            val script = parts.drop(1).firstOrNull { it.length == 4 }?.lowercase()
            val region = parts.drop(1).firstOrNull { it.length == 2 || it.length == 3 }?.uppercase()
            if (script == "hant") return MobileLocale.ENGLISH_US
            return when (region) {
                "CN" -> MobileLocale.SIMPLIFIED_CHINESE_CN
                "SG" -> MobileLocale.SIMPLIFIED_CHINESE_SG
                else -> MobileLocale.ENGLISH_US
            }
        }
    }
    return MobileLocale.ENGLISH_US
}

interface PlatformLocaleProvider {
    fun preferredLanguageTags(): List<String>
}

enum class MobileString(internal val resource: StringResource) {
    AppName(Res.string.app_name),
    StageLabel(Res.string.stage_label),
    RestoreTitle(Res.string.restore_title),
    SessionPreserved(Res.string.session_preserved),
    Retry(Res.string.retry),
    UseAnotherAccount(Res.string.use_another_account),
    ServerAddress(Res.string.server_address),
    Username(Res.string.username),
    Password(Res.string.password),
    Login(Res.string.login),
    Personal(Res.string.personal),
    Teams(Res.string.teams),
    Transfers(Res.string.transfers),
    Profile(Res.string.profile),
    TeamsTitle(Res.string.teams_title),
    TeamsComingSoon(Res.string.teams_coming_soon),
    TransferQueueTitle(Res.string.transfer_queue_title),
    TransfersEmpty(Res.string.transfers_empty),
    TransfersEmptyDetail(Res.string.transfers_empty_detail),
    Pause(Res.string.pause),
    Resume(Res.string.resume),
    Cancel(Res.string.cancel),
    Account(Res.string.account),
    Email(Res.string.email),
    NotSet(Res.string.not_set),
    CurrentServer(Res.string.current_server),
    Diagnostics(Res.string.diagnostics),
    Logout(Res.string.logout),
    RoomDatabase(Res.string.room_database),
    RoomUnchecked(Res.string.room_unchecked),
    RoomHealthy(Res.string.room_healthy),
    RoomCheckFailed(Res.string.room_check_failed),
    UnknownError(Res.string.unknown_error),
    CheckRoom(Res.string.check_room),
    OfficeDiagnostics(Res.string.office_diagnostics),
    TeamId(Res.string.team_id),
    FileId(Res.string.file_id),
    LoadReadOnlyDocument(Res.string.load_read_only_document),
    CurrentUser(Res.string.current_user),
    OfficeReady(Res.string.office_ready),
    OfficeConfigLoading(Res.string.office_config_loading),
    OfficeConfigLoaded(Res.string.office_config_loaded),
    OfficeAppReady(Res.string.office_app_ready),
    OfficeDocumentReady(Res.string.office_document_ready),
    OfficeError(Res.string.office_error),
    LoadingFailed(Res.string.loading_failed),
    Queued(Res.string.queued),
    Preparing(Res.string.preparing),
    Running(Res.string.running),
    Paused(Res.string.paused),
    WaitingForLogin(Res.string.waiting_for_login),
    Succeeded(Res.string.succeeded),
    Failed(Res.string.failed),
    Canceled(Res.string.canceled),
    ErrorCredentials(Res.string.error_credentials),
    ErrorUnauthorized(Res.string.error_unauthorized),
    ErrorQuota(Res.string.error_quota),
    ErrorNameConflict(Res.string.error_name_conflict),
    ErrorNotFound(Res.string.error_not_found),
    ErrorNetworkRetry(Res.string.error_network_retry),
    ErrorServerChanged(Res.string.error_server_changed),
    ErrorTransfer(Res.string.error_transfer),
    ErrorInvalidTransfer(Res.string.error_invalid_transfer),
    ErrorFileUnavailable(Res.string.error_file_unavailable),
    ErrorSessionExpired(Res.string.error_session_expired),
    ErrorRequest(Res.string.error_request),
    ErrorRequestWithId(Res.string.error_request_with_id),
    ErrorInvalidInput(Res.string.error_invalid_input),
    ErrorNetwork(Res.string.error_network),
    ErrorSessionPreserved(Res.string.error_session_preserved),
    ErrorServerEmpty(Res.string.error_server_empty),
    ErrorServerScheme(Res.string.error_server_scheme),
    ErrorServerHttps(Res.string.error_server_https),
    ErrorServerHost(Res.string.error_server_host),
    RootDirectory(Res.string.root_directory),
    FolderPlaceholder(Res.string.folder_placeholder),
    UploadToRoot(Res.string.upload_to_root),
    Folder(Res.string.folder),
    File(Res.string.file),
    Sort(Res.string.sort),
    SwitchView(Res.string.switch_view),
    RefreshDirectory(Res.string.refresh_directory),
    BackToProfile(Res.string.back_to_profile),
    BackToParent(Res.string.back_to_parent),
    SortName(Res.string.sort_name),
    SortUpdated(Res.string.sort_updated),
    SortSize(Res.string.sort_size),
    SortAscending(Res.string.sort_ascending),
    SortDescending(Res.string.sort_descending),
    RetryLoadMore(Res.string.retry_load_more),
    TotalItems(Res.string.total_items),
    PersonalEmpty(Res.string.personal_empty),
    DirectoryEmpty(Res.string.directory_empty),
    PersonalEmptyDetail(Res.string.personal_empty_detail),
    DirectoryEmptyDetail(Res.string.directory_empty_detail),
    DirectoryLoadFailed(Res.string.directory_load_failed),
    BackToRoot(Res.string.back_to_root),
    SelectFileForDetails(Res.string.select_file_for_details),
    Type(Res.string.type),
    Size(Res.string.size),
    UpdatedAt(Res.string.updated_at),
    CreatedAt(Res.string.created_at),
    VersionCount(Res.string.version_count),
    ChooseFile(Res.string.choose_file),
    ChooseFiles(Res.string.choose_files),
    Upload(Res.string.upload),
    Download(Res.string.download),
    UploadToCurrentFolder(Res.string.upload_to_current_folder),
    UploadQueuedSummary(Res.string.upload_queued_summary),
    UploadNothingQueued(Res.string.upload_nothing_queued),
    ViewTransfers(Res.string.view_transfers),
    Uploads(Res.string.uploads),
    Downloads(Res.string.downloads),
    UploadsEmpty(Res.string.uploads_empty),
    DownloadsEmpty(Res.string.downloads_empty),
    TransferBytes(Res.string.transfer_bytes),
    ClearFinished(Res.string.clear_finished),
    ClearFinishedTitle(Res.string.clear_finished_title),
    ClearFinishedMessage(Res.string.clear_finished_message),
    Clear(Res.string.clear),
    ErrorBackgroundScheduling(Res.string.error_background_scheduling),
    CreateFolder(Res.string.create_folder),
    Rename(Res.string.rename),
    Move(Res.string.move),
    Copy(Res.string.copy),
    Delete(Res.string.delete),
    MoreActions(Res.string.more_actions),
    SelectedCount(Res.string.selected_count),
    FolderName(Res.string.folder_name),
    NewName(Res.string.new_name),
    Confirm(Res.string.confirm),
    DeleteSingleTitle(Res.string.delete_single_title),
    DeleteSingleMessage(Res.string.delete_single_message),
    DeleteMultipleTitle(Res.string.delete_multiple_title),
    DeleteMultipleMessage(Res.string.delete_multiple_message),
    ChooseDestination(Res.string.choose_destination),
    DestinationRoot(Res.string.destination_root),
    DestinationEmpty(Res.string.destination_empty),
    CurrentFolder(Res.string.current_folder),
    FolderCreated(Res.string.folder_created),
    FileRenamed(Res.string.file_renamed),
    FileMoved(Res.string.file_moved),
    FileCopied(Res.string.file_copied),
    FilesMovedToTrash(Res.string.files_moved_to_trash),
    ErrorInvalidDestination(Res.string.error_invalid_destination),
    ErrorInvalidFileName(Res.string.error_invalid_file_name),
    NameRequired(Res.string.name_required),
    NameTooLong(Res.string.name_too_long),
    Close(Res.string.close),
    NotificationTransferTitle(Res.string.notification_transfer_title),
    NotificationChannel(Res.string.notification_channel),
    NotificationPreparing(Res.string.notification_preparing),
    NotificationTransferring(Res.string.notification_transferring),
    NotificationUploading(Res.string.notification_uploading),
    NotificationDownloading(Res.string.notification_downloading),
    Search(Res.string.search),
    SearchFiles(Res.string.search_files),
    SearchHint(Res.string.search_hint),
    SearchInitial(Res.string.search_initial),
    SearchNoResults(Res.string.search_no_results),
    SearchFailed(Res.string.search_failed),
    SearchResultCount(Res.string.search_result_count),
    OpenLocation(Res.string.open_location),
    Preview(Res.string.preview),
    PreviewLoading(Res.string.preview_loading),
    PreviewTruncated(Res.string.preview_truncated),
    PreviewUnsupported(Res.string.preview_unsupported),
    PreviewUnsupportedDetail(Res.string.preview_unsupported_detail),
    PreviewEmptyText(Res.string.preview_empty_text),
    PreviewReadOnly(Res.string.preview_read_only),
    ErrorPreviewFailed(Res.string.error_preview_failed),
    RetryPreview(Res.string.retry_preview),
    Register(Res.string.register),
    ForgotPassword(Res.string.forgot_password),
    VerificationCode(Res.string.verification_code),
    SendCode(Res.string.send_code),
    ResendIn(Res.string.resend_in),
    ConfirmPassword(Res.string.confirm_password),
    BackToLogin(Res.string.back_to_login),
    RegistrationSuccess(Res.string.registration_success),
    ResetPassword(Res.string.reset_password),
    NewPassword(Res.string.new_password),
    ResetCodeSent(Res.string.reset_code_sent),
    ResetSuccess(Res.string.reset_success),
    Language(Res.string.language),
    LanguageEnglish(Res.string.language_english),
    LanguageChineseCn(Res.string.language_chinese_cn),
    LanguageChineseSg(Res.string.language_chinese_sg),
    Appearance(Res.string.appearance),
    ThemeSystem(Res.string.theme_system),
    ThemeLight(Res.string.theme_light),
    ThemeDark(Res.string.theme_dark),
    AccountDetails(Res.string.account_details),
    EmailVerified(Res.string.email_verified),
    EmailUnverified(Res.string.email_unverified),
    Role(Res.string.role),
    RegisteredAt(Res.string.registered_at),
    Storage(Res.string.storage),
    StorageUsage(Res.string.storage_usage),
    StorageNearQuota(Res.string.storage_near_quota),
    SwitchServer(Res.string.switch_server),
    SwitchServerTitle(Res.string.switch_server_title),
    SwitchServerMessage(Res.string.switch_server_message),
    RefreshProfile(Res.string.refresh_profile),
    ErrorRateLimited(Res.string.error_rate_limited),
    ErrorVerificationExpired(Res.string.error_verification_expired),
    ErrorVerificationCode(Res.string.error_verification_code),
    ErrorAccountConflict(Res.string.error_account_conflict),
    ;

    internal val resourceKey: String
        get() = resource.key
}

data class UiMessage(val key: MobileString, val arguments: List<Any?> = emptyList())

class MobileStrings internal constructor(
    val locale: MobileLocale,
    private val values: Map<MobileString, String>,
) {
    operator fun get(key: MobileString): String = checkNotNull(values[key]) { "localization.missing.$key" }

    fun format(key: MobileString, vararg arguments: Any?): String =
        arguments.foldIndexed(get(key)) { index, result, value -> result.replace("{$index}", value.toString()) }

    fun resolve(message: UiMessage?): String? = message?.let { format(it.key, *it.arguments.toTypedArray()) }

    internal fun keys(): Set<MobileString> = values.keys
}

@Composable
fun mobileStringsFor(languageTags: List<String>): MobileStrings {
    val locale = resolveMobileLocale(languageTags)
    val values = mutableMapOf<MobileString, String>()
    for (key in MobileString.entries) values[key] = stringResource(key.resource)
    return MobileStrings(locale, values)
}

suspend fun loadMobileStrings(languageTags: List<String>): MobileStrings {
    val locale = resolveMobileLocale(languageTags)
    val values = mutableMapOf<MobileString, String>()
    for (key in MobileString.entries) values[key] = getString(key.resource)
    return MobileStrings(locale, values)
}

val LocalMobileStrings = staticCompositionLocalOf<MobileStrings> { error("localization.notProvided") }
