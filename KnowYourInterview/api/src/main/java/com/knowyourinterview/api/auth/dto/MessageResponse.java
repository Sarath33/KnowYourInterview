package com.knowyourinterview.api.auth.dto;

/**
 * Simple typed envelope for endpoints that return only a human-readable status message
 * (e.g. forgot-password / reset-password), instead of an ad-hoc Map&lt;String,String&gt;.
 */
public record MessageResponse(String message) {
}
