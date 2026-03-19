# v1.3.2 - Gemini Planner Reliability Update

This release tightens Gemini's structured build flow and expands the build planner's v2 contract.

Highlights:

- Fixed a Gemini retry bug where empty `build_plan` objects could hijack normal requests like "give me a sword" and trigger repeated build retries.
- Stopped stale or cancelled async build workers from continuing to retry after `/cancel`, context clear, or a newer request.
- Fixed palette alias resolution in build plans so `$floor`-style references resolve correctly.
- Fixed `coordMode: "absolute"` so explicit `origin` is used correctly instead of falling back to the player position.
- Expanded build-plan v2 support across the planner, MCP docs, and Gemini schema.

Release assets:

- `gemini-ai-companion-1.3.2.jar`
- `gemini-minecraft-mcp-sidecar.jar`
