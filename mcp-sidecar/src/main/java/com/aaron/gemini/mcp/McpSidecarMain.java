package com.aaron.gemini.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class McpSidecarMain {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final String DEFAULT_BRIDGE_URL = "http://127.0.0.1:7766";
	private static final String PROTOCOL_VERSION = "2025-03-26";
	private static final Path DEBUG_LOG_PATH = Path.of(System.getProperty("user.home"), ".codex", "gemini-minecraft-mcp.log");

	private final HttpClient httpClient;
	private final String bridgeBaseUrl;
	private final String bridgeToken;
	private final Map<String, ToolDef> tools;

	private McpSidecarMain(String bridgeBaseUrl, String bridgeToken) {
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		this.bridgeBaseUrl = trimTrailingSlash(bridgeBaseUrl);
		this.bridgeToken = bridgeToken == null ? "" : bridgeToken.trim();
		this.tools = buildTools();
	}

	public static void main(String[] args) throws Exception {
		Map<String, String> parsed = parseArgs(args);
		String bridgeUrl = firstNonBlank(parsed.get("bridge-url"), System.getenv("MCP_BRIDGE_URL"), DEFAULT_BRIDGE_URL);
		String explicitToken = firstNonBlank(parsed.get("bridge-token"), System.getenv("MCP_BRIDGE_TOKEN"), "");
		String tokenFile = firstNonBlank(parsed.get("token-file"), System.getenv("MCP_BRIDGE_TOKEN_FILE"), "");
		String projectRoot = firstNonBlank(parsed.get("project-root"), System.getenv("MCP_PROJECT_ROOT"), "");
		String bridgeToken = resolveBridgeToken(explicitToken, tokenFile, projectRoot);
		debugLog("startup bridgeUrl=" + bridgeUrl + " tokenPresent=" + (!bridgeToken.isBlank()) + " projectRoot=" + projectRoot);
		McpSidecarMain app = new McpSidecarMain(bridgeUrl, bridgeToken);
		app.run();
	}

	private void run() throws Exception {
		BufferedInputStream in = new BufferedInputStream(System.in);
		while (true) {
			String header = readHeaders(in);
			if (header == null) {
				debugLog("stdin eof before next request");
				return;
			}
			int contentLength = parseContentLength(header);
			if (contentLength < 0) {
				debugLog("request missing content-length header");
				continue;
			}
			String payload = readBody(in, contentLength);
			JsonObject request = JsonParser.parseString(payload).getAsJsonObject();
			handleRequest(request);
		}
	}

	private void handleRequest(JsonObject request) throws IOException, InterruptedException {
		String method = getString(request, "method", "");
		debugLog("request method=" + method);
		JsonElement id = request.get("id");
		JsonObject params = request.has("params") && request.get("params").isJsonObject()
			? request.getAsJsonObject("params")
			: new JsonObject();

		switch (method) {
			case "initialize" -> {
				debugLog("initialize received");
				JsonObject result = new JsonObject();
				result.addProperty("protocolVersion", PROTOCOL_VERSION);
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
				JsonObject serverInfo = new JsonObject();
				serverInfo.addProperty("name", "gemini-minecraft-mcp-sidecar");
				serverInfo.addProperty("version", "1.0.0");
				result.add("serverInfo", serverInfo);
				writeResponse(id, result);
			}
			case "notifications/initialized" -> {
				// no-op
			}
			case "ping" -> writeResponse(id, new JsonObject());
			case "tools/list" -> writeResponse(id, toolsListResult());
			case "tools/call" -> writeToolsCall(id, params);
			default -> writeError(id, -32601, "Method not found: " + method, null);
		}
	}

	private void writeToolsCall(JsonElement id, JsonObject params) throws IOException, InterruptedException {
		String name = getString(params, "name", "");
		debugLog("tools/call name=" + name);
		ToolDef tool = tools.get(name);
		if (tool == null) {
			writeError(id, -32602, "Unknown tool: " + name, null);
			return;
		}
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
			? params.getAsJsonObject("arguments")
			: new JsonObject();

		JsonObject health = probeHealth();
		if (health.has("error")) {
			writeToolError(id, health.getAsJsonObject("error"));
			return;
		}
		if (health.has("enabled") && !health.get("enabled").getAsBoolean()) {
			JsonObject error = new JsonObject();
			error.addProperty("code", "BRIDGE_DISABLED");
			error.addProperty("message", "Minecraft MCP bridge is disabled.");
			writeToolError(id, error);
			return;
		}

		JsonObject response = callBridge(tool, arguments);
		boolean isError = response.has("error");
		JsonObject result = new JsonObject();
		result.addProperty("isError", isError);
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", GSON.toJson(response));
		content.add(text);
		result.add("content", content);
		result.add("structuredContent", response);
		writeResponse(id, result);
	}

	private JsonObject toolsListResult() {
		JsonObject result = new JsonObject();
		JsonArray array = new JsonArray();
		for (ToolDef tool : tools.values()) {
			JsonObject obj = new JsonObject();
			obj.addProperty("name", tool.name);
			obj.addProperty("description", tool.description);
			obj.add("inputSchema", tool.inputSchema);
			array.add(obj);
		}
		result.add("tools", array);
		return result;
	}

	private JsonObject probeHealth() {
		try {
			debugLog("probeHealth " + bridgeBaseUrl + "/v1/health");
			HttpRequest request = baseRequest("/v1/health").GET().build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (ConnectException e) {
			return errorObject("BRIDGE_UNAVAILABLE", "Minecraft bridge is not reachable.");
		} catch (Exception e) {
			return errorObject("BRIDGE_UNAVAILABLE", "Minecraft bridge probe failed: " + e.getMessage());
		}
	}

	private JsonObject callBridge(ToolDef tool, JsonObject arguments) {
		try {
			debugLog("callBridge path=" + tool.path + " method=" + tool.method);
			HttpRequest.Builder builder = baseRequest(tool.path);
			if ("GET".equals(tool.method)) {
				builder.GET();
			} else {
				String body = tool.bodyBuilder.apply(arguments).toString();
				builder.header("Content-Type", "application/json");
				builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
			}
			HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (ConnectException e) {
			return errorObject("BRIDGE_UNAVAILABLE", "Minecraft bridge is not reachable.");
		} catch (Exception e) {
			return errorObject("BRIDGE_REQUEST_FAILED", "Minecraft bridge request failed: " + e.getMessage());
		}
	}

	private HttpRequest.Builder baseRequest(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(bridgeBaseUrl + path)).timeout(Duration.ofSeconds(5));
		if (!bridgeToken.isBlank()) {
			builder.header("Authorization", "Bearer " + bridgeToken);
		}
		return builder;
	}

	private Map<String, ToolDef> buildTools() {
		Map<String, ToolDef> defs = new LinkedHashMap<>();
		defs.put("minecraft_session", new ToolDef("minecraft_session", "Get the current Minecraft bridge and active-player session.", "GET", "/v1/session", objectSchema(), args -> new JsonObject()));
		defs.put("minecraft_inventory", new ToolDef("minecraft_inventory", "Read the active player's inventory summary.", "POST", "/v1/tools/inventory", objectSchema(), args -> args));
		defs.put("minecraft_nearby_entities", new ToolDef("minecraft_nearby_entities", "List nearby entities around the active player.", "POST", "/v1/tools/nearby", objectSchema(), args -> args));
		defs.put("minecraft_scan_blocks", new ToolDef("minecraft_scan_blocks", "Scan for nearby blocks matching a block id or tag.", "POST", "/v1/tools/blocks", schema("target", "string", true, "Block id or #tag.", "radius", "integer", false, "Optional scan radius."), args -> args));
		defs.put("minecraft_scan_containers", new ToolDef("minecraft_scan_containers", "Scan nearby containers and summarize their contents.", "POST", "/v1/tools/containers", schema("filter", "string", false, "Optional block filter.", "radius", "integer", false, "Optional scan radius."), args -> args));
		defs.put("minecraft_blockdata", new ToolDef("minecraft_blockdata", "Inspect a block entity by coordinates or nearest matching container.", "POST", "/v1/tools/blockdata", schema("target", "string", false, "Optional block filter.", "radius", "integer", false, "Optional search radius.", "x", "integer", false, "Absolute block x.", "y", "integer", false, "Absolute block y.", "z", "integer", false, "Absolute block z."), args -> args));
		defs.put("minecraft_players", new ToolDef("minecraft_players", "List online players and their positions.", "POST", "/v1/tools/players", objectSchema(), args -> args));
		defs.put("minecraft_stats", new ToolDef("minecraft_stats", "Read the active player's health, food, armor, XP, and effects.", "POST", "/v1/tools/stats", objectSchema(), args -> args));
		defs.put("minecraft_buildsite", new ToolDef("minecraft_buildsite", "Summarize the terrain around the active player for planning builds.", "POST", "/v1/tools/buildsite", schema("radius", "integer", false, "Optional scan radius."), args -> args));
		defs.put("minecraft_recipe_lookup", new ToolDef("minecraft_recipe_lookup", "Look up crafting recipes for an item.", "POST", "/v1/tools/recipe", schema("item", "string", true, "Item id to resolve."), args -> args));
		defs.put("minecraft_smelt_lookup", new ToolDef("minecraft_smelt_lookup", "Look up smelting and other cooking recipes for an item.", "POST", "/v1/tools/smelt", schema("item", "string", true, "Item id to resolve."), args -> args));
		defs.put("minecraft_item_lookup", new ToolDef("minecraft_item_lookup", "Inspect the tooltip of an inventory slot, mainhand, or offhand item.", "POST", "/v1/tools/lookup", schema("target", "string", false, "mainhand, offhand, or slot N"), args -> args));
		defs.put("minecraft_item_components", new ToolDef("minecraft_item_components", "Inspect item components for an inventory slot, mainhand, or offhand item.", "POST", "/v1/tools/nbt", schema("target", "string", false, "mainhand, offhand, or slot N"), args -> args));
		defs.put("minecraft_capture_view", new ToolDef("minecraft_capture_view", "Capture the active player's current view as a PNG screenshot and return it as base64.", "POST", "/v1/actions/capture_view", objectSchema(), args -> args));
		defs.put("minecraft_highlight", new ToolDef("minecraft_highlight", "Render x-ray highlights in the world for the active player.", "POST", "/v1/actions/highlight", highlightSchema(), args -> args));
		defs.put("minecraft_execute_build_plan", new ToolDef("minecraft_execute_build_plan", "Compile and execute a structured voxel build plan using the mod's planner.", "POST", "/v1/actions/execute_build_plan", buildPlanSchema(), args -> args));
		defs.put("minecraft_execute_commands", new ToolDef("minecraft_execute_commands", "Execute validated Minecraft commands through the mod's existing command pipeline.", "POST", "/v1/actions/execute_commands", commandsSchema(), args -> args));
		defs.put("minecraft_undo_last_batch", new ToolDef("minecraft_undo_last_batch", "Undo the last MCP or AI command/build batch for the active player.", "POST", "/v1/actions/undo", objectSchema(), args -> new JsonObject()));
		return defs;
	}

	private static JsonObject buildPlanSchema() {
		JsonObject schema = objectSchema();
		schema.addProperty("description", "Either provide build_plan explicitly or pass the build_plan object as the root arguments object.");
		schema.addProperty("additionalProperties", true);
		return schema;
	}

	private static JsonObject commandsSchema() {
		JsonObject schema = objectSchema();
		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		JsonObject properties = schema.getAsJsonObject("properties");
		JsonObject commands = new JsonObject();
		commands.addProperty("type", "array");
		commands.add("items", items);
		properties.add("commands", commands);
		JsonArray required = new JsonArray();
		required.add("commands");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject highlightSchema() {
		JsonObject schema = objectSchema();
		JsonObject highlight = objectSchema();
		JsonObject props = highlight.getAsJsonObject("properties");
		props.add("x", primitive("number", "World x coordinate."));
		props.add("y", primitive("number", "World y coordinate."));
		props.add("z", primitive("number", "World z coordinate."));
		props.add("label", primitive("string", "Optional label."));
		props.add("color", primitive("string", "Named color or hex code."));
		props.add("durationMs", primitive("integer", "Highlight duration in milliseconds."));
		JsonArray required = new JsonArray();
		required.add("x");
		required.add("y");
		required.add("z");
		highlight.add("required", required);
		JsonObject highlights = new JsonObject();
		highlights.addProperty("type", "array");
		highlights.add("items", highlight);
		schema.getAsJsonObject("properties").add("highlights", highlights);
		JsonArray topRequired = new JsonArray();
		topRequired.add("highlights");
		schema.add("required", topRequired);
		return schema;
	}

	private static JsonObject schema(String key1, String type1, boolean required1, String desc1) {
		JsonObject schema = objectSchema();
		schema.getAsJsonObject("properties").add(key1, primitive(type1, desc1));
		if (required1) {
			JsonArray required = new JsonArray();
			required.add(key1);
			schema.add("required", required);
		}
		return schema;
	}

	private static JsonObject schema(String key1, String type1, boolean required1, String desc1, String key2, String type2, boolean required2, String desc2) {
		JsonObject schema = objectSchema();
		JsonObject properties = schema.getAsJsonObject("properties");
		properties.add(key1, primitive(type1, desc1));
		properties.add(key2, primitive(type2, desc2));
		JsonArray required = new JsonArray();
		if (required1) {
			required.add(key1);
		}
		if (required2) {
			required.add(key2);
		}
		if (!required.isEmpty()) {
			schema.add("required", required);
		}
		return schema;
	}

	private static JsonObject schema(String key1, String type1, boolean required1, String desc1, String key2, String type2, boolean required2, String desc2, String key3, String type3, boolean required3, String desc3, String key4, String type4, boolean required4, String desc4) {
		JsonObject schema = objectSchema();
		JsonObject properties = schema.getAsJsonObject("properties");
		properties.add(key1, primitive(type1, desc1));
		properties.add(key2, primitive(type2, desc2));
		properties.add(key3, primitive(type3, desc3));
		properties.add(key4, primitive(type4, desc4));
		JsonArray required = new JsonArray();
		if (required1) required.add(key1);
		if (required2) required.add(key2);
		if (required3) required.add(key3);
		if (required4) required.add(key4);
		if (!required.isEmpty()) {
			schema.add("required", required);
		}
		return schema;
	}

	private static JsonObject schema(String key1, String type1, boolean required1, String desc1, String key2, String type2, boolean required2, String desc2, String key3, String type3, boolean required3, String desc3, String key4, String type4, boolean required4, String desc4, String key5, String type5, boolean required5, String desc5) {
		JsonObject schema = schema(key1, type1, required1, desc1, key2, type2, required2, desc2, key3, type3, required3, desc3, key4, type4, required4, desc4);
		schema.getAsJsonObject("properties").add(key5, primitive(type5, desc5));
		if (required5) {
			JsonArray required = schema.has("required") ? schema.getAsJsonArray("required") : new JsonArray();
			required.add(key5);
			schema.add("required", required);
		}
		return schema;
	}

	private static JsonObject objectSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);
		schema.add("properties", new JsonObject());
		return schema;
	}

	private static JsonObject primitive(String type, String description) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", type);
		schema.addProperty("description", description);
		return schema;
	}

	private void writeResponse(JsonElement id, JsonObject result) throws IOException {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		if (id != null) {
			response.add("id", id.deepCopy());
		}
		response.add("result", result);
		writeMessage(response);
	}

	private void writeToolError(JsonElement id, JsonObject error) throws IOException {
		JsonObject result = new JsonObject();
		result.addProperty("isError", true);
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", GSON.toJson(error));
		content.add(text);
		result.add("content", content);
		result.add("structuredContent", error);
		writeResponse(id, result);
	}

	private void writeError(JsonElement id, int code, String message, JsonObject data) throws IOException {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		if (id != null) {
			response.add("id", id.deepCopy());
		}
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		if (data != null) {
			error.add("data", data);
		}
		response.add("error", error);
		writeMessage(response);
	}

	private void writeMessage(JsonObject message) throws IOException {
		byte[] bytes = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
		OutputStream out = System.out;
		out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.write(bytes);
		out.flush();
	}

	private static String readHeaders(InputStream in) throws IOException {
		ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
		StringBuilder recent = new StringBuilder();
		while (true) {
			int value = in.read();
			if (value == -1) {
				if (headerBytes.size() == 0) {
					return null;
				}
				throw new EOFException("Unexpected EOF while reading MCP headers.");
			}
			headerBytes.write(value);
			recent.append((char) value);
			if (recent.length() > 4) {
				recent.delete(0, recent.length() - 4);
			}
			String tail = recent.toString();
			if ("\r\n\r\n".equals(tail) || tail.endsWith("\n\n")) {
				return headerBytes.toString(StandardCharsets.UTF_8);
			}
		}
	}

	private static int parseContentLength(String headers) {
		for (String line : headers.split("\r\n")) {
			int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			if (!"content-length".equals(name)) {
				continue;
			}
			return Integer.parseInt(line.substring(colon + 1).trim());
		}
		return -1;
	}

	private static String readBody(InputStream in, int length) throws IOException {
		byte[] body = in.readNBytes(length);
		if (body.length != length) {
			throw new EOFException("Unexpected EOF while reading MCP body.");
		}
		return new String(body, StandardCharsets.UTF_8);
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> parsed = new LinkedHashMap<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (!arg.startsWith("--")) {
				continue;
			}
			String key = arg.substring(2);
			String value = (i + 1) < args.length ? args[i + 1] : "";
			if ((i + 1) < args.length && !value.startsWith("--")) {
				parsed.put(key, value);
				i++;
			} else {
				parsed.put(key, "true");
			}
		}
		return parsed;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static String resolveBridgeToken(String explicitToken, String tokenFile, String projectRoot) {
		if (explicitToken != null && !explicitToken.isBlank()) {
			return explicitToken.trim();
		}
		if (tokenFile != null && !tokenFile.isBlank()) {
			String token = readTokenFromGlobalSettings(safePath(tokenFile));
			if (!token.isBlank()) {
				return token;
			}
		}
		for (Path path : discoverGlobalSettingsPaths(projectRoot)) {
			String token = readTokenFromGlobalSettings(path);
			if (!token.isBlank()) {
				return token;
			}
		}
		return "";
	}

	private static synchronized void debugLog(String line) {
		try {
			Files.createDirectories(DEBUG_LOG_PATH.getParent());
			String entry = Instant.now() + " " + line + System.lineSeparator();
			Files.writeString(DEBUG_LOG_PATH, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	private static List<Path> discoverGlobalSettingsPaths(String projectRoot) {
		List<Path> paths = new ArrayList<>();
		Path explicitRoot = safePath(projectRoot);
		if (explicitRoot != null) {
			paths.add(explicitRoot.resolve("run").resolve("ai-settings").resolve("global.json"));
			paths.add(explicitRoot.resolve("run").resolve("run").resolve("ai-settings").resolve("global.json"));
		}
		paths.add(Path.of(System.getProperty("user.dir"), "run", "ai-settings", "global.json"));
		paths.add(Path.of(System.getProperty("user.dir"), "run", "run", "ai-settings", "global.json"));
		Path jarPath = currentExecutablePath();
		if (jarPath != null) {
			Path current = jarPath.toAbsolutePath().getParent();
			for (int i = 0; i < 6 && current != null; i++) {
				paths.add(current.resolve("run").resolve("ai-settings").resolve("global.json"));
				paths.add(current.resolve("run").resolve("run").resolve("ai-settings").resolve("global.json"));
				current = current.getParent();
			}
		}
		List<Path> deduped = new ArrayList<>();
		for (Path path : paths) {
			if (path == null) {
				continue;
			}
			Path normalized = path.normalize().toAbsolutePath();
			if (!deduped.contains(normalized)) {
				deduped.add(normalized);
			}
		}
		return deduped;
	}

	private static Path safePath(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String cleaned = raw.trim();
		if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
			cleaned = cleaned.substring(1, cleaned.length() - 1);
		}
		try {
			return Path.of(cleaned);
		} catch (Exception e) {
			return null;
		}
	}

	private static Path currentExecutablePath() {
		try {
			URI uri = McpSidecarMain.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			return Path.of(uri);
		} catch (Exception e) {
			return null;
		}
	}

	private static String readTokenFromGlobalSettings(Path path) {
		try {
			if (path == null || !Files.exists(path)) {
				return "";
			}
			JsonObject parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (parsed != null && parsed.has("mcpToken") && !parsed.get("mcpToken").isJsonNull()) {
				return parsed.get("mcpToken").getAsString().trim();
			}
		} catch (Exception ignored) {
		}
		return "";
	}

	private static String trimTrailingSlash(String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_BRIDGE_URL;
		}
		String trimmed = value.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}

	private static String getString(JsonObject object, String key, String fallback) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return fallback;
		}
		return object.get(key).getAsString();
	}

	private static JsonObject errorObject(String code, String message) {
		JsonObject root = new JsonObject();
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		root.add("error", error);
		return root;
	}

	private record ToolDef(String name, String description, String method, String path, JsonObject inputSchema, BodyBuilder bodyBuilder) {}

	@FunctionalInterface
	private interface BodyBuilder {
		JsonObject apply(JsonObject arguments);
	}
}
