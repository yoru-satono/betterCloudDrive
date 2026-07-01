package com.betterclouddrive.web.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class OnlyOfficeCallbackRequest {
    private Integer status;
    private String url;
    private String key;
    private Integer error;
    private List<String> users;
    private String token;
}
