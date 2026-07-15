package com.betterclouddrive.service;

public interface TeamTaskQueueConsumer {
    int processQueuedTasks(int batchSize);
}
