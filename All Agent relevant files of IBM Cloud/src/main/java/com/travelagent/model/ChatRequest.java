package com.travelagent.model;

import lombok.Data;

/**
 * Incoming chat request from the frontend.
 */
@Data
public class ChatRequest {
    private String message;
    private String sessionId;
}
