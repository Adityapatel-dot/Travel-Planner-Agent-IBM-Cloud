/**
 * TravelAI — Frontend JavaScript
 * Handles: chat UI, API calls, tab switching, weather/itinerary/budget rendering
 */

// ── State ──────────────────────────────────────────────────────────────────
let sessionId  = generateId();
let isLoading  = false;
let selectedStyle = 'Adventure';

// ── Init ───────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', async () => {
  initStylePills();
  await checkStatus();
  await loadDestinations();
  addWelcomeMessage();
});

// ── Status Check ───────────────────────────────────────────────────────────
async function checkStatus() {
  try {
    const res  = await fetch('/api/status');
    const data = await res.json();
    const dot  = document.getElementById('statusDot');
    const txt  = document.getElementById('statusText');
    const pill = document.getElementById('statusPill');

    if (data.watsonxConfigured) {
      dot.className  = 'status-dot live';
      txt.textContent = 'IBM Granite Live';
      pill.title = 'Connected to IBM WatsonX.ai — ' + data.model;
    } else {
      dot.className  = 'status-dot demo';
      txt.textContent = 'Demo Mode';
      pill.title = 'Running in Demo Mode — add IBM WatsonX credentials for live AI';
    }
  } catch {
    document.getElementById('statusText').textContent = 'Offline';
  }
}

// ── Destinations ───────────────────────────────────────────────────────────
async function loadDestinations() {
  try {
    const res   = await fetch('/api/destinations');
    const dests = await res.json();
    renderDestinations(dests);
  } catch {
    // Silently fail — skeletons remain
  }
}

function renderDestinations(dests) {
  const grid = document.getElementById('destinationsGrid');
  grid.innerHTML = '';
  dests.forEach((d, i) => {
    const card = document.createElement('div');
    card.className = 'dest-card';
    card.style.animationDelay = (i * 0.08) + 's';
    card.style.opacity = '0';
    card.innerHTML = `
      <div class="dest-img-wrap">
        <img class="dest-img" src="${d.imageUrl}" alt="${d.city}" loading="lazy"
             onerror="this.src='https://source.unsplash.com/400x260/?travel,city'" />
        <span class="dest-emoji-overlay">${d.emoji}</span>
        <span class="dest-badge">${d.country}</span>
      </div>
      <div class="dest-info">
        <div class="dest-city">${d.city}</div>
        <div class="dest-country">${d.country}</div>
        <div class="dest-tagline">${d.tagline}</div>
        <div class="dest-budget">From ₹${d.minBudget.toLocaleString('en-IN')}</div>
      </div>`;
    card.onclick = () => sendSuggestion(`Plan a 5-day trip to ${d.city}, ${d.country}`);
    grid.appendChild(card);
  });
}

// ── Welcome Message ────────────────────────────────────────────────────────
function addWelcomeMessage() {
  const text = `👋 Welcome to **TravelAI** — your intelligent travel companion powered by **IBM Granite AI**!

I can help you:
- 🗺️ **Plan trips** with day-by-day itineraries
- 🌤️ **Check live weather** for any destination
- 💰 **Budget your trip** with a full cost breakdown
- 🏨 **Find hotels** for every budget tier
- ✈️ **Plan transport** options

**To get started, try:**
> *"Plan a 5-day trip to Goa for 2 people with ₹60,000 budget"*

Or use the **Quick Trip Planner** on the right panel! Where shall we go? 🌍`;

  appendMessage('ai', text);
}

// ── Chat Functions ─────────────────────────────────────────────────────────
async function sendMessage() {
  const input = document.getElementById('chatInput');
  const msg   = input.value.trim();
  if (!msg || isLoading) return;

  input.value = '';
  autoResize(input);
  hideSuggestions();

  appendMessage('user', msg);
  showTyping();
  setLoading(true);

  try {
    const res  = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: msg, sessionId })
    });
    const data = await res.json();

    removeTyping();
    appendMessage('ai', data.message, data.demoMode);

    // Render side-widgets from response
    if (data.destinations && data.destinations.length > 0) {
      renderDestinations(data.destinations);
    }
    if (data.weather) {
      renderWeatherWidget(data.weather, 'weatherWidget');
      // Also populate the weather tab input
      document.getElementById('weatherCityInput').value = data.weather.city || '';
    }
    if (data.type === 'itinerary') {
      renderItineraryFromText(data.message, msg);
      renderBudgetFromText(data.message);
    }

  } catch (err) {
    removeTyping();
    appendMessage('ai', '⚠️ Connection error. Please make sure the server is running on port 8080.');
  } finally {
    setLoading(false);
  }
}

