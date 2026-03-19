#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");

const DEFAULT_BRIDGE_URL = "http://127.0.0.1:7766";
const PROTOCOL_VERSION = "2025-03-26";
const DEBUG_LOG_PATH = path.join(os.homedir(), ".codex", "gemini-minecraft-mcp.log");

function debugLog(line) {
  try {
    fs.mkdirSync(path.dirname(DEBUG_LOG_PATH), { recursive: true });
    fs.appendFileSync(DEBUG_LOG_PATH, `${new Date().toISOString()} ${line}\n`, "utf8");
  } catch {
    // ignore
  }
}

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2);
    let value = "true";
    if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
      value = argv[i + 1];
      i += 1;
    }
    parsed[key] = value;
  }
  return parsed;
}

function firstNonBlank(...values) {
  for (const value of values) {
    if (value != null && String(value).trim()) {
      return String(value).trim();
    }
  }
  return "";
}

function safeJoin(base, ...segments) {
  try {
    return path.join(base, ...segments);
  } catch {
    return null;
  }
}

function discoverGlobalSettingsPaths(projectRoot) {
  const paths = [];
  const addRoot = (root) => {
    if (!root) return;
    const one = safeJoin(root, "run", "ai-settings", "global.json");
    const two = safeJoin(root, "run", "run", "ai-settings", "global.json");
    if (one) paths.push(one);
    if (two) paths.push(two);
  };

  addRoot(projectRoot);
  addRoot(process.cwd());

  let current = path.resolve(__dirname);
  for (let i = 0; i < 6; i += 1) {
    addRoot(current);
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }

  return [...new Set(paths)];
}

function readTokenFromGlobalSettings(filePath) {
  try {
    const raw = fs.readFileSync(filePath, "utf8");
    const data = JSON.parse(raw);
    return typeof data.mcpToken === "string" ? data.mcpToken.trim() : "";
  } catch {
    return "";
  }
}

function resolveBridgeToken(explicitToken, tokenFile, projectRoot) {
  if (explicitToken) return explicitToken.trim();
  if (tokenFile) {
    const token = readTokenFromGlobalSettings(tokenFile);
    if (token) return token;
  }
  for (const filePath of discoverGlobalSettingsPaths(projectRoot)) {
    const token = readTokenFromGlobalSettings(filePath);
    if (token) return token;
  }
  return "";
}

function trimTrailingSlash(url) {
  return url.replace(/\/+$/, "");
}

function objectSchema() {
  return { type: "object", additionalProperties: true, properties: {} };
}

function primitive(type, description) {
  return { type, description };
}

function schema(...entries) {
  const obj = objectSchema();
  const required = [];
  for (const [key, type, requiredFlag, description] of entries) {
    obj.properties[key] = primitive(type, description);
    if (requiredFlag) required.push(key);
  }
  if (required.length) obj.required = required;
  return obj;
}

function commandsSchema() {
  const obj = objectSchema();
  obj.properties.commands = {
    type: "array",
    items: {
      anyOf: [
        { type: "string" },
        {
          type: "object",
          additionalProperties: false,
          properties: {
            command: primitive("string", "Minecraft command string."),
            delayTicks: primitive("integer", "Optional delay in ticks before this command executes, relative to the previous command."),
            delayMs: primitive("number", "Optional delay in milliseconds before this command executes, relative to the previous command."),
          },
          required: ["command"],
        },
      ],
    },
  };
  obj.required = ["commands"];
  return obj;
}

function highlightSchema() {
  const obj = objectSchema();
  const item = objectSchema();
  item.properties = {
    x: primitive("number", "World x coordinate."),
    y: primitive("number", "World y coordinate."),
    z: primitive("number", "World z coordinate."),
    label: primitive("string", "Optional label."),
    color: primitive("string", "Named color or hex code."),
    durationMs: primitive("integer", "Highlight duration in milliseconds."),
  };
  item.required = ["x", "y", "z"];
  obj.properties.highlights = { type: "array", items: item };
  obj.required = ["highlights"];
  return obj;
}

const TOOLS = {
  minecraft_help: {
    description: "Get a detailed guide for using this Minecraft MCP server, including workflow, build-plans, and when to use each major tool.",
    inputSchema: schema(
      ["topic", "string", false, "Optional topic such as workflow, build-plan, commands, vision, or tools."],
      ["task", "string", false, "Optional current task so the guide can be framed around it."]
    ),
    localHandler: (argumentsObject = {}) => {
      const topic = typeof argumentsObject.topic === "string" ? argumentsObject.topic.trim().toLowerCase() : "";
      const task = typeof argumentsObject.task === "string" ? argumentsObject.task.trim() : "";
      let guide = buildAgentWorkflowGuide();
      if (topic === "build" || topic === "build-plan" || topic === "buildplan") {
        guide = buildBuildPlanGuide();
      } else if (topic === "buildsite" || topic === "terrain" || topic === "site") {
        guide = buildBuildsiteGuide();
      } else if (topic === "commands" || topic === "raw-commands") {
        guide = buildCommandsGuide();
      } else if (topic === "vision" || topic === "capture") {
        guide = buildVisionGuide();
      } else if (topic === "tools" || topic === "catalog") {
        guide = "# Tool Catalog\n\n" + buildToolCatalogText();
      }
      return {
        topic: topic || "workflow",
        task,
        guide,
      };
    },
  },
  minecraft_describe_tool: {
    description: "Explain exactly how to use one Minecraft MCP tool, including argument expectations, when to use it, and examples.",
    inputSchema: schema(["name", "string", true, "Tool name to explain."]),
    localHandler: (argumentsObject = {}) => {
      const name = typeof argumentsObject.name === "string" ? argumentsObject.name.trim() : "";
      return describeTool(name);
    },
  },
  minecraft_session: {
    description: "Get the current Minecraft bridge and active-player session.",
    method: "GET",
    path: "/v1/session",
    inputSchema: objectSchema(),
  },
  minecraft_inventory: {
    description: "Read the active player's inventory summary.",
    method: "POST",
    path: "/v1/tools/inventory",
    inputSchema: objectSchema(),
  },
  minecraft_nearby_entities: {
    description: "List nearby entities around the active player.",
    method: "POST",
    path: "/v1/tools/nearby",
    inputSchema: objectSchema(),
  },
  minecraft_scan_blocks: {
    description: "Scan for nearby blocks matching a block id or tag.",
    method: "POST",
    path: "/v1/tools/blocks",
    inputSchema: schema(
      ["target", "string", true, "Block id or #tag."],
      ["radius", "integer", false, "Optional scan radius."]
    ),
  },
  minecraft_scan_containers: {
    description: "Scan nearby containers and summarize their contents.",
    method: "POST",
    path: "/v1/tools/containers",
    inputSchema: schema(
      ["filter", "string", false, "Optional block filter."],
      ["radius", "integer", false, "Optional scan radius."]
    ),
  },
  minecraft_blockdata: {
    description: "Inspect a block entity by coordinates or nearest matching container.",
    method: "POST",
    path: "/v1/tools/blockdata",
    inputSchema: schema(
      ["target", "string", false, "Optional block filter."],
      ["radius", "integer", false, "Optional search radius."],
      ["x", "integer", false, "Absolute block x."],
      ["y", "integer", false, "Absolute block y."],
      ["z", "integer", false, "Absolute block z."]
    ),
  },
  minecraft_players: {
    description: "List online players and their positions.",
    method: "POST",
    path: "/v1/tools/players",
    inputSchema: objectSchema(),
  },
  minecraft_stats: {
    description: "Read the active player's health, food, armor, XP, and effects.",
    method: "POST",
    path: "/v1/tools/stats",
    inputSchema: objectSchema(),
  },
  minecraft_buildsite: {
    description: "Summarize terrain around the active player for build planning, including relative ground range, headroom clear percent, water columns, and top surface blocks.",
    method: "POST",
    path: "/v1/tools/buildsite",
    inputSchema: schema(["radius", "integer", false, "Optional scan radius."]),
  },
  minecraft_recipe_lookup: {
    description: "Look up crafting recipes for an item.",
    method: "POST",
    path: "/v1/tools/recipe",
    inputSchema: schema(["item", "string", true, "Item id to resolve."]),
  },
  minecraft_smelt_lookup: {
    description: "Look up smelting and other cooking recipes for an item.",
    method: "POST",
    path: "/v1/tools/smelt",
    inputSchema: schema(["item", "string", true, "Item id to resolve."]),
  },
  minecraft_item_lookup: {
    description: "Inspect the tooltip of an inventory slot, mainhand, or offhand item.",
    method: "POST",
    path: "/v1/tools/lookup",
    inputSchema: schema(["target", "string", false, "mainhand, offhand, or slot N"]),
  },
  minecraft_item_components: {
    description: "Inspect item components for an inventory slot, mainhand, or offhand item.",
    method: "POST",
    path: "/v1/tools/nbt",
    inputSchema: schema(["target", "string", false, "mainhand, offhand, or slot N"]),
  },
  minecraft_batch_status: {
    description: "Get the status of the latest or a specific delayed MCP command batch.",
    method: "POST",
    path: "/v1/tools/batch_status",
    inputSchema: schema(["batchId", "string", false, "Optional delayed batch id. If omitted, uses the latest batch for the active player."]),
  },
  minecraft_capture_view: {
    description: "Capture the active player's current view as a PNG screenshot and return it as base64.",
    method: "POST",
    path: "/v1/actions/capture_view",
    inputSchema: objectSchema(),
  },
  minecraft_preview_build_plan: {
    description: "Compile and validate a structured voxel build plan without executing it, returning previewCommands, planId, resolvedOrigin, issues, repairs, rotation, and phase data.",
    method: "POST",
    path: "/v1/actions/preview_build_plan",
    inputSchema: {
      type: "object",
      description: "Either provide build_plan explicitly or pass the build_plan object as the root arguments object. Supports coordMode=player|absolute, explicit origin, offset, autoFix, clear, cuboids, blocks, and steps.",
      additionalProperties: true,
      properties: {
        build_plan: { type: "object", additionalProperties: true },
        coordMode: { type: "string", enum: ["player", "absolute"] },
        origin: { type: "object", additionalProperties: true },
        offset: { type: "object", additionalProperties: true },
        autoFix: { type: "boolean" },
      },
    },
  },
  minecraft_highlight: {
    description: "Render x-ray highlights in the world for the active player.",
    method: "POST",
    path: "/v1/actions/highlight",
    inputSchema: highlightSchema(),
  },
  minecraft_execute_build_plan: {
    description: "Compile and execute a structured voxel build plan using the mod's planner, with validation, absolute/player origins, support diagnostics, preview parity via planId, and undo-aware batching.",
    method: "POST",
    path: "/v1/actions/execute_build_plan",
    inputSchema: {
      type: "object",
      description: "Either provide build_plan explicitly, pass the build_plan object as the root arguments object, or send executePlanId/planId from minecraft_preview_build_plan to execute the exact cached preview unchanged.",
      additionalProperties: true,
      properties: {
        build_plan: { type: "object", additionalProperties: true },
        executePlanId: { type: "string" },
        planId: { type: "string" },
        coordMode: { type: "string", enum: ["player", "absolute"] },
        origin: { type: "object", additionalProperties: true },
        offset: { type: "object", additionalProperties: true },
        autoFix: { type: "boolean" },
      },
    },
  },
  minecraft_execute_commands: {
    description: "Execute validated Minecraft commands through the mod's existing command pipeline, including optional delayed sequencing for cinematic batches.",
    method: "POST",
    path: "/v1/actions/execute_commands",
    inputSchema: commandsSchema(),
  },
  minecraft_undo_last_batch: {
    description: "Undo the last MCP or AI command/build batch for the active player.",
    method: "POST",
    path: "/v1/actions/undo",
    inputSchema: objectSchema(),
  },
};

