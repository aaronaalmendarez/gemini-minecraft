<p align="center">
  <img src="banner.png" width="800" />
</p>

<h1 align="center">🌌 Gemini AI Companion</h1>
<p align="center"><i>A next-generation AI orchestration layer for Minecraft, powered by Google Gemini 3.</i></p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge&logo=minecraft&logoColor=white">
  <img src="https://img.shields.io/badge/Fabric-Mod-DFD4BC?style=for-the-badge">
  <img src="https://img.shields.io/badge/Powered%20by-Gemini%203-8E75B2?style=for-the-badge&logo=google&logoColor=white">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge">
</p>

<p align="center">
  <a href="#-getting-started">Getting Started</a> •
  <a href="#-features">Features</a> •
  <a href="#-commands">Commands</a> •
  <a href="#-technical-report-architecture--implementation">How it Works</a>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/rainbow.png" />
</p>

<h2 align="center">🎬 See It In Action</h2>

<p align="center">
  <img src="merged_output_5mb.gif" width="750">
  <br>
  <i>▲ Real-time command execution and natural language reasoning</i>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/rainbow.png" />
</p>

<h2 align="center">✨ Features</h2>

<table align="center">
<tr>
<td align="center" width="33%">🗣️<br><b>Natural Language</b><br>Human-like interaction</td>
<td align="center" width="33%">🧠<br><b>Multi-Mode AI</b><br>Ask, Plan, or Command</td>
<td align="center" width="33%">🔄<br><b>Self-Healing</b><br>10-step auto-retry</td>
</tr>
<tr>
<td align="center" width="33%">🌐<br><b>Web Search</b><br>Google Search Grounding</td>
<td align="center" width="33%">↩️<br><b>Undo Engine</b><br>Revert AI mutations</td>
<td align="center" width="33%">💾<br><b>History</b><br>JSON/TXT Export support</td>
</tr>
</table>

<p align="center">
  <img src="https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/rainbow.png" />
</p>

<h2 align="center">🚀 Getting Started</h2>

