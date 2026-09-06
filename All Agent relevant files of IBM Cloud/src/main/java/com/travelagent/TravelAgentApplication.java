package com.travelagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Travel Planner Agent — Main Spring Boot Application Entry Point
 * Powered by IBM Granite via WatsonX.ai
 */
@SpringBootApplication
public class TravelAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(TravelAgentApplication.class, args);
        System.out.println("\n========================================");
        System.out.println("  ✈️  TravelAI Agent is RUNNING!");
        System.out.println("  🌐  Open: http://localhost:8080");
        System.out.println("  🤖  Model: IBM Granite 3-8B Instruct");
        System.out.println("========================================\n");
    }
}