function buildToolCatalogText() {
  return Object.entries(TOOLS)
    .map(([name, tool]) => {
      const properties = Object.keys(tool.inputSchema?.properties || {});
      const args = properties.length ? properties.join(", ") : "none";
      return `- ${name}: ${tool.description}\n  arguments: ${args}`;
    })
    .join("\n");
}

function buildAgentWorkflowGuide() {
  return [
    "# Gemini Minecraft MCP Agent Guide",
    "",
    "Use this server as a Minecraft capability backend. The external agent does the reasoning; this server provides grounded world reads, safe build execution, screenshots, and undo.",
    "",
    "## Core rules",
    "",
    "- Prefer discovery before action. Read the world state first, then decide.",
    "- Use `minecraft_execute_build_plan` for multi-block structures. Do not emit long raw command lists when a structured build plan is more natural.",
    "- Use `minecraft_execute_commands` for one-off commands like `give`, `say`, `time set`, or small targeted edits.",
    "- Use `minecraft_undo_last_batch` immediately after a bad command/build rather than trying to manually reverse a mistake.",
    "- For visual tasks, use `minecraft_capture_view` to inspect what the player sees before making aesthetic judgments.",
    "- In singleplayer, mutation tools only work while the integrated server is actively ticking. If a mutation call reports server unavailable, keep the world in focus or open it to LAN and retry.",
    "- Do not assume `y=0` means ground. All build-plan coordinates are relative to the player origin, not auto-snapped to terrain.",
    "- Do not infer planner semantics from error strings alone. Use `minecraft_describe_tool` or `minecraft_help` first when the build contract matters.",
    "",
    "## Recommended workflow",
    "",
    "### General world reasoning",
    "1. Call `minecraft_session` to confirm the active world, dimension, and coordinates.",
    "2. Use read tools as needed: inventory, nearby entities, block scans, containers, player stats, recipes, and item lookups.",
    "3. If the task depends on what is visible on screen, call `minecraft_capture_view`.",
    "4. Choose the smallest mutation necessary: highlight, commands, or structured build plan.",
    "",
    "### Building structures",
    "1. Call `minecraft_buildsite` with a radius that matches the requested structure size.",
    "2. Read `minDy`, `maxDy`, `clearPercent`, `waterColumns`, and `surfaceCounts` before choosing build height and footprint.",
    "3. Produce a compact `build_plan` using relative coordinates with the player as the origin.",
    "4. Prefer cuboids over many single blocks.",
    "5. Use `steps` when the build needs a foundation first or when terrain is uneven.",
    "6. Use `rotate` only when orientation matters.",
    "7. Call `minecraft_preview_build_plan` before the real build when the terrain, support, or footprint is uncertain.",
    "8. Execute with `minecraft_execute_build_plan`.",
    "9. If the result is wrong, inspect with `minecraft_capture_view` or another read tool, then revise or undo.",
    "",
    "### Timed command sequences",
    "1. Use `minecraft_execute_commands` with either plain strings or objects like `{ \"command\": \"say beat\", \"delayTicks\": 20 }`.",
    "2. Delays are relative to the previous command.",
    "3. For longer sequences, inspect the returned `batchId` and poll `minecraft_batch_status` until it completes.",
    "4. Delayed batches are for real Minecraft commands only, not skill or settings commands.",
    "",
    "## Build-plan guidance",
    "",
    "- Coordinates should be relative to the player unless you have a strong reason to anchor elsewhere.",
    "- Favor `cuboids` for floors, walls, roofs, and shells.",
    "- Use `blocks` for details like doors, beds, torches, chests, stairs, or decorative accents.",
    "- Include `clear` only when needed.",
    "- Use `steps` for larger builds that benefit from phases like foundation -> walls -> roof -> details.",
    "- If `minecraft_buildsite` reports negative `minDy`/`maxDy`, the nearby ground is below the player's feet. Lower the build or move the player before building.",
    "",
    "## Interpreting `minecraft_buildsite`",
    "",
    "- `minDy` and `maxDy` are surface deltas relative to the player's current block Y.",
    "- Example: if the player is at world y=64 and `maxDy=-9`, then the highest nearby sampled ground is around world y=55.",
    "- `clearPercent` is the percentage of sampled columns that have open headroom above the detected surface. Low values mean the area is obstructed or underground.",
    "- `surfaceCounts` is a top-surface sample, not a full material histogram of the whole volume.",
    "- `waterColumns > 0` means the sampled area includes water on the surface and may need relocation or clearing.",
    "- Never treat `y=0` in a build plan as 'ground level' unless you intentionally positioned the player so that their block Y matches the intended structure base.",
    "",
    "## Planner safety envelope",
    "",
    "- Relative X/Z coordinates are clamped into `[-32, 32]`.",
    "- Relative Y coordinates are clamped into `[-24, 24]`.",
    "- If the planner clamps a point or bounds, it reports a repair like `was clamped into the safe build window`.",
    "- Large or off-center plans should be moved closer to the player or broken into smaller phased plans.",
    "",
    "## Support and foundations",
    "",
    "- The planner checks the lowest occupied columns in the compiled build and looks for solid terrain below them.",
    "- If a build is close to grounded, the planner can auto-add support pillars using `minecraft:stone_bricks`.",
    "- Auto-foundation is capped at 24 columns. Beyond that, the build is rejected and the agent should revise the plan.",
    "- If there is no solid terrain below some columns at all, the planner rejects the build and tells the agent to add a foundation, lower the build, or use `steps`.",
    "- The safest fix for uneven terrain is usually a first phase that lays an explicit foundation at the correct relative Y.",
    "",
    "## Result fields from `minecraft_execute_build_plan`",
    "",
    "- `success`: whether execution completed.",
    "- `applied`: number of compiled Minecraft commands that were actually executed.",
    "- `repairs`: planner repairs or normalizations that were applied before execution.",
    "- `outputs`: command output strings from Minecraft.",
    "- `error`: machine-readable failure summary if the action failed.",
    "- `undoAvailable`: whether `minecraft_undo_last_batch` can revert the result.",
    "- `summary`: final build summary string.",
    "- `appliedRotation`: normalized rotation that was actually used (`0`, `90`, `180`, or `270`).",
    "- `phaseCount`: number of compiled build phases with direct operations.",
    "",
    "## Previewing first",
    "",
    "- `minecraft_preview_build_plan` runs the same planner and command validation without mutating the world.",
    "- Use it when the agent is unsure about terrain alignment, clamping, rotation, or support pillars.",
    "- The preview returns `previewCommands` so the agent can inspect exactly what would run before committing.",
    "",
    "## When to use vision",
    "",
    "- To inspect the player’s current viewpoint.",
    "- To judge terrain fit, doorway alignment, roof shape, or visual mistakes after a build.",
    "- To read what is on screen when the needed information is not already exposed by another tool.",
    "",
    "## Tool catalog",
    "",
    buildToolCatalogText(),
    "",
  ].join("\n");
}

