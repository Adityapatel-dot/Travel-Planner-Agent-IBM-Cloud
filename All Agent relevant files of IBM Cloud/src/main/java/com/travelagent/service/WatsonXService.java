package com.travelagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Service that communicates directly with IBM WatsonX.ai REST API
 * using the IBM Granite 3-8B Instruct model.
 *
 * Flow:
 *   1. Exchange IBM Cloud API Key → IAM Bearer Token (cached 55 min)
 *   2. POST conversation history in Granite chat format to WatsonX generation endpoint
 *   3. Return generated text
 *
 * Falls back gracefully: if not configured → isConfigured() returns false,
 * and TravelAgentService uses pre-built Demo Mode responses.
 */
@Service
public class WatsonXService {

    private static final Logger log = LoggerFactory.getLogger(WatsonXService.class);

    private static final String IAM_TOKEN_URL = "https://iam.cloud.ibm.com/identity/token";
    private static final String GENERATION_PATH = "/ml/v1/text/generation?version=2024-05-01";

    @Value("${watsonx.api-key:}")
    private String apiKey;

    @Value("${watsonx.project-id:}")
    private String projectId;

    @Value("${watsonx.url:https://us-south.ml.cloud.ibm.com}")
    private String watsonxUrl;

    @Value("${watsonx.model-id:ibm/granite-3-8b-instruct}")
    private String modelId;

    private final RestTemplate restTemplate = new RestTemplate();

    // IAM token cache
    private String cachedToken;
    private Instant tokenExpiry;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when WatsonX API key + project ID are configured.
     * If false, callers should fall back to Demo Mode.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
            && projectId != null && !projectId.isBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches (and caches) a Bearer token from IBM IAM.
     * Token is refreshed 5 minutes before expiry.
     */
    private synchronized String getIAMToken() {
        if (cachedToken != null && tokenExpiry != null
                && Instant.now().isBefore(tokenExpiry.minusSeconds(300))) {
            return cachedToken;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = "grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey=" + apiKey;
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<JsonNode> resp = restTemplate.postForEntity(IAM_TOKEN_URL, request, JsonNode.class);
            JsonNode rb = resp.getBody();
            if (rb != null && rb.has("access_token")) {
                cachedToken = rb.get("access_token").asText();
                long expiresIn = rb.has("expires_in") ? rb.get("expires_in").asLong(3600) : 3600;
                tokenExpiry = Instant.now().plusSeconds(expiresIn);
                log.info("IBM IAM token refreshed, expires in {} seconds", expiresIn);
                return cachedToken;
            }
        } catch (Exception e) {
            log.error("Failed to obtain IBM IAM token: {}", e.getMessage());
            throw new RuntimeException("IBM IAM token fetch failed: " + e.getMessage(), e);
        }
        throw new RuntimeException("IAM token response was empty or invalid");
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a multi-turn conversation history to IBM Granite and returns the reply.
     *
     * @param history     List of {"role":"user"|"assistant", "content":"..."} maps
     * @param systemPrompt The system instructions for the model
     * @return Generated text from IBM Granite
     */
    public String generateWithHistory(List<Map<String, String>> history, String systemPrompt) {
        // Build Granite instruct prompt format
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("<|system|>\n").append(systemPrompt).append("\n");

        for (Map<String, String> message : history) {
            String role    = message.getOrDefault("role", "user");
            String content = message.getOrDefault("content", "");
            if ("user".equals(role)) {
                promptBuilder.append("<|user|>\n").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                promptBuilder.append("<|assistant|>\n").append(content).append("\n");
            }
        }
        promptBuilder.append("<|assistant|>\n");

        String prompt = promptBuilder.toString();
        log.debug("Sending {} chars to IBM Granite", prompt.length());

        try {
            String token = getIAMToken();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("decoding_method", "greedy");
            parameters.put("max_new_tokens", 2048);
            parameters.put("temperature", 0.7);
            parameters.put("repetition_penalty", 1.1);
            parameters.put("stop_sequences", List.of("<|user|>", "<|endoftext|>", "<|system|>"));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model_id",   modelId);
            requestBody.put("input",      prompt);
            requestBody.put("project_id", projectId);
            requestBody.put("parameters", parameters);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            String endpoint = watsonxUrl + GENERATION_PATH;

            ResponseEntity<JsonNode> response = restTemplate.postForEntity(endpoint, request, JsonNode.class);
            JsonNode rb = response.getBody();

            if (rb != null && rb.has("results") && rb.get("results").size() > 0) {
                String generated = rb.get("results").get(0).get("generated_text").asText("").trim();
                log.info("IBM Granite generated {} chars", generated.length());
                return generated;
            }

        } catch (Exception e) {
            log.error("WatsonX generation error: {}", e.getMessage());
            throw new RuntimeException("IBM Granite API error: " + e.getMessage(), e);
        }

        return "I'm sorry, I couldn't generate a response right now. Please try again.";
    }
}
