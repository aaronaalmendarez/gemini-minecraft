package aaron.myfirstmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import org.lwjgl.glfw.GLFW;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

public class MyFirstFabricModClient implements ClientModInitializer {
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
	private static final Path CLIENT_CONFIG_PATH = Path.of("config", "gemini-ai-companion-client.json");
	private volatile boolean recording;
	private long recordStartMs;
	private TargetDataLine targetLine;
	private ByteArrayOutputStream audioBuffer;
	private AudioFormat audioFormat;
	private Thread recordThread;
	private String voiceUiLabel;
	private long voiceUiUntilMs;
	private static final ClientVoiceConfig VOICE_CONFIG = new ClientVoiceConfig();

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
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("chat")
				.then(ClientCommandManager.literal("voice")
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
				)
			);
		});

		ClientPlayNetworking.registerGlobalReceiver(MyFirstFabricMod.ConfigPayloadS2C.ID, (payload, context) -> {
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
			startRecording(client);
		} else if (!pressed && recording) {
			stopRecording(client);
		} else if (recording && System.currentTimeMillis() - recordStartMs >= VOICE_MAX_SECONDS * 1000L) {
			stopRecording(client);
		}
	}

	private void startRecording(MinecraftClient client) {
		if (recording) {
			return;
		}
		audioFormat = new AudioFormat(VOICE_SAMPLE_RATE, 16, 1, true, false);
		try {
			targetLine = openSelectedMicrophone(audioFormat);
			if (targetLine == null) {
				voiceUiLabel = "No microphone detected";
				voiceUiUntilMs = System.currentTimeMillis() + 2000;
				return;
			}
			targetLine.open(audioFormat);
			targetLine.start();
		} catch (Exception e) {
			voiceUiLabel = "Microphone error";
			voiceUiUntilMs = System.currentTimeMillis() + 2000;
			return;
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
				}
			}
		}, "GeminiAI-VoiceCapture");
		recordThread.setDaemon(true);
		recordThread.start();
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
		if (!ClientPlayNetworking.canSend(MyFirstFabricMod.AudioPayloadC2S.ID)) {
			voiceUiLabel = "Server missing voice support";
			voiceUiUntilMs = System.currentTimeMillis() + 2000;
			return;
		}
		ClientPlayNetworking.send(new MyFirstFabricMod.AudioPayloadC2S("audio/wav", wav));
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
		if (!recording && (voiceUiLabel == null || now > voiceUiUntilMs)) {
			return;
		}
		int width = client.getWindow().getScaledWidth();
		int y = 12;
		String label;
		if (recording) {
			long elapsed = now - recordStartMs;
			label = "Recording " + formatTime(elapsed) + " / 00:30";
		} else {
			label = voiceUiLabel;
		}
		int textWidth = client.textRenderer.getWidth(label);
		int boxWidth = textWidth + 24;
		int x = (width - boxWidth) / 2;
		context.fill(x, y, x + boxWidth, y + 22, 0xAA121926);
		context.drawBorder(x, y, boxWidth, 22, 0xFF2E3A52);
		drawGradientText(context, label, x + 12, y + 7, (int) (now / 75));
		if (recording) {
			float progress = Math.min(1f, (now - recordStartMs) / (float) (VOICE_MAX_SECONDS * 1000L));
			int barX = x + 8;
			int barY = y + 18;
			int barWidth = boxWidth - 16;
			context.fill(barX, barY, barX + barWidth, barY + 2, 0x553A4A5E);
			context.fill(barX, barY, barX + (int) (barWidth * progress), barY + 2, 0xFF6BB5FF);
		}
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
		private static final int BUTTON_WIDTH = 150;
		private static final int BUTTON_HEIGHT = 20;
		private static final int SMALL_BUTTON_WIDTH = 34;
		private static final int SAVE_BUTTON_WIDTH = 70;
		private static final int PANEL_WIDTH = 420;
		private static final int PANEL_HEIGHT = 340;
		private static final int SECTION_GAP = 22;
		private static final int HEADER_HEIGHT = 14;
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
		private final Map<ButtonWidget, ButtonStyle> buttonStyles = new HashMap<>();
		private final List<ButtonWidget> micOptionButtons = new ArrayList<>();
		private List<String> micDevices = new ArrayList<>();
		private boolean micDropdownOpen;
		private int micDropdownX;
		private int micDropdownY;
		private int micDropdownWidth;

		private ChatConfigScreen() {
			super(Text.literal("Chat AI Config"));
		}

		@Override
		protected void init() {
			openTimeMs = System.currentTimeMillis();
			panelX = (this.width - PANEL_WIDTH) / 2;
			panelY = (this.height - PANEL_HEIGHT) / 2;
			int leftCol = panelX + 22;
			int rightCol = panelX + PANEL_WIDTH - BUTTON_WIDTH - 22;
			int row = panelY + 36;

			settingsHeaderY = row;
			row += HEADER_HEIGHT + 6;
			debugButton = addStyledButton(leftCol, row, "Debug", this::toggleDebug, ButtonStyle.TOGGLE_ON);
			sidebarButton = addStyledButton(rightCol, row, "Sidebar", this::toggleSidebar, ButtonStyle.TOGGLE_ON);
			row += 24;
			soundsButton = addStyledButton(leftCol, row, "Sounds", this::toggleSounds, ButtonStyle.TOGGLE_ON);
			particlesButton = addStyledButton(rightCol, row, "Particles", this::cycleParticles, ButtonStyle.ACTION);
			row += 24;
			voiceButton = addStyledButton(leftCol, row, "Voice", this::toggleVoice, ButtonStyle.TOGGLE_ON);
			modelButton = addStyledButton(rightCol, row, "Model", this::cycleModel, ButtonStyle.ACTION);
			row += 24;
			micButton = addStyledButton(rightCol, row, "Microphone", this::toggleMicDropdown, ButtonStyle.ACTION);
			row += 24;
			retriesRowY = row;
			retriesMinus = addSmallStyledButton(panelX + PANEL_WIDTH / 2 - 52, row, SMALL_BUTTON_WIDTH, "-", () -> adjustRetries(-1), ButtonStyle.ACTION);
			retriesPlus = addSmallStyledButton(panelX + PANEL_WIDTH / 2 + 22, row, SMALL_BUTTON_WIDTH, "+", () -> adjustRetries(1), ButtonStyle.ACTION);

			row += SECTION_GAP;
			actionsHeaderY = row;
			row += HEADER_HEIGHT + 6;
			addStyledButton(leftCol, row, "History", () -> sendCommand("/chat history"), ButtonStyle.ACTION);
			addStyledButton(rightCol, row, "Export", () -> sendCommand("/chat export 5 txt"), ButtonStyle.ACTION);
			row += 24;
			addStyledButton(leftCol, row, "Smart Retry", () -> sendCommand("/chat smarter"), ButtonStyle.ACTION);
			addStyledButton(rightCol, row, "Cancel", () -> sendCommand("/chat cancel"), ButtonStyle.DANGER);

			row += SECTION_GAP;
			keysHeaderY = row;
			row += HEADER_HEIGHT + 6;
			int fieldWidth = 230;
			playerKeyField = new TextFieldWidget(this.textRenderer, leftCol, row, fieldWidth, BUTTON_HEIGHT, Text.literal("Player Key"));
			playerKeyField.setPlaceholder(Text.literal("Player API key"));
			playerKeyField.setMaxLength(128);
			addSelectableChild(playerKeyField);
			addDrawableChild(playerKeyField);
			addSmallStyledButton(leftCol + fieldWidth + 8, row, SAVE_BUTTON_WIDTH, "Save", () -> saveKey(7, playerKeyField.getText()), ButtonStyle.ACTION);

			row += 24;
			serverKeyField = new TextFieldWidget(this.textRenderer, leftCol, row, fieldWidth, BUTTON_HEIGHT, Text.literal("Server Key"));
			serverKeyField.setPlaceholder(Text.literal("Server default key"));
			serverKeyField.setMaxLength(128);
			addSelectableChild(serverKeyField);
			addDrawableChild(serverKeyField);
			addSmallStyledButton(leftCol + fieldWidth + 8, row, SAVE_BUTTON_WIDTH, "Save", () -> saveKey(8, serverKeyField.getText()), ButtonStyle.ACTION);
			keyStatusY = row + 24;

			addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
				.dimensions(panelX + (PANEL_WIDTH - BUTTON_WIDTH) / 2, panelY + PANEL_HEIGHT - 28, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());

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
				hideMicDropdownButtons();
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
			hideMicDropdownButtons();
			int maxItems = 8;
			List<String> display = new ArrayList<>();
			display.add("Default");
			for (String device : micDevices) {
				if (display.size() >= maxItems) {
					break;
				}
				display.add(device);
			}
			micDropdownWidth = BUTTON_WIDTH;
			micDropdownX = micButton.getX();
			int height = display.size() * (BUTTON_HEIGHT + 2) + 4;
			int belowY = micButton.getY() + BUTTON_HEIGHT + 4;
			int aboveY = micButton.getY() - height - 4;
			int panelBottom = panelY + PANEL_HEIGHT - 40;
			micDropdownY = (belowY + height <= panelBottom) ? belowY : Math.max(panelY + 20, aboveY);
			int y = micDropdownY;
			for (String device : display) {
				String label = shortenLabel(device, 18);
				ButtonWidget button = addSmallStyledButton(micDropdownX, y, micDropdownWidth, label, () -> selectMicrophone(device), ButtonStyle.ACTION);
				micOptionButtons.add(button);
				y += BUTTON_HEIGHT + 2;
			}
		}

		private void hideMicDropdownButtons() {
			for (ButtonWidget button : micOptionButtons) {
				button.visible = false;
				button.active = false;
			}
			micOptionButtons.clear();
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
			hideMicDropdownButtons();
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
			if (!ClientPlayNetworking.canSend(MyFirstFabricMod.ConfigPayloadC2S.ID)) {
				return;
			}
			JsonObject obj = new JsonObject();
			obj.addProperty("type", type);
			writer.accept(obj);
			ClientPlayNetworking.send(new MyFirstFabricMod.ConfigPayloadC2S(obj.toString()));
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
			renderBackground(context, mouseX, mouseY, delta);
			long now = System.currentTimeMillis();
			float progress = Math.min(1f, (now - openTimeMs) / (float) ANIM_DURATION_MS);
			float alpha = 0.6f + 0.4f * progress;
			int panelAlpha = (int) (0xD0 * alpha);
			context.fillGradient(0, 0, this.width, this.height, 0xB00E1018, 0xB0101A2B);
			context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, (panelAlpha << 24) | COLOR_PANEL_BG);
			context.drawBorder(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF2E3A52);
			context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, panelY + 12, 0xFFE6EAF2);
			drawSectionHeader(context, "SETTINGS", settingsHeaderY);
			drawSectionHeader(context, "ACTIONS", actionsHeaderY);
			drawSectionHeader(context, "API KEYS", keysHeaderY);
			drawRetriesControl(context);
			drawKeyStatus(context);
			drawToast(context, now);
			drawButtonStyles(context);
			drawMicDropdownBackdrop(context);
			super.render(context, mouseX, mouseY, delta);
		}

		private void drawMicDropdownBackdrop(DrawContext context) {
			if (!micDropdownOpen || micOptionButtons.isEmpty()) {
				return;
			}
			int height = micOptionButtons.size() * (BUTTON_HEIGHT + 2) + 4;
			context.fill(micDropdownX - 2, micDropdownY - 2, micDropdownX + micDropdownWidth + 2, micDropdownY + height, 0xCC101A2B);
			context.drawBorder(micDropdownX - 2, micDropdownY - 2, micDropdownWidth + 4, height + 2, 0xFF2E3A52);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			boolean handled = super.mouseClicked(mouseX, mouseY, button);
			if (micDropdownOpen) {
				boolean inside = mouseX >= micDropdownX - 4
					&& mouseX <= micDropdownX + micDropdownWidth + 4
					&& mouseY >= micDropdownY - 4
					&& mouseY <= micDropdownY + (micOptionButtons.size() * (BUTTON_HEIGHT + 2)) + 4;
				boolean onMicButton = micButton != null
					&& mouseX >= micButton.getX()
					&& mouseX <= micButton.getX() + micButton.getWidth()
					&& mouseY >= micButton.getY()
					&& mouseY <= micButton.getY() + micButton.getHeight();
				if (!inside && !onMicButton) {
					micDropdownOpen = false;
					hideMicDropdownButtons();
				}
			}
			return handled;
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
			context.drawTextWithShadow(textRenderer, Text.literal("Retries"), panelX + 24, retriesRowY + 6, 0xFFB7C6DA);
			context.drawCenteredTextWithShadow(
				textRenderer,
				Text.literal(String.valueOf(ClientConfigState.INSTANCE.retries)),
				centerX,
				retriesRowY + 6,
				0xFFFFFFFF
			);
		}

		private void drawSectionHeader(DrawContext context, String label, int y) {
			int lineY = y + 6;
			context.fill(panelX + 18, lineY, panelX + PANEL_WIDTH - 18, lineY + 1, 0xFF3A4A5E);
			int textWidth = textRenderer.getWidth(label);
			int textX = panelX + 24;
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
			context.drawTextWithShadow(textRenderer, Text.literal(keyStatus).formatted(color), panelX + 24, keyStatusY + 2, 0xFFFFFFFF);
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