function buildCommandsGuide() {
  return [
    "# Raw Commands Guide",
    "",
    "Use `minecraft_execute_commands` for one-off commands and small targeted actions.",
    "",
    "## Good use cases",
    "- `give @s minecraft:apple 1`",
    "- `say hello`",
    "- `time set day`",
    "- one or two small world edits where a build plan would be excessive",
    "",
    "## Avoid using raw commands when",
    "- building a house, tower, wall, interior, or other multi-block structure",
    "- you can describe the intent more clearly as floors, walls, roofs, or detail blocks",
    "",
    "For normal structures, prefer `minecraft_execute_build_plan` instead.",
    "",
    "## Command payload shape",
    "",
    "```json",
    "{",
    "  \"commands\": [",
    "    \"give @s minecraft:apple 1\"",
    "  ]",
    "}",
    "```",
    "",
    "Delayed sequencing is also supported:",
    "",
    "```json",
    "{",
    "  \"commands\": [",
    "    { \"command\": \"say intro\", \"delayTicks\": 0 },",
    "    { \"command\": \"say beat\", \"delayTicks\": 20 },",
    "    { \"command\": \"say finale\", \"delayMs\": 1500 }",
    "  ]",
    "}",
    "```",
    "",
    "When delays are present, the result includes `pending=true` and a `batchId`. Poll `minecraft_batch_status` until it completes.",
    "",
    "## Normalization",
    "",
    "- Literal `\\uXXXX` Unicode escapes are decoded before execution, so color codes like `\\u00a7` work without manual repair.",
    "- `effect give ... 0` is normalized to a minimum duration of `1` so the command does not fail on Minecraft's duration floor.",
    "",
  ].join("\n");
}

function buildVisionGuide() {
  return [
    "# Vision Guide",
    "",
    "Use `minecraft_capture_view` when the task depends on what the player currently sees.",
    "",
    "## Good use cases",
    "- judge whether a build looks correct",
    "- inspect terrain fit before finalizing a structure",
    "- read visible UI or signs when the needed data is not already exposed by another tool",
    "",
    "## Return shape",
    "- `mimeType`",
    "- `lookAt`",
    "- `byteLength`",
    "- `imageBase64`",
    "- `imagePath`",
    "- `summary`",
    "",
    "The capture is also persisted to a PNG file on disk so external tooling can inspect it directly if needed.",
    "",
  ].join("\n");
}

function buildBuildsiteGuide() {
  return [
    "# Buildsite Guide",
    "",
    "Use `minecraft_buildsite` before planning terrain-sensitive structures such as houses, towers, shrines, farms, bridges, or anything that needs to sit on real ground.",
    "",
    "## Returned fields",
    "",
    "- `radius`: actual scan radius used by the server.",
    "- `minDy`: lowest detected surface Y relative to the player's current block Y.",
    "- `maxDy`: highest detected surface Y relative to the player's current block Y.",
    "- `clearPercent`: percent of sampled columns with open headroom above the surface.",
    "- `waterColumns`: sampled columns whose surface block is water.",
    "- `totalColumns`: number of sampled columns.",
    "- `surfaceCounts`: top surface sample, usually the four most common surface blocks.",
    "",
    "## How to read it",
    "",
    "- If both `minDy` and `maxDy` are negative, the surrounding surface is below the player. A build plan with `y=0` will float unless you lower it.",
    "- If both are positive, the player is below surrounding ground or inside terrain. Move the player or raise the structure.",
    "- If the range crosses zero, the area is mixed or uneven. Use a foundation phase or choose a flatter spot.",
    "- Low `clearPercent` means the site is obstructed, roofed over, forested, underground, or otherwise tight.",
    "",
    "## Agent rule",
    "",
    "Never convert `buildsite` directly into world coordinates and then ignore it. Use it to decide the relative Y of the first foundation or floor cuboid before calling `minecraft_execute_build_plan`.",
    "",
  ].join("\n");
}

function buildExampleUsageForTool(name) {
  switch (name) {
    case "minecraft_session":
      return "{ }";
    case "minecraft_buildsite":
      return '{ "radius": 16 }';
    case "minecraft_batch_status":
      return "{ }";
    case "minecraft_preview_build_plan":
      return '{ "summary": "Preview small oak hut", "cuboids": [{"name":"floor","block":"oak_planks","from":{"x":0,"y":0,"z":0},"to":{"x":4,"y":0,"z":4}}] }';
    case "minecraft_capture_view":
      return "{ }";
    case "minecraft_execute_commands":
      return '{ "commands": ["give @s minecraft:apple 1", {"command":"say beat","delayTicks":20}] }';
    case "minecraft_execute_build_plan":
      return '{ "summary": "Small oak hut", "cuboids": [{"name":"floor","block":"oak_planks","from":{"x":0,"y":0,"z":0},"to":{"x":4,"y":0,"z":4}}] }';
    case "minecraft_help":
      return '{ "topic": "build-plan", "task": "build a small house" }';
    case "minecraft_describe_tool":
      return '{ "name": "minecraft_execute_build_plan" }';
    default:
      return "{}";
  }
}

function buildWhenToUseForTool(name) {
  switch (name) {
    case "minecraft_batch_status":
      return "Use after a delayed execute_commands call so the agent can tell whether the batch is still running, completed, or failed.";
    case "minecraft_preview_build_plan":
      return "Use before committing a structure when grounding, clamping, support pillars, or final command shape is uncertain.";
    case "minecraft_execute_build_plan":
      return "Use for real structures: huts, houses, towers, walls, interiors, platforms, statues, and other multi-block builds.";
    case "minecraft_execute_commands":
      return "Use for one-off commands, compact command batches, or timed command sequences where a full build plan would be overkill.";
    case "minecraft_capture_view":
      return "Use when the task depends on the player’s visible viewpoint or when you need visual confirmation.";
    case "minecraft_buildsite":
      return "Use before planning a structure so the agent knows terrain shape, surface composition, relative surface height, and headroom.";
    case "minecraft_help":
      return "Use when the agent is unsure how this MCP server is intended to be used.";
    case "minecraft_describe_tool":
      return "Use when the agent is unsure about one specific tool’s semantics or payload shape.";
    default:
      return "Use when you need the structured Minecraft capability described by the tool.";
  }
}

