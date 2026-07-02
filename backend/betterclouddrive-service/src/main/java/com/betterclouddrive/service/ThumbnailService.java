package com.betterclouddrive.service;

public interface ThumbnailService {

    void generateThumbnailAsync(Long fileId);

    void generateThumbnail(Long fileId);
}