function sendSuggestion(text) {
  document.getElementById('chatInput').value = text;
  sendMessage();
}

function handleKeyDown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendMessage();
  }
}

function autoResize(el) {
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 130) + 'px';
}

function clearChat() {
  sessionId = generateId();
  document.getElementById('chatMessages').innerHTML = '';
  addWelcomeMessage();
  showSuggestions();
  showToast('Conversation cleared ✓');
}

// ── Message Rendering ──────────────────────────────────────────────────────
function appendMessage(role, text, isDemo = false) {
  const container = document.getElementById('chatMessages');
  const div = document.createElement('div');
  div.className = `message ${role}`;

  const avatarEmoji = role === 'ai' ? '🤖' : '👤';
  const content     = role === 'ai' ? renderMarkdown(text) : escapeHtml(text);

  const ibmBadge = (role === 'ai')
    ? `<div class="msg-badge">🤖 IBM Granite ${isDemo ? '· Demo Mode' : '· Live'}</div>`
    : '';

  div.innerHTML = `
    <div class="msg-avatar">${avatarEmoji}</div>
    <div class="msg-bubble">${content}${ibmBadge}</div>`;

  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
}

function showTyping() {
  const container = document.getElementById('chatMessages');
  const div = document.createElement('div');
  div.className = 'message ai typing-indicator';
  div.id = 'typingIndicator';
  div.innerHTML = `
    <div class="msg-avatar">🤖</div>
    <div class="msg-bubble">
      <div class="typing-dot"></div>
      <div class="typing-dot"></div>
      <div class="typing-dot"></div>
    </div>`;
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
}

function removeTyping() {
  const el = document.getElementById('typingIndicator');
  if (el) el.remove();
}

// ── Markdown Renderer (simple) ─────────────────────────────────────────────
function renderMarkdown(text) {
  if (!text) return '';
  let html = escapeHtml(text);

  // Headers
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm,  '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm,   '<h1>$1</h1>');

  // Bold + Italic
  html = html.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
  html = html.replace(/\*\*(.+?)\*\*/g,     '<strong>$1</strong>');
  html = html.replace(/\*(.+?)\*/g,         '<em>$1</em>');

  // Inline code
  html = html.replace(/`(.+?)`/g, '<code>$1</code>');

  // Horizontal rules
  html = html.replace(/^---$/gm, '<hr style="border-color:var(--border);margin:10px 0">');

  // Tables  (simple: | header | header |)
  html = html.replace(/(\|.+\|\n)(\|[-| :]+\|\n)((\|.+\|\n)+)/g, (match) => {
    const rows = match.trim().split('\n').filter(r => r && !r.match(/^\|[-| :]+\|$/));
    if (rows.length < 2) return match;
    const headerCells = rows[0].split('|').slice(1,-1).map(c => `<th>${c.trim()}</th>`).join('');
    const bodyRows = rows.slice(1).map(r => {
      const cells = r.split('|').slice(1,-1).map(c => `<td>${c.trim()}</td>`).join('');
      return `<tr>${cells}</tr>`;
    }).join('');
    return `<table><thead><tr>${headerCells}</tr></thead><tbody>${bodyRows}</tbody></table>`;
  });

  // Blockquotes
  html = html.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>');

  // Unordered lists
  html = html.replace(/(^- .+$\n?)+/gm, block => {
    const items = block.trim().split('\n').map(l => `<li>${l.replace(/^- /, '').trim()}</li>`).join('');
    return `<ul>${items}</ul>`;
  });

  // Numbered lists
  html = html.replace(/(^\d+\. .+$\n?)+/gm, block => {
    const items = block.trim().split('\n').map(l => `<li>${l.replace(/^\d+\. /, '').trim()}</li>`).join('');
    return `<ol>${items}</ol>`;
  });

  // Paragraphs (double newline)
  html = html.replace(/\n\n+/g, '</p><p>');
  html = '<p>' + html + '</p>';

  // Single newlines → br (inside p tags only — skip inside block elements)
  html = html.replace(/([^>])\n([^<])/g, '$1<br>$2');

  return html;
}