function buildPitfallsForTool(name) {
  switch (name) {
    case "minecraft_batch_status":
      return [
        "This only applies to delayed MCP command batches.",
        "If no batchId is provided, it reports the latest batch for the active player.",
      ];
    case "minecraft_preview_build_plan":
      return [
        "Do not treat preview success as proof the final result is visually perfect; it only proves the planner and command validation succeeded.",
        "Preview does not mutate the world and does not create an undo batch.",
      ];
    case "minecraft_execute_build_plan":
      return [
        "Do not send prose instead of geometry.",
        "Do not use raw command strings inside the build plan.",
        "For most structures, prefer cuboids over many single blocks.",
        "Do not assume `y=0` is ground; it is relative to the player origin.",
        "Do not ignore clamping repairs. If the plan was clamped, move it closer or make it smaller.",
        "If support-pillar errors appear, add an explicit foundation phase or lower the build instead of retrying the same floating plan.",
      ];
    case "minecraft_buildsite":
      return [
        "Do not treat `minDy` or `maxDy` as absolute world Y values.",
        "Do not assume high `clearPercent` means the terrain is flat; it only describes headroom above sampled surface columns.",
      ];
    case "minecraft_execute_commands":
      return [
        "Do not use this as a substitute for a normal house or tower build plan.",
        "Keep the command batch compact and deliberate.",
        "Delayed batches support real Minecraft commands only. Do not use delayed skill or setting commands.",
      ];
    case "minecraft_capture_view":
      return [
        "It captures what the client currently sees, not an arbitrary detached camera angle.",
      ];
    default:
      return [];
  }
}

function describeTool(name) {
  const tool = TOOLS[name];
  if (!tool) {
    throw new Error(`Unknown tool: ${name}`);
  }
  const properties = Object.entries(tool.inputSchema?.properties || {}).map(([key, value]) => ({
    name: key,
    type: value?.type || "unknown",
    description: value?.description || "",
    required: (tool.inputSchema?.required || []).includes(key),
  }));
  return {
    name,
    description: tool.description,
    whenToUse: buildWhenToUseForTool(name),
    arguments: properties,
    exampleArguments: buildExampleUsageForTool(name),
    pitfalls: buildPitfallsForTool(name),
    responseFields: buildResponseFieldsForTool(name),
    workflowNotes: buildWorkflowNotesForTool(name),
    relatedResources:
      name === "minecraft_execute_build_plan"
        ? ["minecraft://guide/agent-workflow", "minecraft://guide/build-plan", "minecraft://guide/buildsite", "minecraft_build_planner"]
        : name === "minecraft_buildsite"
          ? ["minecraft://guide/agent-workflow", "minecraft://guide/buildsite", "minecraft://guide/build-plan", "minecraft_agent_guide"]
        : ["minecraft://guide/agent-workflow", "minecraft_agent_guide"],
  };
}

function buildResponseFieldsForTool(name) {
  switch (name) {
    case "minecraft_buildsite":
      return [
        { name: "radius", description: "Actual scan radius used." },
        { name: "minDy", description: "Lowest detected surface Y relative to the player's block Y." },
        { name: "maxDy", description: "Highest detected surface Y relative to the player's block Y." },
        { name: "clearPercent", description: "Percent of sampled columns with open headroom above the surface." },
        { name: "waterColumns", description: "How many sampled columns had water as the surface block." },
        { name: "totalColumns", description: "Total sampled columns." },
        { name: "surfaceCounts", description: "Most common sampled surface blocks and counts." },
      ];
    case "minecraft_batch_status":
      return [
        { name: "success", description: "Whether the status lookup succeeded." },
        { name: "batchId", description: "Delayed batch identifier." },
        { name: "pending", description: "Whether the batch is still running." },
        { name: "completed", description: "Whether the batch has finished." },
        { name: "totalCommands", description: "Total scheduled commands." },
        { name: "applied", description: "Commands successfully applied so far." },
        { name: "failed", description: "Commands failed so far." },
        { name: "nextIndex", description: "Next command index to execute." },
        { name: "outputs", description: "Collected command outputs." },
        { name: "error", description: "Combined failure summary, if any." },
        { name: "undoAvailable", description: "Whether the completed batch can be undone." },
        { name: "summary", description: "Human-readable batch state." },
      ];
    case "minecraft_execute_commands":
      return [
        { name: "success", description: "Whether the command execution or scheduling request succeeded." },
        { name: "applied", description: "Commands executed immediately or scheduled in the batch." },
        { name: "outputs", description: "Immediate command outputs for non-delayed execution." },
        { name: "error", description: "Failure summary if validation or execution failed." },
        { name: "undoAvailable", description: "Whether the resulting mutation can be undone." },
        { name: "summary", description: "Execution or scheduling summary." },
        { name: "pending", description: "True when a delayed batch was scheduled and is still running." },
        { name: "batchId", description: "Returned when a delayed batch was scheduled." },
        { name: "previewCommands", description: "Validated command list, especially useful for delayed batches." },
      ];
    case "minecraft_preview_build_plan":
      return [
        { name: "success", description: "Whether the preview compiled and validated successfully." },
        { name: "applied", description: "How many commands would execute if the build were committed." },
        { name: "repairs", description: "Planner repairs or clamping notices." },
        { name: "error", description: "Failure summary if the preview failed." },
        { name: "summary", description: "Planner summary of the build." },
        { name: "appliedRotation", description: "Final normalized rotation." },
        { name: "phaseCount", description: "Number of compiled direct-operation phases." },
        { name: "resolvedOrigin", description: "Exact world origin the planner actually used." },
        { name: "issues", description: "Structured floating/grounding issues with cuboid names, gaps, and suggestedY." },
        { name: "autoFixAvailable", description: "Whether the planner believes a safe grounding fix exists." },
        { name: "planId", description: "Cached preview id that execute can reuse unchanged via executePlanId." },
        { name: "previewCommands", description: "The exact validated Minecraft commands that would run." },
      ];
    case "minecraft_execute_build_plan":
      return [
        { name: "success", description: "Whether the build executed successfully." },
        { name: "applied", description: "Number of compiled Minecraft commands executed." },
        { name: "repairs", description: "Planner repairs, clamping notices, or support messages." },
        { name: "outputs", description: "Minecraft command outputs." },
        { name: "error", description: "Failure summary if execution failed." },
        { name: "undoAvailable", description: "Whether the result can be rolled back with minecraft_undo_last_batch." },
        { name: "summary", description: "Planner summary of what was built or attempted." },
        { name: "appliedRotation", description: "Final normalized rotation used: 0, 90, 180, or 270." },
        { name: "phaseCount", description: "How many direct-operation phases the planner compiled." },
        { name: "resolvedOrigin", description: "Exact world origin the planner actually used." },
        { name: "issues", description: "Structured floating/grounding issues for failed or revised builds." },
        { name: "autoFixAvailable", description: "Whether the planner believed a safe grounding fix was available." },
        { name: "planId", description: "Preview cache id if the build was executed from a cached preview." },
      ];
    default:
      return [];
  }
}

function buildWorkflowNotesForTool(name) {
  switch (name) {
    case "minecraft_buildsite":
      return [
        "Use this before finalizing any structure that needs to rest on real terrain.",
        "Read `minDy` and `maxDy` before deciding the base Y of the plan.",
        "If the ground range is below zero, lower the plan or move the player to the intended build level.",
      ];
    case "minecraft_batch_status":
      return [
        "Use this after a delayed `minecraft_execute_commands` call returns a `batchId`.",
        "Poll until `pending` becomes false before assuming the full sequence finished.",
      ];
    case "minecraft_preview_build_plan":
      return [
        "Use this after minecraft_buildsite and before minecraft_execute_build_plan when the structure may float or get clamped.",
        "If preview returns support-pillar or clamping repairs, revise the build before executing it.",
        "If preview succeeds and the command list looks reasonable, then use minecraft_execute_build_plan with the same payload.",
      ];
    case "minecraft_execute_build_plan":
      return [
        "Start with minecraft_session, then minecraft_buildsite, then produce the plan.",
        "For uneven terrain, use `steps` with a foundation phase before walls or roofs.",
        "If the result contains clamping or support-pillar repairs, revise the plan rather than blindly retrying the same geometry.",
      ];
    default:
      return [];
  }
}

