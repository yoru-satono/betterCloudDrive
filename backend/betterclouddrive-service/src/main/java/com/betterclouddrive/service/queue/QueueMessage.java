package com.betterclouddrive.service.queue;

import java.util.Map;

public record QueueMessage(String stream, String id, Map<String, String> body) {
}
