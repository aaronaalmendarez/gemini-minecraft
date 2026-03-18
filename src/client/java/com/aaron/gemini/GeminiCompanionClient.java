package com.aaron.gemini;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.lwjgl.glfw.GLFW;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class GeminiCompanionClient implements ClientModInitializer {
	private static final String KEY_CATEGORY = "key.categories.gemini_ai_companion";
	private static final String KEY_OPEN_CONFIG = "key.gemini_ai_companion.chat_config";
	private static final String KEY_PUSH_TO_TALK = "key.gemini_ai_companion.push_to_talk";
	private KeyBinding openConfigKey;
	private KeyBinding pushToTalkKey;
	private static final Gson GSON = new Gson();
	private static final int VOICE_SAMPLE_RATE = 16000;
	private static final int VOICE_MAX_SECONDS = 30;
	private static final int VOICE_MAX_BYTES = VOICE_SAMPLE_RATE * 2 * VOICE_MAX_SECONDS + 1024;
	private static final int VOICE_MIN_BYTES = 8000;
	private static final int VISION_MAX_WIDTH = 960;
	private static final int VISION_MAX_HEIGHT = 540;
	private static final int VISION_MAX_BYTES = 2_500_000;
	private static final Path CLIENT_CONFIG_PATH = Path.of("config", "gemini-ai-companion-client.json");
	private volatile boolean recording;
	private long recordStartMs;
	private TargetDataLine targetLine;
	private ByteArrayOutputStream audioBuffer;
	private AudioFormat audioFormat;
	private Thread recordThread;
	private String voiceUiLabel;
	private long voiceUiUntilMs;
	private String visionUiLabel;
	private long visionUiUntilMs;
	private static final ClientVoiceConfig VOICE_CONFIG = new ClientVoiceConfig();
	private boolean pendingSettingsSend;
	private final Object levelLock = new Object();
	private final float[] levelSamples = new float[24];
	private int levelIndex;
	private float levelEma;
	private long lastLevelMs;
	private static final List<GeminiCompanion.Highlight> ACTIVE_HIGHLIGHTS = new ArrayList<>();
	private static final BufferAllocator XRAY_ALLOCATOR = new BufferAllocator(1 << 18);

	private static void drawHighlightBox(MatrixStack matrices, VertexConsumer buffer, Vec3d camPos, GeminiCompanion.Highlight h, double eps) {
		matrices.push();
		matrices.translate(h.x() - camPos.x, h.y() - camPos.y, h.z() - camPos.z);
		float r = ((h.colorHex() >> 16) & 0xFF) / 255f;
		float g = ((h.colorHex() >> 8) & 0xFF) / 255f;
		float b = (h.colorHex() & 0xFF) / 255f;
		float a = ((h.colorHex() >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 1.0f;
		}
		WorldRenderer.drawBox(
			matrices,
			buffer,
			0 - eps, 0 - eps, 0 - eps,
			1 + eps, 1 + eps, 1 + eps,
			r, g, b, a
		);
		matrices.pop();
	}

	private static void drawHighlightBoxColored(MatrixStack matrices, VertexConsumer buffer, Vec3d camPos, GeminiCompanion.Highlight h, double eps, float r, float g, float b, float a) {
		matrices.push();
		matrices.translate(h.x() - camPos.x, h.y() - camPos.y, h.z() - camPos.z);
		WorldRenderer.drawBox(
			matrices,
			buffer,
			0 - eps, 0 - eps, 0 - eps,
			1 + eps, 1 + eps, 1 + eps,
			r, g, b, a
		);
		matrices.pop();
	}

	private static float brighten(float channel, float mul, float add) {
		return Math.min(1.0f, channel * mul + add);
	}

	private static boolean isXrayHighlight(GeminiCompanion.Highlight h) {
		int alpha = (h.colorHex() >>> 24) & 0xFF;
		return alpha < 0xFF;
	}

	@Override
	public void onInitializeClient() {
		loadClientConfig();
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			KEY_OPEN_CONFIG,
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			KEY_CATEGORY
		));
		pushToTalkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			KEY_PUSH_TO_TALK,
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_V,
			KEY_CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openConfigKey.wasPressed()) {
				client.setScreen(new ChatConfigScreen());
			}
			handleVoiceKey(client);
			maybeSendSettingsSnapshot(client);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			pendingSettingsSend = true;
		});

		ClientPlayNetworking.registerGlobalReceiver(GeminiCompanion.HighlightsPayloadS2C.ID, (payload, context) -> {
			context.client().execute(() -> {
				ACTIVE_HIGHLIGHTS.addAll(payload.highlights());
			});
		});

		WorldRenderEvents.LAST.register(context -> {
			if (ACTIVE_HIGHLIGHTS.isEmpty()) {
				return;
			}
			long now = System.currentTimeMillis();
			ACTIVE_HIGHLIGHTS.removeIf(h -> now > h.expiryMs());
			if (ACTIVE_HIGHLIGHTS.isEmpty()) {
				return;
			}
			MatrixStack matrices = context.matrixStack();
			VertexConsumerProvider consumers = context.consumers();
			Camera camera = context.camera();
			Vec3d camPos = camera.getPos();
			final double eps = 0.002;
			VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());
			for (GeminiCompanion.Highlight h : ACTIVE_HIGHLIGHTS) {
				if (isXrayHighlight(h)) {
					continue;
				}
				drawHighlightBox(matrices, buffer, camPos, h, eps);
			}

			boolean hasXray = false;
			for (GeminiCompanion.Highlight h : ACTIVE_HIGHLIGHTS) {
				if (isXrayHighlight(h)) {
					hasXray = true;
					break;
				}
			}
			if (hasXray) {
				RenderSystem.disableDepthTest();
				RenderSystem.depthMask(false);
				RenderSystem.enableBlend();
				RenderSystem.defaultBlendFunc();
				RenderSystem.disableCull();
				RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
				RenderSystem.lineWidth(3.0f);
				BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);
				for (GeminiCompanion.Highlight h : ACTIVE_HIGHLIGHTS) {
					if (!isXrayHighlight(h)) {
						continue;
					}
					drawHighlightBox(matrices, builder, camPos, h, eps);
					float r = ((h.colorHex() >> 16) & 0xFF) / 255f;
					float g = ((h.colorHex() >> 8) & 0xFF) / 255f;
					float b = (h.colorHex() & 0xFF) / 255f;
					float glowR = brighten(r, 1.25f, 0.15f);
					float glowG = brighten(g, 1.25f, 0.15f);
					float glowB = brighten(b, 1.25f, 0.15f);
					drawHighlightBoxColored(matrices, builder, camPos, h, eps + 0.035, glowR, glowG, glowB, 0.55f);
				}
				BufferRenderer.drawWithGlobalProgram(builder.end());
				RenderSystem.enableCull();
				RenderSystem.disableBlend();
				RenderSystem.depthMask(true);
				RenderSystem.enableDepthTest();
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("chatvoice")
				.then(ClientCommandManager.literal("on").executes(context -> {
					VOICE_CONFIG.enabled = true;
					saveClientConfig();
					sendClientMessage("Voice input enabled.");
					return 1;
				}))
				.then(ClientCommandManager.literal("off").executes(context -> {
					VOICE_CONFIG.enabled = false;
					saveClientConfig();
					sendClientMessage("Voice input disabled.");
					return 1;
				}))
				.then(ClientCommandManager.literal("status").executes(context -> {
					sendClientMessage("Voice input is " + (VOICE_CONFIG.enabled ? "ON" : "OFF") + ".");
					return 1;
				}))
			);
			dispatcher.register(ClientCommandManager.literal("chat")
				.then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
					.executes(context -> {
						MinecraftClient client = MinecraftClient.getInstance();
						if (client.player != null) {
							forwardChatCommand(client, "chat " + StringArgumentType.getString(context, "text"));
						}
						return 1;
					}))
				.executes(context -> {
					MinecraftClient client = MinecraftClient.getInstance();
					if (client.player != null) {
						forwardChatCommand(client, "chat");
					}
					return 1;
				}));
		});

		ClientPlayNetworking.registerGlobalReceiver(GeminiCompanion.ConfigPayloadS2C.ID, (payload, context) -> {
			JsonObject obj = GSON.fromJson(payload.json(), JsonObject.class);
			boolean debug = obj.get("debug").getAsBoolean();
			boolean sidebar = obj.get("sidebar").getAsBoolean();
			boolean sounds = obj.get("sounds").getAsBoolean();
			String particles = obj.get("particles").getAsString();
			int retries = obj.get("retries").getAsInt();
			String model = obj.get("model").getAsString();
			boolean hasPlayerKey = obj.has("hasPlayerKey") && obj.get("hasPlayerKey").getAsBoolean();
			boolean hasServerKey = obj.has("hasServerKey") && obj.get("hasServerKey").getAsBoolean();
			context.client().execute(() -> {
				ClientConfigState state = ClientConfigState.INSTANCE;
				state.debug = debug;
				state.sidebar = sidebar;
				state.sounds = sounds;
				state.particles = particles;
				state.retries = retries;
				state.model = model;
				state.hasPlayerKey = hasPlayerKey;
				state.hasServerKey = hasServerKey;
				state.synced = true;
				if (context.client().currentScreen instanceof ChatConfigScreen screen) {
					screen.refreshFromState();
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(GeminiCompanion.VisionRequestPayloadS2C.ID, (payload, context) -> {
			context.client().execute(() -> captureAndSendVision(payload.requestId()));
		});

		ClientPlayNetworking.registerGlobalReceiver(GeminiCompanion.SettingsApplyPayloadS2C.ID, (payload, context) -> {
			context.client().execute(() -> applyClientSetting(payload.key(), payload.value()));
		});

		HudRenderCallback.EVENT.register((context, tickDelta) -> renderVoiceOverlay(context));
	}

	private void handleVoiceKey(MinecraftClient client) {
		if (client.player == null) {
			return;
		}
		boolean pressed = pushToTalkKey.isPressed();
		if (pressed && !recording && client.currentScreen == null) {
			if (!VOICE_CONFIG.enabled) {
				voiceUiLabel = "Voice input disabled";
				voiceUiUntilMs = System.currentTimeMillis() + 1500;
				return;
			}
			if (startRecording(client)) {
				sendVoiceState("LISTENING");
			}
		} else if (!pressed && recording) {
			sendVoiceState("IDLE");
			stopRecording(client);
		} else if (recording && System.currentTimeMillis() - recordStartMs >= VOICE_MAX_SECONDS * 1000L) {
			sendVoiceState("IDLE");
			stopRecording(client);
		}
	}

	private boolean startRecording(MinecraftClient client) {
		if (recording) {
			return true;
		}
		audioFormat = new AudioFormat(VOICE_SAMPLE_RATE, 16, 1, true, false);
		try {
			targetLine = openSelectedMicrophone(audioFormat);
			if (targetLine == null) {
				voiceUiLabel = "No microphone detected";
				voiceUiUntilMs = System.currentTimeMillis() + 2000;
				return false;
			}
			targetLine.open(audioFormat);
			targetLine.start();
		} catch (Exception e) {
			voiceUiLabel = "Microphone error";
			voiceUiUntilMs = System.currentTimeMillis() + 2000;
			return false;
		}
		recording = true;
		recordStartMs = System.currentTimeMillis();
		audioBuffer = new ByteArrayOutputStream();
			recordThread = new Thread(() -> {
				byte[] buffer = new byte[4096];
				while (recording && targetLine != null) {
					int count = targetLine.read(buffer, 0, buffer.length);
					if (count > 0) {
						if (audioBuffer.size() + count > VOICE_MAX_BYTES) {
							recording = false;
							break;
						}
						audioBuffer.write(buffer, 0, count);
						updateAudioLevel(buffer, count);
					}
				}
			}, "GeminiAI-VoiceCapture");
		recordThread.setDaemon(true);
		recordThread.start();
		return true;
	}

	private void stopRecording(MinecraftClient client) {
		if (!recording) {
			return;
		}
		recording = false;
		if (targetLine != null) {
			try {
				targetLine.stop();
				targetLine.close();
			} catch (Exception ignored) {
			}
		}
		byte[] pcm = audioBuffer == null ? new byte[0] : audioBuffer.toByteArray();
		if (pcm.length < VOICE_MIN_BYTES) {
			voiceUiLabel = "Recording too short";
			voiceUiUntilMs = System.currentTimeMillis() + 1500;
			return;
		}
		byte[] wav;
		try {
			wav = toWav(pcm, audioFormat);
		} catch (IOException e) {
			voiceUiLabel = "Audio encode failed";
			voiceUiUntilMs = System.currentTimeMillis() + 1500;
			return;
		}
		voiceUiLabel = "Transcribing...";
		voiceUiUntilMs = System.currentTimeMillis() + 2000;
		if (!ClientPlayNetworking.canSend(GeminiCompanion.AudioPayloadC2S.ID)) {
			voiceUiLabel = "Server missing voice support";
			voiceUiUntilMs = System.currentTimeMillis() + 2000;
			return;
		}
		ClientPlayNetworking.send(new GeminiCompanion.AudioPayloadC2S("audio/wav", wav));
	}

	private void updateAudioLevel(byte[] buffer, int length) {
		if (length < 2) {
			return;
		}
		int sampleCount = length / 2;
		double sum = 0.0;
		double peak = 0.0;
		for (int i = 0; i < sampleCount; i++) {
			int lo = buffer[i * 2] & 0xFF;
			int hi = buffer[i * 2 + 1] & 0xFF;
			short sample = (short) ((hi << 8) | lo);
			double norm = sample / 32768.0;
			double abs = Math.abs(norm);
			if (abs > peak) {
				peak = abs;
			}
			sum += norm * norm;
		}
		double rms = Math.sqrt(sum / sampleCount);
		float level = (float) Math.min(1.0, Math.max(rms, peak) * 3.5);
		synchronized (levelLock) {
			levelEma = levelEma * 0.8f + level * 0.2f;
			levelSamples[levelIndex] = levelEma;
			levelIndex = (levelIndex + 1) % levelSamples.length;
			lastLevelMs = System.currentTimeMillis();
		}
	}

	private void sendVoiceState(String state) {
		if (!ClientPlayNetworking.canSend(GeminiCompanion.VoiceStatePayloadC2S.ID)) {
			return;
		}
		ClientPlayNetworking.send(new GeminiCompanion.VoiceStatePayloadC2S(state));
	}

	private void applyClientSetting(String key, String value) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		Path optionsPath = client.runDirectory.toPath().resolve("options.txt");
		String previous = updateOptionFile(optionsPath, key, value);
		reloadGameOptions(client);
		pendingSettingsSend = true;
		String message = previous == null
			? ("Setting " + key + " set to " + value + ".")
			: ("Setting " + key + " changed " + previous + " -> " + value + ".");
		sendClientMessage(message);
		voiceUiLabel = "Setting applied";
		voiceUiUntilMs = System.currentTimeMillis() + 1500;
	}

	private String updateOptionFile(Path path, String key, String value) {
		String previous = null;
		try {
			List<String> lines = Files.exists(path) ? Files.readAllLines(path) : new ArrayList<>();
			boolean updated = false;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (line == null || line.isBlank() || line.startsWith("#")) {
					continue;
				}
				int idx = line.indexOf(':');
				if (idx <= 0) {
					continue;
				}
				String existingKey = line.substring(0, idx).trim();
				if (existingKey.equals(key)) {
					previous = line.substring(idx + 1).trim();
					lines.set(i, key + ":" + value);
					updated = true;
					break;
				}
			}
			if (!updated) {
				lines.add(key + ":" + value);
			}
			Files.write(path, lines);
		} catch (IOException ignored) {
		}
		return previous;
	}

	private void reloadGameOptions(MinecraftClient client) {
		try {
			var method = client.options.getClass().getMethod("load");
			method.invoke(client.options);
		} catch (Exception ignored) {
		}
	}

	private void maybeSendSettingsSnapshot(MinecraftClient client) {
		if (!pendingSettingsSend) {
			return;
		}
		if (client == null || client.player == null) {
			return;
		}
		if (!ClientPlayNetworking.canSend(GeminiCompanion.SettingsPayloadC2S.ID)) {
			return;
		}
		pendingSettingsSend = false;
		JsonObject snapshot = buildSettingsSnapshot(client);
		ClientPlayNetworking.send(new GeminiCompanion.SettingsPayloadC2S(snapshot.toString()));
	}

	private void forwardChatCommand(MinecraftClient client, String command) {
		if (client == null || client.getNetworkHandler() == null) {
			return;
		}
		if (ClientPlayNetworking.canSend(GeminiCompanion.ChatForwardPayloadC2S.ID)) {
			ClientPlayNetworking.send(new GeminiCompanion.ChatForwardPayloadC2S(command));
			return;
		}
		Object handler = client.getNetworkHandler();
		if (invokeSendChatCommand(handler, command)) {
			return;
		}
		sendClientMessage("Unable to forward /chat command to server (missing mod).");
	}

	private boolean invokeSendChatCommand(Object handler, String command) {
		try {
			Method method = handler.getClass().getMethod("sendChatCommand", String.class);
			method.invoke(handler, command);
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}

	private boolean sendCommandPacket(Object handler, String command) {
		try {
			Class<?> packetClass;
			try {
				packetClass = Class.forName("net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket");
			} catch (ClassNotFoundException ignored) {
				packetClass = Class.forName("net.minecraft.network.packet.c2s.play.ChatCommandC2SPacket");
			}
			Constructor<?> ctor = packetClass.getConstructor(String.class);
			Object packet = ctor.newInstance(command);
			Class<?> packetType = Class.forName("net.minecraft.network.packet.Packet");
			Method sendPacket = handler.getClass().getMethod("sendPacket", packetType);
			sendPacket.invoke(handler, packet);
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}

	private JsonObject buildSettingsSnapshot(MinecraftClient client) {
		Map<String, String> settings = new LinkedHashMap<>();
		Map<String, String> controls = new LinkedHashMap<>();
		Path optionsPath = client.runDirectory.toPath().resolve("options.txt");
		Map<String, String> options = readOptionsFile(optionsPath);

		for (var entry : options.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (key.startsWith("key_")) {
				controls.put(key, value);
			} else {
				settings.put(key, value);
			}
		}

		JsonObject root = new JsonObject();
		root.add("video", mapToJson(settings));
		root.add("controls", mapToJson(controls));
		root.addProperty("updatedMs", System.currentTimeMillis());
		return root;
	}

	private Map<String, String> readOptionsFile(Path path) {
		Map<String, String> map = new LinkedHashMap<>();
		if (!Files.exists(path)) {
			return map;
		}
		try {
			for (String line : Files.readAllLines(path)) {
				if (line == null || line.isBlank() || line.startsWith("#")) {
					continue;
				}
				int idx = line.indexOf(':');
				if (idx <= 0 || idx >= line.length() - 1) {
					continue;
				}
				String key = line.substring(0, idx).trim();
				String value = line.substring(idx + 1).trim();
				map.put(key, value);
			}
		} catch (IOException ignored) {
		}
		return map;
	}

	private JsonObject mapToJson(Map<String, String> map) {
		JsonObject obj = new JsonObject();
		for (var entry : map.entrySet()) {
			obj.addProperty(entry.getKey(), entry.getValue());
		}
		return obj;
	}

	private byte[] toWav(byte[] pcm, AudioFormat format) throws IOException {
		try (ByteArrayInputStream input = new ByteArrayInputStream(pcm);
			 AudioInputStream audioStream = new AudioInputStream(input, format, pcm.length / format.getFrameSize());
			 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, output);
			return output.toByteArray();
		}
	}

	private void renderVoiceOverlay(DrawContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean showVoice = voiceUiLabel != null && now <= voiceUiUntilMs;
		boolean showVision = visionUiLabel != null && now <= visionUiUntilMs;
		if (!recording && !showVoice && !showVision) {
			return;
		}
		String label = null;
		int accent = 0xFF6BB5FF;
		if (recording) {
			label = "LISTENING";
			accent = 0xFF4CAF50;
		} else if (showVision) {
			label = visionUiLabel;
			accent = 0xFFB388FF;
		} else if (showVoice) {
			label = voiceUiLabel;
			if (label != null && label.toLowerCase(Locale.ROOT).contains("transcrib")) {
				label = "TRANSCRIBING";
				accent = 0xFF6BB5FF;
			}
		}
		if (label != null) {
			int[] pill = drawStatusPill(context, client, label, accent);
			if (recording) {
				drawRecordingTimerBar(context, client, now, pill[0], pill[1], pill[2]);
				drawAudioWaveform(context, client, now, pill[0], pill[1], pill[2]);
			}
		}
	}

	private int[] drawStatusPill(DrawContext context, MinecraftClient client, String label, int accent) {
		int x = 10;
		int y = 8;
		int textWidth = client.textRenderer.getWidth(label);
		int pillWidth = textWidth + 34;
		context.fill(x, y, x + pillWidth, y + 18, 0xCC121926);
		context.drawBorder(x, y, pillWidth, 18, 0xFF2E3A52);
		context.fill(x + 8, y + 6, x + 12, y + 10, accent);
		context.drawTextWithShadow(client.textRenderer, Text.literal(label), x + 16, y + 5, 0xFFE6EAF2);
		return new int[] { x, y, pillWidth };
	}

	private void drawRecordingTimerBar(DrawContext context, MinecraftClient client, long now, int pillX, int pillY, int pillWidth) {
		long elapsed = now - recordStartMs;
		float progress = Math.min(1f, elapsed / (float) (VOICE_MAX_SECONDS * 1000L));
		String timer = formatTime(elapsed) + " / 00:30";
		int barX = pillX;
		int barY = pillY + 20;
		int barW = pillWidth;
		context.fill(barX, barY, barX + barW, barY + 8, 0xCC0F1622);
		context.drawBorder(barX, barY, barW, 8, 0xFF2E3A52);
		context.fill(barX + 1, barY + 1, barX + 1 + (int) ((barW - 2) * progress), barY + 7, 0xFF4CAF50);
		int textX = barX + (barW - client.textRenderer.getWidth(timer)) / 2;
		context.drawTextWithShadow(client.textRenderer, Text.literal(timer), textX, barY + 10, 0xFF9BE7C4);
	}

	private void drawAudioWaveform(DrawContext context, MinecraftClient client, long now, int pillX, int pillY, int pillWidth) {
		float[] snapshot = new float[levelSamples.length];
		int index;
		long lastMs;
		synchronized (levelLock) {
			System.arraycopy(levelSamples, 0, snapshot, 0, levelSamples.length);
			index = levelIndex;
			lastMs = lastLevelMs;
		}
		boolean noSignal = now - lastMs > 600L;
		int bars = 12;
		int barWidth = Math.max(2, (pillWidth - 6) / bars);
		int startX = pillX + 3;
		int startY = pillY + 40;
		int height = 12;
		context.fill(startX - 2, startY - 2, startX + pillWidth - 1, startY + height + 2, 0xAA0F1622);
		context.drawBorder(startX - 2, startY - 2, pillWidth - 1, height + 4, 0xFF2E3A52);
		if (noSignal) {
			context.drawTextWithShadow(client.textRenderer, Text.literal("No signal"), startX + 6, startY + height + 4, 0xFF9AA3AF);
			return;
		}
		for (int i = 0; i < bars; i++) {
			int sampleIndex = (index - 1 - i);
			while (sampleIndex < 0) {
				sampleIndex += snapshot.length;
			}
			float level = snapshot[sampleIndex];
			level = Math.max(0.05f, level);
			int barHeight = Math.max(2, (int) (level * height));
			int x = startX + i * barWidth;
			int y = startY + height - barHeight;
			int color = colorForLevel(level);
			context.fill(x, y, x + barWidth - 1, startY + height, color);
		}
	}

	private int colorForLevel(float level) {
		if (level < 0.2f) {
			return 0xFF4FC3F7;
		}
		if (level < 0.5f) {
			return 0xFF29B6F6;
		}
		if (level < 0.75f) {
			return 0xFFFFB74D;
		}
		return 0xFFFF5252;
	}

	private void captureAndSendVision(long requestId) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.getFramebuffer() == null) {
			return;
		}
		visionUiLabel = "Capturing...";
		visionUiUntilMs = System.currentTimeMillis() + 1500;
		String lookAt = buildLookAtContext(client);
		NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
		if (image == null) {
			visionUiLabel = "Vision failed";
			visionUiUntilMs = System.currentTimeMillis() + 2000;
			return;
		}
		NativeImage working = image;
		try {
			working = scaleToFit(working, VISION_MAX_WIDTH, VISION_MAX_HEIGHT);
			byte[] png = encodePng(working);
			while (png.length > VISION_MAX_BYTES && working.getWidth() > 240 && working.getHeight() > 135) {
				NativeImage smaller = scaleToFit(working, (int) (working.getWidth() * 0.75), (int) (working.getHeight() * 0.75));
				if (working != image) {
					working.close();
				}
				working = smaller;
				png = encodePng(working);
			}
			if (!ClientPlayNetworking.canSend(GeminiCompanion.VisionPayloadC2S.ID)) {
				visionUiLabel = "Vision unsupported";
				visionUiUntilMs = System.currentTimeMillis() + 2000;
				return;
			}
			ClientPlayNetworking.send(new GeminiCompanion.VisionPayloadC2S(requestId, "image/png", lookAt, png));
			visionUiLabel = "ANALYZING";
			visionUiUntilMs = System.currentTimeMillis() + 3500;
		} catch (Exception ignored) {
			visionUiLabel = "Vision failed";
			visionUiUntilMs = System.currentTimeMillis() + 2000;
		} finally {
			if (working != null) {
				working.close();
			}
			if (image != null && image != working) {
				image.close();
			}
		}
	}

	private String buildLookAtContext(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null) {
			return "";
		}
		HitResult hit = client.crosshairTarget;
		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			return "";
		}
		if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
			BlockPos pos = blockHit.getBlockPos();
			String blockId = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
			return "Looking at block " + blockId + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
		}
		if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			String entityId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
			return "Looking at entity " + entityId + " @ " +
				String.format(Locale.ROOT, "%.1f,%.1f,%.1f", entity.getX(), entity.getY(), entity.getZ());
		}
		return "";
	}

	private NativeImage scaleToFit(NativeImage source, int maxWidth, int maxHeight) {
		int width = source.getWidth();
		int height = source.getHeight();
		if (width <= maxWidth && height <= maxHeight) {
			return source;
		}
		double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
		int newWidth = Math.max(1, (int) Math.round(width * scale));
		int newHeight = Math.max(1, (int) Math.round(height * scale));
		NativeImage scaled = new NativeImage(newWidth, newHeight, false);
		for (int y = 0; y < newHeight; y++) {
			int srcY = (int) Math.min(height - 1, Math.round(y / scale));
			for (int x = 0; x < newWidth; x++) {
				int srcX = (int) Math.min(width - 1, Math.round(x / scale));
				scaled.setColor(x, y, source.getColor(srcX, srcY));
			}
		}
		return scaled;
	}

	private byte[] encodePng(NativeImage image) throws IOException {
		int width = image.getWidth();
		int height = image.getHeight();
		BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int abgr = image.getColor(x, y);
				int a = (abgr >>> 24) & 0xFF;
				int b = (abgr >>> 16) & 0xFF;
				int g = (abgr >>> 8) & 0xFF;
				int r = abgr & 0xFF;
				int argb = (a << 24) | (r << 16) | (g << 8) | b;
				buffered.setRGB(x, y, argb);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(buffered, "png", out);
		return out.toByteArray();
	}

	private void drawGradientText(DrawContext context, String text, int x, int y, int offset) {
		MinecraftClient client = MinecraftClient.getInstance();
		int advance = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			float hue = ((offset + i * 6) % 360) / 360f;
			int rgb = Color.HSBtoRGB(hue, 0.75f, 1f) & 0xFFFFFF;
			int color = 0xFF000000 | rgb;
			context.drawTextWithShadow(client.textRenderer, Text.literal(String.valueOf(ch)), x + advance, y, color);
			advance += client.textRenderer.getWidth(String.valueOf(ch));
		}
	}

	private String formatTime(long elapsedMs) {
		long totalSeconds = Math.min(VOICE_MAX_SECONDS, elapsedMs / 1000L);
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		return String.format("%02d:%02d", minutes, seconds);
	}

	private TargetDataLine openSelectedMicrophone(AudioFormat format) {
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		String preferred = VOICE_CONFIG.micName;
		if (preferred != null && !preferred.isBlank()) {
			for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
				if (!preferred.equals(mixerInfo.getName())) {
					continue;
				}
				Mixer mixer = AudioSystem.getMixer(mixerInfo);
				if (mixer.isLineSupported(info)) {
					try {
						return (TargetDataLine) mixer.getLine(info);
					} catch (Exception ignored) {
					}
				}
			}
		}
		if (!AudioSystem.isLineSupported(info)) {
			return null;
		}
		try {
			return (TargetDataLine) AudioSystem.getLine(info);
		} catch (Exception e) {
			return null;
		}
	}

	private static List<String> listInputDevices(AudioFormat format) {
		List<String> devices = new ArrayList<>();
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
			Mixer mixer = AudioSystem.getMixer(mixerInfo);
			if (mixer.isLineSupported(info)) {
				devices.add(mixerInfo.getName());
			}
		}
		return devices;
	}

	private static void loadClientConfig() {
		try {
			if (!Files.exists(CLIENT_CONFIG_PATH)) {
				return;
			}
			String json = Files.readString(CLIENT_CONFIG_PATH);
			JsonObject obj = GSON.fromJson(json, JsonObject.class);
			if (obj != null && obj.has("micName")) {
				VOICE_CONFIG.micName = obj.get("micName").getAsString();
			}
			if (obj != null && obj.has("voiceEnabled")) {
				VOICE_CONFIG.enabled = obj.get("voiceEnabled").getAsBoolean();
			}
		} catch (Exception ignored) {
		}
	}

	private static void saveClientConfig() {
		try {
			Files.createDirectories(CLIENT_CONFIG_PATH.getParent());
			JsonObject obj = new JsonObject();
			obj.addProperty("micName", VOICE_CONFIG.micName == null ? "" : VOICE_CONFIG.micName);
			obj.addProperty("voiceEnabled", VOICE_CONFIG.enabled);
			Files.writeString(CLIENT_CONFIG_PATH, GSON.toJson(obj));
		} catch (Exception ignored) {
		}
	}

	private static void sendClientMessage(String message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		client.player.sendMessage(Text.literal(message), false);
	}

	private static final class ClientVoiceConfig {
		private String micName;
		private boolean enabled = true;
	}

	private static final class ChatConfigScreen extends Screen {
		private static final int BUTTON_WIDTH = 110;
		private static final int BUTTON_HEIGHT = 18;
		private static final int SMALL_BUTTON_WIDTH = 24;
		private static final int SAVE_BUTTON_WIDTH = 50;
		private static final int PANEL_WIDTH = 310;
		private static final int PANEL_HEIGHT = 270;
		private static final int SECTION_GAP = 18;
		private static final int HEADER_HEIGHT = 12;
		private static final int ROW_GAP = 18;
		private static final int ANIM_DURATION_MS = 150;
		private static final int COLOR_PANEL_BG = 0x191F2B;
		private static final int COLOR_PANEL_BORDER = 0x2E3A52;
		private int panelX;
		private int panelY;
		private int settingsHeaderY;
		private int actionsHeaderY;
		private int keysHeaderY;
		private int retriesRowY;
		private int keyStatusY;
		private long openTimeMs;
		private String toastMessage;
		private long toastExpiryMs;
		private ButtonWidget debugButton;
		private ButtonWidget sidebarButton;
		private ButtonWidget soundsButton;
		private ButtonWidget particlesButton;
		private ButtonWidget modelButton;
		private ButtonWidget voiceButton;
		private ButtonWidget micButton;
		private ButtonWidget retriesMinus;
		private ButtonWidget retriesPlus;
		private TextFieldWidget playerKeyField;
		private TextFieldWidget serverKeyField;
		private final List<ClickableWidget> baseWidgets = new ArrayList<>();
		private final Map<ButtonWidget, ButtonStyle> buttonStyles = new HashMap<>();
		private final List<String> micDropdownItems = new ArrayList<>();
		private List<String> micDevices = new ArrayList<>();
		private boolean micDropdownOpen;
		private int micDropdownX;
		private int micDropdownY;
		private int micDropdownWidth;
		private int micDropdownHeight;
		private int lastMouseX;
		private int lastMouseY;

		private ChatConfigScreen() {
			super(Text.literal("Chat AI Config"));
		}

		@Override
		protected void init() {
			openTimeMs = System.currentTimeMillis();
			panelX = (this.width - PANEL_WIDTH) / 2;
			panelY = (this.height - PANEL_HEIGHT) / 2;
			int leftCol = panelX + 16;
			int rightCol = panelX + PANEL_WIDTH - BUTTON_WIDTH - 16;
			int row = panelY + 28;

			settingsHeaderY = row;
			row += HEADER_HEIGHT + 6;
			debugButton = addStyledButton(leftCol, row, "Debug", this::toggleDebug, ButtonStyle.TOGGLE_ON);
			registerBaseWidget(debugButton);
			sidebarButton = addStyledButton(rightCol, row, "Sidebar", this::toggleSidebar, ButtonStyle.TOGGLE_ON);
			registerBaseWidget(sidebarButton);
			row += ROW_GAP;
			soundsButton = addStyledButton(leftCol, row, "Sounds", this::toggleSounds, ButtonStyle.TOGGLE_ON);
			registerBaseWidget(soundsButton);
			particlesButton = addStyledButton(rightCol, row, "Particles", this::cycleParticles, ButtonStyle.ACTION);
			registerBaseWidget(particlesButton);
			row += ROW_GAP;
			voiceButton = addStyledButton(leftCol, row, "Voice", this::toggleVoice, ButtonStyle.TOGGLE_ON);
			registerBaseWidget(voiceButton);
			modelButton = addStyledButton(rightCol, row, "Model", this::cycleModel, ButtonStyle.ACTION);
			registerBaseWidget(modelButton);
			row += ROW_GAP;
			micButton = addStyledButton(rightCol, row, "Microphone", this::toggleMicDropdown, ButtonStyle.ACTION);
			registerBaseWidget(micButton);
			row += ROW_GAP;
			retriesRowY = row;
			retriesMinus = addSmallStyledButton(panelX + PANEL_WIDTH / 2 - 40, row, SMALL_BUTTON_WIDTH, "-", () -> adjustRetries(-1), ButtonStyle.ACTION);
			registerBaseWidget(retriesMinus);
			retriesPlus = addSmallStyledButton(panelX + PANEL_WIDTH / 2 + 18, row, SMALL_BUTTON_WIDTH, "+", () -> adjustRetries(1), ButtonStyle.ACTION);
			registerBaseWidget(retriesPlus);

			row += SECTION_GAP;
			actionsHeaderY = row;
			row += HEADER_HEIGHT + 6;
			registerBaseWidget(addStyledButton(leftCol, row, "History", () -> sendCommand("/chat history"), ButtonStyle.ACTION));
			registerBaseWidget(addStyledButton(rightCol, row, "Export", () -> sendCommand("/chat export 5 txt"), ButtonStyle.ACTION));
			row += ROW_GAP;
			registerBaseWidget(addStyledButton(leftCol, row, "Smart Retry", () -> sendCommand("/chat smarter"), ButtonStyle.ACTION));
			registerBaseWidget(addStyledButton(rightCol, row, "Cancel", () -> sendCommand("/chat cancel"), ButtonStyle.DANGER));

			row += SECTION_GAP;
			keysHeaderY = row;
			row += HEADER_HEIGHT + 6;
			int fieldWidth = 160;
			playerKeyField = new TextFieldWidget(this.textRenderer, leftCol, row, fieldWidth, BUTTON_HEIGHT, Text.literal("Player Key"));
			playerKeyField.setPlaceholder(Text.literal("Player API key"));
			playerKeyField.setMaxLength(128);
			addSelectableChild(playerKeyField);
			addDrawableChild(playerKeyField);
			registerBaseWidget(playerKeyField);
			registerBaseWidget(addSmallStyledButton(leftCol + fieldWidth + 6, row, SAVE_BUTTON_WIDTH, "Save", () -> saveKey(7, playerKeyField.getText()), ButtonStyle.ACTION));

			row += ROW_GAP;
			serverKeyField = new TextFieldWidget(this.textRenderer, leftCol, row, fieldWidth, BUTTON_HEIGHT, Text.literal("Server Key"));
			serverKeyField.setPlaceholder(Text.literal("Server default key"));
			serverKeyField.setMaxLength(128);
			addSelectableChild(serverKeyField);
			addDrawableChild(serverKeyField);
			registerBaseWidget(serverKeyField);
			registerBaseWidget(addSmallStyledButton(leftCol + fieldWidth + 6, row, SAVE_BUTTON_WIDTH, "Save", () -> saveKey(8, serverKeyField.getText()), ButtonStyle.ACTION));
			keyStatusY = row + ROW_GAP;

			registerBaseWidget(addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
				.dimensions(panelX + (PANEL_WIDTH - BUTTON_WIDTH) / 2, panelY + PANEL_HEIGHT - 22, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build()));

			requestSync();
			refreshFromState();
		}

		private ButtonWidget addStyledButton(int x, int y, String label, Runnable action, ButtonStyle style) {
			ButtonWidget button = ButtonWidget.builder(Text.literal(label), btn -> action.run())
				.dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build();
			buttonStyles.put(button, style);
			addDrawableChild(button);
			return button;
		}

		private ButtonWidget addSmallStyledButton(int x, int y, int width, String label, Runnable action, ButtonStyle style) {
			ButtonWidget button = ButtonWidget.builder(Text.literal(label), btn -> action.run())
				.dimensions(x, y, width, BUTTON_HEIGHT)
				.build();
			buttonStyles.put(button, style);
			addDrawableChild(button);
			return button;
		}

		private enum ButtonStyle {
			TOGGLE_ON(0xFF2D5A3D, 0xFF3D7A4D),
			TOGGLE_OFF(0xFF5A2D2D, 0xFF7A3D3D),
			ACTION(0xFF2D3A5A, 0xFF3D4A7A),
			DANGER(0xFF5A3A2D, 0xFF7A4A3D);

			private final int fill;
			private final int border;

			ButtonStyle(int fill, int border) {
				this.fill = fill;
				this.border = border;
			}
		}

		private void adjustRetries(int delta) {
			int next = Math.max(0, Math.min(10, ClientConfigState.INSTANCE.retries + delta));
			sendConfigUpdate(5, obj -> obj.addProperty("value", next));
			showToast("Saved");
		}

		private void toggleBoolean(int type, boolean value) {
			sendConfigUpdate(type, obj -> obj.addProperty("value", value));
			showToast("Saved");
		}

		private void toggleDebug() {
			toggleBoolean(1, !ClientConfigState.INSTANCE.debug);
		}

		private void toggleSidebar() {
			toggleBoolean(2, !ClientConfigState.INSTANCE.sidebar);
		}

		private void toggleSounds() {
			toggleBoolean(3, !ClientConfigState.INSTANCE.sounds);
		}

		private void toggleVoice() {
			VOICE_CONFIG.enabled = !VOICE_CONFIG.enabled;
			saveClientConfig();
			showToast(VOICE_CONFIG.enabled ? "Voice enabled" : "Voice disabled");
			refreshFromState();
		}

		private void cycleParticles() {
			String current = ClientConfigState.INSTANCE.particles;
			String next = switch (current) {
				case "on" -> "minimal";
				case "minimal" -> "off";
				default -> "on";
			};
			sendConfigUpdate(4, obj -> obj.addProperty("value", next));
			showToast("Saved");
		}

		private void cycleModel() {
			String current = ClientConfigState.INSTANCE.model;
			String next = switch (current) {
				case "flash" -> "flash-thinking";
				case "flash-thinking" -> "pro";
				case "pro" -> "auto";
				default -> "flash";
			};
			sendConfigUpdate(6, obj -> obj.addProperty("value", next));
			showToast("Saved");
		}

		private void toggleMicDropdown() {
			micDropdownOpen = !micDropdownOpen;
			if (micDropdownOpen) {
				refreshMicDevices();
				buildMicDropdownButtons();
			} else {
				clearMicDropdown();
			}
		}

		private void refreshMicDevices() {
			AudioFormat format = new AudioFormat(VOICE_SAMPLE_RATE, 16, 1, true, false);
			micDevices = listInputDevices(format);
			if (micDevices.isEmpty()) {
				micDevices = List.of();
			}
		}

		private void buildMicDropdownButtons() {
			clearMicDropdown();
			int maxItems = 8;
			micDropdownItems.add("Default");
			for (String device : micDevices) {
				if (micDropdownItems.size() >= maxItems) {
					break;
				}
				micDropdownItems.add(device);
			}
			micDropdownWidth = BUTTON_WIDTH;
			micDropdownX = micButton.getX();
			micDropdownHeight = micDropdownItems.size() * (BUTTON_HEIGHT + 2) + 4;
			int belowY = micButton.getY() + BUTTON_HEIGHT + 4;
			int aboveY = micButton.getY() - micDropdownHeight - 4;
			int panelBottom = panelY + PANEL_HEIGHT - 40;
			micDropdownY = (belowY + micDropdownHeight <= panelBottom) ? belowY : Math.max(panelY + 20, aboveY);
		}

		private void clearMicDropdown() {
			micDropdownItems.clear();
			micDropdownHeight = 0;
		}

		private void selectMicrophone(String device) {
			if ("Default".equals(device)) {
				VOICE_CONFIG.micName = "";
			} else {
				VOICE_CONFIG.micName = device;
			}
			saveClientConfig();
			showToast("Mic set");
			micDropdownOpen = false;
			clearMicDropdown();
			refreshFromState();
		}

		private void saveKey(int type, String value) {
			if (value == null || value.isBlank()) {
				return;
			}
			sendConfigUpdate(type, obj -> obj.addProperty("value", value.trim()));
			showToast("Key saved");
		}

		private void sendCommand(String command) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null) {
				return;
			}
			client.player.networkHandler.sendCommand(command.startsWith("/") ? command.substring(1) : command);
		}

		private void requestSync() {
			sendConfigUpdate(0, obj -> {});
		}

		private void sendConfigUpdate(int type, java.util.function.Consumer<JsonObject> writer) {
			if (!ClientPlayNetworking.canSend(GeminiCompanion.ConfigPayloadC2S.ID)) {
				return;
			}
			JsonObject obj = new JsonObject();
			obj.addProperty("type", type);
			writer.accept(obj);
			ClientPlayNetworking.send(new GeminiCompanion.ConfigPayloadC2S(obj.toString()));
		}

		private void refreshFromState() {
			ClientConfigState state = ClientConfigState.INSTANCE;
			if (debugButton != null) {
				debugButton.setMessage(formatToggle("Debug", state.debug));
				buttonStyles.put(debugButton, state.debug ? ButtonStyle.TOGGLE_ON : ButtonStyle.TOGGLE_OFF);
			}
			if (sidebarButton != null) {
				sidebarButton.setMessage(formatToggle("Sidebar", state.sidebar));
				buttonStyles.put(sidebarButton, state.sidebar ? ButtonStyle.TOGGLE_ON : ButtonStyle.TOGGLE_OFF);
			}
			if (soundsButton != null) {
				soundsButton.setMessage(formatToggle("Sounds", state.sounds));
				buttonStyles.put(soundsButton, state.sounds ? ButtonStyle.TOGGLE_ON : ButtonStyle.TOGGLE_OFF);
			}
			if (particlesButton != null) {
				particlesButton.setMessage(Text.literal("Particles: " + state.particles));
			}
			if (modelButton != null) {
				modelButton.setMessage(Text.literal("Model: " + state.model));
			}
			if (voiceButton != null) {
				voiceButton.setMessage(formatToggle("Voice", VOICE_CONFIG.enabled));
				buttonStyles.put(voiceButton, VOICE_CONFIG.enabled ? ButtonStyle.TOGGLE_ON : ButtonStyle.TOGGLE_OFF);
			}
			if (micButton != null) {
				String label = VOICE_CONFIG.micName == null || VOICE_CONFIG.micName.isBlank() ? "Default" : VOICE_CONFIG.micName;
				micButton.setMessage(Text.literal("Mic: " + shortenLabel(label, 16)));
			}
		}

		private Text formatToggle(String label, boolean enabled) {
			String state = enabled ? "ON" : "OFF";
			Formatting color = enabled ? Formatting.GREEN : Formatting.RED;
			return Text.literal(label + ": ").append(Text.literal(state).formatted(color));
		}

		@Override
		public void render(DrawContext context, int mouseX, int mouseY, float delta) {
			lastMouseX = mouseX;
			lastMouseY = mouseY;
			renderBackground(context, mouseX, mouseY, delta);
			long now = System.currentTimeMillis();
			float progress = Math.min(1f, (now - openTimeMs) / (float) ANIM_DURATION_MS);
			float alpha = 0.6f + 0.4f * progress;
			int panelAlpha = (int) (0xD0 * alpha);
			context.fillGradient(0, 0, this.width, this.height, 0xB00E1018, 0xB0101A2B);
			context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, (panelAlpha << 24) | COLOR_PANEL_BG);
			context.drawBorder(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF2E3A52);
			context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, panelY + 8, 0xFFE6EAF2);
			drawLiveInfo(context);
			drawSectionHeader(context, "SETTINGS", settingsHeaderY);
			drawSectionHeader(context, "ACTIONS", actionsHeaderY);
			drawSectionHeader(context, "API KEYS", keysHeaderY);
			drawRetriesControl(context);
			drawKeyStatus(context);
			drawToast(context, now);
			drawButtonStyles(context);
			super.render(context, mouseX, mouseY, delta);
			drawMicDropdownBackdrop(context);
		}

		private void drawMicDropdownBackdrop(DrawContext context) {
			if (!micDropdownOpen || micDropdownItems.isEmpty()) {
				return;
			}
			context.getMatrices().push();
			context.getMatrices().translate(0, 0, 200);
			context.fill(micDropdownX - 4, micDropdownY - 4, micDropdownX + micDropdownWidth + 4, micDropdownY + micDropdownHeight + 2, 0xFF0F1622);
			context.drawBorder(micDropdownX - 4, micDropdownY - 4, micDropdownWidth + 8, micDropdownHeight + 6, 0xFF2E3A52);
			int y = micDropdownY;
			for (int i = 0; i < micDropdownItems.size(); i++) {
				String device = micDropdownItems.get(i);
				String label = shortenLabel(device, 18);
				boolean hover = mouseOverRect(micDropdownX, y, micDropdownWidth, BUTTON_HEIGHT, lastMouseX, lastMouseY);
				int fill = hover ? 0xFF2D3A5A : 0xFF1E2736;
				context.fill(micDropdownX, y, micDropdownX + micDropdownWidth, y + BUTTON_HEIGHT, fill);
				context.drawBorder(micDropdownX, y, micDropdownWidth, BUTTON_HEIGHT, 0xFF3D4A7A);
				context.drawTextWithShadow(textRenderer, Text.literal(label), micDropdownX + 8, y + 6, 0xFFE6EAF2);
				y += BUTTON_HEIGHT + 2;
			}
			context.getMatrices().pop();
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (micDropdownOpen) {
				boolean inside = mouseX >= micDropdownX - 4
					&& mouseX <= micDropdownX + micDropdownWidth + 4
					&& mouseY >= micDropdownY - 4
					&& mouseY <= micDropdownY + micDropdownHeight + 4;
				boolean onMicButton = micButton != null
					&& mouseX >= micButton.getX()
					&& mouseX <= micButton.getX() + micButton.getWidth()
					&& mouseY >= micButton.getY()
					&& mouseY <= micButton.getY() + micButton.getHeight();
				if (inside) {
					handleMicDropdownClick(mouseX, mouseY);
					return true;
				}
				if (!onMicButton) {
					micDropdownOpen = false;
					clearMicDropdown();
					return true;
				}
				return super.mouseClicked(mouseX, mouseY, button);
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}

		private void registerBaseWidget(ClickableWidget widget) {
			if (widget == null) {
				return;
			}
			baseWidgets.add(widget);
		}

		private void setBaseWidgetsVisible(boolean visible) {
			for (ClickableWidget widget : baseWidgets) {
				if (widget == micButton) {
					continue;
				}
				widget.visible = visible;
				widget.active = visible;
			}
		}

		private void handleMicDropdownClick(double mouseX, double mouseY) {
			int y = micDropdownY;
			for (String device : micDropdownItems) {
				if (mouseX >= micDropdownX && mouseX <= micDropdownX + micDropdownWidth
					&& mouseY >= y && mouseY <= y + BUTTON_HEIGHT) {
					selectMicrophone(device);
					return;
				}
				y += BUTTON_HEIGHT + 2;
			}
		}

		private boolean mouseOverRect(int x, int y, int width, int height, int mouseX, int mouseY) {
			return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
		}

		private String shortenLabel(String label, int max) {
			if (label == null) {
				return "";
			}
			if (label.length() <= max) {
				return label;
			}
			return label.substring(0, Math.max(0, max - 1)) + "…";
		}

		private void drawButtonStyles(DrawContext context) {
			for (Map.Entry<ButtonWidget, ButtonStyle> entry : buttonStyles.entrySet()) {
				ButtonWidget button = entry.getKey();
				if (!button.visible) {
					continue;
				}
				int x = button.getX();
				int y = button.getY();
				int w = button.getWidth();
				int h = button.getHeight();
				ButtonStyle style = entry.getValue();
				context.fill(x, y, x + w, y + h, style.fill);
				context.drawBorder(x, y, w, h, style.border);
			}
		}

		private void drawRetriesControl(DrawContext context) {
			int centerX = panelX + PANEL_WIDTH / 2;
			context.drawTextWithShadow(textRenderer, Text.literal("Retries"), panelX + 16, retriesRowY + 4, 0xFFB7C6DA);
			context.drawCenteredTextWithShadow(
				textRenderer,
				Text.literal(String.valueOf(ClientConfigState.INSTANCE.retries)),
				centerX,
				retriesRowY + 4,
				0xFFFFFFFF
			);
		}

		private void drawLiveInfo(DrawContext context) {
			ClientConfigState state = ClientConfigState.INSTANCE;
			String model = state.model == null ? "auto" : state.model;
			String mic = VOICE_CONFIG.micName == null || VOICE_CONFIG.micName.isBlank() ? "Default" : shortenLabel(VOICE_CONFIG.micName, 18);
			String voiceState = VOICE_CONFIG.enabled ? "ON" : "OFF";
			String info = "Model: " + model + " | Voice: " + voiceState + " | Mic: " + mic;
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(info), this.width / 2, panelY + 20, 0xFF9FB0C7);
		}

		private void drawSectionHeader(DrawContext context, String label, int y) {
			int lineY = y + 5;
			context.fill(panelX + 14, lineY, panelX + PANEL_WIDTH - 14, lineY + 1, 0xFF3A4A5E);
			int textWidth = textRenderer.getWidth(label);
			int textX = panelX + 18;
			context.fill(textX - 4, y, textX + textWidth + 4, y + 12, 0xFF191F2B);
			context.drawTextWithShadow(textRenderer, Text.literal(label).formatted(Formatting.AQUA), textX, y, 0xFF6BB5FF);
		}

		private void drawKeyStatus(DrawContext context) {
			ClientConfigState state = ClientConfigState.INSTANCE;
			String keyStatus;
			Formatting color;
			if (state.hasPlayerKey) {
				keyStatus = "Player key set";
				color = Formatting.GREEN;
			} else if (state.hasServerKey) {
				keyStatus = "Using server default";
				color = Formatting.YELLOW;
			} else {
				keyStatus = "No API key set";
				color = Formatting.RED;
			}
			context.drawTextWithShadow(textRenderer, Text.literal(keyStatus).formatted(color), panelX + 16, keyStatusY + 2, 0xFFFFFFFF);
		}

		private void drawToast(DrawContext context, long now) {
			if (toastMessage == null || now > toastExpiryMs) {
				toastMessage = null;
				return;
			}
			int toastWidth = textRenderer.getWidth(toastMessage) + 20;
			int toastX = (width - toastWidth) / 2;
			int toastY = panelY + PANEL_HEIGHT + 6;
			context.fill(toastX, toastY, toastX + toastWidth, toastY + 20, 0xDD1A2A3A);
			context.drawBorder(toastX, toastY, toastWidth, 20, 0xFF4CAF50);
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(toastMessage), width / 2, toastY + 6, 0xFF4CAF50);
		}

		private void showToast(String message) {
			toastMessage = message;
			toastExpiryMs = System.currentTimeMillis() + 2000;
		}

		@Override
		public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
			// Handled in render().
		}

		@Override
		public boolean shouldPause() {
			return false;
		}
	}

	private static final class ClientConfigState {
		private static final ClientConfigState INSTANCE = new ClientConfigState();
		private boolean debug = false;
		private boolean sidebar = true;
		private boolean sounds = true;
		private String particles = "on";
		private int retries = 5;
		private String model = "flash";
		private boolean synced = false;
		private boolean hasPlayerKey = false;
		private boolean hasServerKey = false;
	}
}