function buildBuildPlanGuide() {
  return [
    "# Build Plan Contract",
    "",
    "The `minecraft_execute_build_plan` tool accepts the same build-plan contract used by the in-mod voxel planner.",
    "If you can describe a structure as floors, walls, roofs, shells, or a handful of detail blocks, prefer this tool over raw `minecraft_execute_commands`.",
    "",
    "## When To Use This Tool",
    "",
    "- Use `minecraft_execute_build_plan` for huts, houses, towers, interiors, platforms, walls, statues, or any other multi-block structure.",
    "- Use `minecraft_execute_commands` for one-off commands like `give`, `say`, `time set`, or tiny targeted edits where a build plan would be overkill.",
    "- If you are unsure about terrain fit, call `minecraft_buildsite` first and then produce the plan.",
    "",
    "## Root Shape",
    "",
    "You may pass either:",
    "- a root object that already is the build plan",
    "- or an object with a top-level `build_plan` field",
    "",
    "Both of these are valid:",
    "",
    "```json",
    "{",
    "  \"summary\": \"Small hut\",",
    "  \"cuboids\": [ ... ]",
    "}",
    "```",
    "",
    "```json",
    "{",
    "  \"build_plan\": {",
    "    \"summary\": \"Small hut\",",
    "    \"cuboids\": [ ... ]",
    "  }",
    "}",
    "```",
    "",
    "## Common Top-Level Fields",
    "",
    "- `summary`: short description of the structure",
    "- `coordMode`: `player` or `absolute`",
    "- `origin`: explicit origin. In `player` mode it is a relative shift from the player. In `absolute` mode it is a real world origin.",
    "- `offset`: optional extra relative shift applied after the base origin",
    "- `autoFix`: whether the planner may auto-lower near-ground builds and add limited support repair",
    "- `rotate`: `0`, `90`, `180`, `270`, `cw`, or `ccw`",
    "- `palette`: aliases for block ids, useful for custom mod blocks",
    "- `clear`: volumes to clear before building",
    "- `cuboids`: bulk structural geometry",
    "- `blocks`: single-block details",
    "- `steps`: phased sub-plans for larger structures",
    "",
    "## Coordinate Rules",
    "",
    "- `coordMode=player` means the plan is relative to the active player.",
    "- `coordMode=absolute` means `origin` is an explicit world coordinate and cuboid/block coordinates are relative to that absolute origin.",
    "- If `coordMode=absolute` is used without `origin`, the planner falls back to the player's current block position and reports that fallback in `repairs`.",
    "- Positive `x` is east, positive `y` is up, positive `z` is south.",
    "- Keep small test structures close to the origin, for example between `-8` and `8` on x/z.",
    "- The planner clamps X/Z into `[-32, 32]` and Y into `[-24, 24]`. If you exceed that window, the plan is repaired and reported as clamped.",
    "- Absolute `origin` is not clamped into the player's safe window. Relative cuboid/block coordinates still are.",
    "",
    "## Aligning The Build With Real Terrain",
    "",
    "- `minecraft_buildsite` returns `minDy` and `maxDy` relative to the player's block Y.",
    "- Example: player at world y=64 and `maxDy=-9` means the highest nearby sampled ground is roughly world y=55.",
    "- In that situation, a plan with its floor at relative `y=0` starts nine blocks above nearby surface and will likely trigger support repairs or failure.",
    "- Best practice: move the player to the intended build level or lower the foundation/floor phase to match the surface you actually want to build on.",
    "- If `clearPercent` is low, do not brute-force retries. Either choose another spot, clear space explicitly, or use a phased plan.",
    "- If you want a structure at a stable world location, prefer `coordMode=absolute` with an explicit `origin` rather than relying on wherever the player happens to be standing.",
    "",
    "## Accepted Geometry Forms",
    "",
    "The planner is intentionally flexible. For cuboids and clear volumes it accepts several equivalent shapes:",
    "- `from` + `to`",
    "- `start` + `end`",
    "- `start` + `size`",
    "- `location` + `size`",
    "- `location` + `dimensions`",
    "- direct `x`, `y`, `z`, `width`, `height`, `depth` style fields",
    "",
    "For single-block details it accepts:",
    "- `pos`",
    "- `location`",
    "- direct `x`, `y`, `z` fields",
    "",
    "Block ids may be provided as:",
    "- `block`",
    "- `material`",
    "- `id`",
    "",
    "Block properties may be provided as:",
    "- `properties`",
    "- `state`",
    "",
    "## Good Planning Habits",
    "",
    "- Keep plans compact and structural.",
    "- Prefer a handful of cuboids over dozens of raw commands.",
    "- Use blocks for doors, beds, torches, stairs, and fine details.",
    "- If terrain is uneven, inspect with `minecraft_buildsite` first.",
    "- If the structure is larger, use `steps` so the plan reads like phases instead of one giant blob.",
    "- Treat `clear` as a surgical pre-build removal step, not a vague instruction to hollow things out automatically.",
    "",
    "## Minimal Valid Plan",
    "",
    "At least one of these must be present with valid content:",
    "- `clear`",
    "- `cuboids`",
    "- `blocks`",
    "- `steps` containing valid sub-plans",
    "",
    "An empty object is not valid.",
    "",
    "## `clear` Volumes",
    "",
    "- `clear` is for removing space before building.",
    "- A clear volume uses bounds but no block id.",
    "- Use the same bounds formats as cuboids: `from/to`, `start/end`, `start + size`, `location + size`, or `location + dimensions`.",
    "- Clear volumes execute before cuboids and blocks in the same plan phase.",
    "",
    "Example:",
    "",
    "```json",
    "{",
    "  \"summary\": \"Clear a small room before building\",",
    "  \"clear\": [",
    "    {\"name\":\"room_clear\",\"from\":{\"x\":0,\"y\":1,\"z\":0},\"to\":{\"x\":4,\"y\":3,\"z\":4}}",
    "  ],",
    "  \"cuboids\": [",
    "    {\"name\":\"floor\",\"block\":\"stone_bricks\",\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":4,\"y\":0,\"z\":4}}",
    "  ]",
    "}",
    "```",
    "",
    "## Recommended Small-House Example",
    "",
    "This is a good default pattern for a simple house because it is compact, valid, and easy for the planner to compile:",
    "",
    "## Example",
    "",
    "```json",
    "{",
    "  \"summary\": \"Small oak hut\",",
    "  \"cuboids\": [",
    "    {\"name\":\"floor\",\"block\":\"oak_planks\",\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":4,\"y\":0,\"z\":4}},",
    "    {\"name\":\"walls\",\"block\":\"oak_planks\",\"start\":{\"x\":0,\"y\":1,\"z\":0},\"size\":{\"x\":5,\"y\":3,\"z\":5},\"hollow\":true}",
    "  ],",
    "  \"blocks\": [",
    "    {\"name\":\"door\",\"block\":\"oak_door\",\"pos\":{\"x\":2,\"y\":1,\"z\":0},\"properties\":{\"facing\":\"south\"}}",
    "  ]",
    "}",
    "```",
    "",
    "## Example With Wrapper",
    "",
    "```json",
    "{",
    "  \"build_plan\": {",
    "    \"summary\": \"Small oak hut\",",
    "    \"cuboids\": [",
    "      {\"name\":\"floor\",\"block\":\"oak_planks\",\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":4,\"y\":0,\"z\":4}},",
    "      {\"name\":\"walls\",\"block\":\"oak_planks\",\"start\":{\"x\":0,\"y\":1,\"z\":0},\"size\":{\"x\":5,\"y\":3,\"z\":5},\"hollow\":true}",
    "    ],",
    "    \"blocks\": [",
    "      {\"name\":\"door\",\"block\":\"oak_door\",\"pos\":{\"x\":2,\"y\":1,\"z\":0},\"properties\":{\"facing\":\"south\"}}",
    "    ]",
    "  }",
    "}",
    "```",
    "",
    "## Phased Build Example",
    "",
    "Use `steps` when the structure should clearly execute as foundation -> shell -> details.",
    "",
    "```json",
    "{",
    "  \"summary\": \"Small phased hut\",",
    "  \"steps\": [",
    "    {",
    "      \"phase\": \"foundation\",",
    "      \"plan\": {",
    "        \"cuboids\": [",
    "          {\"name\":\"base\",\"block\":\"stone_bricks\",\"from\":{\"x\":0,\"y\":-1,\"z\":0},\"to\":{\"x\":4,\"y\":0,\"z\":4}}",
    "        ]",
    "      }",
    "    },",
    "    {",
    "      \"phase\": \"shell\",",
    "      \"plan\": {",
    "        \"cuboids\": [",
    "          {\"name\":\"walls\",\"block\":\"oak_planks\",\"start\":{\"x\":0,\"y\":1,\"z\":0},\"size\":{\"x\":5,\"y\":3,\"z\":5},\"hollow\":true}",
    "        ],",
    "        \"blocks\": [",
    "          {\"name\":\"door\",\"block\":\"oak_door\",\"pos\":{\"x\":2,\"y\":1,\"z\":0},\"properties\":{\"facing\":\"south\"}}",
    "        ]",
    "      }",
    "    }",
    "  ]",
    "}",
    "```",
    "",
    "## Rotation",
    "",
    "- Supported input values are `0`, `90`, `180`, `270`, `cw`, `clockwise`, `ccw`, and `counterclockwise`.",
    "- The planner normalizes rotation to `0`, `90`, `180`, or `270`.",
    "- `appliedRotation` in the result reports the normalized value that was actually used.",
    "- Rotation affects cuboid bounds and common directional properties like `facing` and `axis`.",
    "",
    "## Phases And `phaseCount`",
    "",
    "- `steps` lets you nest sub-plans.",
    "- `phaseCount` in the result is the number of compiled phases that contained direct operations such as `clear`, `cuboids`, or `blocks`.",
    "- Use phases when terrain is uneven, when you want a clean foundation first, or when a large build should be easier to debug.",
    "",
    "## Foundations And Support Pillars",
    "",
    "- After compiling placements, the planner checks the lowest support targets, not just the raw command list.",
    "- It returns structured `issues` identifying which cuboids or block targets are floating, their `gapBelow`, and a `suggestedY` for grounding.",
    "- Only columns with real air/fluid gaps below them are considered for auto-support pillars.",
    "- If more than 80% of the lowest-layer columns are within 2 blocks of valid ground and `autoFix=true`, the planner can auto-lower the whole build instead of spamming pillars.",
    "- Auto-support pillars use `minecraft:stone_bricks` and are capped at 24 columns.",
    "- If there is no valid solid ground below some lowest columns at all, the planner rejects the build and tells the agent to lower the build or add a foundation.",
    "- Repeated retries with the same floating `y=0` plan are the wrong response. Fix the base Y, add a foundation phase, or use preview issues plus `suggestedY` to revise the plan.",
    "",
    "Example issue payload:",
    "",
    "```json",
    "{",
    "  \"issues\": [",
    "    {\"cuboid\":\"floor\",\"issue\":\"floating\",\"gapBelow\":14,\"suggestedY\":65}",
    "  ],",
    "  \"autoFixAvailable\": true",
    "}",
    "```",
    "",
    "## Why Agents Should Prefer Build Plans",
    "",
    "- More compact than long raw command lists",
    "- Easier to revise after `minecraft_buildsite` or `minecraft_capture_view`",
    "- Better undo semantics as one logical build batch",
    "- Lets the Minecraft-side planner validate, rotate, repair, and optimize structure intent",
    "- Lets agents preview the exact validated command batch before committing",
    "",
    "## Preview Before Commit",
    "",
    "For uncertain builds, use this sequence:",
    "",
    "1. `minecraft_buildsite`",
    "2. `minecraft_preview_build_plan`",
    "3. Inspect `repairs`, `appliedRotation`, `phaseCount`, `resolvedOrigin`, `issues`, and `previewCommands`",
    "4. Keep the returned `planId` if the preview is good",
    "5. Revise if needed",
    "6. Execute either the same payload again or, preferably, `minecraft_execute_build_plan {\"executePlanId\":\"...\"}` so the exact cached preview runs unchanged",
    "",
    "The `planId` path is the strongest option when preview parity matters because preview and execute then use the same compiled command list.",
    "",
    "## Failure Prevention",
    "",
    "- Do not invent a custom shape DSL.",
    "- Do not return prose instead of geometry.",
    "- Do not emit hundreds of raw `setblock` commands for a normal house when a small build plan will do.",
    "- If you are uncertain, use the small-house example as your template and adapt it.",
    "- If you see a support-pillar error, treat it as a terrain alignment or foundation problem, not a sign that the JSON schema is wrong.",
    "- If you see clamping repairs, treat it as a safe-window problem, not a random planner bug.",
    "",
    "## Result Fields From `minecraft_execute_build_plan`",
    "",
    "- `success`: whether the build executed successfully",
    "- `applied`: number of compiled Minecraft commands that were executed",
    "- `repairs`: planner repairs or normalizations",
    "- `outputs`: Minecraft command outputs",
    "- `error`: failure summary when the action fails",
    "- `undoAvailable`: whether `minecraft_undo_last_batch` can revert the batch",
    "- `summary`: human-readable summary of the compiled build",
    "- `appliedRotation`: final normalized rotation",
    "- `phaseCount`: number of compiled direct-operation phases",
    "- `resolvedOrigin`: the world origin actually used after coordMode/origin fallback resolution",
    "- `issues`: structured floating/grounding issues for failing or revised builds",
    "- `autoFixAvailable`: whether the planner believes a safe grounding fix is available",
    "- `planId`: returned by preview so execute can run the exact cached plan",
    "",
  ].join("\n");
}

