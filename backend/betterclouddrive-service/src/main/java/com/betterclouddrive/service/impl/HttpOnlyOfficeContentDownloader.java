package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.service.OnlyOfficeContentDownloader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class HttpOnlyOfficeContentDownloader implements OnlyOfficeContentDownloader {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${onlyoffice.callback-download-timeout-seconds:30}")
    private int timeoutSeconds;

    @Override
    public byte[] download(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "OnlyOffice callback URL is missing");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 1)))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ApiCode.DOWNLOAD_FAILED,
                        "Failed to download edited document from OnlyOffice");
            }
            return response.body();
        } catch (IOException e) {
            throw new BusinessException(ApiCode.DOWNLOAD_FAILED,
                    "Failed to download edited document from OnlyOffice");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ApiCode.DOWNLOAD_FAILED,
                    "Failed to download edited document from OnlyOffice");
        }
    }
}
