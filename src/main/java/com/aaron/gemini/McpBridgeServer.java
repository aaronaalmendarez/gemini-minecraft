package com.aaron.gemini;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class McpBridgeServer {
	private static final Gson GSON = new Gson();
	private static final long SERVER_CALL_TIMEOUT_MS = 5_000L;
	private static final long SERVER_TICK_STALE_MS = 2_500L;
	private static final Logger LOGGER = LoggerFactory.getLogger("gemini-ai-companion-mcp");

	private final MinecraftServer minecraftServer;
	private final int port;
	private final Semaphore requestSemaphore = new Semaphore(1);
	private HttpServer httpServer;
	private ExecutorService executor;

	McpBridgeServer(MinecraftServer minecraftServer, int port) {
		this.minecraftServer = minecraftServer;
		this.port = port;
	}

	synchronized void start() throws IOException {
		if (httpServer != null) {
			return;
		}
		httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		executor = Executors.newCachedThreadPool(new BridgeThreadFactory());
		httpServer.setExecutor(executor);
		httpServer.createContext("/v1/health", this::handleHealth);
		httpServer.createContext("/v1/session", authenticated(this::handleSession));
		httpServer.createContext("/v1/tools/inventory", authenticated(exchange -> handlePlayerTool(exchange, body -> MinecraftCapabilityService.inventory(resolvePlayerOrThrow()))));
		httpServer.createContext("/v1/tools/nearby", authenticated(exchange -> handlePlayerTool(exchange, body -> MinecraftCapabilityService.nearbyEntities(resolvePlayerOrThrow()))));
		httpServer.createContext("/v1/tools/blocks", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.scanBlocks(resolvePlayerOrThrow(), stringOr(body, "target", ""), intOr(body, "radius", 24)))));
		httpServer.createContext("/v1/tools/containers", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.scanContainers(resolvePlayerOrThrow(), nullableString(body, "filter"), intOr(body, "radius", 24)))));
		httpServer.createContext("/v1/tools/blockdata", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.blockData(resolvePlayerOrThrow(), buildBlockDataArgs(body)))));
		httpServer.createContext("/v1/tools/players", authenticated(exchange -> handleServerTool(exchange, () -> MinecraftCapabilityService.players(minecraftServer))));
		httpServer.createContext("/v1/tools/stats", authenticated(exchange -> handlePlayerTool(exchange, body -> MinecraftCapabilityService.stats(resolvePlayerOrThrow()))));
		httpServer.createContext("/v1/tools/buildsite", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.buildsite(resolvePlayerOrThrow(), intOr(body, "radius", VoxelBuildPlanner.DEFAULT_SITE_RADIUS)))));
		httpServer.createContext("/v1/tools/recipe", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.recipe(resolvePlayerOrThrow(), stringOr(body, "item", ""), false))));
		httpServer.createContext("/v1/tools/smelt", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.recipe(resolvePlayerOrThrow(), stringOr(body, "item", ""), true))));
		httpServer.createContext("/v1/tools/lookup", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.itemLookup(resolvePlayerOrThrow(), stringOr(body, "target", "mainhand")))));
		httpServer.createContext("/v1/tools/nbt", authenticated(exchange -> handlePlayerTool(exchange, body ->
			MinecraftCapabilityService.itemComponents(resolvePlayerOrThrow(), stringOr(body, "target", "mainhand")))));
		httpServer.createContext("/v1/tools/batch_status", authenticated(this::handleBatchStatus));
		httpServer.createContext("/v1/actions/capture_view", authenticated(this::handleCaptureView));
		httpServer.createContext("/v1/actions/highlight", authenticated(this::handleHighlight));
		httpServer.createContext("/v1/actions/preview_build_plan", authenticated(this::handlePreviewBuildPlan));
		httpServer.createContext("/v1/actions/execute_build_plan", authenticated(this::handleExecuteBuildPlan));
		httpServer.createContext("/v1/actions/execute_commands", authenticated(this::handleExecuteCommands));
		httpServer.createContext("/v1/actions/undo", authenticated(this::handleUndo));
		httpServer.start();
	}

	synchronized void stop() {
		if (httpServer != null) {
			httpServer.stop(0);
			httpServer = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private HttpHandler authenticated(HttpHandler handler) {
		return exchange -> {
			long startedAt = System.currentTimeMillis();
			String path = exchange.getRequestURI() == null ? "(unknown)" : exchange.getRequestURI().getPath();
			boolean acquired = false;
			try {
				if (!isLoopback(exchange)) {
					writeJson(exchange, 403, error("FORBIDDEN", "Bridge only accepts loopback requests."));
					return;
				}
				if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
					writeJson(exchange, 405, error("METHOD_NOT_ALLOWED", "Unsupported request method."));
					return;
				}
				if (!isAuthorized(exchange)) {
					writeJson(exchange, 401, error("UNAUTHORIZED", "Missing or invalid bearer token."));
					return;
				}
				if (!GeminiCompanion.isMcpBridgeEnabled()) {
					writeJson(exchange, 503, error("BRIDGE_DISABLED", "MCP bridge is disabled."));
					return;
				}
				if (!requestSemaphore.tryAcquire()) {
					writeJson(exchange, 429, error("BRIDGE_BUSY", "Another MCP request is already running. Wait for it to finish and try again."));
					return;
				}
				acquired = true;
				assertServerResponsive(path);
				handler.handle(exchange);
				long elapsed = System.currentTimeMillis() - startedAt;
				if (elapsed >= 250L) {
					LOGGER.info("MCP {} completed in {} ms", path, elapsed);
				}
			} catch (BridgeServerUnavailableException e) {
				writeJson(exchange, 503, error("SERVER_UNAVAILABLE", e.getMessage()));
			} catch (BridgeThreadTimeoutException e) {
				writeJson(exchange, 503, error("SERVER_THREAD_TIMEOUT", e.getMessage()));
			} catch (Throwable t) {
				LOGGER.warn("MCP {} failed: {}", path, t.getMessage());
				writeJson(exchange, 500, error("INTERNAL", "Bridge request failed: " + t.getMessage()));
			} finally {
				if (acquired) {
					requestSemaphore.release();
				}
			}
		};
	}

	private void handleHealth(HttpExchange exchange) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("ok", true);
		body.addProperty("enabled", GeminiCompanion.isMcpBridgeEnabled());
		body.addProperty("port", port);
		body.addProperty("loopbackOnly", true);
		body.addProperty("serverReady", minecraftServer != null);
		long lastTickMs = GeminiCompanion.getLastServerTickMs();
		long tickAgeMs = lastTickMs <= 0L ? -1L : Math.max(0L, System.currentTimeMillis() - lastTickMs);
		body.addProperty("serverTickAgeMs", tickAgeMs);
		body.addProperty("serverResponsive", isServerResponsive());
		body.addProperty("busy", requestSemaphore.availablePermits() == 0);
		writeJson(exchange, 200, body);
	}

	private void handleSession(HttpExchange exchange) throws IOException {
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.SessionInfo> result =
			callOnServerThread(() -> MinecraftCapabilityService.session(minecraftServer, GeminiCompanion.isMcpBridgeEnabled(), port));
		writeServiceResult(exchange, result);
	}

	private void handleServerTool(HttpExchange exchange, Supplier<MinecraftCapabilityService.ServiceResult<?>> action) throws IOException {
		MinecraftCapabilityService.ServiceResult<?> result = callOnServerThread(action);
		writeServiceResult(exchange, result);
	}

	private void handlePlayerTool(HttpExchange exchange, ThrowingJsonFunction<MinecraftCapabilityService.ServiceResult<?>> action) throws IOException {
		JsonObject body = readJsonBody(exchange);
		MinecraftCapabilityService.ServiceResult<?> result = callOnServerThread(() -> {
			try {
				return action.apply(body);
			} catch (NoActivePlayerException e) {
				return e.result();
			}
		});
		writeServiceResult(exchange, result);
	}

	private void handleHighlight(HttpExchange exchange) throws IOException {
		JsonObject body = readJsonBody(exchange);
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> playerResult =
			callOnServerThread(() -> MinecraftCapabilityService.resolveActivePlayer(minecraftServer));
		if (playerResult.error() != null) {
			writeServiceResult(exchange, playerResult);
			return;
		}
		List<GeminiCompanion.Highlight> highlights = parseHighlights(body);
		GeminiCompanion.McpActionResult result = callOnServerThread(() ->
			GeminiCompanion.executeMcpHighlights(playerResult.data().serverPlayer(), highlights));
		writeJson(exchange, result.success() ? 200 : 400, GSON.toJsonTree(result).getAsJsonObject());
	}

	private void handleCaptureView(HttpExchange exchange) throws IOException {
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> playerResult =
			callOnServerThread(() -> MinecraftCapabilityService.resolveActivePlayer(minecraftServer));
		if (playerResult.error() != null) {
			writeServiceResult(exchange, playerResult);
			return;
		}
		CompletableFuture<GeminiCompanion.McpVisionCaptureResult> future =
			callOnServerThread(() -> GeminiCompanion.requestMcpCaptureView(playerResult.data().serverPlayer()));
		try {
			GeminiCompanion.McpVisionCaptureResult result = future.get(SERVER_CALL_TIMEOUT_MS + 10_000L, TimeUnit.MILLISECONDS);
			writeJson(exchange, result.success() ? 200 : 400, GSON.toJsonTree(result).getAsJsonObject());
		} catch (TimeoutException e) {
			writeJson(exchange, 503, error("VISION_TIMEOUT", "Client vision capture did not complete in time."));
		} catch (Exception e) {
			writeJson(exchange, 500, error("VISION_FAILED", "Vision capture failed: " + e.getMessage()));
		}
	}

	private void handleExecuteBuildPlan(HttpExchange exchange) throws IOException {
		JsonObject body = readJsonBody(exchange);
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> playerResult =
			callOnServerThread(() -> MinecraftCapabilityService.resolveActivePlayer(minecraftServer));
		if (playerResult.error() != null) {
			writeServiceResult(exchange, playerResult);
			return;
		}
		GeminiCompanion.McpActionResult result = callOnServerThread(() ->
			GeminiCompanion.executeMcpBuildPlan(playerResult.data().serverPlayer(), body));
		writeJson(exchange, result.success() ? 200 : 400, GSON.toJsonTree(result).getAsJsonObject());
	}

	private void handlePreviewBuildPlan(HttpExchange exchange) throws IOException {
		JsonObject body = readJsonBody(exchange);
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> playerResult =
			callOnServerThread(() -> MinecraftCapabilityService.resolveActivePlayer(minecraftServer));
		if (playerResult.error() != null) {
			writeServiceResult(exchange, playerResult);
			return;
		}
		GeminiCompanion.McpActionResult result = callOnServerThread(() ->
			GeminiCompanion.previewMcpBuildPlan(playerResult.data().serverPlayer(), body));
		writeJson(exchange, result.success() ? 200 : 400, GSON.toJsonTree(result).getAsJsonObject());
	}

	private void handleExecuteCommands(HttpExchange exchange) throws IOException {
		JsonObject body = readJsonBody(exchange);
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> playerResult =
			callOnServerThread(() -> MinecraftCapabilityService.resolveActivePlayer(minecraftServer));
		if (playerResult.error() != null) {
			writeServiceResult(exchange, playerResult);
			return;
		}
		List<GeminiCompanion.McpCommandSpec> commands = parseCommandSpecs(body);
		GeminiCompanion.McpActionResult result = callOnServerThread(() ->
			GeminiCompanion.executeMcpCommands(playerResult.data().serverPlayer(), commands));
		writeJson(exchange, result.success() ? 200 : 400, GSON.toJsonTree(result).getAsJsonObject());
	}

	private void handleBatchStatus(HttpExchange exchange) throws IOException {
		JsonObject body = readJsonBody(exchange);
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> playerResult =
			callOnServerThread(() -> MinecraftCapabilityService.resolveActivePlayer(minecraftServer));
		if (playerResult.error() != null) {
			writeServiceResult(exchange, playerResult);
			return;
		}
		String batchId = nullableString(body, "batchId");
		GeminiCompanion.McpBatchStatusResult result = callOnServerThread(() ->
			GeminiCompanion.getMcpBatchStatus(playerResult.data().serverPlayer(), batchId));
		writeJson(exchange, result.success() ? 200 : 400, GSON.toJsonTree(result).getAsJsonObject());
	}

	private void handleUndo(HttpExchange exchange) throws IOException {
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> playerResult =
			callOnServerThread(() -> MinecraftCapabilityService.resolveActivePlayer(minecraftServer));
		if (playerResult.error() != null) {
			writeServiceResult(exchange, playerResult);
			return;
		}
		GeminiCompanion.McpActionResult result = callOnServerThread(() ->
			GeminiCompanion.executeMcpUndo(playerResult.data().serverPlayer()));
		writeJson(exchange, result.success() ? 200 : 400, GSON.toJsonTree(result).getAsJsonObject());
	}

	private ServerPlayerEntity resolvePlayerOrThrow() throws NoActivePlayerException {
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> result =
			MinecraftCapabilityService.resolveActivePlayer(minecraftServer);
		if (result.error() != null || result.data() == null) {
			throw new NoActivePlayerException(result);
		}
		return result.data().serverPlayer();
	}

	private List<GeminiCompanion.McpCommandSpec> parseCommandSpecs(JsonObject body) {
		List<GeminiCompanion.McpCommandSpec> commands = new ArrayList<>();
		if (body == null || !body.has("commands") || !body.get("commands").isJsonArray()) {
			return commands;
		}
		for (JsonElement element : body.getAsJsonArray("commands")) {
			if (element == null || element.isJsonNull()) {
				continue;
			}
			if (element.isJsonPrimitive()) {
				commands.add(new GeminiCompanion.McpCommandSpec(element.getAsString(), 0));
				continue;
			}
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject obj = element.getAsJsonObject();
			String command = stringOr(obj, "command", "");
			int delayTicks = Math.max(0, intOr(obj, "delayTicks", 0));
			if (delayTicks <= 0 && obj.has("delayMs") && obj.get("delayMs").isJsonPrimitive()) {
				double delayMs = obj.get("delayMs").getAsDouble();
				delayTicks = Math.max(0, (int) Math.ceil(delayMs / 50.0));
			}
			commands.add(new GeminiCompanion.McpCommandSpec(command, delayTicks));
		}
		return commands;
	}

	private <T> T callOnServerThread(Supplier<T> supplier) {
		assertServerResponsive("server-thread call");
		if (minecraftServer.isOnThread()) {
			return supplier.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		minecraftServer.execute(() -> {
			try {
				future.complete(supplier.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(SERVER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new BridgeThreadTimeoutException(
				"Minecraft server thread did not respond in time. If this is singleplayer, make sure the world is not paused and keep the game active or opened to LAN while MCP is running.",
				e
			);
		} catch (Exception e) {
			throw new IllegalStateException("Bridge server thread call failed", e);
		}
	}

	private void writeServiceResult(HttpExchange exchange, MinecraftCapabilityService.ServiceResult<?> result) throws IOException {
		if (result == null) {
			writeJson(exchange, 500, error("INTERNAL", "No service result was produced."));
			return;
		}
		if (result.error() != null) {
			writeJson(exchange, 400, error(result.error().code(), result.error().message()));
			return;
		}
		JsonObject json = GSON.toJsonTree(result.data()).getAsJsonObject();
		writeJson(exchange, 200, json);
	}

	private JsonObject readJsonBody(HttpExchange exchange) throws IOException {
		if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			return new JsonObject();
		}
		try (InputStream in = exchange.getRequestBody()) {
			String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (raw.isBlank()) {
				return new JsonObject();
			}
			JsonElement parsed = JsonParser.parseString(raw);
			return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
		}
	}

	private boolean isAuthorized(HttpExchange exchange) {
		String auth = exchange.getRequestHeaders().getFirst("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			return false;
		}
		String token = auth.substring("Bearer ".length()).trim();
		String expected = GeminiCompanion.getMcpBridgeToken();
		return expected != null && !expected.isBlank() && expected.equals(token);
	}

	private boolean isLoopback(HttpExchange exchange) {
		if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
			return false;
		}
		return exchange.getRemoteAddress().getAddress().isLoopbackAddress();
	}

	private void assertServerResponsive(String path) {
		if (minecraftServer == null || !minecraftServer.isRunning()) {
			throw new BridgeServerUnavailableException("Minecraft server is not running.");
		}
		if (minecraftServer.isOnThread()) {
			return;
		}
		if (!minecraftServer.isSingleplayer()) {
			return;
		}
		long lastTickMs = GeminiCompanion.getLastServerTickMs();
		if (lastTickMs <= 0L) {
			return;
		}
		long ageMs = Math.max(0L, System.currentTimeMillis() - lastTickMs);
		if (ageMs > SERVER_TICK_STALE_MS) {
			LOGGER.info("Rejected MCP {} because integrated server tick age is {} ms", path, ageMs);
			throw new BridgeServerUnavailableException(
				"Minecraft's integrated server is not actively ticking right now. Keep the world unpaused and in focus, or open to LAN before using MCP."
			);
		}
	}

	private boolean isServerResponsive() {
		if (minecraftServer == null || !minecraftServer.isRunning()) {
			return false;
		}
		if (!minecraftServer.isSingleplayer()) {
			return true;
		}
		long lastTickMs = GeminiCompanion.getLastServerTickMs();
		if (lastTickMs <= 0L) {
			return false;
		}
		return (System.currentTimeMillis() - lastTickMs) <= SERVER_TICK_STALE_MS;
	}

	private static JsonObject error(String code, String message) {
		JsonObject root = new JsonObject();
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		root.add("error", error);
		return root;
	}

	private static void writeJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static String stringOr(JsonObject obj, String key, String fallback) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		return obj.get(key).getAsString();
	}

	private static String nullableString(JsonObject obj, String key) {
		String value = stringOr(obj, key, "");
		return value.isBlank() ? null : value;
	}

	private static int intOr(JsonObject obj, String key, int fallback) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static String buildBlockDataArgs(JsonObject body) {
		if (body == null) {
			return "";
		}
		if (body.has("x") && body.has("y") && body.has("z")) {
			return body.get("x").getAsInt() + " " + body.get("y").getAsInt() + " " + body.get("z").getAsInt();
		}
		String target = stringOr(body, "target", "");
		int radius = intOr(body, "radius", 24);
		if (target.isBlank()) {
			return "nearest " + radius;
		}
		return "nearest " + target + " " + radius;
	}

	private static List<GeminiCompanion.Highlight> parseHighlights(JsonObject body) {
		List<GeminiCompanion.Highlight> highlights = new ArrayList<>();
		if (body == null || !body.has("highlights") || !body.get("highlights").isJsonArray()) {
			return highlights;
		}
		long now = System.currentTimeMillis();
		JsonArray array = body.getAsJsonArray("highlights");
		for (JsonElement element : array) {
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject obj = element.getAsJsonObject();
			double x = obj.has("x") ? obj.get("x").getAsDouble() : 0.0;
			double y = obj.has("y") ? obj.get("y").getAsDouble() : 0.0;
			double z = obj.has("z") ? obj.get("z").getAsDouble() : 0.0;
			String label = stringOr(obj, "label", "");
			long durationMs = obj.has("durationMs") ? Math.max(500L, obj.get("durationMs").getAsLong()) : 15_000L;
			int color = parseHighlightColor(stringOr(obj, "color", "white"));
			highlights.add(new GeminiCompanion.Highlight(x, y, z, label, color, now + durationMs));
		}
		return highlights;
	}

	private static int parseHighlightColor(String raw) {
		String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "red" -> 0xFFFF5555;
			case "green" -> 0xFF55FF55;
			case "blue" -> 0xFF5555FF;
			case "gold", "yellow" -> 0xFFFFAA00;
			case "purple" -> 0xFFAA00AA;
			case "white", "" -> 0xFFFFFFFF;
			default -> {
				try {
					String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
					int value = Integer.parseUnsignedInt(hex, 16);
					yield hex.length() <= 6 ? (0xFF000000 | value) : value;
				} catch (Exception e) {
					yield 0xFFFFFFFF;
				}
			}
		};
	}

	@FunctionalInterface
	private interface ThrowingJsonFunction<T> {
		T apply(JsonObject body) throws NoActivePlayerException;
	}

	private static final class NoActivePlayerException extends Exception {
		private final MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> result;

		private NoActivePlayerException(MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> result) {
			this.result = result;
		}

		private MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.ActivePlayerContext> result() {
			return result;
		}
	}

	private static final class BridgeThreadTimeoutException extends RuntimeException {
		private BridgeThreadTimeoutException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private static final class BridgeServerUnavailableException extends RuntimeException {
		private BridgeServerUnavailableException(String message) {
			super(message);
		}
	}

	private static final class BridgeThreadFactory implements ThreadFactory {
		private int counter = 1;

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "gemini-mcp-bridge-" + counter++);
			thread.setDaemon(true);
			return thread;
		}
	}
}
