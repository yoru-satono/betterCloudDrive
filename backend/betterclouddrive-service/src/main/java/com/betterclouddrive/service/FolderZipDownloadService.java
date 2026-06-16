package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;

import java.io.IOException;
import java.io.OutputStream;

public interface FolderZipDownloadService {
    void validateDownloadable(FileEntity folder);
    void writeZip(FileEntity folder, OutputStream outputStream) throws IOException;
    FolderZipDownloadCacheEntity getOrCreateCachedZip(FileEntity folder);
    void markDownloaded(FolderZipDownloadCacheEntity cache);
}
