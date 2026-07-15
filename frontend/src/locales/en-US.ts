export default {
  common: { success: 'Success' },
  error: {
    internal: 'Service temporarily unavailable. Please try again later', validation: 'Invalid submission: {fields}', method_not_allowed: 'Operation not supported',
    malformed_body: 'Invalid request body', missing_parameter: 'Missing parameter: {name}', invalid_parameter: 'Invalid parameter: {name}', bad_request: 'Invalid request',
    unauthorized: 'Please sign in first', token_expired: 'Your session has expired. Please sign in again', token_blacklisted: 'Your session is no longer valid. Please sign in again',
    forbidden: 'You do not have permission to do this', not_found: 'The requested content was not found', file_not_found: 'File not found', conflict: 'Data conflict',
    file_name_conflict: 'An item with this name already exists', rate_limited: 'Too many requests. Please try again later', storage_quota_exceeded: 'Not enough storage space', instant_upload_not_found: 'Instant upload source not found',
    invalid_share_code: 'Invalid share code', share_password_required: 'Enter the share password', share_expired: 'This share link has expired', share_visit_limit: 'This share has reached its visit limit',
    file_too_large: 'File exceeds the size limit', email_code_expired: 'Verification code has expired', email_code_mismatch: 'Incorrect verification code', password_reset_failed: 'Password reset failed',
    folder_download_limit_exceeded: 'Folder download exceeds the limit', storage_error: 'Storage service temporarily unavailable', upload_failed: 'Upload failed', download_failed: 'Download failed'
  },
  ui: {
    language: 'Language', chinese: '简体中文', english: 'English', languageSaved: 'Language updated', languageSaveFailed: 'Could not update language',
    publicSubtitle: 'Secure · Efficient · Reliable', profile: 'Profile', appearance: 'Appearance', storage: 'Storage', admin: 'Administrator', user: 'User', unboundEmail: 'No email linked',
    themeMode: 'Theme mode', system: 'System', light: 'Light', dark: 'Dark', themeLocal: 'Theme preference is stored on this device only.', languageSynced: 'Language preference is synchronized across your devices.',
    welcomeBack: 'Welcome back', signInSubtitle: 'Sign in to BetterCloudDrive', username: 'Username', enterUsername: 'Enter username', password: 'Password', enterPassword: 'Enter password', signIn: 'Sign in', forgotPassword: 'Forgot password?', registerAccount: 'Create account', loginSuccess: 'Signed in', requiredUsername: 'Enter your username', requiredPassword: 'Enter your password',
    createAccount: 'Create account', join: 'Join BetterCloudDrive', email: 'Email', emailHint: 'For verification and password recovery', verificationCode: 'Email verification code', sixDigitCode: '6-digit code', sendCode: 'Send code', confirmPassword: 'Confirm password', enterAgain: 'Enter password again', register: 'Register', alreadyAccount: 'Already have an account?', signInNow: 'Sign in', codeSent: 'Verification code sent', registrationSuccess: 'Account created. Please sign in',
    recoverPassword: 'Reset password', recoverHint: 'Enter your account email to receive a code', codeSentTo: 'A code was sent to {email}', registeredEmail: 'Account email', enterRegisteredEmail: 'Enter your account email', code: 'Verification code', enterEmailCode: 'Enter the code from the email', newPassword: 'New password', resetPassword: 'Reset password', resend: 'Send again', backToLogin: 'Back to sign in', resetSuccess: 'Password reset. Please sign in',
    justNow: 'just now', minutesAgo: '{count} minutes ago', hoursAgo: '{count} hours ago', daysAgo: '{count} days ago'
  }
}
