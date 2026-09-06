package com.travelagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Provides destination data:
 *  - A curated list of popular travel destinations (no API key needed)
 *  - Country info from the free RestCountries API (no key needed)
 */
@Service
public class DestinationService {

    private static final Logger log = LoggerFactory.getLogger(DestinationService.class);
    private final RestTemplate restTemplate = new RestTemplate();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a hand-curated list of popular travel destinations.
     * Images are sourced from Unsplash (free, no API key for direct links).
     */
    public List<Map<String, Object>> getPopularDestinations() {
        return List.of(
            dest("Paris",     "France",    "🗼", "Romance, art & iconic cuisine",
                 "#6366f1", "paris,france,eiffel+tower", 75_000),
            dest("Goa",       "India",     "🏖️", "Beaches, vibes & seafood feasts",
                 "#0891b2", "goa,beach,india", 25_000),
            dest("Tokyo",     "Japan",     "🏯", "Futuristic skyline meets ancient temples",
                 "#7c3aed", "tokyo,japan,shibuya", 1_20_000),
            dest("Bali",      "Indonesia", "🌺", "Rice terraces, temples & surf",
                 "#059669", "bali,indonesia,temple", 45_000),
            dest("New York",  "USA",       "🗽", "The city that never sleeps",
                 "#dc2626", "new+york,manhattan,skyline", 1_50_000),
            dest("Dubai",     "UAE",       "🏙️", "Luxury & desert adventures",
                 "#d97706", "dubai,burj+khalifa,skyline", 1_80_000)
        );
    }

    /**
     * Fetches basic country information from the free RestCountries API.
     *
     * @param countryName Country name (e.g. "France", "Japan")
     * @return Map with keys: name, capital, region, flag (emoji)
     */
    public Map<String, Object> getCountryInfo(String countryName) {
        try {
            String url = "https://restcountries.com/v3.1/name/"
                    + countryName.replace(" ", "%20")
                    + "?fields=name,capital,currencies,languages,flags,region";
            JsonNode[] results = restTemplate.getForObject(url, JsonNode[].class);
            if (results != null && results.length > 0) {
                JsonNode info = results[0];
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name",    info.get("name").get("common").asText(countryName));
                out.put("region",  info.get("region").asText(""));
                if (info.has("capital") && info.get("capital").size() > 0) {
                    out.put("capital", info.get("capital").get(0).asText());
                }
                if (info.has("flags") && info.get("flags").has("emoji")) {
                    out.put("flag", info.get("flags").get("emoji").asText("🏳️"));
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("RestCountries lookup failed for '{}': {}", countryName, e.getMessage());
        }
        return Map.of("name", countryName, "region", "");
    }

    // ─── builder helper ───────────────────────────────────────────────────────

    private Map<String, Object> dest(String city, String country, String emoji,
                                     String tagline, String accentColor,
                                     String unsplashQuery, int minBudgetINR) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city",       city);
        m.put("country",    country);
        m.put("emoji",      emoji);
        m.put("tagline",    tagline);
        m.put("color",      accentColor);
        m.put("imageUrl",   "https://source.unsplash.com/400x260/?" + unsplashQuery);
        m.put("minBudget",  minBudgetINR);
        return m;
    }
}
