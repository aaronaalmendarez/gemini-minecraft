# Gemini AI Companion for Minecraft (Fabric 1.21.1)

AI assistant mod for Minecraft with chat, command execution, history, and settings.

## Quick start

1) /chatkey YOUR_API_KEY
2) /chat give me a diamond sword

## Core commands

/chat <text>                Ask the AI
/chat clear | /chat new      Clear history
/chat cancel | /chat stop    Cancel request
/chat undo                  Undo last AI commands
/chat history [count|all] [page]
/chat export [count|all] [txt|json]

## Settings

/chat config                Show settings menu
/chat config debug on|off
/chat config sidebar on|off
/chat config sounds on|off
/chat config particles on|off|minimal
/chat config retries <0-10>
/chat config model <flash|flash-thinking|pro|auto>

Legacy toggles: /chatdebug, /chatsidebar, /chatsounds, /chatparticles, /chatretries, /chatmodel

## API keys

/chatkey <key>
/chatkey clear
/chatkey default <key>
/chatkey default clear
/chatkey info

## Skill commands

/chat skill inventory
/chat skill nearby
/chat skill stats
/chat skill nbt [mainhand|offhand|slot <n>]

## Notes

- Commands run at op level 4 (singleplayer or server with perms)
- Keys stored in plaintext under run/ai-keys
- Requires Fabric API

