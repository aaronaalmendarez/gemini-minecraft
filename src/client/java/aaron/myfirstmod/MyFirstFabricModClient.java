package aaron.myfirstmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
import java.util.Map;
import java.util.HashMap;

public class MyFirstFabricModClient implements ClientModInitializer {
	private static final String KEY_CATEGORY = "key.categories.myfirstmod";
	private static final String KEY_OPEN_CONFIG = "key.myfirstmod.chat_config";
	private KeyBinding openConfigKey;
	private static final Gson GSON = new Gson();

	@Override
	public void onInitializeClient() {
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			KEY_OPEN_CONFIG,
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			KEY_CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openConfigKey.wasPressed()) {
				client.setScreen(new ChatConfigScreen());
			}
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
		private ButtonWidget retriesMinus;
		private ButtonWidget retriesPlus;
		private TextFieldWidget playerKeyField;
		private TextFieldWidget serverKeyField;
		private final Map<ButtonWidget, ButtonStyle> buttonStyles = new HashMap<>();

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
			modelButton = addStyledButton(leftCol, row, "Model", this::cycleModel, ButtonStyle.ACTION);
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
			super.render(context, mouseX, mouseY, delta);
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