### 🛠️ Prerequisites
*   **Minecraft:** 1.21.1
*   **Loader:** Fabric 0.16.0+
*   **API:** [Gemini API Key](https://aistudio.google.com/apikey) (Free Tier supported)

### 📥 Installation
1.  Drop the jar into your `mods` folder.
2.  Set your key via `/chatkey <YOUR_KEY>`.
3.  Type `/chat` to begin the future.

<p align="center">
  <img src="https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/rainbow.png" />
</p>

<h2 align="center">💬 Core Commands</h2>

| Command             | Why use it?                                             |
| :------------------ | :------------------------------------------------------ |
| `/chat <msg>`       | The main gateway to the AI.                             |
| `/chat undo`        | Safety net for when AI goes "too far".                  |
| `/chat smarter`     | Escalates logic to the **Pro** model for complex tasks. |
| `/chat skill <...>` | Injects world context (inventory, entities, stats).     |

<h2 align="center">🛣️ Project Roadmap</h2>

- [x] **Core Integration:** Async Gemini 3 API Connection
- [x] **Self-Healing:** Recursive error correction loop
- [x] **Context Awareness:** Inventory & Entity skill injection
- [ ] **Vision Support:** Image analysis for world snapshots (Planned)
- [ ] **Voice Control:** Dictation-based command execution (Backlog)

<p align="center">
  <img src="https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/rainbow.png" />
</p>

<h2 align="center">📚 Technical Report: Architecture & Implementation</h2>

This section documents the engineering techniques that enable seamless AI-to-game collaboration.

### System Architecture

```mermaid
graph TB
    subgraph Minecraft Server
        A[Player Input<br>/chat command] --> B[Command Handler]
        B --> C{Parse Intent}
        C -->|Subcommand| D[Config/History/Export]
        C -->|AI Request| E[AiChatHandler]
    end
    
    subgraph Async Processing
        E --> F[HTTP Thread Pool]
        F --> G[Gemini API]
        G --> H[Response Parser]
    end
    
    subgraph Execution Engine
        H --> I{Mode Detection}
        I -->|ASK| J[Display Answer]
        I -->|PLAN| K[Display Strategy]
        I -->|COMMAND| L[Command Executor]
        L --> M{Validation}
        M -->|Success| N[Apply to World]
        M -->|Failure| O[Retry Loop]
        O --> G
    end
    
    subgraph State Management
        P[(PlayerState Map)]
        Q[(Chat History)]
        R[(Undo Stack)]
    end
    
    E -.-> P
    E -.-> Q
    L -.-> R
```

### Request Lifecycle

```mermaid
sequenceDiagram
    participant P as Player
    participant M as Mod
    participant T as Thread Pool
    participant G as Gemini API
    participant W as Minecraft World

    P->>M: /chat give me diamond sword
    M->>M: Build context (inventory, stats, history)
    M->>P: 🌈 "Thinking..." animation
    M->>T: Async HTTP request
    T->>G: POST /generateContent
    G-->>T: JSON response
    T->>M: Parse response
    
    alt Mode = COMMAND
        M->>W: Execute /give command
        W-->>M: Success/Failure
        alt Failure
            M->>G: Retry with error context
            G-->>M: Fixed command
            M->>W: Re-execute
        end
        M->>M: Push to undo stack
    end
    
    M->>P: Display result + update sidebar
```

### Self-Healing Command Retry

The mod implements an intelligent retry mechanism that feeds command errors back to Gemini:

```mermaid
flowchart LR
    A[Generate Command] --> B[Execute]
    B --> C{Result}
    C -->|Success| D[Done ✓]
    C -->|Failure| E{Retries < 10?}
    E -->|Yes| F[Append Error to Context]
    F --> G[Request New Command]
    G --> B
    E -->|No| H[Report Failure]
    
    style D fill:#4ade80
    style H fill:#f87171
```

**Example Retry Flow:**
1. AI generates: `/give @p diamond_sword{Enchantments:[{id:sharpness,lvl:5}]}`
2. Server rejects: "Unknown argument: Enchantments" (1.21.1 uses components)
3. Error fed back to Gemini with context
4. AI corrects: `/give @p diamond_sword[enchantments={levels:{sharpness:5}}]`
5. Command succeeds

### Context Window Management

```mermaid
pie title Token Budget Allocation
    "System Prompt" : 2000
    "Player Context" : 500
    "Chat History" : 3500
    "Current Request" : 1000
    "Reserved for Response" : 1000
```

| Component           | Strategy                                             |
| ------------------- | ---------------------------------------------------- |
| **System Prompt**   | Static instructions for Minecraft command generation |
| **Player Context**  | Dynamically injected: inventory, position, stats     |
| **Chat History**    | Rolling window of last 10 exchanges, FIFO eviction   |
| **Response Buffer** | Reserved tokens to prevent truncation                |

### Thread Safety Model

```mermaid
graph LR
    subgraph Main Thread
        A[Tick Events]
        B[Command Registration]
        C[World Mutations]
    end
    
    subgraph Worker Threads
        D[HTTP Requests]
        E[JSON Parsing]
        F[Response Processing]
    end
    
    subgraph Thread-Safe Storage
        G[(ConcurrentHashMap<br>PlayerState)]
        H[(ConcurrentHashMap<br>ChatHistory)]
    end
    
    D --> G
    F --> H
    C -.->|server.execute| A
```

### Undo System Architecture

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Executing: /chat command
    Executing --> Recording: Command succeeds
    Recording --> Idle: Push to undo stack
    Idle --> Reverting: /chat undo
    Reverting --> Idle: Execute inverse commands
    
    note right of Recording
        Captures:
        - Entity spawns → /kill
        - Item gives → /clear
        - Effects → /effect clear
    end note
```

---

<div align="center">

**Made with ❤️ for the Minecraft community**

[⬆ Back to Top](#-gemini-ai-companion)

</div>