const RESOURCES = {
  "minecraft://guide/agent-workflow": {
    name: "Minecraft Agent Workflow Guide",
    description: "Recommended reasoning workflow for agents using this Minecraft MCP server.",
    mimeType: "text/markdown",
    read: () => buildAgentWorkflowGuide(),
  },
  "minecraft://guide/build-plan": {
    name: "Minecraft Build Plan Guide",
    description: "Structured voxel build-plan contract and example payload.",
    mimeType: "text/markdown",
    read: () => buildBuildPlanGuide(),
  },
  "minecraft://guide/buildsite": {
    name: "Minecraft Buildsite Guide",
    description: "How to interpret buildsite terrain summaries and align build-plan Y correctly.",
    mimeType: "text/markdown",
    read: () => buildBuildsiteGuide(),
  },
};

const PROMPTS = {
  minecraft_agent_guide: {
    description: "Loads the recommended operating guide for agents using this Minecraft MCP server.",
    arguments: [
      {
        name: "task",
        description: "Optional current goal or task the agent should keep in mind while using the server.",
        required: false,
      },
    ],
    get: (args = {}) => {
      const task = typeof args.task === "string" ? args.task.trim() : "";
      const messages = [
        {
          role: "assistant",
          content: {
            type: "resource",
            resource: {
              uri: "minecraft://guide/agent-workflow",
              mimeType: "text/markdown",
              text: buildAgentWorkflowGuide(),
            },
          },
        },
        {
          role: "assistant",
          content: {
            type: "resource",
            resource: {
              uri: "minecraft://guide/buildsite",
              mimeType: "text/markdown",
              text: buildBuildsiteGuide(),
            },
          },
        },
      ];
      if (task) {
        messages.push({
          role: "user",
          content: {
            type: "text",
            text: `Current task: ${task}\nUse the guide above while choosing Minecraft MCP tools.`,
          },
        });
      }
      return {
        description: "Minecraft MCP operating guide",
        messages,
      };
    },
  },
  minecraft_build_planner: {
    description: "Returns a strong planning prompt for agents building structures through minecraft_execute_build_plan.",
    arguments: [
      {
        name: "goal",
        description: "What structure or build outcome the agent should produce.",
        required: true,
      },
      {
        name: "constraints",
        description: "Optional style, size, or material constraints.",
        required: false,
      },
      {
        name: "useVision",
        description: "Set to true if the agent should capture the current view before finalizing the build.",
        required: false,
      },
    ],
    get: (args = {}) => {
      const goal = typeof args.goal === "string" ? args.goal.trim() : "";
      if (!goal) {
        throw new Error("Missing required argument: goal");
      }
      const constraints = typeof args.constraints === "string" ? args.constraints.trim() : "";
      const useVision = String(args.useVision || "").trim().toLowerCase() === "true";
      return {
        description: "Minecraft build planner prompt",
        messages: [
          {
            role: "assistant",
            content: {
              type: "resource",
              resource: {
                uri: "minecraft://guide/agent-workflow",
                mimeType: "text/markdown",
                text: buildAgentWorkflowGuide(),
              },
            },
          },
          {
            role: "assistant",
            content: {
              type: "resource",
              resource: {
                uri: "minecraft://guide/build-plan",
                mimeType: "text/markdown",
                text: buildBuildPlanGuide(),
              },
            },
          },
          {
            role: "assistant",
            content: {
              type: "resource",
              resource: {
                uri: "minecraft://guide/buildsite",
                mimeType: "text/markdown",
                text: buildBuildsiteGuide(),
              },
            },
          },
          {
            role: "user",
            content: {
              type: "text",
              text:
                `Build goal: ${goal}\n` +
                (constraints ? `Constraints: ${constraints}\n` : "") +
                `Required workflow:\n` +
                `1. Call minecraft_session.\n` +
                `2. Call minecraft_buildsite before finalizing the structure.\n` +
                (useVision ? `3. Call minecraft_capture_view before finalizing the result if visual context matters.\n` : "") +
                `4. Produce a compact build_plan using relative coordinates.\n` +
                `5. Prefer cuboids over many single-block placements.\n` +
                `6. Call minecraft_preview_build_plan before committing if terrain fit or support is uncertain.\n` +
                `7. Execute via minecraft_execute_build_plan.\n` +
                `8. If the result is wrong, use minecraft_undo_last_batch or revise after another inspection.`,
            },
          },
        ],
      };
    },
  },
};

