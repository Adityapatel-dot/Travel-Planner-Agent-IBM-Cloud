package com.travelagent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Weather data returned by the weather service.
 * Includes current conditions and a 5-day daily forecast.
 */
@Data
@Builder
public class WeatherData {

    private String city;
    private String country;
    private double temperature;
    private double feelsLike;
    private int humidity;
    private String description;
    /** Emoji icon representing the condition */
    private String icon;
    private List<DailyForecast> forecast;

    @Data
    @Builder
    public static class DailyForecast {
        private String date;       // "2024-12-25"
        private String dayName;    // "Wed"
        private double minTemp;
        private double maxTemp;
        private String description;
        private String icon;
        private int humidity;
    }
}
