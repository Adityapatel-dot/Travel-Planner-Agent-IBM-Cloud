package com.travelagent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response returned from the AI chat endpoint.
 * Contains the text message plus optional structured data
 * (weather, destinations) that the frontend renders as widgets.
 */
@Data
@Builder
public class ChatResponse {
    /** The AI-generated markdown response text */
    private String message;

    /** Session ID echoed back for continued conversation */
    private String sessionId;

    /** True when running without IBM WatsonX credentials */
    private boolean demoMode;

    /** "chat" | "itinerary" — hint for the frontend renderer */
    private String type;

    /** Populated when the response involves weather data */
    private Map<String, Object> weather;

    /** Popular destination cards (sent on first message) */
    private List<Map<String, Object>> destinations;

    /** Unix epoch milliseconds */
    private long timestamp;
}