class App {
  constructor(bridgeUrl, bridgeToken) {
    this.bridgeUrl = trimTrailingSlash(bridgeUrl || DEFAULT_BRIDGE_URL);
    this.bridgeToken = (bridgeToken || "").trim();
  }

  baseHeaders() {
    const headers = {};
    if (this.bridgeToken) headers.Authorization = `Bearer ${this.bridgeToken}`;
    return headers;
  }

  httpJson(tool, body, methodOverride) {
    return new Promise((resolve) => {
      const method = methodOverride || tool.method;
      const url = new URL(`${this.bridgeUrl}${tool.path}`);
      const headers = this.baseHeaders();
      let payload = null;
      if (method !== "GET") {
        headers["Content-Type"] = "application/json";
        payload = Buffer.from(JSON.stringify(body ?? {}), "utf8");
        headers["Content-Length"] = String(payload.length);
      }
      debugLog(`callBridge path=${tool.path} method=${method}`);
      const req = http.request(
        {
          hostname: url.hostname,
          port: Number(url.port || 80),
          path: url.pathname + url.search,
          method,
          headers,
          timeout: 10000,
        },
        (res) => {
          const chunks = [];
          res.on("data", (chunk) => chunks.push(chunk));
          res.on("end", () => {
            const text = Buffer.concat(chunks).toString("utf8");
            try {
              resolve(JSON.parse(text));
            } catch {
              resolve({
                error: {
                  code: "BRIDGE_REQUEST_FAILED",
                  message: `Minecraft bridge returned non-JSON response (${res.statusCode}).`,
                },
              });
            }
          });
        }
      );
      req.on("timeout", () => {
        req.destroy(new Error("request timeout"));
      });
      req.on("error", (error) => {
        resolve({
          error: {
            code: "BRIDGE_REQUEST_FAILED",
            message: `Minecraft bridge request failed: ${error.message}`,
          },
        });
      });
      if (payload) req.write(payload);
      req.end();
    });
  }

  async probeHealth() {
    try {
      debugLog(`probeHealth ${this.bridgeUrl}/v1/health`);
      const url = new URL(`${this.bridgeUrl}/v1/health`);
      const headers = this.baseHeaders();
      return await new Promise((resolve) => {
        const req = http.request(
          {
            hostname: url.hostname,
            port: Number(url.port || 80),
            path: url.pathname + url.search,
            method: "GET",
            headers,
            timeout: 5000,
          },
          (res) => {
            const chunks = [];
            res.on("data", (chunk) => chunks.push(chunk));
            res.on("end", () => {
              try {
                resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
              } catch (error) {
                resolve({
                  error: {
                    code: "BRIDGE_UNAVAILABLE",
                    message: `Minecraft bridge probe failed: ${error.message}`,
                  },
                });
              }
            });
          }
        );
        req.on("timeout", () => req.destroy(new Error("request timeout")));
        req.on("error", (error) => {
          resolve({
            error: {
              code: "BRIDGE_UNAVAILABLE",
              message: `Minecraft bridge probe failed: ${error.message}`,
            },
          });
        });
        req.end();
      });
    } catch (error) {
      return { error: { code: "BRIDGE_UNAVAILABLE", message: `Minecraft bridge probe failed: ${error.message}` } };
    }
  }

  async handleRequest(request) {
    const method = request?.method || "";
    debugLog(`request method=${method}`);
    const requestId = request?.id;
    const params = request?.params || {};

    if (method === "initialize") {
      debugLog("initialize received");
      const requestedProtocolVersion =
        typeof params.protocolVersion === "string" && params.protocolVersion.trim()
          ? params.protocolVersion.trim()
          : PROTOCOL_VERSION;
      return {
        jsonrpc: "2.0",
        id: requestId,
        result: {
          protocolVersion: requestedProtocolVersion,
          capabilities: {
            experimental: {},
            prompts: { listChanged: false },
            resources: { subscribe: false, listChanged: false },
            tools: { listChanged: false },
          },
          serverInfo: { name: "gemini-minecraft-mcp-node", version: "1.0.0" },
        },
      };
    }
    if (method === "notifications/initialized") return null;
    if (method === "ping") return { jsonrpc: "2.0", id: requestId, result: {} };
    if (method === "prompts/list") {
      return {
        jsonrpc: "2.0",
        id: requestId,
        result: {
          prompts: Object.entries(PROMPTS).map(([name, prompt]) => ({
            name,
            description: prompt.description,
            arguments: prompt.arguments || [],
          })),
        },
      };
    }
    if (method === "prompts/get") {
      const name = params.name || "";
      const prompt = PROMPTS[name];
      if (!prompt) {
        return { jsonrpc: "2.0", id: requestId, error: { code: -32602, message: `Unknown prompt: ${name}` } };
      }
      try {
        return {
          jsonrpc: "2.0",
          id: requestId,
          result: prompt.get(params.arguments || {}),
        };
      } catch (error) {
        return { jsonrpc: "2.0", id: requestId, error: { code: -32602, message: error.message } };
      }
    }
    if (method === "resources/list") {
      return {
        jsonrpc: "2.0",
        id: requestId,
        result: {
          resources: Object.entries(RESOURCES).map(([uri, resource]) => ({
            uri,
            name: resource.name,
            description: resource.description,
            mimeType: resource.mimeType,
          })),
        },
      };
    }
    if (method === "resources/read") {
      const uri = params.uri || "";
      const resource = RESOURCES[uri];
      if (!resource) {
        return { jsonrpc: "2.0", id: requestId, error: { code: -32602, message: `Unknown resource: ${uri}` } };
      }
      return {
        jsonrpc: "2.0",
        id: requestId,
        result: {
          contents: [
            {
              uri,
              mimeType: resource.mimeType,
              text: resource.read(),
            },
          ],
        },
      };
    }
    if (method === "tools/list") {
      return {
        jsonrpc: "2.0",
        id: requestId,
        result: {
          tools: Object.entries(TOOLS).map(([name, tool]) => ({
            name,
            description: tool.description,
            inputSchema: tool.inputSchema,
          })),
        },
      };
    }
    if (method === "tools/call") {
      const name = params.name || "";
      debugLog(`tools/call name=${name}`);
      const tool = TOOLS[name];
      if (!tool) {
        return { jsonrpc: "2.0", id: requestId, error: { code: -32602, message: `Unknown tool: ${name}` } };
      }
      const argumentsObject = params.arguments || {};
      if (typeof tool.localHandler === "function") {
        try {
          const response = tool.localHandler(argumentsObject);
          return {
            jsonrpc: "2.0",
            id: requestId,
            result: {
              isError: false,
              content: [{ type: "text", text: JSON.stringify(response) }],
              structuredContent: response,
            },
          };
        } catch (error) {
          return toolErrorResponse(requestId, {
            code: "LOCAL_TOOL_FAILED",
            message: error.message || `Failed to handle local tool ${name}.`,
          });
        }
      }
      const health = await this.probeHealth();
      if (health.error) return toolErrorResponse(requestId, health.error);
      if (health.enabled === false) {
        return toolErrorResponse(requestId, {
          code: "BRIDGE_DISABLED",
          message: "Minecraft MCP bridge is disabled.",
        });
      }
      const response = await this.httpJson(tool, argumentsObject);
      const errorPayload = response.error;
      const isError =
        (typeof errorPayload === "string" && errorPayload.trim() !== "") ||
        (errorPayload && typeof errorPayload === "object") ||
        (errorPayload != null && typeof errorPayload !== "string");
      return {
        jsonrpc: "2.0",
        id: requestId,
        result: {
          isError,
          content: [{ type: "text", text: JSON.stringify(response) }],
          structuredContent: response,
        },
      };
    }
    return {
      jsonrpc: "2.0",
      id: requestId,
      error: { code: -32601, message: `Method not found: ${method}` },
    };
  }
}

