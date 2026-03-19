# v1.3.1 - Voice Fix Update

This release fixes the push-to-talk voice transcription path.

## Highlights

- Fixed voice transcription requests that were returning `HTTP 500`
- Switched voice STT to a plain audio transcription request instead of the structured chat schema path
- Improved transcription error reporting so failures now include Gemini's real error message
- Kept the MCP bridge, build preview, delayed command batching, and voxel planner updates from `v1.3.0`

## Included Assets

- `gemini-ai-companion-1.3.1.jar`
- `gemini-minecraft-mcp-sidecar.jar`
