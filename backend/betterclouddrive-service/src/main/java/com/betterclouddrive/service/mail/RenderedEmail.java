package com.betterclouddrive.service.mail;

public record RenderedEmail(String subject, String plainText, String html) {
}
