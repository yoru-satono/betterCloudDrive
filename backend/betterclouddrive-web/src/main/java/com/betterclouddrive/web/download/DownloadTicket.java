package com.betterclouddrive.web.download;

public record DownloadTicket(Long userId, Long resourceId, DownloadTicketType type) {
}