function escapeHtml(text) {
  return String(text)
    .replace(/&/g,  '&amp;')
    .replace(/</g,  '&lt;')
    .replace(/>/g,  '&gt;')
    .replace(/"/g,  '&quot;');
}

// ── Tab Switching ──────────────────────────────────────────────────────────
function setActiveTab(name) {
  document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  document.getElementById('tab-content-' + name).classList.add('active');
}

// ── Quick Plan ─────────────────────────────────────────────────────────────
function quickPlan() {
  const dest   = document.getElementById('qpDestination').value.trim();
  const days   = document.getElementById('qpDays').value;
  const people = document.getElementById('qpPeople').value;
  const budget = document.getElementById('qpBudget').value.trim();

  if (!dest) {
    showToast('⚠️ Please enter a destination!');
    document.getElementById('qpDestination').focus();
    return;
  }

  let msg = `Plan a ${days} trip to ${dest} for ${people}`;
  if (budget) msg += ` with ₹${budget} budget`;
  if (selectedStyle !== 'Adventure') msg += `, I prefer ${selectedStyle.toLowerCase()} travel`;

  sendSuggestion(msg);
}

// ── Style Pills ────────────────────────────────────────────────────────────
function initStylePills() {
  document.querySelectorAll('.style-pill').forEach(pill => {
    pill.onclick = () => {
      document.querySelectorAll('.style-pill').forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      selectedStyle = pill.dataset.style;
    };
  });
}

// ── Weather Tab ────────────────────────────────────────────────────────────
async function fetchWeather() {
  const city = document.getElementById('weatherCityInput').value.trim();
  if (!city) { showToast('⚠️ Enter a city name!'); return; }

  const widget = document.getElementById('weatherWidget');
  widget.innerHTML = '<div class="placeholder-state"><div class="placeholder-icon">⏳</div><p>Fetching weather…</p></div>';

  try {
    const res  = await fetch('/api/weather/' + encodeURIComponent(city));
    const data = await res.json();
    if (data.error) throw new Error(data.error);
    renderWeatherWidget(data, 'weatherWidget');
  } catch (err) {
    widget.innerHTML = `<div class="placeholder-state"><div class="placeholder-icon">❌</div><p>Could not fetch weather. Try another city.</p></div>`;
  }
}

function renderWeatherWidget(data, containerId) {
  const el = document.getElementById(containerId);
  if (!el) return;

  const demoNote = data.demo
    ? `<div style="text-align:center;margin-top:10px;font-size:0.72rem;color:var(--text-3)">📡 Demo weather data — add OpenWeatherMap key for live data</div>`
    : '';

  const forecastCards = (data.forecast || []).map(f => `
    <div class="forecast-card">
      <div class="fc-day">${f.dayName}</div>
      <div class="fc-icon">${f.icon}</div>
      <div class="fc-temps">
        <span class="fc-max">${Math.round(f.maxTemp)}°</span>
        <span style="color:var(--text-3)"> / ${Math.round(f.minTemp)}°</span>
      </div>
      <div class="fc-hum">💧 ${f.humidity}%</div>
    </div>`).join('');

  el.innerHTML = `
    <div class="weather-current">
      <div class="weather-icon-big">${data.icon || '🌤️'}</div>
      <div>
        <div class="weather-temp-main">${Math.round(data.temperature)}°C</div>
        <div class="weather-city-name">${data.city}${data.country && data.country !== '—' ? ', ' + data.country : ''}</div>
        <div class="weather-desc">${data.description}</div>
        <div class="weather-meta">Feels like ${Math.round(data.feelsLike)}°C · Humidity ${data.humidity}%</div>
      </div>
    </div>
    <div class="forecast-grid">${forecastCards}</div>
    ${demoNote}`;

  // Also switch to weather tab if we're not already there
  if (!document.getElementById('tab-content-weather').classList.contains('active')) {
    // Don't auto-switch — let the AI response show it inline
  }
}

// ── Itinerary Rendering ────────────────────────────────────────────────────
function renderItineraryFromText(aiText, userMsg) {
  const panel = document.getElementById('itineraryPanel');

  // Extract destination name from user message or AI text
  let dest  = extractDestinationName(userMsg) || extractDestinationName(aiText) || 'Your Trip';
  let days  = extractDays(userMsg) || extractDays(aiText) || '5';
  let daysNum = parseInt(days, 10) || 5;

  // Parse day sections from AI response
  const dayBlocks = parseDayBlocks(aiText, daysNum);

  const headerHtml = `
    <div class="itinerary-header-card">
      <div class="itin-dest">✈️ ${dest}</div>
      <div class="itin-meta">${daysNum}-Day Personalised Itinerary by IBM Granite AI</div>
      <div class="itin-badges">
        <span class="itin-badge">🤖 IBM Granite</span>
        <span class="itin-badge">📅 ${daysNum} Days</span>
        <span class="itin-badge">🗺️ ${dest}</span>
      </div>
    </div>`;

  const timelineItems = dayBlocks.map((block, i) => `
    <div class="timeline-day" style="animation-delay:${i*0.1}s;opacity:0">
      <div class="tl-day-header">
        <div class="tl-day-num">${i + 1}</div>
        <div class="tl-day-title">${block.title}</div>
      </div>
      <div class="tl-day-body">
        ${block.activities.map(a => `
          <div class="tl-activity">
            <div class="tl-act-icon">${a.icon}</div>
            <div>${a.text}</div>
          </div>`).join('')}
      </div>
    </div>`).join('');

  panel.innerHTML = `
    ${headerHtml}
    <div class="timeline">${timelineItems || '<div class="placeholder-state"><p>Itinerary is shown in the chat. Ask for a more specific plan to see the day-by-day timeline here!</p></div>'}</div>`;

  // Animate cards in
  setTimeout(() => {
    panel.querySelectorAll('.timeline-day').forEach((el, i) => {
      setTimeout(() => { el.style.opacity = '1'; }, i * 100);
    });
  }, 50);

  // Auto-switch to Itinerary tab
  setActiveTab('itinerary');
  showToast('📅 Itinerary saved to Itinerary tab!');
}

function parseDayBlocks(text, totalDays) {
  const blocks = [];
  const dayPatterns = [
    /\*\*Day (\d+)[— –-]([^*\n]+)\*\*/g,
    /###.*Day (\d+)[— –-]([^\n]+)/g,
    /^Day (\d+)[— –-]([^\n]+)/gm
  ];

  let matches = [];
  for (const pat of dayPatterns) {
    let m;
    while ((m = pat.exec(text)) !== null) matches.push({ dayNum: parseInt(m[1]), title: m[2].trim(), index: m.index });
    if (matches.length > 0) break;
  }

  if (matches.length === 0) {
    // Fallback: create generic blocks
    const genericActivities = [
      [{ icon: '🌅', text: 'Arrive and settle in your accommodation' },
       { icon: '🗺️', text: 'Explore the city centre' },
       { icon: '🍽️', text: 'Try local cuisine at a recommended restaurant' }],
    ];
    for (let i = 1; i <= Math.min(totalDays, 5); i++) {
      blocks.push({ title: `Day ${i} — Exploration`, activities: genericActivities[0] });
    }
    return blocks;
  }

  for (let i = 0; i < matches.length; i++) {
    const start = matches[i].index;
    const end   = matches[i + 1] ? matches[i + 1].index : text.length;
    const block = text.substring(start, end);
    const activities = extractActivitiesFromBlock(block);
    blocks.push({ title: `Day ${matches[i].dayNum} — ${matches[i].title}`, activities });
  }
  return blocks;
}

function extractActivitiesFromBlock(block) {
  const activities = [];
  const lines = block.split('\n');
  const activityIcons = { 'morning': '🌅', 'afternoon': '☀️', 'evening': '🌆', 'night': '🌙', 'dinner': '🍽️', 'lunch': '🥗', 'breakfast': '☕', 'visit': '🏛️', 'tour': '🎟️', 'hotel': '🏨', 'depart': '✈️' };

  for (const line of lines) {
    const clean = line.replace(/[-*•]/, '').replace(/\*\*/g, '').trim();
    if (clean.length < 5) continue;
    if (clean.startsWith('Day ') || clean.startsWith('**Day')) continue;

    let icon = '📌';
    for (const [kw, em] of Object.entries(activityIcons)) {
      if (clean.toLowerCase().includes(kw)) { icon = em; break; }
    }
    // Emoji at start of line
    const emojiMatch = clean.match(/^([🌅☀️🌆🌙🏛️🎟️🍽️🥗☕🏨✈️🌊🏖️🛍️🎨⛪🌸💆🌿🏄🎭🗺️])/u);
    if (emojiMatch) icon = emojiMatch[1];

    activities.push({ icon, text: clean.replace(/^[🌅☀️🌆🌙🏛️🎟️🍽️🥗☕🏨✈️🌊🏖️🛍️🎨⛪🌸💆🌿🏄🎭🗺️]/u, '').trim() });
    if (activities.length >= 5) break;
  }
  return activities;
}

// ── Budget Rendering ───────────────────────────────────────────────────────
function renderBudgetFromText(aiText) {
  const panel = document.getElementById('budgetPanel');

  // Try to extract budget table from AI text
  const categories = extractBudgetCategories(aiText);
  if (!categories.length) return;

  const total = categories.reduce((s, c) => s + c.amount, 0);
  const colors = ['#6366f1', '#8b5cf6', '#06b6d4', '#10b981', '#f59e0b'];

  const bars = categories.map((cat, i) => {
    const pct = total > 0 ? Math.round((cat.amount / total) * 100) : 0;
    return `
      <div class="budget-row">
        <div class="budget-row-header">
          <span class="budget-cat">${cat.icon} ${cat.name}</span>
          <span class="budget-amt">₹${cat.amount.toLocaleString('en-IN')}</span>
        </div>
        <div class="budget-bar-track">
          <div class="budget-bar-fill" style="width:${pct}%;background:${colors[i % colors.length]};animation-duration:${0.6 + i*0.1}s"></div>
        </div>
      </div>`;
  }).join('');

  panel.innerHTML = `
    <div class="budget-header-card glass-card">
      <div>
        <div class="budget-total-amount">₹${total.toLocaleString('en-IN')}</div>
        <div class="budget-total-label">Estimated Total (per person)</div>
      </div>
      <div style="font-size:2.5rem;margin-left:auto">💰</div>
    </div>
    <div class="glass-card" style="padding:24px">
      <h3 style="font-family:var(--font-heading);font-size:1rem;color:var(--text-1);margin-bottom:18px">💼 Budget Breakdown</h3>
      <div class="budget-bars">${bars}</div>
    </div>`;
}

function extractBudgetCategories(text) {
  // Try to find budget numbers from the AI response
  const patterns = [
    { name: 'Accommodation', icon: '🏨', regex: /accommodation[^₹\d]*[₹]?\s*([\d,]+)/i },
    { name: 'Food',          icon: '🍽️', regex: /food[^₹\d]*[₹]?\s*([\d,]+)/i           },
    { name: 'Transport',     icon: '🚕', regex: /transport[^₹\d]*[₹]?\s*([\d,]+)/i      },
    { name: 'Activities',    icon: '🎯', regex: /activities?[^₹\d]*[₹]?\s*([\d,]+)/i    },
  ];

  const cats = [];
  for (const p of patterns) {
    const m = text.match(p.regex);
    if (m) {
      const amt = parseInt(m[1].replace(/,/g, ''), 10);
      if (!isNaN(amt) && amt > 0) cats.push({ name: p.name, icon: p.icon, amount: amt });
    }
  }

  // Fallback: generic budget if nothing parsed
  if (cats.length === 0) {
    return [
      { name: 'Accommodation', icon: '🏨', amount: 12000 },
      { name: 'Food',          icon: '🍽️', amount: 6000  },
      { name: 'Transport',     icon: '🚕', amount: 4000  },
      { name: 'Activities',    icon: '🎯', amount: 8000  },
    ];
  }
  return cats;
}

// ── Utilities ──────────────────────────────────────────────────────────────
function extractDestinationName(text) {
  if (!text) return null;
  const destinations = [
    'Paris','France','London','UK','Tokyo','Japan','Bali','Indonesia',
    'New York','USA','Dubai','UAE','Singapore','Goa','Mumbai','Delhi','India',
    'Bangkok','Thailand','Rome','Italy','Barcelona','Spain','Istanbul','Turkey',
    'Maldives','Sri Lanka','Sydney','Australia','Kyoto','Osaka','Vietnam',
    'Amsterdam','Prague','Cairo','Egypt','Phuket','Chiang Mai','Ho Chi Minh'
  ];
  const lower = text.toLowerCase();
  for (const d of destinations) {
    if (lower.includes(d.toLowerCase())) return d;
  }
  // Try to match "trip to X" pattern
  const m = text.match(/trip to ([A-Z][a-zA-Z ]+?)(?:\s+for|\s+with|\s*$)/);
  return m ? m[1].trim() : null;
}

function extractDays(text) {
  if (!text) return '5';
  const m = text.match(/(\d+)[- ]?day/i);
  return m ? m[1] : '5';
}

function generateId() {
  return 'sess-' + Math.random().toString(36).substr(2, 9);
}

function setLoading(val) {
  isLoading = val;
  const btn = document.getElementById('sendBtn');
  if (btn) btn.disabled = val;
}

function hideSuggestions() {
  const chips = document.getElementById('suggestionChips');
  if (chips) chips.style.display = 'none';
}

function showSuggestions() {
  const chips = document.getElementById('suggestionChips');
  if (chips) chips.style.display = '';
}

function showToast(msg, duration = 3000) {
  const toast = document.getElementById('toast');
  toast.textContent = msg;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), duration);
}
