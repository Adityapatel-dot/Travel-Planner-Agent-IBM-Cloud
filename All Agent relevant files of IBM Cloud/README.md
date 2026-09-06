# ✈️ IBM Cloud AI Travel Planner Agent

An intelligent, AI-powered travel assistant built using **Spring Boot (Java 17)**, **IBM Granite 3-8B Instruct** model via **IBM watsonx.ai REST APIs**, and **Open-Meteo Weather API**. This application delivers personalized multi-day trip itineraries, real-time weather forecasts, and dynamic destination recommendations through a modern glassmorphic web interface.

---

## 🔗 Working GitHub Repository Link
**[https://github.com/Adityapatel-dot/Travel-Planner-Agent-IBM-Cloud](https://github.com/Adityapatel-dot/Travel-Planner-Agent-IBM-Cloud)**

---

## 🌟 Key Features

- **🤖 IBM Granite 3-8B AI Integration**: Directly leverages IBM Cloud Lite Services and IBM watsonx.ai REST APIs (`ibm/granite-3-8b-instruct`) for intelligent travel planning.
- **🌤️ Real-Time Weather Forecasts**: Integrated with Open-Meteo API to fetch live temperatures, weather conditions, and wind speed.
- **🗺️ Interactive Destination Search**: Browse curated travel destinations, budget guidelines, and key highlights.
- **🟢 Live API Health Monitoring**: Displays real-time connectivity status to IBM Cloud IAM & Granite LLM services.
- **🎨 Glassmorphism UI**: Built with responsive vanilla CSS and HTML5 for a premium visual experience.

---

## 📂 Required Submission Documents

This repository contains all mandatory files for Problem Statement No. 5:
1. **IBM Cloud Agent Files**: Spring Boot Java backend (`src/`), Maven configuration (`pom.xml`), environment template (`.env.example`).
2. **`problemstatement.pdf`**: Detailed problem statement documentation.
3. **`IBM Cloud AI Travel Planner Agent Project.pptx`**: Official project presentation.

---

## 🛠️ Technology Stack

- **Backend**: Java 17, Spring Boot 3.2.3, REST APIs, Lombok, Jackson
- **AI Engine**: IBM watsonx.ai REST API, IBM Granite 3-8B Instruct Model
- **Authentication**: IBM Cloud IAM Token Generation (`https://iam.cloud.ibm.com/identity/token`)
- **Frontend**: HTML5, CSS3 (Glassmorphism & Flexbox/Grid), JavaScript (ES6+ Fetch API)
- **Build Tool**: Apache Maven

---

## 🚀 Getting Started

### Prerequisites

- Java Development Kit (JDK 17 or higher)
- Apache Maven
- IBM Cloud Account with access to IBM watsonx.ai

### Environment Configuration

Create a `.env` file in the root directory (refer to `.env.example`):

```env
WATSONX_API_KEY=your_ibm_cloud_api_key
WATSONX_PROJECT_ID=your_watsonx_project_id
WATSONX_URL=https://eu-gb.ml.cloud.ibm.com
WATSONX_MODEL_ID=ibm/granite-3-8b-instruct
```

### Running the Application

```bash
# Build and package the project
mvn clean package

# Run the Spring Boot application
mvn spring-boot:run
```

Access the application in your browser at: `http://localhost:8080`

---

## 📊 System Architecture

```
User (Browser) <---> Spring Boot REST Controller
                           |
            +--------------+--------------+
            |                             |
    IBM WatsonX Service            Weather Service
  (IBM Granite 3-8B LLM)         (Open-Meteo API)
            |                             |
  IBM Cloud IAM Token REST API       Live Weather Data
```

---

## 📄 License

This project is created for the AICTE & IBM Cloud University Engagement Program.
