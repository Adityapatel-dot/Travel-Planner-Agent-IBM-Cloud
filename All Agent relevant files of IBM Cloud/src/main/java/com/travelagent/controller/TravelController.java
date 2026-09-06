package com.travelagent.controller;

import com.travelagent.model.ChatRequest;
import com.travelagent.model.ChatResponse;
import com.travelagent.model.WeatherData;
import com.travelagent.service.DestinationService;
import com.travelagent.service.TravelAgentService;
import com.travelagent.service.WatsonXService;
import com.travelagent.service.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller exposing all Travel Planner Agent endpoints.
 *
 * Endpoints:
 *   POST /api/chat              — main conversational AI (IBM Granite)
 *   GET  /api/weather/{city}    — 5-day weather forecast
 *   GET  /api/destinations      — popular destinations list
 *   GET  /api/status            — service health + configuration info
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class TravelController {

    @Autowired private TravelAgentService travelAgentService;
    @Autowired private WeatherService     weatherService;
    @Autowired private DestinationService destinationService;
    @Autowired private WatsonXService     watsonXService;

    // ─────────────────────────────────────────────────────────────────────────
    // Chat (IBM Granite)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main conversational endpoint.
     * Accepts a user message, routes it through the agent (IBM Granite or Demo Mode),
     * and returns a ChatResponse with text + optional weather/destination widgets.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        ChatResponse response = travelAgentService.chat(sessionId, request.getMessage());
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Weather
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns current weather + 5-day forecast for a given city.
     * Falls back to demo weather when OpenWeatherMap key is absent.
     */
    @GetMapping("/weather/{city}")
    public ResponseEntity<Map<String, Object>> getWeather(@PathVariable String city) {
        try {
            WeatherData data = weatherService.getWeather(city);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("city",        data.getCity());
            result.put("country",     data.getCountry());
            result.put("temperature", data.getTemperature());
            result.put("feelsLike",   data.getFeelsLike());
            result.put("humidity",    data.getHumidity());
            result.put("description", data.getDescription());
            result.put("icon",        data.getIcon());
            result.put("forecast",    data.getForecast());
            result.put("demo",        !weatherService.isConfigured());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> err = Map.of("error", "Could not fetch weather: " + e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Destinations
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the curated list of popular travel destinations. */
    @GetMapping("/destinations")
    public ResponseEntity<List<Map<String, Object>>> getDestinations() {
        return ResponseEntity.ok(destinationService.getPopularDestinations());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status / Health
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns service configuration status — useful for the frontend to show Live vs Demo badge. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("service",          "TravelAI Travel Planner Agent");
        status.put("watsonxConfigured", watsonXService.isConfigured());
        status.put("weatherConfigured", weatherService.isConfigured());
        status.put("model",             "ibm/granite-3-8b-instruct");
        status.put("demoMode",          !watsonXService.isConfigured());
        status.put("version",           "1.0.0");
        return ResponseEntity.ok(status);
    }
}