function toolErrorResponse(requestId, error) {
  return {
    jsonrpc: "2.0",
    id: requestId,
    result: {
      isError: true,
      content: [{ type: "text", text: JSON.stringify(error) }],
      structuredContent: error,
    },
  };
}

function writeMessage(message, transportStyle = "framed") {
  const payload = Buffer.from(JSON.stringify(message), "utf8");
  const preview = payload
    .slice(0, 200)
    .toString("utf8")
    .replace(/\r/g, "\\r")
    .replace(/\n/g, "\\n");
  debugLog(`write transport=${transportStyle} len=${payload.length} preview=${preview}`);
  if (transportStyle === "bare") {
    process.stdout.write(payload);
    process.stdout.write("\n");
    return;
  }
  process.stdout.write(`Content-Length: ${payload.length}\r\n\r\n`);
  process.stdout.write(payload);
}

function extractBareJsonMessage(buffer) {
  let start = 0;
  while (start < buffer.length && /\s/.test(String.fromCharCode(buffer[start]))) {
    start += 1;
  }
  if (start >= buffer.length) return null;

  const first = String.fromCharCode(buffer[start]);
  if (first !== "{" && first !== "[") return null;

  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let i = start; i < buffer.length; i += 1) {
    const ch = String.fromCharCode(buffer[i]);
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === "\\") {
        escaped = true;
      } else if (ch === "\"") {
        inString = false;
      }
      continue;
    }

    if (ch === "\"") {
      inString = true;
      continue;
    }
    if (ch === "{" || ch === "[") {
      depth += 1;
      continue;
    }
    if (ch === "}" || ch === "]") {
      depth -= 1;
      if (depth === 0) {
        const end = i + 1;
        const body = buffer.slice(start, end).toString("utf8");
        try {
          return { bytesConsumed: end, message: JSON.parse(body) };
        } catch (error) {
          debugLog(`bare json parse failed: ${error.message}`);
          return { bytesConsumed: end, message: null };
        }
      }
    }
  }

  return null;
}

function createMessageParser(onMessage) {
  let buffer = Buffer.alloc(0);
  let loggedFirstChunk = false;
  return (chunk) => {
    if (!loggedFirstChunk) {
      loggedFirstChunk = true;
      const preview = chunk
        .slice(0, 160)
        .toString("utf8")
        .replace(/\r/g, "\\r")
        .replace(/\n/g, "\\n");
      debugLog(`stdin first chunk len=${chunk.length} preview=${preview}`);
    }
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const bareJson = extractBareJsonMessage(buffer);
      if (bareJson) {
        buffer = buffer.slice(bareJson.bytesConsumed);
        if (bareJson.message != null) {
          onMessage(bareJson.message, "bare");
        }
        continue;
      }

      const headerEndCrlf = buffer.indexOf(Buffer.from("\r\n\r\n"));
      const headerEndLf = buffer.indexOf(Buffer.from("\n\n"));
      let headerEnd = -1;
      let separatorLength = 0;
      if (headerEndCrlf >= 0 && (headerEndLf < 0 || headerEndCrlf < headerEndLf)) {
        headerEnd = headerEndCrlf;
        separatorLength = 4;
      } else if (headerEndLf >= 0) {
        headerEnd = headerEndLf;
        separatorLength = 2;
      }
      if (headerEnd < 0) return;

      const headerText = buffer.slice(0, headerEnd).toString("utf8");
      const match = headerText.match(/content-length\s*:\s*(\d+)/i);
      if (!match) {
        debugLog("request missing content-length header");
        buffer = buffer.slice(headerEnd + separatorLength);
        continue;
      }
      const contentLength = Number(match[1]);
      const totalNeeded = headerEnd + separatorLength + contentLength;
      if (buffer.length < totalNeeded) return;

      const body = buffer.slice(headerEnd + separatorLength, totalNeeded).toString("utf8");
      buffer = buffer.slice(totalNeeded);
      try {
        onMessage(JSON.parse(body), "framed");
      } catch (error) {
        debugLog(`request json parse failed: ${error.message}`);
      }
    }
  };
}

async function main() {
  const parsed = parseArgs(process.argv.slice(2));
  const bridgeUrl = firstNonBlank(parsed["bridge-url"], process.env.MCP_BRIDGE_URL, DEFAULT_BRIDGE_URL);
  const explicitToken = firstNonBlank(parsed["bridge-token"], process.env.MCP_BRIDGE_TOKEN, "");
  const tokenFile = firstNonBlank(parsed["token-file"], process.env.MCP_BRIDGE_TOKEN_FILE, "");
  const projectRoot = firstNonBlank(parsed["project-root"], process.env.MCP_PROJECT_ROOT, __dirname);
  const bridgeToken = resolveBridgeToken(explicitToken, tokenFile, projectRoot);
  debugLog(
    `startup bridgeUrl=${bridgeUrl} tokenPresent=${Boolean(bridgeToken)} projectRoot=${projectRoot} stdin_tty=${process.stdin.isTTY} stdout_tty=${process.stdout.isTTY} stderr_tty=${process.stderr.isTTY}`
  );

  const app = new App(bridgeUrl, bridgeToken);
  const pending = [];
  let transportStyle = "framed";

  const parser = createMessageParser((request, detectedTransportStyle) => {
    transportStyle = detectedTransportStyle || transportStyle;
    debugLog(`dispatch method=${request?.method || ""} transport=${transportStyle}`);
    const work = Promise.resolve(app.handleRequest(request))
      .then((response) => {
        debugLog(`response ready method=${request?.method || ""} hasResponse=${response != null}`);
        if (response != null) writeMessage(response, transportStyle);
      })
      .catch((error) => {
        debugLog(`request handler failed: ${error.message}`);
      });
    pending.push(work);
    work.finally(() => {
      const index = pending.indexOf(work);
      if (index >= 0) pending.splice(index, 1);
    });
  });

  process.stdin.on("data", parser);
  process.stdin.on("end", () => {
    debugLog("stdin eof before next request");
    process.exit(0);
  });
  process.stdin.on("error", (error) => {
    debugLog(`stdin error: ${error.message}`);
  });
  process.stdout.on("error", (error) => {
    debugLog(`stdout error: ${error.message}`);
  });
  process.stdin.resume();
}

main().catch((error) => {
  debugLog(`fatal: ${error.message}`);
  process.exit(1);
});
