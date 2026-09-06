package com.travelagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelagent.model.WeatherData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Fetches real-time weather data from OpenWeatherMap free-tier API.
 * Falls back to deterministic demo data when the API key is absent.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Value("${weather.api-key:}")
    private String apiKey;

    @Value("${weather.base-url:https://api.openweathermap.org/data/2.5}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /** Returns true when a real OpenWeatherMap API key is configured. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Gets weather + 5-day forecast for the given city.
     * Gracefully falls back to demo data on any error.
     */
    public WeatherData getWeather(String city) {
        if (!isConfigured()) {
            log.info("Weather API not configured — using demo data for '{}'", city);
            return buildDemoWeather(city);
        }
        try {
            // 3-hour interval forecast: 40 entries = 5 days
            String url = baseUrl + "/forecast?q=" + city
                    + "&appid=" + apiKey + "&units=metric&cnt=40";
            JsonNode resp = restTemplate.getForObject(url, JsonNode.class);
            if (resp == null) return buildDemoWeather(city);

            JsonNode cityNode = resp.get("city");
            String cityName = cityNode.get("name").asText(city);
            String country  = cityNode.get("country").asText("");

            JsonNode first  = resp.get("list").get(0);
            double   temp   = first.get("main").get("temp").asDouble();
            double   feels  = first.get("main").get("feels_like").asDouble();
            int      hum    = first.get("main").get("humidity").asInt();
            String   desc   = first.get("weather").get(0).get("description").asText();
            String   icon   = emojiFor(first.get("weather").get(0).get("main").asText());

            // Aggregate one forecast entry per day
            Map<String, WeatherData.DailyForecast> dailyMap = new LinkedHashMap<>();
            for (JsonNode entry : resp.get("list")) {
                if (dailyMap.size() >= 5) break;
                String dateStr = entry.get("dt_txt").asText().substring(0, 10);
                if (!dailyMap.containsKey(dateStr)) {
                    LocalDate date   = LocalDate.parse(dateStr, DATE_FMT);
                    String    dayNm  = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                    String    wMain  = entry.get("weather").get(0).get("main").asText();
                    dailyMap.put(dateStr, WeatherData.DailyForecast.builder()
                        .date(dateStr).dayName(dayNm)
                        .minTemp(round(entry.get("main").get("temp_min").asDouble()))
                        .maxTemp(round(entry.get("main").get("temp_max").asDouble()))
                        .description(entry.get("weather").get(0).get("description").asText())
                        .icon(emojiFor(wMain))
                        .humidity(entry.get("main").get("humidity").asInt())
                        .build());
                }
            }

            return WeatherData.builder()
                .city(cityName).country(country)
                .temperature(round(temp)).feelsLike(round(feels))
                .humidity(hum).description(desc).icon(icon)
                .forecast(new ArrayList<>(dailyMap.values()))
                .build();

        } catch (Exception e) {
            log.warn("OpenWeatherMap call failed for '{}': {} — using demo data", city, e.getMessage());
            return buildDemoWeather(city);
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private String emojiFor(String main) {
        if (main == null) return "🌤️";
        return switch (main.toLowerCase()) {
            case "clear"                -> "☀️";
            case "clouds"               -> "⛅";
            case "rain", "drizzle"      -> "🌧️";
            case "thunderstorm"         -> "⛈️";
            case "snow"                 -> "❄️";
            case "mist", "fog", "haze"  -> "🌫️";
            default                     -> "🌤️";
        };
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Deterministic demo weather keyed on city name for reproducible demos. */
    private WeatherData buildDemoWeather(String city) {
        String c = city.toLowerCase();
        double base = c.contains("goa") || c.contains("bali") || c.contains("dubai") ? 32.0
                    : c.contains("paris") || c.contains("london")                      ? 16.0
                    : c.contains("tokyo") || c.contains("singapore")                   ? 24.0
                    : c.contains("new york") || c.contains("chicago")                  ? 14.0
                    : 26.0;

        String icon = (base > 28) ? "☀️" : (base < 18) ? "⛅" : "🌤️";
        String desc = (base > 28) ? "clear sky" : (base < 18) ? "partly cloudy" : "pleasant";

        Random rng = new Random(city.hashCode());
        LocalDate today = LocalDate.now();
        List<WeatherData.DailyForecast> forecast = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double jitter = (rng.nextDouble() - 0.5) * 6;
            LocalDate d = today.plusDays(i);
            forecast.add(WeatherData.DailyForecast.builder()
                .date(d.format(DATE_FMT))
                .dayName(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                .minTemp(round(base - 3 + jitter))
                .maxTemp(round(base + 3 + jitter))
                .description(i % 2 == 0 ? "clear sky" : "partly cloudy")
                .icon(i % 2 == 0 ? "☀️" : "⛅")
                .humidity(55 + rng.nextInt(35))
                .build());
        }

        return WeatherData.builder()
            .city(city).country("—")
            .temperature(base).feelsLike(round(base - 1.5))
            .humidity(65).description(desc).icon(icon)
            .forecast(forecast)
            .build();
    }
}
