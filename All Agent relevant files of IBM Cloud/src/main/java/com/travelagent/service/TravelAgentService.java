package com.travelagent.service;

import com.travelagent.model.ChatResponse;
import com.travelagent.model.WeatherData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main travel-agent orchestration service.
 *
 * On each turn:
 *   1. Add user message to in-memory session history
 *   2. Detect intent (weather / itinerary / general)
 *   3. Extract destination name if present
 *   4. Optionally pre-fetch weather data and embed it in the prompt
 *   5. Call IBM Granite via WatsonXService (or Demo Mode)
 *   6. Return structured ChatResponse with text + optional widgets
 */
@Service
public class TravelAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelAgentService.class);

    /** Max turns kept in each session context (to respect token limits) */
    private static final int MAX_HISTORY = 12;

    @Autowired private WatsonXService    watsonX;
    @Autowired private WeatherService    weather;
    @Autowired private DestinationService destinations;

    /** Session store: sessionId -> conversation history */
    private final Map<String, List<Map<String, String>>> sessions = new ConcurrentHashMap<>();

    // -- System prompt fed to IBM Granite -----------------------------------------------
    private static final String SYSTEM_PROMPT =
        "You are TravelAI, a world-class expert travel planning assistant powered by IBM Granite AI.\n" +
        "You help users plan amazing personalized trips with detailed itineraries, destination\n" +
        "recommendations, budget-conscious advice, hotel suggestions, and transport options.\n\n" +
        "When planning any trip, always provide ALL of the following sections clearly:\n\n" +
        "## Destination Travel Plan\n\n" +
        "### Destination Highlights\n" +
        "(3-4 sentences about why this place is special)\n\n" +
        "### Day-by-Day Itinerary\n" +
        "**Day 1 - Theme**\n" +
        "- Morning: (activity with specific name, brief note, approx cost)\n" +
        "- Afternoon: (activity)\n" +
        "- Evening: (activity + dinner recommendation)\n\n" +
        "(Repeat for each day)\n\n" +
        "### Budget Breakdown (Per Person)\n" +
        "| Category | Budget Option | Mid-Range | Luxury |\n" +
        "(Always include a table with 4 rows: Accommodation, Food, Transport, Activities)\n\n" +
        "### Hotel Recommendations\n" +
        "- Budget: (hotel name, price per night, one-liner)\n" +
        "- Mid-Range: (hotel name, price, one-liner)\n" +
        "- Luxury: (hotel name, price, one-liner)\n\n" +
        "### Getting There\n" +
        "(Flights/trains from major cities, airport transfers)\n\n" +
        "### Must-Try Foods\n" +
        "(5+ local dishes)\n\n" +
        "### Travel Tips\n" +
        "(Visa, currency, best season, safety, local customs - at least 5 tips)\n\n" +
        "Rules:\n" +
        "- Always be specific: name actual hotels, restaurants, landmarks, and approximate costs in INR\n" +
        "- Be friendly, enthusiastic, and helpful\n" +
        "- Respect the user's stated budget\n" +
        "- For Indian travellers: include visa info and give costs in both INR and local currency\n" +
        "- Keep responses well-structured with markdown headers and bullet points\n";

    // -----------------------------------------------------------------------------------

    public ChatResponse chat(String sessionId, String userMessage) {
        List<Map<String, String>> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

        String lower = userMessage.toLowerCase();

        // Intent detection
        boolean wantsWeather   = lower.contains("weather") || lower.contains("climate")
                              || lower.contains("temperature") || lower.contains("forecast")
                              || lower.contains("rain") || lower.contains("snow");
        boolean wantsItinerary = lower.contains("plan") || lower.contains("itinerary")
                              || lower.contains("days") || lower.contains("trip")
                              || lower.contains("travel to") || lower.contains("visit")
                              || lower.contains("schedule");

        // Extract destination
        String destination = extractDestination(userMessage);

        // Conditionally fetch weather to enrich the prompt context
        WeatherData weatherData = null;
        if (destination != null && (wantsWeather || wantsItinerary)) {
            try {
                weatherData = weather.getWeather(destination);
            } catch (Exception e) {
                log.warn("Weather pre-fetch failed: {}", e.getMessage());
            }
        }

        // Build augmented user message (inject live weather context)
        String augmented = userMessage;
        if (weatherData != null) {
            augmented += "\n\n[Real-time weather context for " + destination + ": "
                + weatherData.getDescription() + ", "
                + weatherData.getTemperature() + "C, humidity "
                + weatherData.getHumidity() + "%. "
                + "5-day forecast available. Please factor this into your recommendations.]";
        }

        // Push to history
        history.add(Map.of("role", "user", "content", augmented));
        trimHistory(history);

        // Generate AI response
        String aiText;
        boolean isDemo = !watsonX.isConfigured();

        if (!isDemo) {
            try {
                aiText = watsonX.generateWithHistory(new ArrayList<>(history), SYSTEM_PROMPT);
                log.info("IBM Granite response: {} chars for session {}", aiText.length(), sessionId);
            } catch (Exception e) {
                log.error("WatsonX call failed, falling back to demo: {}", e.getMessage());
                aiText = buildDemoResponse(userMessage, destination, wantsItinerary);
                isDemo = true;
            }
        } else {
            aiText = buildDemoResponse(userMessage, destination, wantsItinerary);
        }

        // Store assistant reply in history
        history.add(Map.of("role", "assistant", "content", aiText));

        // Assemble response
        ChatResponse.ChatResponseBuilder builder = ChatResponse.builder()
            .message(aiText)
            .sessionId(sessionId)
            .demoMode(isDemo)
            .type(wantsItinerary ? "itinerary" : "chat")
            .timestamp(System.currentTimeMillis());

        // Attach weather widget data if available
        if (weatherData != null) {
            Map<String, Object> wMap = new LinkedHashMap<>();
            wMap.put("city",        weatherData.getCity());
            wMap.put("country",     weatherData.getCountry());
            wMap.put("temperature", weatherData.getTemperature());
            wMap.put("feelsLike",   weatherData.getFeelsLike());
            wMap.put("humidity",    weatherData.getHumidity());
            wMap.put("description", weatherData.getDescription());
            wMap.put("icon",        weatherData.getIcon());
            wMap.put("forecast",    weatherData.getForecast());
            builder.weather(wMap);
        }

        // Include destination cards on the very first message
        if (history.size() <= 2) {
            builder.destinations(this.destinations.getPopularDestinations());
        }

        return builder.build();
    }

    // -- Demo Mode responses ------------------------------------------------------------

    private String buildDemoResponse(String msg, String dest, boolean wantsItinerary) {
        int days = parseDays(msg);

        if (dest != null && wantsItinerary) {
            return destinationItinerary(dest.toLowerCase(), days);
        }
        if (dest != null) {
            return destinationQuickInfo(dest);
        }

        String lower = msg.toLowerCase();
        if (lower.matches(".*\\b(hi|hello|hey|hola|namaste)\\b.*")) {
            return welcomeMessage();
        }
        if (lower.contains("suggest") || lower.contains("recommend") || lower.contains("best place")
                || lower.contains("where should") || lower.contains("destination")) {
            return destinationSuggestions();
        }
        if (lower.contains("budget") || lower.contains("cost") || lower.contains("cheap")) {
            return budgetTravelTips();
        }
        return genericHelp();
    }

    private String destinationItinerary(String dest, int days) {
        if (dest.contains("goa")   || dest.contains("india"))      return goaItinerary(days);
        if (dest.contains("paris") || dest.contains("france"))     return parisItinerary(days);
        if (dest.contains("tokyo") || dest.contains("japan"))      return tokyoItinerary(days);
        if (dest.contains("bali")  || dest.contains("indonesia"))  return baliItinerary(days);
        if (dest.contains("dubai") || dest.contains("uae"))        return dubaiItinerary(days);
        if (dest.contains("new york") || dest.contains("usa"))     return newYorkItinerary(days);
        if (dest.contains("singapore"))                            return singaporeItinerary(days);
        return genericItinerary(capitalise(dest), days);
    }

    private String destinationQuickInfo(String dest) {
        return "## " + capitalise(dest) + " - Quick Overview\n\n"
            + "**" + capitalise(dest) + "** is a fantastic destination! Here's what you should know:\n\n"
            + "- **Best time to visit:** October - March (pleasant weather)\n"
            + "- **Average budget:** Rs.40,000 - Rs.1,20,000 per person (5 days)\n"
            + "- **Visa:** Check requirements on the official consulate website\n"
            + "- **Climate:** Varies by season; check the Weather tab above\n\n"
            + "Would you like me to create a full **day-by-day itinerary**? "
            + "Just tell me:\n> \"Plan a N-day trip to " + capitalise(dest)
            + " for X people with Rs.budget budget\"\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    // -- Destination itineraries (Demo Mode) --------------------------------------------

    private String goaItinerary(int days) {
        return "## " + days + "-Day Goa Trip - Sun, Sand & Seafood!\n\n"
            + "**Overview:** Goa is India's beach paradise - a vibrant blend of Portuguese heritage, "
            + "pristine beaches, nightlife, and mouthwatering seafood. Best visited November to February.\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Arrival & North Goa Beaches**\n"
            + "- Morning: Arrive, check in. Head to **Baga Beach** (free entry)\n"
            + "- Afternoon: Water sports at Calangute - parasailing Rs.1,200, jet ski Rs.800\n"
            + "- Evening: Sunset at **Sinquerim Beach** + Tito's Street\n"
            + "- Dinner: **Britto's Restaurant** - Goan prawn curry Rs.350\n\n"
            + "**Day 2 - Heritage & Culture**\n"
            + "- Morning: **Basilica of Bom Jesus** (UNESCO) + Se Cathedral - free\n"
            + "- Afternoon: **Fontainhas** Portuguese Quarter walk\n"
            + "- Evening: **Anjuna Flea Market** (Wednesdays only) or Mapusa Market\n"
            + "- Night: Pub-hopping on Tito's Lane\n\n"
            + "**Day 3 - South Goa Serenity**\n"
            + "- Morning: **Palolem Beach** - crescent-shaped paradise\n"
            + "- Afternoon: Dolphin-watching boat trip Rs.500\n"
            + "- Evening: Yoga session at **Agonda Beach** (Rs.300-600/class)\n"
            + "- Dinner: Beach shack with lobster + cocktails Rs.1,200\n\n"
            + "**Day 4 - Adventure Day**\n"
            + "- Morning: **Dudhsagar Waterfall** jeep safari Rs.2,500 (book ahead!)\n"
            + "- Afternoon: **Mandovi River** backwater cruise + spice plantation Rs.600\n"
            + "- Evening: Explore **Panjim** city market\n\n"
            + "**Day 5 - Relaxation & Departure**\n"
            + "- Sunrise at **Vagator Beach** - dramatic cliffs & sunset cove\n"
            + "- Morning: Traditional Goan Ayurvedic massage Rs.800\n"
            + "- Shopping: Cashews, Bebinca sweets, Feni liquor souvenirs\n\n"
            + "---\n\n"
            + "### Budget Breakdown (Per Person, 5 Days)\n\n"
            + "| Category | Budget | Mid-Range | Luxury |\n"
            + "|----------|--------|-----------|--------|\n"
            + "| Accommodation | Rs.4,000 | Rs.12,500 | Rs.30,000 |\n"
            + "| Food | Rs.3,000 | Rs.6,000 | Rs.12,500 |\n"
            + "| Transport | Rs.2,000 | Rs.4,000 | Rs.7,500 |\n"
            + "| Activities | Rs.3,500 | Rs.7,500 | Rs.15,000 |\n"
            + "| **TOTAL** | **Rs.12,500** | **Rs.30,000** | **Rs.65,000** |\n\n"
            + "---\n\n"
            + "### Hotel Recommendations\n"
            + "- **Budget:** Zostel Goa - Rs.600-1,200/night (hostel, great community)\n"
            + "- **Mid-Range:** Vivanta Panaji - Rs.3,500-5,000/night (riverside views)\n"
            + "- **Luxury:** Taj Exotica Goa - Rs.14,000+/night (private beach, spa)\n\n"
            + "### Getting There\n"
            + "- Flights: Mumbai-Goa Rs.2,500-5,000 | Delhi-Goa Rs.4,000-8,000\n"
            + "- Train: Rajdhani Express from Mumbai to Madgaon (10h) Rs.800-2,500\n\n"
            + "### Must-Try Foods\n"
            + "- Fish Curry Rice | Prawn Balchao | Bebinca | Xacuti Chicken | Feni Cocktails\n\n"
            + "### Travel Tips\n"
            + "- Scooter rental Rs.350/day - the best way to explore\n"
            + "- Carry SPF 50+ sunscreen and a reusable water bottle\n"
            + "- Many beach shacks are cash-only - carry Rs.5,000 cash\n"
            + "- Avoid Dec 25 - Jan 5 (prices 2x higher)\n"
            + "- Swimming is safer at south Goa beaches (lifeguards present)\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    private String parisItinerary(int days) {
        return "## " + days + "-Day Paris Experience - La Ville Lumiere!\n\n"
            + "**Overview:** Paris is the world's most visited city - a timeless tapestry of art, "
            + "boulevards, Michelin stars, and that iconic iron tower. Magical year-round, "
            + "but April-June and September-October are glorious.\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Iconic Paris**\n"
            + "- Morning: **Eiffel Tower** - summit ticket EUR 29 (~Rs.2,600). Book online!\n"
            + "- Afternoon: **Seine River Cruise** with Bateaux Mouches EUR 17 (~Rs.1,500)\n"
            + "- Evening: Walk **Champs-Elysees** then Arc de Triomphe sunset climb EUR 15\n"
            + "- Dinner: French onion soup at **Au Pied de Cochon** (~Rs.2,200/person)\n\n"
            + "**Day 2 - Art & Culture**\n"
            + "- Morning: **Louvre Museum** (EUR 22) - Da Vinci, Venus de Milo\n"
            + "- Afternoon: **Musee d'Orsay** (EUR 16) - Van Gogh, Monet originals\n"
            + "- Evening: Stroll **Tuileries Garden**, buy crepes from a street cart (~Rs.200)\n\n"
            + "**Day 3 - Montmartre & Bohemian Paris**\n"
            + "- Morning: **Sacre-Coeur** at dawn - free and breathtaking views\n"
            + "- Afternoon: Artists' square, Moulin Rouge area, vintage shops\n"
            + "- Evening: **Le Marais** - trendy galleries, falafels, cocktail bars\n"
            + "- Dinner: Rooftop with Eiffel Tower view at **Terrass Hotel** (~Rs.4,000)\n\n"
            + "**Day 4 - Versailles**\n"
            + "- Full day: **Palace of Versailles** by RER B (EUR 20 palace + EUR 12 train)\n"
            + "- Hall of Mirrors, Marie Antoinette's estate, Grand Canal boat ride\n\n"
            + "**Day 5 - Hidden Gems & Departure**\n"
            + "- Morning: **Marche d'Aligre** local market + fresh croissants Rs.150\n"
            + "- Afternoon: **Latin Quarter** and **Notre-Dame** (exterior)\n"
            + "- Shopping: Laduree macarons, Fragonard perfume, wine\n\n"
            + "---\n\n"
            + "### Budget Breakdown (Per Person, 5 Days, INR)\n\n"
            + "| Category | Budget | Mid-Range | Luxury |\n"
            + "|----------|--------|-----------|--------|\n"
            + "| Accommodation | Rs.17,500 | Rs.40,000 | Rs.1,00,000 |\n"
            + "| Food | Rs.12,500 | Rs.25,000 | Rs.50,000 |\n"
            + "| Transport | Rs.4,000 | Rs.7,500 | Rs.15,000 |\n"
            + "| Activities | Rs.8,000 | Rs.15,000 | Rs.30,000 |\n"
            + "| **TOTAL** | **Rs.42,000** | **Rs.87,500** | **Rs.1,95,000** |\n"
            + "*(Flights from India: add Rs.40,000-70,000 per person)*\n\n"
            + "---\n\n"
            + "### Hotel Recommendations\n"
            + "- **Budget:** Generator Paris Hostel - Rs.3,500-5,000/night (stylish, central)\n"
            + "- **Mid-Range:** Hotel des Arts Montmartre - Rs.8,000-12,000/night\n"
            + "- **Luxury:** Le Bristol Paris - Rs.40,000+/night (5-star palace hotel)\n\n"
            + "### Getting There\n"
            + "- Air France, Emirates, IndiGo: Delhi/Mumbai to Paris CDG (~Rs.40,000-70,000 RT)\n"
            + "- Airport to city: RER B train EUR 12 or taxi EUR 55\n\n"
            + "### Must-Try Foods\n"
            + "- Croissant | Crepes Suzette | Baguette sandwich | Creme brulee | Escargot | Macarons\n\n"
            + "### Travel Tips\n"
            + "- Book Eiffel Tower and Louvre tickets ONLINE to avoid 2-hour queues!\n"
            + "- Get a Navigo Decouverte weekly metro pass EUR 22 (unlimited rides)\n"
            + "- Learn basic French: Bonjour, Merci, S'il vous plait\n"
            + "- Schengen visa required for Indians - apply 6 weeks ahead\n"
            + "- Tap-to-pay is universal in Paris - carry just EUR 50 cash\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    private String tokyoItinerary(int days) {
        return "## " + days + "-Day Tokyo Adventure - Future Meets Tradition!\n\n"
            + "**Overview:** Tokyo is mesmerising - cherry blossoms and neon signs, "
            + "ancient shrines beside glass towers, world's best ramen and sushi. "
            + "Best season: March-April or October-November.\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Modern Tokyo**\n"
            + "- Morning: **Shibuya Crossing** (world's busiest) + Hachiko statue\n"
            + "- Afternoon: **Tokyo Skytree** (634m) - JPY 2,100 (~Rs.1,150) top deck\n"
            + "- Evening: **Akihabara** - anime, electronics, maid cafes\n"
            + "- Dinner: **Tsukiji Outer Market** - fresh sushi bowl JPY 1,500\n\n"
            + "**Day 2 - Traditional Tokyo**\n"
            + "- Morning: **Senso-ji Temple**, Asakusa - Japan's oldest temple (free)\n"
            + "- Afternoon: **Nakamise Street** shopping, kimono rental JPY 2,500\n"
            + "- Evening: **Ueno Park** and Tokyo National Museum JPY 620\n"
            + "- Dinner: **Ichiran Ramen** solo dining experience JPY 1,200\n\n"
            + "**Day 3 - Pop Culture & Harajuku**\n"
            + "- Morning: **Takeshita Street** Harajuku - kawaii fashion\n"
            + "- Afternoon: **teamLab Planets** digital art museum JPY 3,200 (~Rs.1,750)\n"
            + "- Evening: **Roppongi Hills** and Mori Art Museum JPY 2,000\n"
            + "- Dinner: Izakaya in Shinjuku - yakitori & sake JPY 2,500\n\n"
            + "**Day 4 - Day Trip (Nikko or Yokohama)**\n"
            + "- **Nikko National Park** - UNESCO shrines, Toshogu and waterfalls\n"
            + "- OR **Yokohama Chinatown** + waterfront Minato Mirai\n\n"
            + "**Day 5 - Fuji and Onsen**\n"
            + "- Morning: **Hakone** for iconic Mt. Fuji views\n"
            + "- Afternoon: Outdoor onsen hot spring JPY 1,500\n"
            + "- Shopping: Matcha KitKats, Ghibli merchandise, Capsule toys\n\n"
            + "---\n\n"
            + "### Budget Breakdown (Per Person, 5 Days, INR)\n\n"
            + "| Category | Budget | Mid-Range | Luxury |\n"
            + "|----------|--------|-----------|--------|\n"
            + "| Accommodation | Rs.12,500 | Rs.35,000 | Rs.90,000 |\n"
            + "| Food | Rs.7,500 | Rs.15,000 | Rs.35,000 |\n"
            + "| Transport | Rs.5,000 | Rs.9,000 | Rs.17,500 |\n"
            + "| Activities | Rs.10,000 | Rs.18,000 | Rs.35,000 |\n"
            + "| **TOTAL** | **Rs.35,000** | **Rs.77,000** | **Rs.1,77,500** |\n\n"
            + "---\n\n"
            + "### Hotel Recommendations\n"
            + "- **Budget:** Khaosan Tokyo Ninja Hostel - Rs.2,000-3,500/night\n"
            + "- **Mid-Range:** Shinjuku Granbell Hotel - Rs.7,000-10,000/night\n"
            + "- **Luxury:** The Peninsula Tokyo - Rs.35,000+/night\n\n"
            + "### Getting There\n"
            + "- ANA / JAL / IndiGo: Delhi/Mumbai to Narita (~Rs.45,000-80,000 RT)\n"
            + "- Airport to Tokyo: Narita Express JPY 3,070 or Limousine Bus JPY 3,200\n\n"
            + "### Must-Try Foods\n"
            + "- Ramen | Sushi | Tempura | Takoyaki | Matcha Ice Cream | Wagyu Beef | Onigiri\n\n"
            + "### Travel Tips\n"
            + "- IC Card (Suica/Pasmo) - tap for all trains and convenience stores\n"
            + "- Japan is still 60% cash - carry JPY 20,000 in hand\n"
            + "- Rent a pocket Wi-Fi at airport (JPY 350/day)\n"
            + "- Sakura season (late March to early April) - book hotels 3 months ahead!\n"
            + "- Google Translate camera handles Japanese menus perfectly\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    private String baliItinerary(int days) {
        return "## " + days + "-Day Bali Escape - Island of the Gods!\n\n"
            + "**Overview:** Bali offers emerald rice terraces, ornate temples, world-class surf, "
            + "and deep spiritual energy. Perfect for adventure, culture, and total relaxation. "
            + "Best season: April-October (dry season).\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Arrival & Seminyak**\n"
            + "- Afternoon: Arrive, check in to beachfront villa in Seminyak\n"
            + "- Evening: Surf lesson at Kuta Beach Rs.1,500 (beginner-friendly)\n"
            + "- Sunset at **Ku De Ta** beach club - Bali's most iconic sunset spot\n"
            + "- Dinner: Fresh seafood BBQ at Jimbaran Bay Rs.1,800\n\n"
            + "**Day 2 - Spiritual Ubud**\n"
            + "- Morning: **Sacred Monkey Forest Sanctuary** Rs.300\n"
            + "- Afternoon: **Ubud Art Market** + **Ubud Royal Palace** (free)\n"
            + "- Late Afternoon: **Tegallalang Rice Terraces** - stunning photo spot Rs.50\n"
            + "- Evening: **Kecak Fire Dance** at Uluwatu Temple Rs.600\n\n"
            + "**Day 3 - Temple Trail**\n"
            + "- Sunrise: **Tanah Lot** sea temple - most photographed in Bali (Rs.200)\n"
            + "- Morning: **Uluwatu** cliffside temple - dramatic ocean views\n"
            + "- Afternoon: **Padang Padang** secret beach (from Eat Pray Love film)\n"
            + "- Dinner: Cliff-top restaurant at Uluwatu Rs.2,500\n\n"
            + "**Day 4 - Adventure**\n"
            + "- 4 AM: **Mount Batur Sunrise Trek** Rs.1,800 - breathtaking crater views!\n"
            + "- Morning: Natural hot springs bath Rs.700\n"
            + "- Afternoon: Snorkeling at **Amed Blue Lagoon** Rs.1,000\n\n"
            + "**Day 5 - Wellness & Departure**\n"
            + "- Morning: **Traditional Balinese massage** Rs.800 (90 min!)\n"
            + "- Afternoon: Silver jewelry at Celuk Village, batik shopping\n"
            + "- Temple blessing ceremony (meaningful experience)\n\n"
            + "---\n\n"
            + "### Budget Breakdown (Per Person, 5 Days, INR)\n\n"
            + "| Category | Budget | Mid-Range | Luxury |\n"
            + "|----------|--------|-----------|--------|\n"
            + "| Accommodation | Rs.7,500 | Rs.25,000 | Rs.75,000 |\n"
            + "| Food | Rs.4,000 | Rs.10,000 | Rs.25,000 |\n"
            + "| Transport | Rs.3,000 | Rs.6,000 | Rs.12,500 |\n"
            + "| Activities | Rs.5,000 | Rs.12,000 | Rs.25,000 |\n"
            + "| **TOTAL** | **Rs.19,500** | **Rs.53,000** | **Rs.1,37,500** |\n\n"
            + "---\n\n"
            + "### Hotel Recommendations\n"
            + "- **Budget:** Puri Garden Hotel Ubud - Rs.1,200-2,000/night\n"
            + "- **Mid-Range:** Alaya Resort Ubud - Rs.5,500-8,000/night (infinity pool!)\n"
            + "- **Luxury:** Four Seasons Bali at Sayan - Rs.35,000+/night\n\n"
            + "### Getting There\n"
            + "- IndiGo / Air Asia: Mumbai/Delhi to Denpasar Ngurah Rai (~Rs.18,000-35,000 RT)\n"
            + "- Airport to Seminyak: Taxi Rs.1,500 or Grab app Rs.900\n\n"
            + "### Must-Try Foods\n"
            + "- Nasi Goreng | Satay | Babi Guling (suckling pig) | Gado-Gado | Pisang Goreng | Arak\n\n"
            + "### Travel Tips\n"
            + "- Visa on arrival for Indians - USD 35 (30 days), pay at airport\n"
            + "- Scooter rental USD 5/day in Ubud - great for rice terrace exploration\n"
            + "- Wear a sarong at temples (provided free or buy for Rs.150)\n"
            + "- Mosquito repellent is essential - dengue risk in rainy season\n"
            + "- USD is widely accepted; have Rupiah (IDR) for local stalls\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    private String dubaiItinerary(int days) {
        return "## " + days + "-Day Dubai Experience - Where Luxury Meets the Desert!\n\n"
            + "**Overview:** Dubai is a city of superlatives - tallest building, largest mall, "
            + "ski slope inside a desert. A dazzling blend of ultra-modern luxury and ancient "
            + "Bedouin culture. Best visited October to April.\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Modern Marvels**\n"
            + "- Morning: **Burj Khalifa** At the Top (124F) - AED 149 (~Rs.3,400)\n"
            + "- Afternoon: **Dubai Mall** (world's largest) + Dubai Fountain show (free)\n"
            + "- Evening: **Dubai Marina** walk + yacht dinner cruise AED 250\n\n"
            + "**Day 2 - Old Dubai**\n"
            + "- Morning: **Gold Souk** and **Spice Souk** in Deira (free to browse)\n"
            + "- Afternoon: **Abra** water taxi across Dubai Creek - AED 1!\n"
            + "- Evening: **Al Fahidi Historical District** (free, excellent museum)\n"
            + "- Dinner: Bur Dubai's Al Dhiyafah Street - Indian/Pakistani food Rs.300\n\n"
            + "**Day 3 - Desert Safari**\n"
            + "- Full Day: **Desert Safari** - dune bashing, camel ride, henna, "
            + "belly dancing, BBQ dinner under stars - AED 250 (~Rs.5,700)\n\n"
            + "**Day 4 - Beaches & Attractions**\n"
            + "- Morning: **Jumeirah Beach** - free public beach, turquoise water\n"
            + "- Afternoon: **Palm Jumeirah** monorail + Atlantis water park AED 300\n"
            + "- Evening: **Museum of the Future** - AED 145 (most stunning building!)\n\n"
            + "**Day 5 - Shopping & Departure**\n"
            + "- Morning: **Dubai Duty Free** - electronics, perfumes, gold\n"
            + "- Afternoon: **Global Village** (seasonal) or **Ibn Battuta Mall**\n"
            + "- Departure: Dubai International Airport (DXB)\n\n"
            + "---\n\n"
            + "### Budget Breakdown (Per Person, 5 Days, INR)\n\n"
            + "| Category | Budget | Mid-Range | Luxury |\n"
            + "|----------|--------|-----------|--------|\n"
            + "| Accommodation | Rs.25,000 | Rs.60,000 | Rs.1,75,000 |\n"
            + "| Food | Rs.10,000 | Rs.22,500 | Rs.50,000 |\n"
            + "| Transport | Rs.5,000 | Rs.10,000 | Rs.20,000 |\n"
            + "| Activities | Rs.12,500 | Rs.22,500 | Rs.50,000 |\n"
            + "| **TOTAL** | **Rs.52,500** | **Rs.1,15,000** | **Rs.2,95,000** |\n\n"
            + "---\n\n"
            + "### Hotel Recommendations\n"
            + "- **Budget:** Ibis World Trade Centre - Rs.5,000-7,000/night\n"
            + "- **Mid-Range:** Jumeirah Beach Hotel - Rs.15,000-22,000/night\n"
            + "- **Luxury:** Burj Al Arab - Rs.1,00,000+/night (world's most iconic hotel)\n\n"
            + "### Getting There\n"
            + "- Emirates / IndiGo / Air Arabia: All major Indian cities to Dubai (~Rs.15,000-40,000 RT)\n"
            + "- Airport to city: Dubai Metro (Red Line) AED 12 or taxi AED 80\n\n"
            + "### Must-Try Foods\n"
            + "- Shawarma | Hummus & Pita | Al Harees | Luqaimat (honey dumplings) | Camel Burger | Kunafa\n\n"
            + "### Travel Tips\n"
            + "- Visa on arrival for Indian passport holders - free, 14 days\n"
            + "- Dress modestly in malls, souks, and mosques (shoulders & knees covered)\n"
            + "- May to September: 45C+ - avoid outdoor activities midday\n"
            + "- Cards accepted everywhere; carry AED 200 cash for souks & tips\n"
            + "- Use Careem/Uber app - much cheaper than hotel taxis\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    private String newYorkItinerary(int days) {
        return "## " + days + "-Day New York City - The City That Never Sleeps!\n\n"
            + "**Overview:** New York is electrifying - towering skyscrapers, world-class museums, "
            + "Broadway shows, Central Park, and the greatest food melting pot on Earth. "
            + "Best in April-June and September-November.\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Manhattan Icons**\n"
            + "- Morning: **Statue of Liberty** + **Ellis Island** - USD 24 ferry (book online!)\n"
            + "- Afternoon: **One World Observatory** (104F) - USD 34 (~Rs.2,850)\n"
            + "- Evening: Walk **Brooklyn Bridge** at sunset - free!\n"
            + "- Dinner: Joe's Pizza in Greenwich Village USD 3/slice\n\n"
            + "**Day 2 - Midtown & Museums**\n"
            + "- Morning: **Metropolitan Museum of Art** - USD 30 (suggested donation)\n"
            + "- Afternoon: **Central Park** - rowboats, Bethesda Fountain, Strawberry Fields\n"
            + "- Evening: **Broadway Show** (TKTS discount booth) USD 50-150\n"
            + "- Dinner: Keens Steakhouse - USD 80/person (legendary)\n\n"
            + "**Day 3 - Brooklyn & Culture**\n"
            + "- Morning: **DUMBO** Brooklyn - Manhattan Bridge arch photo\n"
            + "- Afternoon: **Brooklyn Museum** or **Prospect Park**\n"
            + "- Evening: **Chelsea Market** food hall + High Line elevated park\n"
            + "- Dinner: **Koreatown** on 32nd Street - KBBQ feast USD 25\n\n"
            + "**Day 4 - Neighborhoods**\n"
            + "- Morning: **Times Square** (best at night, but see by day too)\n"
            + "- Afternoon: **5th Avenue** shopping\n"
            + "- Evening: **Top of the Rock** (Rockefeller Center) - USD 42\n\n"
            + "**Day 5 - Queens & Departure**\n"
            + "- Morning: **Flushing, Queens** - best dim sum outside China\n"
            + "- Afternoon: **MoMA** (Museum of Modern Art) - USD 25\n"
            + "- Departure: JFK or Newark airport\n\n"
            + "---\n\n"
            + "### Budget Breakdown (Per Person, 5 Days, INR)\n\n"
            + "| Category | Budget | Mid-Range | Luxury |\n"
            + "|----------|--------|-----------|--------|\n"
            + "| Accommodation | Rs.17,500 | Rs.45,000 | Rs.1,30,000 |\n"
            + "| Food | Rs.12,500 | Rs.25,000 | Rs.55,000 |\n"
            + "| Transport | Rs.4,000 | Rs.7,500 | Rs.17,500 |\n"
            + "| Activities | Rs.12,500 | Rs.22,500 | Rs.45,000 |\n"
            + "| **TOTAL** | **Rs.46,500** | **Rs.1,00,000** | **Rs.2,47,500** |\n"
            + "*(Flights from India: add Rs.60,000-1,00,000 per person)*\n\n"
            + "---\n\n"
            + "### Hotel Recommendations\n"
            + "- **Budget:** HI NYC Hostel - Rs.3,500-5,500/night (Upper West Side)\n"
            + "- **Mid-Range:** Pod 51 Hotel - Rs.9,000-14,000/night (Midtown)\n"
            + "- **Luxury:** The Plaza Hotel - Rs.75,000+/night (Central Park South)\n\n"
            + "### Travel Tips\n"
            + "- MTA Metro card - USD 33 for 7-day unlimited subway + bus\n"
            + "- TKTS Booth in Times Square for 30-50% off same-day Broadway tickets\n"
            + "- NYC Pass worth it if you visit 4+ attractions (USD 170/4 days)\n"
            + "- US B1/B2 visa required for Indians - apply 6-8 weeks ahead\n"
            + "- NYC winters (Dec-Feb) are brutal - pack layers!\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    private String singaporeItinerary(int days) {
        return "## " + days + "-Day Singapore - The Lion City!\n\n"
            + "**Overview:** Singapore is Asia's most polished gem - impeccably clean, ultra-safe, "
            + "stunning architecture, and one of the world's best food cities. "
            + "Perfect for families and first-time international travellers.\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Marina Bay**\n"
            + "- Morning: **Gardens by the Bay** - Supertree Grove (free, light show at 8 PM)\n"
            + "- Afternoon: **Marina Bay Sands** SkyPark observation deck SGD 32 (~Rs.2,000)\n"
            + "- Evening: **Clarke Quay** riverside dining & bar scene\n"
            + "- Dinner: **Jumbo Seafood** - chilli crab SGD 80/kg (must try!)\n\n"
            + "**Day 2 - Cultural Districts**\n"
            + "- Morning: **Little India** - Sri Veeramakaliamman Temple, shop-houses\n"
            + "- Afternoon: **Chinatown** Heritage Centre SGD 10, street food\n"
            + "- Evening: **Arab Street** and Sultan Mosque (free entry)\n"
            + "- Dinner: **Maxwell Food Centre** hawker centre - SGD 3-8 plates!\n\n"
            + "**Day 3 - Nature & Sentosa**\n"
            + "- Morning: **Singapore Zoo** or **Night Safari** SGD 42\n"
            + "- Afternoon: **Sentosa Island** - Universal Studios SGD 83, or free beaches\n"
            + "- Evening: **iFly indoor skydiving** or cable car SGD 35\n\n"
            + "**Day 4 - Shopping & Architecture**\n"
            + "- Morning: **Orchard Road** - Paragon, ION Orchard, luxury brands\n"
            + "- Afternoon: **National Museum of Singapore** SGD 15\n"
            + "- Evening: **Lau Pa Sat** hawker festival market (free entry)\n\n"
            + "**Day 5 - Departure**\n"
            + "- Morning: **Singapore Botanic Gardens** UNESCO site (free!)\n"
            + "- Last shopping: Changi Airport (best airport in the world!) - duty free\n\n"
            + "---\n\n"
            + "### Budget Breakdown (Per Person, 5 Days, INR)\n\n"
            + "| Category | Budget | Mid-Range | Luxury |\n"
            + "|----------|--------|-----------|--------|\n"
            + "| Accommodation | Rs.12,500 | Rs.30,000 | Rs.80,000 |\n"
            + "| Food | Rs.5,000 | Rs.12,500 | Rs.30,000 |\n"
            + "| Transport | Rs.2,500 | Rs.5,000 | Rs.10,000 |\n"
            + "| Activities | Rs.7,500 | Rs.17,500 | Rs.37,500 |\n"
            + "| **TOTAL** | **Rs.27,500** | **Rs.65,000** | **Rs.1,57,500** |\n\n"
            + "### Travel Tips\n"
            + "- No visa required for Indian passport holders (30 days free!)\n"
            + "- EZ-Link card for MRT + bus (top up at any 7-Eleven)\n"
            + "- Singapore is extremely clean - no littering fines SGD 500!\n"
            + "- Eat at hawker centres - same food as restaurants at 1/4 the price\n"
            + "- Tropical climate year-round: 28-32C, afternoon showers common\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    private String genericItinerary(String dest, int days) {
        return "## " + days + "-Day Trip to " + dest + "\n\n"
            + "**Overview:** " + dest + " is a wonderful destination with rich experiences "
            + "waiting to be discovered. Here's your personalised " + days + "-day travel plan.\n\n"
            + "---\n\n"
            + "### Day-by-Day Itinerary\n\n"
            + "**Day 1 - Arrival & Orientation**\n"
            + "- Arrive and check into your accommodation\n"
            + "- Explore the city centre and get your bearings\n"
            + "- Try the local cuisine at a well-reviewed restaurant\n\n"
            + "**Days 2-" + (days - 1) + " - Exploration**\n"
            + "- Visit top landmarks and UNESCO heritage sites\n"
            + "- Photography at iconic spots\n"
            + "- Local market and souvenir shopping\n"
            + "- Day trips to nearby natural attractions\n\n"
            + "**Day " + days + " - Relaxation & Departure**\n"
            + "- Leisurely morning at a local cafe\n"
            + "- Last-minute souvenir shopping\n"
            + "- Depart\n\n"
            + "---\n\n"
            + "### Estimated Budget (Per Person)\n\n"
            + "| Category | Estimated (5 days) |\n"
            + "|----------|-----------------------|\n"
            + "| Accommodation | Rs.10,000 - Rs.40,000 |\n"
            + "| Food | Rs.5,000 - Rs.15,000 |\n"
            + "| Transport | Rs.2,500 - Rs.8,000 |\n"
            + "| Activities | Rs.3,000 - Rs.12,000 |\n\n"
            + "**Want a detailed plan?** Tell me your exact budget, number of people, "
            + "and travel style (adventure / culture / relaxation) for a fully customised itinerary!\n\n"
            + "*Powered by IBM Granite AI | TravelAI*";
    }

    // -- Generic fallback messages -------------------------------------------------------

    private String welcomeMessage() {
        return "**Welcome to TravelAI** - your intelligent travel companion powered by **IBM Granite AI**!\n\n"
            + "I can help you:\n"
            + "- Plan personalised trips with day-by-day itineraries\n"
            + "- Check live weather for any destination\n"
            + "- Budget your trip with a full cost breakdown\n"
            + "- Find hotels for every budget tier\n"
            + "- Plan flights and transport options\n"
            + "- Discover local cuisine and restaurants\n\n"
            + "---\n\n"
            + "**To get started, try:**\n"
            + "> *\"Plan a 5-day trip to Goa for 2 people with Rs.60,000 budget\"*\n"
            + "> *\"What is the weather like in Tokyo in December?\"*\n\n"
            + "Or use the **Quick Trip Planner** on the right panel! Where shall we go?";
    }

    private String destinationSuggestions() {
        return "**Top Travel Recommendations from TravelAI!**\n\n"
            + "### Beach & Relaxation\n"
            + "- **Goa, India** - Best domestic beach destination *(Rs.20,000-40,000/person)*\n"
            + "- **Bali, Indonesia** - Tropical paradise with culture *(Rs.40,000-80,000/person)*\n"
            + "- **Maldives** - Ultimate luxury overwater bungalows *(Rs.1,50,000+/person)*\n"
            + "- **Phuket, Thailand** - Vibrant beaches + nightlife *(Rs.35,000-70,000/person)*\n\n"
            + "### City Experiences\n"
            + "- **Singapore** - Clean, safe, world-class food *(Rs.60,000-1,20,000/person)*\n"
            + "- **Tokyo, Japan** - Futuristic meets ancient *(Rs.90,000-1,50,000/person)*\n"
            + "- **Dubai, UAE** - Luxury and desert adventures *(Rs.80,000-2,00,000/person)*\n"
            + "- **New York, USA** - The iconic city experience *(Rs.1,20,000-2,50,000/person)*\n\n"
            + "### Culture & Heritage\n"
            + "- **Paris, France** - Romance, art and cuisine *(Rs.80,000-1,80,000/person)*\n"
            + "- **Rome, Italy** - Ancient history and pasta *(Rs.70,000-1,40,000/person)*\n"
            + "- **Istanbul, Turkey** - East meets West *(Rs.50,000-1,00,000/person)*\n"
            + "- **Kyoto, Japan** - Traditional Japan at its finest *(Rs.90,000-1,50,000/person)*\n\n"
            + "---\n"
            + "**Tell me your budget and preferences and I'll create your perfect itinerary!**";
    }

    private String budgetTravelTips() {
        return "**Budget Travel Tips from TravelAI!**\n\n"
            + "### Under Rs.30,000 (5 days)\n"
            + "- **Goa, India** - Indian beach paradise, scooter rental, beach shacks\n"
            + "- **Hampi, India** - Ancient ruins, backpacker-friendly\n"
            + "- **Manali, India** - Mountains and adventure\n\n"
            + "### Rs.30,000 - Rs.80,000 (5 days)\n"
            + "- **Bali, Indonesia** - Stunning villas at great prices\n"
            + "- **Thailand** - Bangkok, Chiang Mai, or Phuket\n"
            + "- **Vietnam** - History, beaches and pho\n\n"
            + "### Top Budget Travel Hacks\n"
            + "- Book flights 60-90 days in advance for best rates\n"
            + "- Use hostels/guesthouses - save 60-70% on accommodation\n"
            + "- Eat at local markets not tourist restaurants\n"
            + "- Prefer trains over taxis for city-to-city travel\n"
            + "- Get a City Tourist Card for unlimited transport + discounts\n\n"
            + "Want me to plan a specific budget trip? Tell me your destination and budget!";
    }

    private String genericHelp() {
        return "I'm your **TravelAI** assistant powered by **IBM Granite AI**!\n\n"
            + "I didn't quite catch your travel request. Here are some things I can do:\n\n"
            + "**Try asking me:**\n"
            + "- *\"Plan a 7-day trip to Bali for 2 people with Rs.1,00,000 budget\"*\n"
            + "- *\"What's the weather in Paris in December?\"*\n"
            + "- *\"Suggest budget-friendly beach destinations\"*\n"
            + "- *\"How much does a trip to Dubai cost?\"*\n"
            + "- *\"Best time to visit Japan\"*\n\n"
            + "Or use the **Quick Trip Planner** panel - fill in destination, dates & budget and click Generate!\n\n"
            + "What would you like to plan?";
    }

    // -- Utility helpers -----------------------------------------------------------------

    private int parseDays(String msg) {
        for (String word : msg.split("\\s+")) {
            try {
                int n = Integer.parseInt(word);
                if (n >= 1 && n <= 30) return n;
            } catch (NumberFormatException ignored) {}
        }
        return 5;
    }

    private String extractDestination(String message) {
        String[] known = {
            "Paris", "France", "London", "UK", "England",
            "Tokyo", "Japan", "Kyoto", "Osaka",
            "Bali", "Indonesia",
            "New York", "USA", "America",
            "Dubai", "UAE",
            "Singapore",
            "Goa", "Mumbai", "Delhi", "Jaipur", "Agra", "Kerala", "India",
            "Bangkok", "Thailand", "Phuket", "Chiang Mai",
            "Sydney", "Australia", "Melbourne",
            "Amsterdam", "Netherlands",
            "Rome", "Italy", "Venice", "Milan",
            "Barcelona", "Spain", "Madrid",
            "Istanbul", "Turkey",
            "Cairo", "Egypt",
            "Maldives", "Sri Lanka",
            "Vietnam", "Hanoi", "Ho Chi Minh",
            "Prague", "Czech",
            "New Zealand", "Auckland",
            "South Africa", "Cape Town",
            "Mexico", "Cancun",
            "Brazil", "Rio",
            "Peru", "Machu Picchu"
        };
        String lower = message.toLowerCase();
        for (String dest : known) {
            if (lower.contains(dest.toLowerCase())) return dest;
        }
        return null;
    }

    private String capitalise(String s) {
        if (s == null || s.isBlank()) return s;
        String[] words = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private void trimHistory(List<Map<String, String>> history) {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }
}
