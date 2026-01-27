<div align="center">

# 🤖 Gemini AI Companion

### Talk to Minecraft. AI Understands.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-Mod-DFD4BC?style=for-the-badge)](https://fabricmc.net)
[![Gemini](https://img.shields.io/badge/Powered%20by-Gemini%20AI-8E75B2?style=for-the-badge&logo=google&logoColor=white)](https://ai.google.dev)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

**The most powerful AI mod for Minecraft. Ask questions, execute commands, and control your game using natural language.**

[Getting Started](#-getting-started) •
[Features](#-features) •
[Commands](#-commands) •
[Configuration](#%EF%B8%8F-configuration)

</div>

---

## ✨ Features

| Feature                         | Description                                                                         |
| ------------------------------- | ----------------------------------------------------------------------------------- |
| 🗣️ **Natural Language Commands** | Say "give me a diamond sword with sharpness 5" instead of memorizing command syntax |
| 🧠 **Three AI Modes**            | ASK (questions), PLAN (strategies), COMMAND (execute actions)                       |
| 🔄 **Self-Healing Commands**     | AI automatically fixes and retries failed commands up to 10 times                   |
| 🌐 **Web Search**                | AI can search the web for Minecraft info using Google Grounding                     |
| 📊 **Live Sidebar**              | Real-time stats: tokens used, response time, current mode                           |
| ↩️ **Undo Support**              | Made a mistake? `/chat undo` reverses the last AI actions                           |
| 💾 **Chat History**              | Export conversations to TXT or JSON                                                 |
| ⚙️ **In-Game Config GUI**        | Press `G` to open settings (keybind configurable)                                   |
| 🎨 **Visual Feedback**           | Rainbow thinking animation, particles, and sound effects                            |

---

## 🚀 Getting Started

### Prerequisites
- Minecraft 1.21.1
- [Fabric Loader](https://fabricmc.net/use/installer/) 0.16.0+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Gemini API Key](https://aistudio.google.com/apikey) (free)

### Installation

1. Download the latest release from [Releases](../../releases)
2. Place the `.jar` in your `mods` folder
3. Launch Minecraft with Fabric
4. Set your API key:
   ```
   /chatkey YOUR_API_KEY_HERE
   ```
5. Start chatting:
   ```
   /chat give me a diamond pickaxe with efficiency 5
   ```

---

## 💬 Commands

### Core Commands

| Command           | Description                           |
| ----------------- | ------------------------------------- |
| `/chat <message>` | Send a message to the AI              |
| `/chat clear`     | Clear conversation history            |
| `/chat cancel`    | Cancel the current AI request         |
| `/chat undo`      | Undo the last AI-executed commands    |
| `/chat smarter`   | Re-run last prompt with the Pro model |

### History & Export

| Command                 | Description                     |
| ----------------------- | ------------------------------- |
| `/chat history`         | Show recent conversations       |
| `/chat history all`     | Show full history               |
| `/chat export 10 txt`   | Export last 10 exchanges to TXT |
| `/chat export all json` | Export full history to JSON     |

### Context Commands

| Command                    | Description                               |
| -------------------------- | ----------------------------------------- |
| `/chat skill inventory`    | Include your inventory in AI context      |
| `/chat skill nearby`       | Include nearby entities in context        |
| `/chat skill stats`        | Include player stats (health, hunger, XP) |
| `/chat skill nbt mainhand` | Include NBT data of held item             |

---

## ⚙️ Configuration

### In-Game GUI
Press **`G`** to open the config screen (keybind configurable in Controls).

### Command-Based Config

```bash
/chat config                    # Show all settings
/chat config model flash        # Set AI model (flash/flash-thinking/pro/auto)
/chat config retries 5          # Set command retry limit (0-10)
/chat config sidebar on         # Toggle sidebar stats
/chat config sounds off         # Toggle sound effects
/chat config particles minimal  # Set particle level (on/minimal/off)
/chat config debug on           # Show executed commands
```

### API Key Management

| Command                  | Description                   |
| ------------------------ | ----------------------------- |
| `/chatkey <key>`         | Set your personal API key     |
| `/chatkey clear`         | Remove your API key           |
| `/chatkey default <key>` | Set server-wide default key   |
| `/chatkey info`          | Show key configuration status |

You can also set the `GEMINI_API_KEY` environment variable.

---

## 🎯 AI Models

| Model              | Best For                                  | Speed      |
| ------------------ | ----------------------------------------- | ---------- |
| **Flash**          | Quick commands, simple questions          | ⚡ Fastest  |
| **Flash Thinking** | Complex reasoning, multi-step tasks       | ⚡ Fast     |
| **Pro**            | Difficult problems, detailed explanations | 🐢 Slower   |
| **Auto**           | Automatically selects based on task       | ⚡ Adaptive |

---

## 📝 Examples

```bash
# Items & Equipment
/chat give me full netherite armor with protection 4
/chat give me a bow with infinity and power 5

# World Interaction
/chat teleport me to the nearest village
/chat what biome am I in?
/chat make it stop raining

# Combat & Entities
/chat kill all zombies within 50 blocks
/chat spawn 5 wolves and tame them

# Complex Commands
/chat create a 10x10 glass dome around me
/chat give all players speed 2 for 5 minutes

# Questions
/chat how do I find diamonds in 1.21?
/chat what does a smithing template do?
```

---

## 🔧 Technical Details

- **Minecraft Version:** 1.21.1
- **Mod Loader:** Fabric
- **API:** Google Gemini (gemini-2.0-flash, gemini-2.0-pro)
- **Commands:** Execute at OP level 4
- **Data Storage:** `run/ai-keys/` (API keys), `run/chat-logs/` (exports)

---

## 📄 License

MIT License - see [LICENSE](LICENSE) for details.

---

<div align="center">

**Made with ❤️ for the Minecraft community**

[⬆ Back to Top](#-gemini-ai-companion)

</div>
