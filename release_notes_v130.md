## v1.3.0 - MCP Bridge Update

This release turns the Minecraft MCP path into a real first-class integration instead of a side experiment.

### Highlights

- Local MCP bridge inside the mod with loopback-only auth
- Copy-paste MCP setup from in-game chat for Codex, Claude Desktop, Claude Code, Gemini CLI, OpenCode, and generic clients
- Supported Node MCP sidecar with richer prompts, resources, help tools, and planner guidance
- `minecraft_preview_build_plan` for dry-run validation before touching the world
- `minecraft_capture_view` PNG output with saved screenshot paths
- Delayed MCP command sequencing with `delayTicks` / `delayMs`
- `minecraft_batch_status` for polling cinematic/timed command batches
- Better command normalization:
  - `\uXXXX` Unicode escape decoding
  - `effect give ... 0` coerced to a minimum duration of `1`
- Stronger build-plan docs around terrain alignment, safe-window clamping, support pillars, phases, and preview-first workflow

### Release Assets

- `gemini-ai-companion-1.3.0.jar`
- `gemini-minecraft-mcp-sidecar.jar`

### Recommended Setup

1. Install the mod jar in Fabric 1.21.1
2. Join a world
3. Run `/chat mcp enable`
4. Run `/chat mcp setup <client>`
5. Click `[Copy]`
6. Paste into your MCP client config
7. Restart the MCP client
