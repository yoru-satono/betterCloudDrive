"""Fixed, non-sensitive native media messages."""
MESSAGES = {
    'play': ('播放', 'Play'), 'pause': ('暂停', 'Pause'), 'volume': ('音量', 'Volume'),
    'fullscreen': ('全屏', 'Full screen'), 'retry': ('重试', 'Retry'), 'loading': ('正在打开视频…', 'Opening video…'),
    'MEDIA_FORBIDDEN': ('没有权限播放此视频，请确认登录状态和文件权限。', 'Access denied. Check your login and file permissions.'),
    'MEDIA_CHANGED': ('视频内容已变化，请重新打开。', 'The video changed. Open it again.'),
    'MEDIA_NETWORK_ERROR': ('视频连接中断，请检查网络后重试。', 'The video connection failed. Check the connection and retry.'),
    'MEDIA_INVALID_RANGE': ('服务器返回的视频范围无效，请重试或检查服务器。', 'The server returned an invalid byte range.'),
    'MEDIA_FORMAT_ERROR': ('无法解码此视频格式，请选择支持的视频。', 'This video format could not be decoded.'),
    'INVALID_MEDIA': ('无法打开此视频文件。', 'This video file cannot be opened.'),
}
def message(code, locale='en-US'):
    return MESSAGES.get(code, MESSAGES['MEDIA_NETWORK_ERROR'])[0 if str(locale).startswith('zh') else 1]
