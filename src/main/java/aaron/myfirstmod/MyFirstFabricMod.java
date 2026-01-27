package aaron.myfirstmod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.structure.StructureStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.damage.DamageSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.HashMap;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

public class MyFirstFabricMod implements ModInitializer {
public static final String MOD_ID = "gemini-ai-companion";
	public static final Identifier CONFIG_PACKET_C2S = Identifier.of(MOD_ID, "config_c2s");
	public static final Identifier CONFIG_PACKET_S2C = Identifier.of(MOD_ID, "config_s2c");
	public static final Identifier AUDIO_PACKET_C2S = Identifier.of(MOD_ID, "audio_c2s");
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String API_KEY_ENV = "GEMINI_API_KEY";
	private static final String GEMINI_ENDPOINT_BASE =
		"https://generativelanguage.googleapis.com/v1beta/models/";
	private static final String SYSTEM_PROMPT =
		"You are an assistant inside Minecraft. Classify the user's request into one mode: ASK, PLAN, or COMMAND. " +
		"Return ONLY a JSON object with fields: mode (ASK|PLAN|COMMAND), message (plain text), and commands (array). " +
		"For COMMAND mode, include one or more Minecraft commands in commands. For other modes, use an empty array. " +
		"Do not include markdown, bullet lists, or code fences. Do not include extra fields. " +
		"You can access the full player inventory context when it is provided; do not claim you lack inventory access. " +
		"If you are unsure about the player's items or equipment, proactively issue /chat skill inventory before answering. " +
		"If you need extra data, you may issue /chat skill inventory, /chat skill nearby, /chat skill stats, or /chat skill nbt <mainhand|offhand|slot N>. " +
		"/chat skill recipe <item> returns all matching recipes across vanilla and modded recipe types with ingredients. " +
		"/chat skill smelt <item> returns cooking recipes only. " +
		"After skill output is returned, continue the task using that data. " +
		"Minecraft version rules: Items use COMPONENTS, not old NBT. " +
		"Enchantments use: enchantments={levels:{\"minecraft:enchantment\":level}}. " +
		"Old tags like Enchantments, lvl, Damage are FORBIDDEN. " +
		"/execute syntax MUST follow vanilla order.";
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
	private static final Map<UUID, Integer> THINKING_TICKS = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> SEARCHING_TICKS = new ConcurrentHashMap<>();
	private static final Map<UUID, ModeState> MODE_STATE = new ConcurrentHashMap<>();
	private static final Map<UUID, StatusState> STATUS_STATE = new ConcurrentHashMap<>();
	private static final Map<UUID, List<ChatTurn>> CHAT_HISTORY = new ConcurrentHashMap<>();
	private static final Map<UUID, String> API_KEYS = new ConcurrentHashMap<>();
	private static final Map<UUID, List<DeathRecord>> DEATHS = new ConcurrentHashMap<>();
	private static final Map<UUID, List<String>> LAST_UNDO_COMMANDS = new ConcurrentHashMap<>();
	private static final Map<UUID, String> LAST_PROMPT = new ConcurrentHashMap<>();
	private static final Map<UUID, RequestState> REQUEST_STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, ModelPreference> MODEL_PREFERENCES = new ConcurrentHashMap<>();
	private static final Map<UUID, ParticleSetting> PARTICLE_SETTINGS = new ConcurrentHashMap<>();
	private static final Set<UUID> SOUND_DISABLED = ConcurrentHashMap.newKeySet();
	private static final int MAX_HISTORY_TURNS = 10;
	private static final int MAX_DEATHS = 20;
	private static final long DEATH_EXPIRY_MS = 2L * 60L * 60L * 1000L;
	private static final long RECENT_DEATH_MS = 30L * 60L * 1000L;
	private static final int DEFAULT_COMMAND_RETRIES = 5;
	private static final int MAX_COMMAND_RETRIES = 10;
	private static final int MIN_COMMAND_RETRIES = 0;
	private static final int MAX_COMMAND_STEPS = 5;
	private static final int SEARCH_ANIMATION_TICKS = 40;
	private static final int MAX_VOICE_BYTES = 1_000_000;
	private static final Set<UUID> COMMAND_DEBUG_ENABLED = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Integer> COMMAND_RETRY_LIMITS = new ConcurrentHashMap<>();
	private static final int MAX_CONTEXT_TOKENS = 16000;
	private static final int HISTORY_EXCHANGES_PER_PAGE = 3;
	private static final boolean SIDEBAR_DEFAULT_ENABLED = true;
	private static boolean SIDEBAR_ENABLED = SIDEBAR_DEFAULT_ENABLED;
	private static final String SCOREBOARD_OBJECTIVE = "gemini_ai";
	private static final String[] SCOREBOARD_LINES = new String[] {
		"Mode",
		"State",
		"Tokens",
		"Session Tokens",
		"Last Response",
		"Retries",
		"Context Size"
	};
	private static final String[] SCOREBOARD_ENTRIES = new String[] {
		Formatting.BLACK.toString(),
		Formatting.DARK_BLUE.toString(),
		Formatting.DARK_GREEN.toString(),
		Formatting.DARK_AQUA.toString(),
		Formatting.DARK_RED.toString(),
		Formatting.DARK_PURPLE.toString(),
		Formatting.GOLD.toString()
	};
	private static final Map<UUID, AiStats> AI_STATS = new ConcurrentHashMap<>();
	private static final Formatting[] RAINBOW = new Formatting[] {
		Formatting.RED,
		Formatting.GOLD,
		Formatting.YELLOW,
		Formatting.GREEN,
		Formatting.AQUA,
		Formatting.BLUE,
		Formatting.DARK_PURPLE
	};
	private static final SuggestionProvider<ServerCommandSource> MODEL_SUGGESTIONS =
		(context, builder) -> CommandSource.suggestMatching(
			List.of("flash", "flash-thinking", "pro", "auto"),
			builder);

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.argument("text", StringArgumentType.greedyString())
					.executes(context -> handleChatCommand(context.getSource(), StringArgumentType.getString(context, "text")))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("clear")
					.executes(context -> clearChatHistory(context.getSource())))
				.then(CommandManager.literal("new")
					.executes(context -> clearChatHistory(context.getSource()))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("smarter")
					.executes(context -> rerunWithSmarterModel(context.getSource()))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("cancel")
					.executes(context -> cancelChatRequest(context.getSource())))
				.then(CommandManager.literal("stop")
					.executes(context -> cancelChatRequest(context.getSource()))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("undo")
					.executes(context -> undoLastCommands(context.getSource()))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("skill")
					.then(CommandManager.literal("inventory")
						.executes(context -> runChatSkill(context.getSource(), "inventory", null)))
					.then(CommandManager.literal("nearby")
						.executes(context -> runChatSkill(context.getSource(), "nearby", null)))
					.then(CommandManager.literal("stats")
						.executes(context -> runChatSkill(context.getSource(), "stats", null)))
					.then(CommandManager.literal("recipe")
						.then(CommandManager.argument("item", StringArgumentType.word())
							.executes(context -> runChatSkill(
								context.getSource(),
								"recipe",
								StringArgumentType.getString(context, "item")))))
					.then(CommandManager.literal("smelt")
						.then(CommandManager.argument("item", StringArgumentType.word())
							.executes(context -> runChatSkill(
								context.getSource(),
								"smelt",
								StringArgumentType.getString(context, "item")))))
					.then(CommandManager.literal("nbt")
						.executes(context -> runChatSkill(context.getSource(), "nbt", null))
						.then(CommandManager.argument("target", StringArgumentType.word())
							.executes(context -> runChatSkill(
								context.getSource(),
								"nbt",
								StringArgumentType.getString(context, "target")))))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("config")
					.executes(context -> showChatConfig(context.getSource()))
					.then(CommandManager.literal("debug")
						.then(CommandManager.literal("on")
							.executes(context -> setChatDebug(context.getSource(), true)))
						.then(CommandManager.literal("off")
							.executes(context -> setChatDebug(context.getSource(), false))))
					.then(CommandManager.literal("sidebar")
						.then(CommandManager.literal("on")
							.executes(context -> setChatSidebar(context.getSource(), true)))
						.then(CommandManager.literal("off")
							.executes(context -> setChatSidebar(context.getSource(), false))))
					.then(CommandManager.literal("sounds")
						.then(CommandManager.literal("on")
							.executes(context -> setChatSounds(context.getSource(), true)))
						.then(CommandManager.literal("off")
							.executes(context -> setChatSounds(context.getSource(), false))))
					.then(CommandManager.literal("particles")
						.then(CommandManager.literal("on")
							.executes(context -> setChatParticles(context.getSource(), ParticleSetting.ON)))
						.then(CommandManager.literal("off")
							.executes(context -> setChatParticles(context.getSource(), ParticleSetting.OFF)))
						.then(CommandManager.literal("minimal")
							.executes(context -> setChatParticles(context.getSource(), ParticleSetting.MINIMAL))))
					.then(CommandManager.literal("retries")
						.then(CommandManager.argument("count", IntegerArgumentType.integer(MIN_COMMAND_RETRIES, MAX_COMMAND_RETRIES))
							.executes(context -> setChatRetries(context.getSource(), IntegerArgumentType.getInteger(context, "count")))))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("config")
					.then(CommandManager.literal("model")
						.then(CommandManager.argument("model", StringArgumentType.word())
							.suggests(MODEL_SUGGESTIONS)
							.executes(context -> setChatModel(
								context.getSource(),
								StringArgumentType.getString(context, "model")))))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("history")
					.executes(context -> showChatHistory(context.getSource(), "5", 1))
					.then(CommandManager.argument("count", StringArgumentType.word())
						.executes(context -> showChatHistory(context.getSource(), StringArgumentType.getString(context, "count"), 1))
						.then(CommandManager.argument("page", IntegerArgumentType.integer(1))
							.executes(context -> showChatHistory(
								context.getSource(),
								StringArgumentType.getString(context, "count"),
								IntegerArgumentType.getInteger(context, "page")))))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("export")
					.executes(context -> exportChat(context.getSource(), "5", "txt"))
					.then(CommandManager.argument("count", StringArgumentType.word())
						.executes(context -> exportChat(context.getSource(), StringArgumentType.getString(context, "count"), "txt"))
						.then(CommandManager.argument("format", StringArgumentType.word())
							.executes(context -> exportChat(
								context.getSource(),
								StringArgumentType.getString(context, "count"),
								StringArgumentType.getString(context, "format")))))));
			dispatcher.register(CommandManager.literal("chatdebug")
				.then(CommandManager.literal("on")
					.executes(context -> setChatDebug(context.getSource(), true)))
				.then(CommandManager.literal("off")
					.executes(context -> setChatDebug(context.getSource(), false))));
			dispatcher.register(CommandManager.literal("chatsidebar")
				.then(CommandManager.literal("on")
					.executes(context -> setChatSidebar(context.getSource(), true)))
				.then(CommandManager.literal("off")
					.executes(context -> setChatSidebar(context.getSource(), false))));
			dispatcher.register(CommandManager.literal("chatsounds")
				.then(CommandManager.literal("on")
					.executes(context -> setChatSounds(context.getSource(), true)))
				.then(CommandManager.literal("off")
					.executes(context -> setChatSounds(context.getSource(), false))));
			dispatcher.register(CommandManager.literal("chatparticles")
				.then(CommandManager.literal("on")
					.executes(context -> setChatParticles(context.getSource(), ParticleSetting.ON)))
				.then(CommandManager.literal("off")
					.executes(context -> setChatParticles(context.getSource(), ParticleSetting.OFF)))
				.then(CommandManager.literal("minimal")
					.executes(context -> setChatParticles(context.getSource(), ParticleSetting.MINIMAL))));
			dispatcher.register(CommandManager.literal("chatmodel")
				.executes(context -> showChatModel(context.getSource()))
				.then(CommandManager.argument("model", StringArgumentType.word())
					.executes(context -> setChatModel(
						context.getSource(),
						StringArgumentType.getString(context, "model")))));
			dispatcher.register(CommandManager.literal("chatkey")
				.then(CommandManager.argument("key", StringArgumentType.greedyString())
					.executes(context -> setChatKey(context.getSource(), StringArgumentType.getString(context, "key"))))
				.then(CommandManager.literal("default")
					.then(CommandManager.argument("key", StringArgumentType.greedyString())
						.executes(context -> setDefaultChatKey(context.getSource(), StringArgumentType.getString(context, "key"))))
					.then(CommandManager.literal("clear")
						.executes(context -> clearDefaultChatKey(context.getSource()))))
				.then(CommandManager.literal("info")
					.executes(context -> showChatKeyInfo(context.getSource())))
				.then(CommandManager.literal("clear")
					.executes(context -> clearChatKey(context.getSource()))));
			dispatcher.register(CommandManager.literal("chatretries")
				.then(CommandManager.argument("count", IntegerArgumentType.integer(MIN_COMMAND_RETRIES, MAX_COMMAND_RETRIES))
					.executes(context -> setChatRetries(context.getSource(), IntegerArgumentType.getInteger(context, "count")))));
			dispatcher.register(CommandManager.literal("chathelp")
				.executes(context -> showChatHelp(context.getSource(), null))
				.then(CommandManager.argument("topic", StringArgumentType.word())
					.executes(context -> showChatHelp(context.getSource(), StringArgumentType.getString(context, "topic")))));
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (THINKING_TICKS.isEmpty()) {
				// Still allow mode indicators to render.
			}

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				UUID id = player.getUuid();
				StatusState status = STATUS_STATE.get(id);
				if (status != null) {
					AiStats stats = getStats(id);
					stats.state = "ERROR";
					updateSidebar(player, stats);
					player.sendMessage(Text.literal(status.message).formatted(status.color), true);
					status.ticksRemaining--;
					if (status.ticksRemaining <= 0) {
						STATUS_STATE.remove(id);
					}
					continue;
				}

				Integer searchingTick = SEARCHING_TICKS.get(id);
				if (searchingTick != null) {
					AiStats stats = getStats(id);
					stats.state = "SEARCHING";
					updateSidebar(player, stats);
					player.sendMessage(thinkingActionBarText("Searching", searchingTick), true);
					searchingTick++;
					if (searchingTick >= SEARCH_ANIMATION_TICKS) {
						SEARCHING_TICKS.remove(id);
					} else {
						SEARCHING_TICKS.put(id, searchingTick);
					}
					continue;
				}

				Integer tick = THINKING_TICKS.get(id);
				if (tick != null) {
					AiStats stats = getStats(id);
					stats.state = formatThinkingState(stats, tick);
					updateSidebar(player, stats);
					player.sendMessage(thinkingActionBarText("Thinking", tick, stats), true);
					THINKING_TICKS.put(id, tick + 1);
					continue;
				}

				ModeState modeState = MODE_STATE.get(id);
				if (modeState != null) {
					player.sendMessage(Text.literal(modeState.label).formatted(modeState.color), true);
					modeState.ticksRemaining--;
					if (modeState.ticksRemaining <= 0) {
						MODE_STATE.remove(id);
					}
				}
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayerEntity player) {
				recordDeath(player, source);
			}
		});

		ServerPlayerEvents.JOIN.register(player -> {
			loadDeaths(player.getUuid());
			loadPlayerSettings(player.getUuid());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.getPlayer().getUuid();
			saveDeaths(playerId);
			savePlayerSettings(playerId);
		});

		loadGlobalSettings();
		PayloadTypeRegistry.playC2S().register(ConfigPayloadC2S.ID, ConfigPayloadC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(ConfigPayloadS2C.ID, ConfigPayloadS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(AudioPayloadC2S.ID, AudioPayloadC2S.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ConfigPayloadC2S.ID, (payload, context) -> {
			handleConfigPacket(context.server(), context.player(), payload.json());
		});
		ServerPlayNetworking.registerGlobalReceiver(AudioPayloadC2S.ID, (payload, context) -> {
			context.server().execute(() -> handleVoicePayload(context.player(), payload));
		});
	}

	private static int handleChatCommand(ServerCommandSource source, String prompt) {
		String apiKey = resolveApiKey(source);
		if (apiKey == null || apiKey.isBlank()) {
			source.sendError(Text.literal("No API key set. Use /chatkey <key>, /chatkey default <key>, or set GEMINI_API_KEY."));
			return 0;
		}

		ServerPlayerEntity player = source.getPlayer();
		boolean inventoryQuery = player != null && isInventoryQuery(prompt);
		if (player != null) {
			THINKING_TICKS.put(player.getUuid(), 0);
			registerRequest(player.getUuid());
			sendCancelPrompt(player);
			LAST_PROMPT.put(player.getUuid(), prompt);
		} else {
			source.sendFeedback(() -> Text.literal("Thinking..."), false);
		}

		String contextSnapshot = player == null ? "Context: unknown" : buildContext(source, player, prompt, inventoryQuery);
		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> handleGeminiFlow(source, player, apiKey, prompt, contextSnapshot, null));
		if (player != null) {
			RequestState state = REQUEST_STATES.get(player.getUuid());
			if (state != null) {
				state.future = future;
			}
		}

		return 1;
	}

	private static void handleVoicePayload(ServerPlayerEntity player, AudioPayloadC2S payload) {
		if (player == null) {
			return;
		}
		String apiKey = resolveApiKey(player.getCommandSource());
		if (apiKey == null || apiKey.isBlank()) {
			player.sendMessage(Text.literal("No API key set. Use /chatkey <key> or /chatkey default <key>."), false);
			return;
		}
		byte[] audio = payload.data();
		if (audio == null || audio.length == 0) {
			player.sendMessage(Text.literal("No audio received."), false);
			return;
		}
		if (audio.length > MAX_VOICE_BYTES) {
			player.sendMessage(Text.literal("Voice clip too large. Try a shorter clip."), false);
			return;
		}
		THINKING_TICKS.put(player.getUuid(), 0);
		registerRequest(player.getUuid());
		sendCancelPrompt(player);
		setStatus(player, "Transcribing voice...", Formatting.AQUA);
		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> handleVoiceFlow(player, apiKey, payload));
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state != null) {
			state.future = future;
		}
	}

	private static void handleVoiceFlow(ServerPlayerEntity player, String apiKey, AudioPayloadC2S payload) {
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state != null && state.cancelled.get()) {
			return;
		}
		String transcript;
		try {
			transcript = callGeminiTranscribe(apiKey, payload.data(), payload.mimeType());
		} catch (Exception e) {
			player.getServer().execute(() -> {
				player.sendMessage(Text.literal("Voice transcription failed: " + e.getMessage()), false);
				THINKING_TICKS.remove(player.getUuid());
				STATUS_STATE.remove(player.getUuid());
			});
			return;
		}
		if (state != null && state.cancelled.get()) {
			return;
		}
		String cleaned = sanitizeTranscript(transcript);
		if (cleaned.isBlank()) {
			player.getServer().execute(() -> {
				player.sendMessage(Text.literal("Could not hear any speech. Try again."), false);
				THINKING_TICKS.remove(player.getUuid());
				STATUS_STATE.remove(player.getUuid());
			});
			return;
		}
		LAST_PROMPT.put(player.getUuid(), cleaned);
		player.getServer().execute(() -> player.sendMessage(Text.literal("You (voice): " + cleaned).formatted(Formatting.GRAY), false));
		boolean inventoryQuery = isInventoryQuery(cleaned);
		String contextSnapshot = buildContext(player.getCommandSource(), player, cleaned, inventoryQuery);
		handleGeminiFlow(player.getCommandSource(), player, apiKey, cleaned, contextSnapshot, null);
	}

	private static boolean isInventoryQuery(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return false;
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		return containsAny(
			lower,
			"inv",
			"inventory",
			"what's in my inventory",
			"what is in my inventory",
			"show my inventory",
			"list my inventory",
			"what do i have",
			"items i have"
		);
	}

	private static String selectAutoSkillCommand(String prompt, String replyMessage) {
		String combined = (prompt == null ? "" : prompt) + " " + (replyMessage == null ? "" : replyMessage);
		String lower = combined.toLowerCase(Locale.ROOT);
		if (lower.isBlank()) {
			return null;
		}
		if (lower.contains("need to check") || lower.contains("i should check") || lower.contains("let me check")
			|| lower.contains("i will check") || lower.contains("i need to check")) {
			if (lower.contains("armor") || lower.contains("enchant") || lower.contains("equipment") || lower.contains("gear")) {
				return "chat skill inventory";
			}
			if (lower.contains("inventory") || lower.contains("items") || lower.contains("what do i have") || lower.contains("what's in")) {
				return "chat skill inventory";
			}
			if (lower.contains("nearby") || lower.contains("around me") || lower.contains("entities")) {
				return "chat skill nearby";
			}
			if (lower.contains("health") || lower.contains("hp") || lower.contains("status") || lower.contains("buff")) {
				return "chat skill stats";
			}
			if (lower.contains("nbt") || lower.contains("components")) {
				return "chat skill nbt mainhand";
			}
		}
		if (isInventoryQuery(prompt)) {
			return "chat skill inventory";
		}
		return null;
	}

	private static String stripSkillPrefix(String output) {
		if (output == null) {
			return "";
		}
		if (output.startsWith("SKILL:")) {
			return output.substring(6).trim();
		}
		return output;
	}

	private static int rerunWithSmarterModel(ServerCommandSource source) {
		String apiKey = resolveApiKey(source);
		if (apiKey == null || apiKey.isBlank()) {
			source.sendError(Text.literal("No API key set. Use /chatkey <key>, /chatkey default <key>, or set GEMINI_API_KEY."));
			return 0;
		}
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		String prompt = LAST_PROMPT.get(player.getUuid());
		if (prompt == null || prompt.isBlank()) {
			source.sendError(Text.literal("No previous prompt to rerun."));
			return 0;
		}
		THINKING_TICKS.put(player.getUuid(), 0);
		registerRequest(player.getUuid());
		sendCancelPrompt(player);
		boolean inventoryQuery = isInventoryQuery(prompt);
		String contextSnapshot = buildContext(source, player, prompt, inventoryQuery);
		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> handleGeminiFlow(source, player, apiKey, prompt, contextSnapshot, ModelChoice.PRO));
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state != null) {
			state.future = future;
		}
		source.sendFeedback(() -> Text.literal("Re-running with smarter model..."), false);
		return 1;
	}

	private static int cancelChatRequest(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state == null) {
			source.sendFeedback(() -> Text.literal("No active request."), false);
			return 1;
		}
		state.cancelled.set(true);
		if (state.future != null) {
			state.future.cancel(true);
		}
		THINKING_TICKS.remove(player.getUuid());
		SEARCHING_TICKS.remove(player.getUuid());
		STATUS_STATE.put(player.getUuid(), new StatusState("Cancelled.", Formatting.RED, 40));
		source.sendFeedback(() -> Text.literal("Request cancelled."), false);
		return 1;
	}

	private static void registerRequest(UUID playerId) {
		RequestState existing = REQUEST_STATES.get(playerId);
		if (existing != null) {
			existing.cancelled.set(true);
			if (existing.future != null) {
				existing.future.cancel(true);
			}
		}
		REQUEST_STATES.put(playerId, new RequestState(new AtomicBoolean(false), null));
	}

	private static void sendCancelPrompt(ServerPlayerEntity player) {
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state == null || state.cancelPromptSent) {
			return;
		}
		MutableText message = Text.literal("AI is thinking... ").formatted(Formatting.GRAY);
		MutableText cancel = Text.literal("[Cancel]").styled(style -> style
			.withColor(Formatting.RED)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat cancel")));
		player.sendMessage(message.append(cancel), false);
		state.cancelPromptSent = true;
	}

	private static void sendSmarterModelPrompt(ServerPlayerEntity player) {
		MutableText message = Text.literal("Want a smarter answer? ").formatted(Formatting.GRAY);
		MutableText button = Text.literal("[Switch to Pro]").styled(style -> style
			.withColor(Formatting.AQUA)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat smarter")));
		player.sendMessage(message.append(button), false);
	}

	private static int setChatDebug(ServerCommandSource source, boolean enabled) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}

		if (enabled) {
			COMMAND_DEBUG_ENABLED.add(player.getUuid());
		} else {
			COMMAND_DEBUG_ENABLED.remove(player.getUuid());
		}

		source.sendFeedback(() -> Text.literal("Command debug " + (enabled ? "enabled" : "disabled") + "."), false);
		savePlayerSettings(player.getUuid());
		return 1;
	}

	private static int setChatSidebar(ServerCommandSource source, boolean enabled) {
		SIDEBAR_ENABLED = enabled;
		saveGlobalSettings();
		if (!enabled) {
			clearSidebar(source);
		}
		source.sendFeedback(() -> Text.literal("Sidebar " + (enabled ? "enabled" : "disabled") + "."), false);
		return 1;
	}

	private static int setChatSounds(ServerCommandSource source, boolean enabled) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		if (enabled) {
			SOUND_DISABLED.remove(player.getUuid());
		} else {
			SOUND_DISABLED.add(player.getUuid());
		}
		source.sendFeedback(() -> Text.literal("Chat sounds " + (enabled ? "enabled" : "disabled") + "."), false);
		savePlayerSettings(player.getUuid());
		return 1;
	}

	private static int setChatParticles(ServerCommandSource source, ParticleSetting setting) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		PARTICLE_SETTINGS.put(player.getUuid(), setting);
		source.sendFeedback(() -> Text.literal("Chat particles set to " + setting.name().toLowerCase(Locale.ROOT) + "."), false);
		savePlayerSettings(player.getUuid());
		return 1;
	}

	private static int setChatModel(ServerCommandSource source, String model) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		ModelPreference preference = parseModelPreference(model);
		if (preference == null) {
			source.sendError(Text.literal("Unknown model. Use flash, flash-thinking, pro, or auto."));
			return 0;
		}
		MODEL_PREFERENCES.put(player.getUuid(), preference);
		saveModelPreference(player.getUuid(), preference);
		source.sendFeedback(() -> Text.literal("Model set to " + preference.display + "."), false);
		return 1;
	}

	private static int showChatModel(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		ModelPreference preference = resolveModelPreference(player.getUuid());
		source.sendFeedback(() -> Text.literal("Current model: " + preference.display + "."), false);
		return 1;
	}

	private static int showChatHistory(ServerCommandSource source, String countToken, int page) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		List<ChatTurn> history = getHistory(player.getUuid());
		if (history.isEmpty()) {
			source.sendFeedback(() -> Text.literal("No chat history yet."), false);
			return 1;
		}

		int totalExchanges = history.size() / 2;
		int requested = parseHistoryCount(countToken, totalExchanges);
		int totalPages = Math.max(1, (int) Math.ceil(requested / (double) HISTORY_EXCHANGES_PER_PAGE));
		int safePage = Math.max(1, Math.min(page, totalPages));
		int endIndex = totalExchanges;
		int startIndex = Math.max(0, endIndex - requested);
		int pageStart = startIndex + (safePage - 1) * HISTORY_EXCHANGES_PER_PAGE;
		int pageEnd = Math.min(startIndex + safePage * HISTORY_EXCHANGES_PER_PAGE, endIndex);

		source.sendFeedback(() -> Text.literal("Chat history (" + requested + " exchanges, page " + safePage + "/" + totalPages + "):"), false);
		for (int i = pageStart; i < pageEnd; i++) {
			int turnIndex = i * 2;
			if (turnIndex + 1 >= history.size()) {
				break;
			}
			ChatTurn user = history.get(turnIndex);
			ChatTurn model = history.get(turnIndex + 1);
			MutableText userLine = formatHistoryLine("You", user.text, user.timestampMs);
			String modelLabel = model.mode == null || model.mode.isBlank() ? "AI" : "AI (" + model.mode + ")";
			MutableText modelLine = formatHistoryLine(modelLabel, model.text, model.timestampMs);
			source.sendFeedback(() -> userLine, false);
			source.sendFeedback(() -> modelLine, false);
		}

		MutableText nav = Text.empty();
		if (safePage > 1) {
			nav.append(historyNavButton("<< Prev", countToken, safePage - 1));
		}
		if (safePage < totalPages) {
			if (!nav.getString().isEmpty()) {
				nav.append(Text.literal(" ")); 
			}
			nav.append(historyNavButton("Next >>", countToken, safePage + 1));
		}
		if (!nav.getString().isEmpty()) {
			source.sendFeedback(() -> nav, false);
		}
		return 1;
	}

	private static int exportChat(ServerCommandSource source, String countToken, String format) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		List<ChatTurn> history = getHistory(player.getUuid());
		if (history.isEmpty()) {
			source.sendFeedback(() -> Text.literal("No chat history to export."), false);
			return 1;
		}
		int totalExchanges = history.size() / 2;
		int requested = parseHistoryCount(countToken, totalExchanges);
		List<ChatTurn> subset = sliceHistory(history, requested);
		String safeFormat = format == null ? "txt" : format.toLowerCase(Locale.ROOT);
		if (!safeFormat.equals("txt") && !safeFormat.equals("json")) {
			source.sendError(Text.literal("Unknown format. Use txt or json."));
			return 0;
		}

		Path exportPath = writeChatExport(player.getUuid(), subset, safeFormat);
		if (exportPath == null) {
			source.sendError(Text.literal("Failed to export chat log."));
			return 0;
		}
		source.sendFeedback(() -> Text.literal("Chat exported to " + exportPath.toString()), false);
		return 1;
	}

	private static int clearChatHistory(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		CHAT_HISTORY.remove(player.getUuid());
		source.sendFeedback(() -> Text.literal("Chat context cleared."), false);
		return 1;
	}

	private static int setChatKey(ServerCommandSource source, String key) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		if (key == null || key.isBlank()) {
			source.sendError(Text.literal("API key cannot be empty."));
			return 0;
		}
		API_KEYS.put(player.getUuid(), key.trim());
		writeApiKey(player.getUuid(), key.trim());
		source.sendFeedback(() -> Text.literal("API key saved."), false);
		return 1;
	}

	private static int clearChatKey(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		API_KEYS.remove(player.getUuid());
		deleteApiKey(player.getUuid());
		source.sendFeedback(() -> Text.literal("API key cleared."), false);
		return 1;
	}

	private static int setDefaultChatKey(ServerCommandSource source, String key) {
		if (key == null || key.isBlank()) {
			source.sendError(Text.literal("API key cannot be empty."));
			return 0;
		}
		writeDefaultApiKey(key.trim());
		source.sendFeedback(() -> Text.literal("Server default API key saved."), false);
		return 1;
	}

	private static int clearDefaultChatKey(ServerCommandSource source) {
		deleteDefaultApiKey();
		source.sendFeedback(() -> Text.literal("Server default API key cleared."), false);
		return 1;
	}

	private static int showChatKeyInfo(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		String playerKey = API_KEYS.get(player.getUuid());
		boolean hasPlayerKey = playerKey != null && !playerKey.isBlank() || readApiKey(player.getUuid()) != null;
		boolean hasDefaultKey = readDefaultApiKey() != null;
		boolean hasEnv = System.getenv(API_KEY_ENV) != null && !System.getenv(API_KEY_ENV).isBlank();

		source.sendFeedback(() -> Text.literal("API key sources:"), false);
		source.sendFeedback(() -> Text.literal("Player key: " + (hasPlayerKey ? "set" : "missing")), false);
		source.sendFeedback(() -> Text.literal("Server default: " + (hasDefaultKey ? "set" : "missing")), false);
		source.sendFeedback(() -> Text.literal("Env GEMINI_API_KEY: " + (hasEnv ? "set" : "missing")), false);
		source.sendFeedback(() -> Text.literal("Precedence: player > server default > env"), false);
		return 1;
	}

	private static int setChatRetries(ServerCommandSource source, int count) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		int clamped = Math.max(MIN_COMMAND_RETRIES, Math.min(MAX_COMMAND_RETRIES, count));
		COMMAND_RETRY_LIMITS.put(player.getUuid(), clamped);
		source.sendFeedback(() -> Text.literal("Command retries set to " + clamped + "."), false);
		savePlayerSettings(player.getUuid());
		return 1;
	}

	private static int runChatSkill(ServerCommandSource source, String skill, String target) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		StringBuilder command = new StringBuilder("chat skill ").append(skill);
		if (target != null && !target.isBlank()) {
			command.append(" ").append(target);
		}
		String output = executeSkillCommand(player, command.toString(), false);
		source.sendFeedback(() -> Text.literal(output), false);
		return 1;
	}

	private static int showChatConfig(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		boolean debug = COMMAND_DEBUG_ENABLED.contains(player.getUuid());
		boolean sounds = !SOUND_DISABLED.contains(player.getUuid());
		ParticleSetting particles = PARTICLE_SETTINGS.getOrDefault(player.getUuid(), ParticleSetting.ON);
		int retries = getRetryLimit(player);
		String sidebar = SIDEBAR_ENABLED ? "ON" : "OFF";

		source.sendFeedback(() -> Text.literal("Chat AI Settings:"), false);
		source.sendFeedback(() -> settingsToggle("Debug", debug, "/chat config debug "), false);
		source.sendFeedback(() -> settingsToggle("Sidebar", SIDEBAR_ENABLED, "/chat config sidebar "), false);
		source.sendFeedback(() -> settingsToggle("Sounds", sounds, "/chat config sounds "), false);
		source.sendFeedback(() -> settingsParticlesLine(particles), false);
		source.sendFeedback(() -> settingsRetriesLine(retries), false);
		source.sendFeedback(() -> Text.literal("Sidebar: " + sidebar), false);
		sendConfigSnapshot(player);
		return 1;
	}

	private static Text settingsToggle(String label, boolean enabled, String commandPrefix) {
		String onCommand = commandPrefix + "on";
		String offCommand = commandPrefix + "off";
		MutableText line = Text.literal(label + ": ").formatted(Formatting.GRAY);
		MutableText on = Text.literal("[ON]").styled(style -> style
			.withColor(enabled ? Formatting.GREEN : Formatting.DARK_GRAY)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, onCommand)));
		MutableText off = Text.literal("[OFF]").styled(style -> style
			.withColor(!enabled ? Formatting.RED : Formatting.DARK_GRAY)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, offCommand)));
		return line.append(on).append(Text.literal(" ")).append(off);
	}

	private static Text settingsParticlesLine(ParticleSetting setting) {
		MutableText line = Text.literal("Particles: ").formatted(Formatting.GRAY);
		line.append(particlesButton("ON", setting == ParticleSetting.ON, "/chat config particles on"));
		line.append(Text.literal(" "));
		line.append(particlesButton("MIN", setting == ParticleSetting.MINIMAL, "/chat config particles minimal"));
		line.append(Text.literal(" "));
		line.append(particlesButton("OFF", setting == ParticleSetting.OFF, "/chat config particles off"));
		return line;
	}

	private static Text particlesButton(String label, boolean active, String command) {
		return Text.literal("[" + label + "]").styled(style -> style
			.withColor(active ? Formatting.GREEN : Formatting.DARK_GRAY)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
	}

	private static Text settingsRetriesLine(int retries) {
		MutableText line = Text.literal("Retries: ").formatted(Formatting.GRAY);
		MutableText value = Text.literal(String.valueOf(retries)).formatted(Formatting.WHITE);
		MutableText minus = Text.literal("[-]").styled(style -> style
			.withColor(Formatting.AQUA)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat config retries " + Math.max(MIN_COMMAND_RETRIES, retries - 1))));
		MutableText plus = Text.literal("[+]").styled(style -> style
			.withColor(Formatting.AQUA)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat config retries " + Math.min(MAX_COMMAND_RETRIES, retries + 1))));
		return line.append(minus).append(Text.literal(" ")).append(value).append(Text.literal(" ")).append(plus);
	}

	private static void handleConfigPacket(net.minecraft.server.MinecraftServer server, ServerPlayerEntity player, String json) {
		JsonObject obj = GSON.fromJson(json, JsonObject.class);
		int type = obj.has("type") ? obj.get("type").getAsInt() : 0;
		server.execute(() -> {
			switch (type) {
				case 0 -> sendConfigSnapshot(player);
				case 1 -> setChatDebugFromPacket(player, obj.get("value").getAsBoolean());
				case 2 -> setChatSidebarFromPacket(obj.get("value").getAsBoolean());
				case 3 -> setChatSoundsFromPacket(player, obj.get("value").getAsBoolean());
				case 4 -> setChatParticlesFromPacket(player, obj.get("value").getAsString());
				case 5 -> setChatRetriesFromPacket(player, obj.get("value").getAsInt());
				case 6 -> setChatModelFromPacket(player, obj.get("value").getAsString());
				case 7 -> setChatKeyFromPacket(player, obj.get("value").getAsString());
				case 8 -> setDefaultKeyFromPacket(player, obj.get("value").getAsString());
				default -> {
				}
			}
			sendConfigSnapshot(player);
		});
	}

	private static void sendConfigSnapshot(ServerPlayerEntity player) {
		JsonObject obj = new JsonObject();
		obj.addProperty("debug", COMMAND_DEBUG_ENABLED.contains(player.getUuid()));
		obj.addProperty("sidebar", SIDEBAR_ENABLED);
		obj.addProperty("sounds", !SOUND_DISABLED.contains(player.getUuid()));
		ParticleSetting particles = PARTICLE_SETTINGS.getOrDefault(player.getUuid(), ParticleSetting.ON);
		obj.addProperty("particles", particles.name().toLowerCase(Locale.ROOT));
		obj.addProperty("retries", getRetryLimit(player));
		ModelPreference model = resolveModelPreference(player.getUuid());
		obj.addProperty("model", model.storage);
		String playerKey = readApiKey(player.getUuid());
		obj.addProperty("hasPlayerKey", playerKey != null && !playerKey.isBlank());
		String serverKey = readDefaultApiKey();
		obj.addProperty("hasServerKey", serverKey != null && !serverKey.isBlank());
		ServerPlayNetworking.send(player, new ConfigPayloadS2C(GSON.toJson(obj)));
	}

	private static void setChatDebugFromPacket(ServerPlayerEntity player, boolean enabled) {
		if (enabled) {
			COMMAND_DEBUG_ENABLED.add(player.getUuid());
		} else {
			COMMAND_DEBUG_ENABLED.remove(player.getUuid());
		}
		savePlayerSettings(player.getUuid());
	}

	private static void setChatSidebarFromPacket(boolean enabled) {
		SIDEBAR_ENABLED = enabled;
		saveGlobalSettings();
	}

	private static void setChatSoundsFromPacket(ServerPlayerEntity player, boolean enabled) {
		if (enabled) {
			SOUND_DISABLED.remove(player.getUuid());
		} else {
			SOUND_DISABLED.add(player.getUuid());
		}
		savePlayerSettings(player.getUuid());
	}

	private static void setChatParticlesFromPacket(ServerPlayerEntity player, String value) {
		ParticleSetting setting = parseParticleSetting(value);
		if (setting != null) {
			PARTICLE_SETTINGS.put(player.getUuid(), setting);
			savePlayerSettings(player.getUuid());
		}
	}

	private static void setChatRetriesFromPacket(ServerPlayerEntity player, int count) {
		int clamped = Math.max(MIN_COMMAND_RETRIES, Math.min(MAX_COMMAND_RETRIES, count));
		COMMAND_RETRY_LIMITS.put(player.getUuid(), clamped);
		savePlayerSettings(player.getUuid());
	}

	private static void setChatModelFromPacket(ServerPlayerEntity player, String value) {
		ModelPreference preference = parseModelPreference(value);
		if (preference != null) {
			MODEL_PREFERENCES.put(player.getUuid(), preference);
			saveModelPreference(player.getUuid(), preference);
		}
	}

	private static void setChatKeyFromPacket(ServerPlayerEntity player, String key) {
		if (key == null || key.isBlank()) {
			return;
		}
		API_KEYS.put(player.getUuid(), key.trim());
		writeApiKey(player.getUuid(), key.trim());
	}

	private static void setDefaultKeyFromPacket(ServerPlayerEntity player, String key) {
		if (!player.hasPermissionLevel(4) || key == null || key.isBlank()) {
			return;
		}
		writeDefaultApiKey(key.trim());
	}

	private static ParticleSetting parseParticleSetting(String value) {
		if (value == null) {
			return null;
		}
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "on" -> ParticleSetting.ON;
			case "off" -> ParticleSetting.OFF;
			case "minimal", "min" -> ParticleSetting.MINIMAL;
			default -> null;
		};
	}

	public record ConfigPayloadC2S(String json) implements CustomPayload {
		public static final Id<ConfigPayloadC2S> ID = new Id<>(CONFIG_PACKET_C2S);
		public static final PacketCodec<RegistryByteBuf, ConfigPayloadC2S> CODEC =
			PacketCodec.tuple(PacketCodecs.STRING, ConfigPayloadC2S::json, ConfigPayloadC2S::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record ConfigPayloadS2C(String json) implements CustomPayload {
		public static final Id<ConfigPayloadS2C> ID = new Id<>(CONFIG_PACKET_S2C);
		public static final PacketCodec<RegistryByteBuf, ConfigPayloadS2C> CODEC =
			PacketCodec.tuple(PacketCodecs.STRING, ConfigPayloadS2C::json, ConfigPayloadS2C::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record AudioPayloadC2S(String mimeType, byte[] data) implements CustomPayload {
		public static final Id<AudioPayloadC2S> ID = new Id<>(AUDIO_PACKET_C2S);
		public static final PacketCodec<RegistryByteBuf, AudioPayloadC2S> CODEC =
			PacketCodec.tuple(
				PacketCodecs.STRING, AudioPayloadC2S::mimeType,
				PacketCodecs.BYTE_ARRAY, AudioPayloadC2S::data,
				AudioPayloadC2S::new
			);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	private static int showChatHelp(ServerCommandSource source, String topic) {
		if (topic == null || topic.isBlank()) {
			source.sendFeedback(() -> Text.literal("Chat AI Help:"), false);
			source.sendFeedback(() -> Text.literal("/chat <text> - Ask the AI or issue tasks."), false);
			source.sendFeedback(() -> Text.literal("/chat clear | /chat new - Reset the chat context."), false);
			source.sendFeedback(() -> Text.literal("/chat cancel | /chat stop - Cancel the active request."), false);
			source.sendFeedback(() -> Text.literal("/chat undo - Undo the last AI command batch."), false);
			source.sendFeedback(() -> Text.literal("/chat history [count|all] [page] - Show recent exchanges."), false);
			source.sendFeedback(() -> Text.literal("/chat export [count|all] [txt|json] - Save chat log."), false);
			source.sendFeedback(() -> Text.literal("/chat config - Show settings menu."), false);
			source.sendFeedback(() -> Text.literal("/chatdebug on|off - Show executed commands/output."), false);
			source.sendFeedback(() -> Text.literal("/chatsidebar on|off - Toggle the sidebar stats."), false);
			source.sendFeedback(() -> Text.literal("/chatsounds on|off - Toggle AI sounds."), false);
			source.sendFeedback(() -> Text.literal("/chatparticles on|off|minimal - Toggle AI particles."), false);
			source.sendFeedback(() -> Text.literal("/chatmodel [model] - Show or set model."), false);
			source.sendFeedback(() -> Text.literal("/chatretries <0-10> - Set command retry limit."), false);
			source.sendFeedback(() -> Text.literal("/chatkey <key> - Save your API key."), false);
			source.sendFeedback(() -> Text.literal("/chatkey default <key> - Save a server default key."), false);
			source.sendFeedback(() -> Text.literal("/chatkey info - Show key sources."), false);
			source.sendFeedback(() -> Text.literal("/chatkey clear - Remove saved API key."), false);
			source.sendFeedback(() -> Text.literal("/chathelp <command> - Show help for a command."), false);
			return 1;
		}

		String key = topic.toLowerCase(Locale.ROOT);
		switch (key) {
			case "chat":
				source.sendFeedback(() -> Text.literal("/chat <text> - Ask or command the AI."), false);
				source.sendFeedback(() -> Text.literal("Examples: /chat give me a diamond sword"), false);
				break;
			case "chatdebug":
				source.sendFeedback(() -> Text.literal("/chatdebug on|off - Toggle command output lines."), false);
				break;
			case "config":
				source.sendFeedback(() -> Text.literal("/chat config - Show settings menu."), false);
				source.sendFeedback(() -> Text.literal("/chat config debug|sidebar|sounds|particles|retries ..."), false);
				break;
			case "chatsidebar":
				source.sendFeedback(() -> Text.literal("/chatsidebar on|off - Toggle the sidebar stats."), false);
				break;
			case "chatsounds":
				source.sendFeedback(() -> Text.literal("/chatsounds on|off - Toggle AI sounds."), false);
				break;
			case "chatparticles":
				source.sendFeedback(() -> Text.literal("/chatparticles on|off|minimal - Toggle AI particles."), false);
				break;
			case "chatmodel":
				source.sendFeedback(() -> Text.literal("/chatmodel - Show current model."), false);
				source.sendFeedback(() -> Text.literal("/chatmodel flash|flash-thinking|pro|auto - Set model preference."), false);
				break;
			case "history":
				source.sendFeedback(() -> Text.literal("/chat history [count|all] [page] - View chat history."), false);
				break;
			case "export":
				source.sendFeedback(() -> Text.literal("/chat export [count|all] [txt|json] - Export chat log."), false);
				break;
			case "chatretries":
				source.sendFeedback(() -> Text.literal("/chatretries <0-10> - Set max retries for command mode."), false);
				break;
			case "chatkey":
				source.sendFeedback(() -> Text.literal("/chatkey <key> - Save API key locally."), false);
				source.sendFeedback(() -> Text.literal("/chatkey default <key> - Save server default key."), false);
				source.sendFeedback(() -> Text.literal("/chatkey default clear - Remove server default key."), false);
				source.sendFeedback(() -> Text.literal("/chatkey info - Show key sources."), false);
				source.sendFeedback(() -> Text.literal("/chatkey clear - Remove saved key."), false);
				break;
			case "clear":
			case "new":
			case "chatclear":
				source.sendFeedback(() -> Text.literal("/chat clear | /chat new - Reset chat context."), false);
				break;
			case "cancel":
			case "stop":
				source.sendFeedback(() -> Text.literal("/chat cancel | /chat stop - Cancel the active request."), false);
				break;
			case "undo":
				source.sendFeedback(() -> Text.literal("/chat undo - Undo the last AI command batch."), false);
				break;
			default:
				source.sendFeedback(() -> Text.literal("Unknown topic. Use /chathelp for a list."), false);
				break;
		}
		return 1;
	}

	private static void handleGeminiFlow(ServerCommandSource source, ServerPlayerEntity player, String apiKey, String prompt, String context, ModelChoice overrideModel) {
		RequestState state = player == null ? null : REQUEST_STATES.get(player.getUuid());
		if (state != null && state.cancelled.get()) {
			return;
		}
		if (context == null || context.isBlank()) {
			context = player == null ? "Context: unknown" : buildContext(source, player, prompt);
		}
		List<ChatTurn> history = player == null ? List.of() : getHistory(player.getUuid());
		long startNs = System.nanoTime();
		ModelChoice modelChoice = overrideModel != null ? overrideModel : selectModel(player, prompt, context, history);

		if (player != null) {
			AiStats stats = getStats(player.getUuid());
			stats.state = "THINKING";
			stats.contextSize = history.size();
			stats.tokenPercent = estimateTokenPercent(context, history, prompt);
			updateSidebar(player, stats);
		}

		ModeMessage reply = callGeminiSafely(apiKey, prompt, context, history, null, modelChoice);
		if (state != null && state.cancelled.get()) {
			return;
		}

		if (player != null && reply.commands.isEmpty()) {
			String autoSkill = selectAutoSkillCommand(prompt, reply.message);
			if (autoSkill != null) {
				setStatus(player, "Gathering data...", Formatting.AQUA);
				String skillOutput = executeSkillCommand(player, autoSkill, true);
				String cleaned = stripSkillPrefix(skillOutput);
				String skillContext = "Skill output: " + cleaned + "\nContinue the task using this data. Respond with JSON only.";
				reply = callGeminiSafely(apiKey, prompt, context, history, skillContext, modelChoice);
				if (state != null && state.cancelled.get()) {
					return;
				}
			}
		}

		if ("PLAN".equals(reply.mode) && player != null) {
			String planInstruction =
				"Convert the plan above into COMMAND mode. Return mode COMMAND, message \"Initiating plan.\", and commands array only. " +
				"Plan: " + reply.message;
			ModeMessage planCommands = callGeminiSafely(apiKey, prompt, context, history, planInstruction, modelChoice);
			if ("COMMAND".equals(planCommands.mode)) {
				reply = planCommands;
			}
		}

		if ("COMMAND".equals(reply.mode) && player != null) {
			reply = handleCommandMode(source, player, apiKey, prompt, context, history, reply, modelChoice);
		}

		long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
		ModeMessage finalReply = reply;
		String contextForStats = context;
		source.getServer().execute(() -> {
			if (state != null && state.cancelled.get()) {
				REQUEST_STATES.remove(player.getUuid());
				return;
			}
			if (player != null) {
				THINKING_TICKS.remove(player.getUuid());
				STATUS_STATE.remove(player.getUuid());
				if (!finalReply.message.startsWith("Error:")) {
					MODE_STATE.put(player.getUuid(), new ModeState(modeLabel(finalReply.mode), modeColor(finalReply.mode), 60));
				}

				AiStats stats = getStats(player.getUuid());
				stats.mode = finalReply.mode;
				stats.lastResponseMs = elapsedMs;
				stats.recordResponseTime(elapsedMs);
				stats.state = finalReply.searchUsed ? "SEARCHING" : resolveState(finalReply.message);
				stats.contextSize = history.size();
				stats.tokenPercent = estimateTokenPercent(contextForStats, history, prompt);
				if (!finalReply.message.startsWith("Error:")) {
					stats.sessionTokens += estimateTokens(finalReply.message);
				}
				updateSidebar(player, stats);
			}

			if (finalReply.message.startsWith("Error:")) {
				source.sendError(Text.literal(finalReply.message));
			} else {
				MutableText message = Text.literal(finalReply.message);
				if (finalReply.mode.equals("ASK")) {
					source.sendFeedback(() -> message, false);
				} else {
					MutableText prefix = Text.literal("[" + finalReply.mode + "] ").formatted(modeColor(finalReply.mode));
					source.sendFeedback(() -> prefix.append(message), false);
					if (finalReply.mode.equals("COMMAND") && shouldShowCommandDebug(player)
						&& finalReply.commands != null && !finalReply.commands.isEmpty()) {
						List<String> filtered = filterSkillCommands(finalReply.commands);
						if (!filtered.isEmpty()) {
							String commandText = String.join(" | ", filtered);
							MutableText commandLine = Text.literal("Commands: " + commandText).formatted(Formatting.DARK_GRAY);
							source.sendFeedback(() -> commandLine, false);
						}
						List<String> outputLines = getStats(player.getUuid()).lastCommandOutput;
						if (outputLines != null && !outputLines.isEmpty()) {
							String outputText = String.join(" | ", outputLines);
							MutableText outputLine = Text.literal("Output: " + outputText).formatted(Formatting.DARK_GRAY);
							source.sendFeedback(() -> outputLine, false);
						}
					}
				}

				if (player != null && finalReply.searchUsed) {
					SEARCHING_TICKS.put(player.getUuid(), 0);
				}

				if (finalReply.searchUsed && finalReply.sources != null && !finalReply.sources.isEmpty()) {
					MutableText searchLine = rainbowText("Searching the web...", 0);
					source.sendFeedback(() -> searchLine, false);
					MutableText sourcesLine = buildSourcesLine(finalReply.sources);
					source.sendFeedback(() -> sourcesLine, false);
				}
			}

			if (player != null) {
				emitFeedbackEffects(player, finalReply);
			}

			if (player != null && !finalReply.message.startsWith("Error:")) {
				appendHistory(player.getUuid(), prompt, finalReply, elapsedMs);
			}
			if (player != null && modelChoice == ModelChoice.FLASH
				&& "COMMAND".equals(finalReply.mode)
				&& finalReply.message.contains("could not produce valid commands")) {
				sendSmarterModelPrompt(player);
			}
			if (player != null) {
				REQUEST_STATES.remove(player.getUuid());
			}
		});
	}

	private static boolean shouldShowCommandDebug(ServerPlayerEntity player) {
		if (player == null) {
			return false;
		}
		return COMMAND_DEBUG_ENABLED.contains(player.getUuid());
	}

	private static String resolveApiKey(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player != null) {
			String cached = API_KEYS.get(player.getUuid());
			if (cached != null && !cached.isBlank()) {
				return cached;
			}
			String stored = readApiKey(player.getUuid());
			if (stored != null && !stored.isBlank()) {
				API_KEYS.put(player.getUuid(), stored);
				return stored;
			}
		}
		String serverDefault = readDefaultApiKey();
		if (serverDefault != null && !serverDefault.isBlank()) {
			return serverDefault;
		}
		return System.getenv(API_KEY_ENV);
	}

	private static int getRetryLimit(ServerPlayerEntity player) {
		if (player == null) {
			return DEFAULT_COMMAND_RETRIES;
		}
		return COMMAND_RETRY_LIMITS.getOrDefault(player.getUuid(), DEFAULT_COMMAND_RETRIES);
	}

	private static ModeMessage handleCommandMode(
		ServerCommandSource source,
		ServerPlayerEntity player,
		String apiKey,
		String prompt,
		String context,
		List<ChatTurn> history,
		ModeMessage initial,
		ModelChoice modelChoice
	) {
		ModeMessage current = initial;
		int steps = 0;
		int retryLimit = getRetryLimit(player);
		for (int attempt = 1; attempt <= retryLimit; attempt++) {
			setRetryStats(player, attempt - 1);
			PreparedCommands prepared = prepareCommandsForExecution(player, current.commands);
			CommandResult validation = validateCommands(player, prepared.executeCommands);
			if (!validation.success) {
				if (attempt == retryLimit) {
					LOGGER.info("AI retry exhausted for player {}. Validation errors: {}", player.getName().getString(), validation.errorSummary);
					return new ModeMessage("COMMAND", "AI could not produce valid commands after several tries.", List.of(), false, List.of());
				}

				LOGGER.info("AI command retry {}/{} for player {}. Validation errors: {}", attempt, retryLimit, player.getName().getString(), validation.errorSummary);
				setStatus(player, "AI encountered an error, retrying (" + attempt + "/" + retryLimit + ")...", Formatting.RED);
				String errorContext = "Command errors: " + validation.errorSummary;
				current = callGeminiSafely(apiKey, prompt, context, history, errorContext, modelChoice);
				if (!"COMMAND".equals(current.mode)) {
					return current;
				}
				continue;
			}

			CommandResult result = executeCommands(player, prepared.executeCommands);
			if (player != null) {
				AiStats stats = getStats(player.getUuid());
				stats.lastCommandOutput = limitOutput(filterUserOutputs(result.outputs));
				updateSidebar(player, stats);
			}
			if (result.success) {
				if (!prepared.undoCommands.isEmpty()) {
					LAST_UNDO_COMMANDS.put(player.getUuid(), prepared.undoCommands);
				}
				steps++;
				if (shouldContinueAfterOutput(prepared.executeCommands, result.outputs) && steps < MAX_COMMAND_STEPS) {
					List<String> outputsForAi = outputsForAi(result.outputs);
					String outputContext = "Command output: " + String.join(" | ", outputsForAi) +
						". Continue the command sequence to complete the task.";
					current = callGeminiSafely(apiKey, prompt, context, history, outputContext, modelChoice);
					if (!"COMMAND".equals(current.mode)) {
						return current;
					}
					continue;
				}

				setRetryStats(player, 0);
				return current;
			}

			if (attempt == retryLimit) {
				LOGGER.info("AI retry exhausted for player {}. Last errors: {}", player.getName().getString(), result.errorSummary);
				return new ModeMessage("COMMAND", "AI could not complete the command after several tries.", List.of(), false, List.of());
			}

			LOGGER.info("AI command retry {}/{} for player {}. Errors: {}", attempt, retryLimit, player.getName().getString(), result.errorSummary);
			setStatus(player, "AI encountered an error, retrying (" + attempt + "/" + retryLimit + ")...", Formatting.RED);
			String errorContext = "Command errors: " + result.errorSummary;
			current = callGeminiSafely(apiKey, prompt, context, history, errorContext, modelChoice);
			if (!"COMMAND".equals(current.mode)) {
				return current;
			}
		}

		return current;
	}

	private static CommandResult executeCommands(ServerPlayerEntity player, List<String> commands) {
		if (commands == null || commands.isEmpty()) {
			return new CommandResult(true, "", List.of());
		}

		List<String> errors = new ArrayList<>();
		List<String> outputs = new ArrayList<>();
		for (String command : commands) {
			if (command == null || command.isBlank()) {
				continue;
			}

			String sanitized = sanitizeCommand(command);
			if (isSkillCommand(sanitized)) {
				outputs.add(executeSkillCommand(player, sanitized, true));
				continue;
			}
			CommandResult result = executeCommandOnServerThread(player, sanitized);
			if (result.outputs != null && !result.outputs.isEmpty()) {
				outputs.addAll(result.outputs);
			}
			if (!result.success) {
				errors.add(result.errorSummary);
				continue;
			}
			if (commandIndicatesAirReplace(sanitized, result.outputs)) {
				errors.add("Command error: target slot was empty.");
				continue;
			}
		}

		if (errors.isEmpty()) {
			return new CommandResult(true, "", outputs);
		}

		return new CommandResult(false, String.join(" | ", errors), outputs);
	}

	private static boolean isSkillCommand(String command) {
		if (command == null) {
			return false;
		}
		return command.startsWith("chat skill");
	}

	private static String executeSkillCommand(ServerPlayerEntity player, String command, boolean internal) {
		String prefix = internal ? "SKILL: " : "";
		if (player == null) {
			return prefix + "error: no player";
		}
		String[] parts = command.split("\\s+");
		if (parts.length < 3) {
			return prefix + "error: missing skill";
		}
		String skill = parts[2].toLowerCase(Locale.ROOT);
		return switch (skill) {
			case "inventory" -> {
				String inventory = buildInventoryContext(player, "inventory", true, Boolean.TRUE);
				yield prefix + (inventory.isEmpty() ? "Inventory: Empty" : "Inventory: " + inventory);
			}
			case "nearby" -> {
				String nearby = buildNearbyEntitiesContext(player, "scan entities nearby", true);
				yield prefix + (nearby.isEmpty() ? "Nearby entities: none" : "Nearby entities: " + nearby);
			}
			case "stats" -> prefix + buildPlayerStatsContext(player);
			case "recipe", "smelt" -> {
				String target = parts.length >= 4 ? parts[3].toLowerCase(Locale.ROOT) : "";
				yield prefix + buildRecipeSkillOutput(player, target, skill.equals("smelt"));
			}
			case "nbt" -> {
				String target = parts.length >= 4 ? parts[3].toLowerCase(Locale.ROOT) : "mainhand";
				ItemStack stack = resolveNbtTarget(player, parts, target);
				yield prefix + formatNbtEntry(target, stack);
			}
			default -> prefix + "error: unknown skill";
		};
	}

	private static ItemStack resolveNbtTarget(ServerPlayerEntity player, String[] parts, String target) {
		if ("mainhand".equals(target)) {
			return player.getMainHandStack();
		}
		if ("offhand".equals(target)) {
			return player.getOffHandStack();
		}
		if ("slot".equals(target) && parts.length >= 5) {
			return stackAtSlot(player, parseSlotIndex(parts[4]));
		}
		if (target.startsWith("slot")) {
			int index = parseSlotIndex(target.substring(4));
			return stackAtSlot(player, index);
		}
		return stackAtSlot(player, parseSlotIndex(target));
	}

	private static ItemStack stackAtSlot(ServerPlayerEntity player, int index) {
		if (player == null || index < 0) {
			return ItemStack.EMPTY;
		}
		PlayerInventory inventory = player.getInventory();
		if (index >= inventory.size()) {
			return ItemStack.EMPTY;
		}
		return inventory.getStack(index);
	}

	private static int parseSlotIndex(String value) {
		try {
			return Integer.parseInt(value.trim());
		} catch (Exception e) {
			return -1;
		}
	}

	private static String formatNbtEntry(String label, ItemStack stack) {
		if (!isStackPresent(stack)) {
			return "NBT: empty";
		}
		String id = Registries.ITEM.getId(stack.getItem()).toString();
		StringBuilder entry = new StringBuilder();
		entry.append("NBT ").append(label).append(": ").append(id).append(" x").append(stack.getCount());
		String components = summarizeComponents(stack.getComponents().toString());
		if (components != null && !components.isBlank() && !components.equals("[]")) {
			entry.append(" components=").append(components);
		}
		return entry.toString();
	}

	private static String buildPlayerStatsContext(ServerPlayerEntity player) {
		if (player == null) {
			return "Stats: unknown";
		}
		float health = player.getHealth();
		float maxHealth = player.getMaxHealth();
		int armor = player.getArmor();
		float absorption = player.getAbsorptionAmount();
		int food = player.getHungerManager().getFoodLevel();
		float saturation = player.getHungerManager().getSaturationLevel();
		int xpLevel = player.experienceLevel;
		float xpProgress = player.experienceProgress;
		Collection<StatusEffectInstance> effects = player.getStatusEffects();
		List<String> effectList = new ArrayList<>();
		for (StatusEffectInstance effect : effects) {
			String id = Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString();
			effectList.add(id + " x" + (effect.getAmplifier() + 1) + " (" + effect.getDuration() + "t)");
		}
		String effectsText = effectList.isEmpty() ? "none" : String.join(", ", effectList);
		return "Stats: health " + formatStat(health) + "/" + formatStat(maxHealth)
			+ " absorption " + formatStat(absorption)
			+ " armor " + armor
			+ " food " + food
			+ " saturation " + formatStat(saturation)
			+ " xp level " + xpLevel
			+ " xp progress " + String.format(Locale.ROOT, "%.2f", xpProgress)
			+ " effects [" + effectsText + "]";
	}

	private static String formatStat(float value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private static String buildRecipeSkillOutput(ServerPlayerEntity player, String itemToken, boolean smeltOnly) {
		if (player == null) {
			return "Recipe: unknown";
		}
		if (itemToken == null || itemToken.isBlank()) {
			return "Recipe error: missing item";
		}
		ItemStack outputStack = resolveItemStack(itemToken);
		if (outputStack == null || outputStack.isEmpty()) {
			List<String> suggestions = findRegistryMatches(Registries.ITEM.getIds(), List.of(itemToken), 5);
			return suggestions.isEmpty()
				? "Recipe error: unknown item " + itemToken
				: "Recipe error: unknown item " + itemToken + ". Suggestions: " + String.join(", ", suggestions);
		}

		RecipeManager manager = player.getServerWorld().getRecipeManager();
		List<String> results = new ArrayList<>();
		collectRecipesForAllTypes(manager, player, outputStack, smeltOnly, results);

		if (results.isEmpty()) {
			return "Recipes for " + itemToken + ": none found";
		}
		return "Recipes for " + itemToken + ": " + String.join(" | ", results);
	}

	private static void collectRecipesForAllTypes(RecipeManager manager, ServerPlayerEntity player, ItemStack outputStack, boolean smeltOnly, List<String> results) {
		for (RecipeType<?> type : Registries.RECIPE_TYPE) {
			String typeId = Registries.RECIPE_TYPE.getId(type).toString();
			if (smeltOnly && !isCookingType(typeId)) {
				continue;
			}
			try {
				List<RecipeEntry<?>> entries = new ArrayList<>(manager.values());
				for (RecipeEntry<?> entry : entries) {
					if (entry == null || entry.value() == null || entry.value().getType() != type) {
						continue;
					}
					Recipe<?> recipe = entry.value();
					ItemStack result = recipe.getResult(player.getRegistryManager());
					if (result == null || result.isEmpty()) {
						continue;
					}
					if (result.getItem() == outputStack.getItem()) {
						results.add(formatRecipeEntry(typeId, entry, recipe, player));
						if (results.size() >= 10) {
							return;
						}
					}
				}
			} catch (Exception e) {
				// ignore recipe type if unavailable
			}
		}
	}

	private static boolean isCookingType(String typeId) {
		if (typeId == null) {
			return false;
		}
		String lower = typeId.toLowerCase(Locale.ROOT);
		return lower.contains("smelting")
			|| lower.contains("blasting")
			|| lower.contains("smoking")
			|| lower.contains("campfire");
	}

	private static String formatRecipeEntry(String typeId, RecipeEntry<?> entry, Recipe<?> recipe, ServerPlayerEntity player) {
		ItemStack result = recipe.getResult(player.getRegistryManager());
		String resultId = Registries.ITEM.getId(result.getItem()).toString();
		int count = result.getCount();
		String ingredients = formatIngredients(recipe);
		return typeId + ":" + entry.id().toString() + " -> " + resultId + " x" + count + " [" + ingredients + "]";
	}

	private static String formatIngredients(Recipe<?> recipe) {
		List<Ingredient> ingredients = recipe.getIngredients();
		if (ingredients == null || ingredients.isEmpty()) {
			return "no-ingredients";
		}
		List<String> parts = new ArrayList<>();
		for (Ingredient ingredient : ingredients) {
			String label = formatIngredient(ingredient);
			if (!label.isBlank()) {
				parts.add(label);
			}
			if (parts.size() >= 9) {
				break;
			}
		}
		return parts.isEmpty() ? "no-ingredients" : String.join(", ", parts);
	}

	private static String formatIngredient(Ingredient ingredient) {
		if (ingredient == null) {
			return "";
		}
		ItemStack[] stacks = ingredient.getMatchingStacks();
		if (stacks == null || stacks.length == 0) {
			return "empty";
		}
		List<String> ids = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			ids.add(Registries.ITEM.getId(stack.getItem()).toString());
			if (ids.size() >= 3) {
				break;
			}
		}
		if (ids.isEmpty()) {
			return "empty";
		}
		String joined = String.join("/", ids);
		if (stacks.length > ids.size()) {
			joined += "/+" + (stacks.length - ids.size());
		}
		return joined;
	}

	private static ItemStack resolveItemStack(String token) {
		String normalized = token.toLowerCase(Locale.ROOT);
		String idText = normalized.contains(":") ? normalized : "minecraft:" + normalized;
		Identifier id = Identifier.tryParse(idText);
		if (id == null || !Registries.ITEM.containsId(id)) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(Registries.ITEM.get(id));
	}

	private static int undoLastCommands(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		List<String> undo = LAST_UNDO_COMMANDS.get(player.getUuid());
		if (undo == null || undo.isEmpty()) {
			source.sendFeedback(() -> Text.literal("Nothing to undo."), false);
			return 1;
		}
		CommandResult result = executeCommands(player, undo);
		LAST_UNDO_COMMANDS.remove(player.getUuid());
		if (result.success) {
			source.sendFeedback(() -> Text.literal("Undo complete."), false);
			return 1;
		}
		source.sendFeedback(() -> Text.literal("Undo completed with issues: " + result.errorSummary), false);
		return 1;
	}

	private static PreparedCommands prepareCommandsForExecution(ServerPlayerEntity player, List<String> commands) {
		List<String> execute = new ArrayList<>();
		List<String> undo = new ArrayList<>();
		if (commands == null) {
			return new PreparedCommands(execute, undo);
		}
		String tagPrefix = "ai_undo_" + player.getUuid().toString().replace("-", "").substring(0, 8);
		int index = 0;
		for (String raw : commands) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String command = sanitizeCommand(raw);
			if (command.isBlank()) {
				continue;
			}
			String lower = command.toLowerCase(Locale.ROOT);
			if (lower.startsWith("summon ")) {
				String tag = tagPrefix + "_" + index;
				String tagged = appendSummonTag(command, tag);
				execute.add(tagged);
				undo.add("kill @e[tag=" + tag + "]");
			} else {
				execute.add(command);
				String undoCommand = buildUndoCommand(command);
				if (undoCommand != null) {
					undo.add(undoCommand);
				}
			}
			index++;
		}
		return new PreparedCommands(execute, undo);
	}

	private static String buildUndoCommand(String command) {
		String[] parts = command.split("\\s+");
		if (parts.length == 0) {
			return null;
		}
		String root = parts[0];
		switch (root) {
			case "give" -> {
				if (parts.length < 3) {
					return null;
				}
				String target = parts[1];
				String item = parts[2];
				int count = 1;
				if (parts.length >= 4) {
					try {
						count = Integer.parseInt(parts[3]);
					} catch (NumberFormatException ignored) {
					}
				}
				return "clear " + target + " " + item + " " + count;
			}
			case "effect" -> {
				if (parts.length >= 4 && parts[1].equals("give")) {
					String target = parts[2];
					String effect = parts[3];
					return "effect clear " + target + " " + effect;
				}
				return null;
			}
			case "item" -> {
				if (parts.length >= 6 && parts[1].equals("replace") && parts[2].equals("entity")) {
					String target = parts[3];
					String slot = parts[4];
					return "item replace entity " + target + " " + slot + " with minecraft:air";
				}
				return null;
			}
			default -> {
				return null;
			}
		}
	}

	private static String appendSummonTag(String command, String tag) {
		if (command.contains("Tags:[")) {
			int start = command.indexOf("Tags:[");
			int end = command.indexOf("]", start);
			if (end > start) {
				String before = command.substring(0, end);
				String after = command.substring(end);
				return before + ",\"" + tag + "\"" + after;
			}
		}
		int brace = command.lastIndexOf('}');
		if (brace > -1) {
			String before = command.substring(0, brace);
			String after = command.substring(brace);
			if (before.contains("Tags:")) {
				return command;
			}
			return before + ",Tags:[\"" + tag + "\"]" + after;
		}
		return command + " {Tags:[\"" + tag + "\"]}";
	}

	private static CommandResult executeCommandOnServerThread(ServerPlayerEntity player, String command) {
		var server = player.getServer();
		var future = new java.util.concurrent.CompletableFuture<CommandResult>();
		server.execute(() -> {
			List<String> messages = new ArrayList<>();
			CommandOutputCollector output = new CommandOutputCollector(messages);
			var source = server.getCommandSource()
				.withEntity(player)
				.withPosition(player.getPos())
				.withWorld(player.getServerWorld())
				.withLevel(4)
				.withOutput(output);

			try {
				server.getCommandManager().executeWithPrefix(source, command);
			} catch (Exception e) {
				String msg = e.getMessage() == null ? ("Command failed: " + command) : e.getMessage();
				future.complete(new CommandResult(false, msg, messages));
				return;
			}

			String errorMessage = findErrorMessage(messages);
			if (errorMessage != null) {
				future.complete(new CommandResult(false, "Command error: " + errorMessage, messages));
			} else {
				future.complete(new CommandResult(true, "", messages));
			}
		});

		try {
			return future.get();
		} catch (Exception e) {
			String msg = e.getMessage() == null ? ("Command failed: " + command) : e.getMessage();
			return new CommandResult(false, msg, List.of());
		}
	}

	private static String findErrorMessage(List<String> messages) {
		for (String message : messages) {
			if (isErrorMessage(message)) {
				return message;
			}
		}
		return null;
	}

	private static boolean isErrorMessage(String message) {
		if (message == null || message.isBlank()) {
			return false;
		}

		String lower = message.toLowerCase();
		return lower.contains("unknown")
			|| lower.contains("no entity")
			|| lower.contains("failed")
			|| lower.contains("invalid")
			|| lower.contains("escape sequence")
			|| lower.contains("error")
			|| lower.contains("incorrect")
			|| lower.contains("cannot")
			|| lower.contains("can't")
			|| lower.contains("not found")
			|| lower.contains("expected");
	}

	private static boolean commandIndicatesAirReplace(String command, List<String> outputs) {
		if (command == null || outputs == null || outputs.isEmpty()) {
			return false;
		}
		String lowerCommand = command.toLowerCase(Locale.ROOT);
		if (!lowerCommand.startsWith("item modify entity") && !lowerCommand.startsWith("item replace entity")) {
			return false;
		}
		for (String output : outputs) {
			if (output == null) {
				continue;
			}
			String lower = output.toLowerCase(Locale.ROOT);
			if (lower.contains("with [air]") || lower.contains("with air")) {
				return true;
			}
		}
		return false;
	}

	private static void setStatus(ServerPlayerEntity player, String message, Formatting color) {
		STATUS_STATE.put(player.getUuid(), new StatusState(message, color, 40));
	}

	private static boolean shouldContinueAfterOutput(List<String> commands, List<String> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return false;
		}

		boolean hasLocate = commands.stream().anyMatch(c -> c != null && c.contains("locate"));
		boolean hasTeleport = commands.stream().anyMatch(c -> c != null && (c.contains("tp ") || c.contains("teleport")));
		if (hasLocate && !hasTeleport) {
			return true;
		}
		boolean hasDataGet = commands.stream().anyMatch(c -> c != null && c.contains("data get"));
		if (hasDataGet) {
			return true;
		}
		for (String output : outputs) {
			if (output != null && output.startsWith("SKILL:")) {
				return true;
			}
		}

		for (String output : outputs) {
			String lower = output.toLowerCase(Locale.ROOT);
			if (lower.contains("located") || lower.contains("coordinates") || lower.contains("is at")) {
				return true;
			}
			if (lower.contains("has the following entity data")
				|| lower.contains("has the following block data")
				|| lower.contains("has the following") && lower.contains("data:")) {
				return true;
			}
		}
		return false;
	}

	private static List<String> outputsForAi(List<String> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return List.of();
		}
		List<String> cleaned = new ArrayList<>();
		for (String output : outputs) {
			if (output == null || output.isBlank()) {
				continue;
			}
			if (output.startsWith("SKILL:")) {
				cleaned.add(output.substring(6).trim());
			} else {
				cleaned.add(output);
			}
		}
		return cleaned;
	}

	private static List<String> filterUserOutputs(List<String> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return List.of();
		}
		List<String> filtered = new ArrayList<>();
		for (String output : outputs) {
			if (output == null || output.isBlank()) {
				continue;
			}
			if (output.startsWith("SKILL:")) {
				continue;
			}
			filtered.add(output);
		}
		return filtered;
	}

	private static void setRetryStats(ServerPlayerEntity player, int retries) {
		if (player == null) {
			return;
		}
		AiStats stats = getStats(player.getUuid());
		stats.retries = retries;
		stats.state = retries > 0 ? "ERROR" : stats.state;
		updateSidebar(player, stats);
	}

	private static List<String> filterSkillCommands(List<String> commands) {
		if (commands == null || commands.isEmpty()) {
			return List.of();
		}
		List<String> filtered = new ArrayList<>();
		for (String command : commands) {
			if (command == null || command.isBlank()) {
				continue;
			}
			String sanitized = sanitizeCommand(command);
			if (isSkillCommand(sanitized)) {
				continue;
			}
			filtered.add(command);
		}
		return filtered;
	}

	private static CommandResult validateCommands(ServerPlayerEntity player, List<String> commands) {
		if (commands == null || commands.isEmpty()) {
			return new CommandResult(false, "No commands provided.", List.of());
		}

		var dispatcher = player.getServer().getCommandManager().getDispatcher();
		List<String> errors = new ArrayList<>();
		for (String raw : commands) {
			String command = sanitizeCommand(raw);
			if (command.isBlank()) {
				errors.add("Empty command.");
				continue;
			}
			if (command.contains("\n") || command.contains("\r")) {
				errors.add("Command contains a newline and is likely invalid: " + command);
				continue;
			}

			String first = command.split("\\s+")[0];
			if (dispatcher.getRoot().getChild(first) == null) {
				errors.add("Unknown command: " + first);
			}

			String lower = command.toLowerCase(Locale.ROOT);
			if (command.contains("Enchantments") || command.contains("StoredEnchantments")
				|| command.contains("Damage") || lower.contains("lvl:")) {
				errors.add("Forbidden legacy NBT tag in command: " + command);
			}

			if (first.equals("tellraw")) {
				String[] parts = command.split("\\s+", 3);
				if (parts.length < 3) {
					errors.add("tellraw missing JSON: " + command);
				} else {
					try {
						JsonParser.parseString(parts[2]);
					} catch (Exception e) {
						errors.add("Invalid tellraw JSON: " + command);
					}
				}
			}
		}

		if (errors.isEmpty()) {
			return new CommandResult(true, "", List.of());
		}

		return new CommandResult(false, String.join(" | ", errors), List.of());
	}

	private static String sanitizeCommand(String command) {
		if (command == null) {
			return "";
		}
		String trimmed = command.trim();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1).trim();
		}
		if (trimmed.contains("\\n")) {
			trimmed = trimmed.replace("\\n", "\\\\n");
		}
		if (trimmed.contains("\\r")) {
			trimmed = trimmed.replace("\\r", "\\\\r");
		}
		return trimmed;
	}

	private static ModeMessage callGeminiSafely(
		String apiKey,
		String prompt,
		String context,
		List<ChatTurn> history,
		String errorContext,
		ModelChoice modelChoice
	) {
		try {
			return callGemini(apiKey, prompt, context, history, errorContext, modelChoice);
		} catch (Exception e) {
			return new ModeMessage("ASK", "Error: " + e.getMessage(), List.of(), false, List.of());
		}
	}

	private static ModeMessage callGemini(
		String apiKey,
		String prompt,
		String context,
		List<ChatTurn> history,
		String errorContext,
		ModelChoice modelChoice
	) throws Exception {
		JsonObject request = new JsonObject();
		JsonObject systemInstruction = new JsonObject();
		JsonArray systemParts = new JsonArray();
		JsonObject systemPart = new JsonObject();
		systemPart.addProperty("text", SYSTEM_PROMPT);
		systemParts.add(systemPart);
		systemInstruction.add("parts", systemParts);
		request.add("systemInstruction", systemInstruction);

		JsonArray contents = new JsonArray();
		if (history != null && !history.isEmpty()) {
			for (ChatTurn turn : history) {
				JsonObject historyContent = new JsonObject();
				historyContent.addProperty("role", turn.role);
				JsonArray historyParts = new JsonArray();
				JsonObject historyPart = new JsonObject();
				historyPart.addProperty("text", turn.text);
				historyParts.add(historyPart);
				historyContent.add("parts", historyParts);
				contents.add(historyContent);
			}
		}
		JsonObject content = new JsonObject();
		content.addProperty("role", "user");
		JsonArray parts = new JsonArray();
		JsonObject part = new JsonObject();
		StringBuilder userText = new StringBuilder();
		userText.append(context).append("\nUser: ").append(prompt);
		if (errorContext != null && !errorContext.isBlank()) {
			userText.append("\n").append(errorContext);
		}
		part.addProperty("text", userText.toString());
		parts.add(part);
		content.add("parts", parts);
		contents.add(content);
		request.add("contents", contents);

		ModelChoice effectiveChoice = modelChoice == null ? ModelChoice.FLASH : modelChoice;
		JsonObject generationConfig = new JsonObject();
		JsonObject thinkingConfig = new JsonObject();
		thinkingConfig.addProperty("thinkingLevel", effectiveChoice.thinkingLevel);
		generationConfig.add("thinkingConfig", thinkingConfig);
		request.add("generationConfig", generationConfig);

		JsonArray tools = new JsonArray();
		JsonObject googleSearch = new JsonObject();
		googleSearch.add("google_search", new JsonObject());
		tools.add(googleSearch);
		request.add("tools", tools);

		String body = GSON.toJson(request);
		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create(GEMINI_ENDPOINT_BASE + effectiveChoice.modelId + ":generateContent?key=" + apiKey))
			.header("Content-Type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();

		HttpResponse<String> response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			return new ModeMessage("ASK", "Error: HTTP " + response.statusCode() + " - " + response.body(), List.of(), false, List.of());
		}

		JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
		JsonArray candidates = json.getAsJsonArray("candidates");
		if (candidates == null || candidates.isEmpty()) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of());
		}

		JsonObject first = candidates.get(0).getAsJsonObject();
		JsonObject responseContent = first.getAsJsonObject("content");
		if (responseContent == null) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of());
		}

		JsonArray responseParts = responseContent.getAsJsonArray("parts");
		if (responseParts == null || responseParts.isEmpty()) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of());
		}

		JsonObject firstPart = responseParts.get(0).getAsJsonObject();
		if (!firstPart.has("text")) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of());
		}

		String raw = firstPart.get("text").getAsString();
		SearchMetadata search = parseSearchMetadata(first);
		return parseModeMessage(raw, search.used, search.sources);
	}

	private static String callGeminiTranscribe(String apiKey, byte[] audioBytes, String mimeType) throws Exception {
		JsonObject request = new JsonObject();
		JsonArray contents = new JsonArray();
		JsonObject content = new JsonObject();
		content.addProperty("role", "user");
		JsonArray parts = new JsonArray();
		JsonObject audioPart = new JsonObject();
		JsonObject inlineData = new JsonObject();
		inlineData.addProperty("mime_type", mimeType == null || mimeType.isBlank() ? "audio/wav" : mimeType);
		inlineData.addProperty("data", Base64.getEncoder().encodeToString(audioBytes));
		audioPart.add("inline_data", inlineData);
		parts.add(audioPart);
		JsonObject promptPart = new JsonObject();
		promptPart.addProperty("text", "Transcribe the speech to plain text. Return only the transcript.");
		parts.add(promptPart);
		content.add("parts", parts);
		contents.add(content);
		request.add("contents", contents);

		JsonObject generationConfig = new JsonObject();
		JsonObject thinkingConfig = new JsonObject();
		thinkingConfig.addProperty("thinkingLevel", "minimal");
		generationConfig.add("thinkingConfig", thinkingConfig);
		request.add("generationConfig", generationConfig);

		String body = GSON.toJson(request);
		String modelId = ModelChoice.FLASH.modelId;
		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create(GEMINI_ENDPOINT_BASE + modelId + ":generateContent?key=" + apiKey))
			.header("Content-Type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();

		HttpResponse<String> response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("HTTP " + response.statusCode());
		}

		JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
		JsonArray candidates = json.getAsJsonArray("candidates");
		if (candidates == null || candidates.isEmpty()) {
			return "";
		}
		JsonObject first = candidates.get(0).getAsJsonObject();
		JsonObject responseContent = first.getAsJsonObject("content");
		if (responseContent == null) {
			return "";
		}
		JsonArray responseParts = responseContent.getAsJsonArray("parts");
		if (responseParts == null || responseParts.isEmpty()) {
			return "";
		}
		JsonObject firstPart = responseParts.get(0).getAsJsonObject();
		if (!firstPart.has("text")) {
			return "";
		}
		return firstPart.get("text").getAsString();
	}

	private static String sanitizeTranscript(String transcript) {
		if (transcript == null) {
			return "";
		}
		String cleaned = transcript.replace('\n', ' ').replace('\r', ' ').trim();
		while (cleaned.contains("  ")) {
			cleaned = cleaned.replace("  ", " ");
		}
		if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
			cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
		}
		return cleaned;
	}

	private static ModeMessage parseModeMessage(String raw, boolean searchUsed, List<SourceLink> sources) {
		try {
			String trimmed = extractJsonObject(raw);
			JsonObject obj = lenientJsonObject(trimmed);
			String mode = obj.has("mode") ? obj.get("mode").getAsString() : "ASK";
			String message = obj.has("message") ? obj.get("message").getAsString() : raw;
			List<String> commands = new ArrayList<>();
			if (obj.has("commands") && obj.get("commands").isJsonArray()) {
				for (var element : obj.getAsJsonArray("commands")) {
					commands.add(element.getAsString());
				}
			}
			return new ModeMessage(normalizeMode(mode), message, commands, searchUsed, sources);
		} catch (Exception e) {
			if (looksLikeCommandPayload(raw)) {
				return new ModeMessage("COMMAND", "Invalid command payload. Regenerate commands only.", List.of(), searchUsed, sources);
			}
			return new ModeMessage("ASK", raw, List.of(), searchUsed, sources);
		}
	}

	private static JsonObject lenientJsonObject(String json) {
		JsonReader reader = new JsonReader(new StringReader(json));
		reader.setLenient(true);
		return GSON.fromJson(reader, JsonObject.class);
	}

	private static boolean looksLikeCommandPayload(String raw) {
		if (raw == null) {
			return false;
		}
		String lower = raw.toLowerCase(Locale.ROOT);
		boolean hasCommands = lower.contains("\"commands\"") || lower.contains("commands:");
		boolean hasMode = lower.contains("\"mode\"") || lower.contains("mode:") || lower.contains("node:");
		return hasCommands && hasMode;
	}

	private static String extractJsonObject(String raw) {
		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return raw.substring(start, end + 1);
		}
		return raw;
	}

	private static String normalizeMode(String mode) {
		String upper = mode.trim().toUpperCase();
		if (upper.equals("PLAN") || upper.equals("COMMAND")) {
			return upper;
		}
		return "ASK";
	}

	private static Formatting modeColor(String mode) {
		return switch (mode) {
			case "PLAN" -> Formatting.AQUA;
			case "COMMAND" -> Formatting.GOLD;
			default -> Formatting.GREEN;
		};
	}

	private static String modeLabel(String mode) {
		return switch (mode) {
			case "PLAN" -> "PLAN MODE";
			case "COMMAND" -> "COMMAND MODE";
			default -> "ASK MODE";
		};
	}

	private static MutableText rainbowText(String text, int offset) {
		MutableText out = Text.empty();
		for (int i = 0; i < text.length(); i++) {
			Formatting color = RAINBOW[(i + offset) % RAINBOW.length];
			out.append(Text.literal(String.valueOf(text.charAt(i))).formatted(color));
		}
		return out;
	}

	private static MutableText thinkingActionBarText(String label, int tick) {
		return rainbowText(label + "...", tick);
	}

	private static MutableText thinkingActionBarText(String label, int tick, AiStats stats) {
		String base;
		if (stats != null && "Thinking".equals(label)) {
			base = formatThinkingAction(stats, tick);
		} else {
			base = label + "...";
		}
		return rainbowText(base, tick);
	}

	private static String buildContext(ServerCommandSource source, ServerPlayerEntity player, String prompt) {
		return buildContext(source, player, prompt, false);
	}

	private static String buildContext(ServerCommandSource source, ServerPlayerEntity player, String prompt, boolean forceInventory) {
		String name = player.getName().getString();
		String uuid = player.getUuidAsString();
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		String biome = player.getWorld().getBiome(player.getBlockPos())
			.getKey()
			.map(key -> key.getValue().toString())
			.orElse("unknown");
		String dimension = player.getWorld().getRegistryKey().getValue().toString();

		String base = "Context: player=" + name +
			" uuid=" + uuid +
			" pos=" + String.format("%.2f,%.2f,%.2f", x, y, z) +
			" biome=" + biome +
			" dimension=" + dimension;
		String timeWeather = buildTimeWeatherContext(player);
		String structure = buildStructureContext(player);
		String nearby = buildNearbyEntitiesContext(player, prompt);
		String deaths = buildRecentDeathsContext(player, prompt);
		String commands = buildCommandUsageContext(source, prompt);
		String registryHints = buildRegistryHints(prompt);
		String inventory = forceInventory
			? buildInventoryContext(player, prompt, true, Boolean.FALSE)
			: buildInventoryContext(player, prompt);
		StringBuilder out = new StringBuilder(base);
		if (!timeWeather.isEmpty()) {
			out.append("\nTime/Weather: ").append(timeWeather);
		}
		if (!structure.isEmpty()) {
			out.append("\nStructure: ").append(structure);
		}
		if (!nearby.isEmpty()) {
			out.append("\nNearby entities: ").append(nearby);
		}
		if (!deaths.isEmpty()) {
			out.append("\nRecent deaths: ").append(deaths);
		}
		if (!commands.isEmpty()) {
			out.append("\nCommand usage hints: ").append(commands);
		}
		if (!registryHints.isEmpty()) {
			out.append("\nRegistry hints: ").append(registryHints);
		}
		if (!inventory.isEmpty()) {
			out.append("\nInventory: ").append(inventory);
		}
		return out.toString();
	}

	private static String buildTimeWeatherContext(ServerPlayerEntity player) {
		long dayTime = player.getWorld().getTimeOfDay() % 24000L;
		boolean isDay = player.getWorld().isDay();
		boolean isRaining = player.getWorld().isRaining();
		boolean isThundering = player.getWorld().isThundering();
		String weather = isThundering ? "thunder" : (isRaining ? "rain" : "clear");
		String phase = isDay ? "day" : "night";
		return phase + ", time=" + dayTime + ", weather=" + weather;
	}

	private static String buildStructureContext(ServerPlayerEntity player) {
		StructureAccessor accessor = player.getServerWorld().getStructureAccessor();
		if (accessor == null) {
			return "";
		}
		Identifier[] common = new Identifier[] {
			Identifier.of("minecraft", "village"),
			Identifier.of("minecraft", "stronghold"),
			Identifier.of("minecraft", "mineshaft"),
			Identifier.of("minecraft", "mansion"),
			Identifier.of("minecraft", "pillager_outpost"),
			Identifier.of("minecraft", "monument"),
			Identifier.of("minecraft", "ancient_city"),
			Identifier.of("minecraft", "buried_treasure"),
			Identifier.of("minecraft", "ruined_portal"),
			Identifier.of("minecraft", "shipwreck"),
			Identifier.of("minecraft", "fortress"),
			Identifier.of("minecraft", "bastion_remnant"),
			Identifier.of("minecraft", "end_city"),
			Identifier.of("minecraft", "trial_chambers")
		};
		var registry = player.getServerWorld().getRegistryManager().get(RegistryKeys.STRUCTURE);
		for (Identifier id : common) {
			var entry = registry.getEntry(RegistryKey.of(RegistryKeys.STRUCTURE, id));
			if (entry.isEmpty()) {
				continue;
			}
			Structure structure = entry.get().value();
			StructureStart start = accessor.getStructureContaining(player.getBlockPos(), structure);
			if (start != null && start.hasChildren()) {
				return id.toString();
			}
		}
		return "";
	}

	private static String buildInventoryContext(ServerPlayerEntity player, String prompt) {
		return buildInventoryContext(player, prompt, false, null);
	}

	private static String buildInventoryContext(ServerPlayerEntity player, String prompt, boolean force, Boolean includeComponentsOverride) {
		if (prompt == null || prompt.isBlank()) {
			return "";
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		if (!force && !containsAny(lower, "craft", "recipe", "make", "build", "need", "have", "inventory", "items", "materials", "show inventory", "what do i have")) {
			return "";
		}
		boolean includeComponents = includeComponentsOverride != null
			? includeComponentsOverride
			: shouldIncludeComponentsForInventory(player, lower);
		List<String> entries = new ArrayList<>();
		appendInventoryEntries(entries, player.getInventory().main, "main", includeComponents);
		appendInventoryEntries(entries, player.getInventory().armor, "armor", includeComponents);
		appendInventoryEntries(entries, player.getInventory().offHand, "offhand", includeComponents);
		if (entries.isEmpty()) {
			appendInventoryEntriesByIndex(entries, player.getInventory(), includeComponents);
		}
		if (entries.isEmpty()) {
			appendHeldAndEquippedEntries(entries, player, includeComponents);
		}
		if (entries.isEmpty()) {
			if (COMMAND_DEBUG_ENABLED.contains(player.getUuid())) {
				int size = player.getInventory().size();
				LOGGER.info("Inventory context empty for player {} (slots={}).", player.getName().getString(), size);
			}
			return "Empty";
		}
		StringBuilder out = new StringBuilder();
		int maxChars = 1400;
		for (String entry : entries) {
			if (out.length() + entry.length() + 2 > maxChars) {
				if (out.isEmpty()) {
					int keep = Math.max(0, maxChars - 3);
					out.append(entry.substring(0, Math.min(keep, entry.length()))).append("...");
				} else {
					out.append("...");
				}
				break;
			}
			if (!out.isEmpty()) {
				out.append(", ");
			}
			out.append(entry);
		}
		return out.toString();
	}

	private static boolean shouldIncludeComponentsForInventory(ServerPlayerEntity player, String lowerPrompt) {
		if (containsAny(
			lowerPrompt,
			"nbt", "component", "components", "tag", "tags", "enchant", "enchanted",
			"lore", "custom", "data", "durability", "metadata", "damage"
		)) {
			return true;
		}
		if (containsAny(lowerPrompt, "doesn't work", "not working", "why", "bug", "issue", "wrong", "fix", "broken", "fails", "error")) {
			return true;
		}
		if (player != null && promptMentionsInventoryItem(player, lowerPrompt)) {
			return true;
		}
		return inventoryHasCustomComponents(player);
	}

	private static boolean promptMentionsInventoryItem(ServerPlayerEntity player, String lowerPrompt) {
		if (player == null || lowerPrompt == null || lowerPrompt.isBlank()) {
			return false;
		}
		for (ItemStack stack : player.getInventory().main) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (promptMentionsStack(stack, lowerPrompt)) {
				return true;
			}
		}
		for (ItemStack stack : player.getInventory().armor) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (promptMentionsStack(stack, lowerPrompt)) {
				return true;
			}
		}
		for (ItemStack stack : player.getInventory().offHand) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (promptMentionsStack(stack, lowerPrompt)) {
				return true;
			}
		}
		return false;
	}

	private static boolean promptMentionsStack(ItemStack stack, String lowerPrompt) {
		String id = Registries.ITEM.getId(stack.getItem()).toString();
		String path = id.substring(id.indexOf(':') + 1);
		if (lowerPrompt.contains(id) || lowerPrompt.contains(path)) {
			return true;
		}
		String display = stack.getName().getString().toLowerCase(Locale.ROOT);
		return !display.isBlank() && lowerPrompt.contains(display);
	}

	private static boolean inventoryHasCustomComponents(ServerPlayerEntity player) {
		if (player == null) {
			return false;
		}
		for (ItemStack stack : player.getInventory().main) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String components = stack.getComponents().toString();
			if (components != null && !components.isBlank() && !components.equals("[]")) {
				return true;
			}
		}
		for (ItemStack stack : player.getInventory().armor) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String components = stack.getComponents().toString();
			if (components != null && !components.isBlank() && !components.equals("[]")) {
				return true;
			}
		}
		for (ItemStack stack : player.getInventory().offHand) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String components = stack.getComponents().toString();
			if (components != null && !components.isBlank() && !components.equals("[]")) {
				return true;
			}
		}
		return false;
	}

	private static void appendInventoryEntries(List<String> entries, DefaultedList<ItemStack> stacks, String label, boolean includeComponents) {
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack stack = stacks.get(i);
			if (!isStackPresent(stack)) {
				continue;
			}
			String id = Registries.ITEM.getId(stack.getItem()).toString();
			StringBuilder entry = new StringBuilder();
			entry.append(label).append("[").append(i).append("] ").append(id).append(" x").append(stack.getCount());
			if (includeComponents) {
				String components = summarizeComponents(stack.getComponents().toString());
				if (components != null && !components.isBlank() && !components.equals("[]")) {
					entry.append(" components=").append(components);
				}
			}
			entries.add(entry.toString());
		}
	}

	private static void appendInventoryEntriesByIndex(List<String> entries, PlayerInventory inventory, boolean includeComponents) {
		if (inventory == null) {
			return;
		}
		int size = inventory.size();
		for (int i = 0; i < size; i++) {
			ItemStack stack = inventory.getStack(i);
			if (!isStackPresent(stack)) {
				continue;
			}
			String label = inventorySlotLabel(i);
			String id = Registries.ITEM.getId(stack.getItem()).toString();
			StringBuilder entry = new StringBuilder();
			entry.append(label).append("[").append(i).append("] ").append(id).append(" x").append(stack.getCount());
			if (includeComponents) {
				String components = summarizeComponents(stack.getComponents().toString());
				if (components != null && !components.isBlank() && !components.equals("[]")) {
					entry.append(" components=").append(components);
				}
			}
			entries.add(entry.toString());
		}
	}

	private static String inventorySlotLabel(int index) {
		if (index < 0) {
			return "slot";
		}
		if (index < 9) {
			return "hotbar";
		}
		if (index < 36) {
			return "main";
		}
		if (index < 40) {
			return "armor";
		}
		return "offhand";
	}

	private static boolean isStackPresent(ItemStack stack) {
		if (stack == null) {
			return false;
		}
		if (stack.getCount() <= 0) {
			return false;
		}
		return stack.getItem() != Items.AIR;
	}

	private static void appendHeldAndEquippedEntries(List<String> entries, ServerPlayerEntity player, boolean includeComponents) {
		ItemStack main = player.getMainHandStack();
		if (isStackPresent(main)) {
			appendSingleStack(entries, "mainhand", 0, main, includeComponents);
		}
		ItemStack offhand = player.getOffHandStack();
		if (isStackPresent(offhand)) {
			appendSingleStack(entries, "offhand", 0, offhand, includeComponents);
		}
		int armorIndex = 0;
		for (ItemStack armor : player.getArmorItems()) {
			if (isStackPresent(armor)) {
				appendSingleStack(entries, "armor", armorIndex, armor, includeComponents);
			}
			armorIndex++;
		}
	}

	private static void appendSingleStack(List<String> entries, String label, int index, ItemStack stack, boolean includeComponents) {
		String id = Registries.ITEM.getId(stack.getItem()).toString();
		StringBuilder entry = new StringBuilder();
		entry.append(label).append("[").append(index).append("] ").append(id).append(" x").append(stack.getCount());
		if (includeComponents) {
			String components = summarizeComponents(stack.getComponents().toString());
			if (components != null && !components.isBlank() && !components.equals("[]")) {
				entry.append(" components=").append(components);
			}
		}
		entries.add(entry.toString());
	}

	private static String summarizeComponents(String components) {
		if (components == null || components.isBlank() || components.equals("[]")) {
			return components;
		}
		String singleLine = components.replace("\n", " ").replace("\r", " ").trim();
		int maxLen = 200;
		if (singleLine.length() > maxLen) {
			return singleLine.substring(0, maxLen) + "...";
		}
		return singleLine;
	}

	private static String buildCommandUsageContext(ServerCommandSource source, String prompt) {
		if (source == null || prompt == null || prompt.isBlank()) {
			return "";
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		List<String> tokens = new ArrayList<>();
		for (String part : lower.split("[^a-z0-9_]+")) {
			if (part.length() >= 3) {
				tokens.add(part);
			}
		}
		if (tokens.isEmpty()) {
			return "";
		}

		var dispatcher = source.getServer().getCommandManager().getDispatcher();
		var root = dispatcher.getRoot();
		List<String> entries = new ArrayList<>();
		for (CommandNode<ServerCommandSource> child : root.getChildren()) {
			String name = child.getName().toLowerCase(Locale.ROOT);
			if (!lower.contains(name) && !containsTokenMatch(tokens, name)) {
				continue;
			}
			List<String> usages = new ArrayList<>();
			Map<CommandNode<ServerCommandSource>, String> usageMap = dispatcher.getSmartUsage(child, source);
			for (String usage : usageMap.values()) {
				usages.add("/" + usage);
				if (usages.size() >= 3) {
					break;
				}
			}
			if (usages.isEmpty()) {
				usages.add("/" + name);
			}
			entries.add(name + ": " + String.join(" | ", usages));
			if (entries.size() >= 6) {
				break;
			}
		}
		return String.join("; ", entries);
	}

	private static boolean containsTokenMatch(List<String> tokens, String name) {
		for (String token : tokens) {
			if (name.contains(token) || token.contains(name)) {
				return true;
			}
		}
		return false;
	}

	private static String buildRegistryHints(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return "";
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		List<String> tokens = new ArrayList<>();
		for (String part : lower.split("[^a-z0-9_]+")) {
			if (part.length() >= 3) {
				tokens.add(part);
			}
		}
		if (tokens.isEmpty()) {
			return "";
		}

		List<String> itemMatches = findRegistryMatches(Registries.ITEM.getIds(), tokens, 6);
		List<String> entityMatches = findRegistryMatches(Registries.ENTITY_TYPE.getIds(), tokens, 6);

		List<String> parts = new ArrayList<>();
		if (!itemMatches.isEmpty()) {
			parts.add("Items: " + String.join(", ", itemMatches));
		}
		if (!entityMatches.isEmpty()) {
			parts.add("Entities: " + String.join(", ", entityMatches));
		}
		return String.join("; ", parts);
	}

	private static List<String> findRegistryMatches(Iterable<net.minecraft.util.Identifier> ids, List<String> tokens, int limit) {
		List<String> matches = new ArrayList<>();
		for (var id : ids) {
			String value = id.toString();
			String path = id.getPath().toLowerCase(Locale.ROOT);
			String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
			if (matchesTokens(tokens, path, namespace, value)) {
				matches.add(value);
				if (matches.size() >= limit) {
					break;
				}
			}
		}
		return matches;
	}

	private static boolean matchesTokens(List<String> tokens, String path, String namespace, String full) {
		for (String token : tokens) {
			if (path.contains(token) || namespace.contains(token) || full.contains(token)) {
				return true;
			}
		}
		return false;
	}

	private static String buildNearbyEntitiesContext(ServerPlayerEntity player, String prompt) {
		return buildNearbyEntitiesContext(player, prompt, false);
	}

	private static String buildNearbyEntitiesContext(ServerPlayerEntity player, String prompt, boolean force) {
		if (prompt == null || prompt.isBlank()) {
			return "";
		}

		String lower = prompt.toLowerCase(Locale.ROOT);
		String specificType = findSpecificEntityType(lower);
		ContextMode mode = force ? ContextMode.ALL : resolveContextMode(lower, specificType);
		if (mode == ContextMode.NONE) {
			return "";
		}

		double radius = switch (mode) {
			case HOSTILE -> 32.0;
			case PASSIVE -> 24.0;
			case PLAYERS -> 64.0;
			case SPECIFIC -> 48.0;
			case ALL -> 32.0;
			default -> 0.0;
		};

		Box box = player.getBoundingBox().expand(radius);
		Map<String, EntityGroup> groups = new HashMap<>();

		for (Entity entity : player.getWorld().getOtherEntities(player, box)) {
			if (shouldExcludeEntity(entity)) {
				continue;
			}

			if (!matchesMode(entity, mode, specificType)) {
				continue;
			}

			String label = labelForEntity(entity);
			double distance = Math.sqrt(player.squaredDistanceTo(entity));
			boolean hostile = entity instanceof HostileEntity;

			EntityGroup group = groups.get(label);
			if (group == null) {
				group = new EntityGroup(label, hostile, distance);
				groups.put(label, group);
			}
			group.count++;
			if (distance < group.nearestDistance) {
				group.nearestDistance = distance;
			}
		}

		if (groups.isEmpty()) {
			return "None";
		}

		List<EntityGroup> list = new ArrayList<>(groups.values());
		list.sort((a, b) -> Double.compare(a.nearestDistance, b.nearestDistance));
		if (list.size() > 10) {
			list = list.subList(0, 10);
		}

		List<String> parts = new ArrayList<>();
		for (EntityGroup group : list) {
			boolean showDistance = shouldShowDistance(mode, group.hostile);
			String entry = group.count + " " + group.label;
			if (showDistance) {
				entry += " (nearest: " + Math.round(group.nearestDistance) + " blocks)";
			}
			parts.add(entry);
		}

		return String.join(", ", parts);
	}

	private static ContextMode resolveContextMode(String lower, String specificType) {
		if (specificType != null) {
			return ContextMode.SPECIFIC;
		}
		if (containsAny(lower, "what's around me", "whats around me", "scan", "entities nearby", "nearby entities", "around me")) {
			return ContextMode.ALL;
		}
		if (containsAny(lower, "kill", "fight", "attack", "mob", "hostile", "danger", "dangerous", "combat")) {
			return ContextMode.HOSTILE;
		}
		if (containsAny(lower, "animal", "farm", "breed", "feed", "cow", "pig", "sheep", "chicken", "horse")) {
			return ContextMode.PASSIVE;
		}
		if (containsAny(lower, "player", "nearby people", "multiplayer", "who's nearby", "who is nearby", "who's near", "who is near")) {
			return ContextMode.PLAYERS;
		}
		return ContextMode.NONE;
	}

	private static boolean containsAny(String text, String... tokens) {
		for (String token : tokens) {
			if (text.contains(token)) {
				return true;
			}
		}
		return false;
	}

	private static String findSpecificEntityType(String lower) {
		for (var id : Registries.ENTITY_TYPE.getIds()) {
			String path = id.getPath();
			if (lower.contains(path)) {
				return path;
			}
		}
		return null;
	}

	private static boolean matchesMode(Entity entity, ContextMode mode, String specificType) {
		return switch (mode) {
			case HOSTILE -> entity instanceof HostileEntity;
			case PASSIVE -> entity instanceof PassiveEntity;
			case PLAYERS -> entity instanceof PlayerEntity;
			case SPECIFIC -> Registries.ENTITY_TYPE.getId(entity.getType()).getPath().equals(specificType);
			case ALL -> true;
			case NONE -> false;
		};
	}

	private static boolean shouldExcludeEntity(Entity entity) {
		return false;
	}

	private static String labelForEntity(Entity entity) {
		String path = Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
		String name = titleCase(path.replace('_', ' '));
		if (entity instanceof VillagerEntity villager) {
			String profession = Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).getPath();
			return name + " (" + titleCase(profession.replace('_', ' ')) + ")";
		}
		return name;
	}

	private static boolean shouldShowDistance(ContextMode mode, boolean hostile) {
		return mode == ContextMode.HOSTILE
			|| mode == ContextMode.PLAYERS
			|| mode == ContextMode.SPECIFIC
			|| (mode == ContextMode.ALL && hostile);
	}

	private static String titleCase(String text) {
		String[] parts = text.split(" ");
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isEmpty()) {
				continue;
			}
			out.append(Character.toUpperCase(part.charAt(0)));
			if (part.length() > 1) {
				out.append(part.substring(1));
			}
			if (i < parts.length - 1) {
				out.append(" ");
			}
		}
		return out.toString();
	}

	private static void recordDeath(ServerPlayerEntity player, DamageSource source) {
		if (player == null) {
			return;
		}
		String cause = describeDeathCause(player, source);
		long now = System.currentTimeMillis();
		List<DeathRecord> list = new ArrayList<>(DEATHS.getOrDefault(player.getUuid(), List.of()));
		list.removeIf(record -> record.cause.equalsIgnoreCase(cause));
		list.add(new DeathRecord(
			cause,
			player.getWorld().getRegistryKey().getValue().toString(),
			player.getX(),
			player.getY(),
			player.getZ(),
			now
		));
		pruneDeaths(list, now);
		while (list.size() > MAX_DEATHS) {
			list.remove(0);
		}
		DEATHS.put(player.getUuid(), list);
		saveDeaths(player.getUuid());
	}

	private static String describeDeathCause(ServerPlayerEntity player, DamageSource source) {
		if (source == null) {
			return "Died";
		}
		Entity attacker = source.getAttacker();
		if (attacker != null) {
			return "Slain by " + attacker.getName().getString();
		}
		String message = source.getDeathMessage(player).getString();
		String name = player.getName().getString();
		if (message.startsWith(name + " ")) {
			message = message.substring(name.length() + 1);
		}
		if (message.isBlank()) {
			return "Died";
		}
		return titleCase(message);
	}

	private static void pruneDeaths(List<DeathRecord> list, long now) {
		list.removeIf(record -> now - record.timeMs > DEATH_EXPIRY_MS);
	}

	private static String buildRecentDeathsContext(ServerPlayerEntity player, String prompt) {
		List<DeathRecord> list = new ArrayList<>(DEATHS.getOrDefault(player.getUuid(), List.of()));
		if (list.isEmpty()) {
			return "";
		}

		long now = System.currentTimeMillis();
		pruneDeaths(list, now);
		if (list.isEmpty()) {
			DEATHS.remove(player.getUuid());
			return "";
		}

		list.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));
		boolean includeMultiple = shouldIncludeMultipleDeaths(prompt);
		List<DeathRecord> selected = new ArrayList<>();
		if (includeMultiple) {
			for (DeathRecord record : list) {
				selected.add(record);
				if (selected.size() >= 5) {
					break;
				}
			}
		} else {
			DeathRecord latest = list.get(0);
			if (now - latest.timeMs > RECENT_DEATH_MS) {
				return "";
			}
			selected.add(latest);
		}

		List<String> parts = new ArrayList<>();
		int index = 1;
		for (DeathRecord record : selected) {
			String coords = Math.round(record.x) + ", " + Math.round(record.y) + ", " + Math.round(record.z);
			String age = formatAge(now - record.timeMs);
			String entry = index + ") " + record.cause + " at " + coords + " (" + age + ", " + record.dimension + ")";
			parts.add(entry);
			index++;
		}
		return String.join(" ", parts);
	}

	private static boolean shouldIncludeMultipleDeaths(String prompt) {
		if (prompt == null) {
			return false;
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		return containsAny(lower, "died", "death", "killed", "danger", "dangerous", "safe", "careful", "avoid", "survive", "survival");
	}

	private static String formatAge(long ageMs) {
		long minutes = ageMs / 60000L;
		if (minutes < 1) {
			return "just now";
		}
		if (minutes < 60) {
			return minutes + "m ago";
		}
		long hours = minutes / 60;
		long remMinutes = minutes % 60;
		if (remMinutes == 0) {
			return hours + "h ago";
		}
		return hours + "h " + remMinutes + "m ago";
	}

	private record ModeMessage(String mode, String message, List<String> commands, boolean searchUsed, List<SourceLink> sources) {}

	private record CommandResult(boolean success, String errorSummary, List<String> outputs) {}

	private record PreparedCommands(List<String> executeCommands, List<String> undoCommands) {}

	private static final class ModeState {
		private final String label;
		private final Formatting color;
		private int ticksRemaining;

		private ModeState(String label, Formatting color, int ticksRemaining) {
			this.label = label;
			this.color = color;
			this.ticksRemaining = ticksRemaining;
		}
	}

	private static final class StatusState {
		private final String message;
		private final Formatting color;
		private int ticksRemaining;

		private StatusState(String message, Formatting color, int ticksRemaining) {
			this.message = message;
			this.color = color;
			this.ticksRemaining = ticksRemaining;
		}
	}

	private static List<ChatTurn> getHistory(UUID playerId) {
		return CHAT_HISTORY.getOrDefault(playerId, List.of());
	}

	private static void appendHistory(UUID playerId, String userMessage, ModeMessage assistantMessage, long responseMs) {
		List<ChatTurn> history = new ArrayList<>(CHAT_HISTORY.getOrDefault(playerId, List.of()));
		long now = System.currentTimeMillis();
		history.add(new ChatTurn("user", userMessage, "USER", now, 0L, List.of(), false));
		history.add(new ChatTurn(
			"model",
			assistantMessage.message,
			assistantMessage.mode,
			now,
			responseMs,
			assistantMessage.commands == null ? List.of() : assistantMessage.commands,
			assistantMessage.searchUsed
		));
		while (history.size() > MAX_HISTORY_TURNS * 2) {
			history.remove(0);
		}
		CHAT_HISTORY.put(playerId, history);
	}

	private record ChatTurn(
		String role,
		String text,
		String mode,
		long timestampMs,
		long responseMs,
		List<String> commands,
		boolean searchUsed
	) {}

	private static final class CommandOutputCollector implements net.minecraft.server.command.CommandOutput {
		private final List<String> messages;

		private CommandOutputCollector(List<String> messages) {
			this.messages = messages;
		}

		@Override
		public void sendMessage(Text message) {
			if (message != null) {
				messages.add(message.getString());
			}
		}

		@Override
		public boolean shouldReceiveFeedback() {
			return true;
		}

		@Override
		public boolean shouldTrackOutput() {
			return true;
		}

		@Override
		public boolean shouldBroadcastConsoleToOps() {
			return false;
		}
	}

	private static AiStats getStats(UUID playerId) {
		return AI_STATS.computeIfAbsent(playerId, id -> new AiStats());
	}

	private static void updateSidebar(ServerPlayerEntity player, AiStats stats) {
		var server = player.getServer();
		server.execute(() -> {
			Scoreboard scoreboard = server.getScoreboard();
			if (!SIDEBAR_ENABLED) {
				clearSidebar(scoreboard);
				return;
			}
			ScoreboardObjective objective = ensureObjective(scoreboard);
			scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);

			Map<String, String> values = new HashMap<>();
			values.put("Mode", stats.mode);
			values.put("State", stats.state);
			values.put("Tokens", stats.tokenPercent + "%");
			values.put("Session Tokens", formatSessionTokens(stats.sessionTokens));
			values.put("Last Response", stats.lastResponseMs + " ms");
			values.put("Retries", String.valueOf(stats.retries));
			values.put("Context Size", stats.contextSize + " msgs");

			clearLegacyEntries(scoreboard, objective);

			int score = SCOREBOARD_LINES.length;
			for (int i = 0; i < SCOREBOARD_LINES.length; i++) {
				String line = SCOREBOARD_LINES[i];
				String entry = SCOREBOARD_ENTRIES[i];
				setScoreboardLine(scoreboard, objective, line, values.getOrDefault(line, "-"), entry, score);
				score--;
			}
		});
	}

	private static ScoreboardObjective ensureObjective(Scoreboard scoreboard) {
		ScoreboardObjective objective = scoreboard.getNullableObjective(SCOREBOARD_OBJECTIVE);
		if (objective == null) {
			objective = scoreboard.addObjective(
				SCOREBOARD_OBJECTIVE,
				ScoreboardCriterion.DUMMY,
				Text.literal("Gemini AI"),
				ScoreboardCriterion.DUMMY.getDefaultRenderType(),
				false,
				(NumberFormat) null
			);
		}
		return objective;
	}

	private static void setScoreboardLine(
		Scoreboard scoreboard,
		ScoreboardObjective objective,
		String label,
		String value,
		String entryName,
		int score
	) {
		String teamName = "ai_" + label.toLowerCase(Locale.ROOT).replace(' ', '_');
		Team team = scoreboard.getTeam(teamName);
		if (team == null) {
			team = scoreboard.addTeam(teamName);
		}

		scoreboard.addScoreHolderToTeam(entryName, team);
		team.setPrefix(Text.literal(label + ": ").formatted(Formatting.GRAY));
		team.setSuffix(Text.literal(value).formatted(Formatting.WHITE));

		ScoreHolder holder = ScoreHolder.fromName(entryName);
		scoreboard.getOrCreateScore(holder, objective).setScore(score);
	}

	private static void clearLegacyEntries(Scoreboard scoreboard, ScoreboardObjective objective) {
		for (String label : SCOREBOARD_LINES) {
			ScoreHolder holder = ScoreHolder.fromName(label);
			scoreboard.removeScore(holder, objective);
			Team team = scoreboard.getTeam("ai_" + label.toLowerCase(Locale.ROOT).replace(' ', '_'));
			if (team != null) {
				Team current = scoreboard.getScoreHolderTeam(label);
				if (current == team) {
					scoreboard.removeScoreHolderFromTeam(label, team);
				}
			}
		}
	}

	private static int estimateTokenPercent(String context, List<ChatTurn> history, String prompt) {
		int tokens = estimateTokens(context) + estimateTokens(prompt);
		if (history != null) {
			for (ChatTurn turn : history) {
				tokens += estimateTokens(turn.text);
			}
		}
		int percent = (int) Math.round((tokens / (double) MAX_CONTEXT_TOKENS) * 100.0);
		return Math.max(0, Math.min(100, percent));
	}

	private static int estimateTokens(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return (int) Math.ceil(text.length() / 4.0);
	}

	private static String formatSessionTokens(int tokens) {
		if (tokens >= 1000) {
			return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
		}
		return String.valueOf(tokens);
	}

	private static String formatThinkingState(AiStats stats, int tick) {
		double seconds = tick / 20.0;
		long avgMs = stats.averageResponseMs();
		if (avgMs > 0) {
			return String.format(Locale.ROOT, "THINKING %.1fs / ~%.1fs", seconds, avgMs / 1000.0);
		}
		return String.format(Locale.ROOT, "THINKING %.1fs", seconds);
	}

	private static String formatThinkingAction(AiStats stats, int tick) {
		double seconds = tick / 20.0;
		long avgMs = stats.averageResponseMs();
		if (avgMs > 0) {
			return String.format(Locale.ROOT, "Thinking %.1fs / ~%.1fs", seconds, avgMs / 1000.0);
		}
		return String.format(Locale.ROOT, "Thinking %.1fs", seconds);
	}

	private static String resolveState(String message) {
		if (message != null && message.startsWith("Error:")) {
			if (message.contains("HTTP 429")) {
				return "RATE LIMITED";
			}
			return "ERROR";
		}
		return "READY";
	}

	private static void emitFeedbackEffects(ServerPlayerEntity player, ModeMessage reply) {
		if (player == null) {
			return;
		}
		ParticleSetting setting = PARTICLE_SETTINGS.getOrDefault(player.getUuid(), ParticleSetting.ON);
		boolean soundsEnabled = !SOUND_DISABLED.contains(player.getUuid());
		boolean isError = reply.message.startsWith("Error:") || reply.message.contains("could not produce valid commands");
		boolean isCommand = "COMMAND".equals(reply.mode);
		boolean isPlan = "PLAN".equals(reply.mode);

		if (setting != ParticleSetting.OFF) {
			if (setting != ParticleSetting.MINIMAL || isError || isCommand) {
				if (isError) {
					spawnParticles(player, ParticleTypes.SMOKE, 10);
				} else if (isCommand) {
					spawnParticles(player, ParticleTypes.TOTEM_OF_UNDYING, 18);
				} else if (isPlan) {
					spawnParticles(player, ParticleTypes.ENCHANT, 12);
				} else {
					spawnParticles(player, ParticleTypes.HAPPY_VILLAGER, 8);
				}
				if (reply.searchUsed) {
					spawnParticles(player, ParticleTypes.END_ROD, 6);
				}
			}
		}

		if (soundsEnabled) {
			if (isError) {
				playSound(player, SoundEvents.ENTITY_VILLAGER_NO);
			} else if (isCommand) {
				playSound(player, SoundEvents.ENTITY_PLAYER_LEVELUP);
			} else if (isPlan) {
				playSound(player, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE);
			} else {
				playSound(player, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);
			}
		}
	}

	private static void spawnParticles(ServerPlayerEntity player, net.minecraft.particle.ParticleEffect particle, int count) {
		player.getServerWorld().spawnParticles(
			particle,
			player.getX(),
			player.getY() + 1.0,
			player.getZ(),
			count,
			0.4,
			0.6,
			0.4,
			0.01
		);
	}

	private static void playSound(ServerPlayerEntity player, net.minecraft.sound.SoundEvent sound) {
		player.getWorld().playSound(
			null,
			player.getBlockPos(),
			sound,
			SoundCategory.PLAYERS,
			0.7f,
			1.0f
		);
	}

	private static void clearSidebar(ServerCommandSource source) {
		clearSidebar(source.getServer().getScoreboard());
	}

	private static void clearSidebar(Scoreboard scoreboard) {
		scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, null);
	}

	private static SearchMetadata parseSearchMetadata(JsonObject candidate) {
		List<SourceLink> sources = new ArrayList<>();
		if (candidate == null || !candidate.has("groundingMetadata")) {
			return new SearchMetadata(false, sources);
		}

		JsonObject grounding = candidate.getAsJsonObject("groundingMetadata");
		if (grounding == null || !grounding.has("groundingChunks")) {
			return new SearchMetadata(false, sources);
		}

		JsonArray chunks = grounding.getAsJsonArray("groundingChunks");
		if (chunks == null || chunks.isEmpty()) {
			return new SearchMetadata(false, sources);
		}

		for (int i = 0; i < chunks.size(); i++) {
			if (sources.size() >= 3) {
				break;
			}
			JsonObject chunk = chunks.get(i).getAsJsonObject();
			if (chunk == null || !chunk.has("web")) {
				continue;
			}
			JsonObject web = chunk.getAsJsonObject("web");
			String uri = web.has("uri") ? web.get("uri").getAsString() : "";
			String host = extractHost(uri);
			String label = host.isBlank() ? uri : host;
			sources.add(new SourceLink("[" + (sources.size() + 1) + "] " + label, uri));
		}

		return new SearchMetadata(!sources.isEmpty(), sources);
	}

	private static String extractHost(String uri) {
		if (uri == null || uri.isBlank()) {
			return "";
		}
		try {
			URI parsed = URI.create(uri);
			return parsed.getHost() == null ? "" : parsed.getHost();
		} catch (Exception e) {
			return "";
		}
	}

	private static final class AiStats {
		private String mode = "ASK";
		private String state = "READY";
		private int tokenPercent = 0;
		private int sessionTokens = 0;
		private long lastResponseMs = 0;
		private int retries = 0;
		private int contextSize = 0;
		private List<String> lastCommandOutput = List.of();
		private final List<Long> recentResponseTimes = new ArrayList<>();

		private void recordResponseTime(long ms) {
			recentResponseTimes.add(ms);
			if (recentResponseTimes.size() > 10) {
				recentResponseTimes.remove(0);
			}
		}

		private long averageResponseMs() {
			if (recentResponseTimes.isEmpty()) {
				return 0L;
			}
			long sum = 0L;
			for (long value : recentResponseTimes) {
				sum += value;
			}
			return Math.round(sum / (double) recentResponseTimes.size());
		}
	}

	private record SearchMetadata(boolean used, List<SourceLink> sources) {}

	private static MutableText buildSourcesLine(List<SourceLink> sources) {
		MutableText line = Text.literal("Sources: ").formatted(Formatting.DARK_GRAY);
		for (int i = 0; i < sources.size(); i++) {
			SourceLink source = sources.get(i);
			MutableText link = Text.literal(source.label)
				.styled(style -> style
					.withUnderline(true)
					.withColor(Formatting.AQUA)
					.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, source.url)));
			line.append(link);
			if (i < sources.size() - 1) {
				line.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY));
			}
		}
		return line;
	}

	private record SourceLink(String label, String url) {}

	private enum ParticleSetting {
		ON,
		OFF,
		MINIMAL
	}

	private enum ModelPreference {
		AUTO("auto", "Auto"),
		FLASH("flash", "Flash"),
		FLASH_THINKING("flash-thinking", "Flash Thinking"),
		PRO("pro", "Pro");

		private final String storage;
		private final String display;

		ModelPreference(String storage, String display) {
			this.storage = storage;
			this.display = display;
		}

		private ModelChoice toChoice() {
			return switch (this) {
				case FLASH -> ModelChoice.FLASH;
				case FLASH_THINKING -> ModelChoice.FLASH_THINKING;
				case PRO -> ModelChoice.PRO;
				default -> ModelChoice.FLASH;
			};
		}
	}

	private enum ModelChoice {
		FLASH("gemini-3-flash-preview", "minimal"),
		FLASH_THINKING("gemini-3-flash-preview", "medium"),
		PRO("gemini-3-pro-preview", "high");

		private final String modelId;
		private final String thinkingLevel;

		ModelChoice(String modelId, String thinkingLevel) {
			this.modelId = modelId;
			this.thinkingLevel = thinkingLevel;
		}
	}

	private enum ContextMode {
		NONE,
		HOSTILE,
		PASSIVE,
		PLAYERS,
		SPECIFIC,
		ALL
	}

	private static final class EntityGroup {
		private final String label;
		private final boolean hostile;
		private int count;
		private double nearestDistance;

		private EntityGroup(String label, boolean hostile, double nearestDistance) {
			this.label = label;
			this.hostile = hostile;
			this.nearestDistance = nearestDistance;
			this.count = 0;
		}
	}

	private record DeathRecord(String cause, String dimension, double x, double y, double z, long timeMs) {}

	private static final class RequestState {
		private final AtomicBoolean cancelled;
		private volatile CompletableFuture<?> future;
		private boolean cancelPromptSent;

		private RequestState(AtomicBoolean cancelled, CompletableFuture<?> future) {
			this.cancelled = cancelled;
			this.future = future;
			this.cancelPromptSent = false;
		}
	}

	private static List<String> limitOutput(List<String> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return List.of();
		}
		List<String> limited = new ArrayList<>();
		for (String line : outputs) {
			if (line == null || line.isBlank()) {
				continue;
			}
			limited.add(line);
			if (limited.size() >= 2) {
				break;
			}
		}
		return limited;
	}

	private static int parseHistoryCount(String countToken, int totalExchanges) {
		if (countToken == null || countToken.isBlank()) {
			return Math.min(5, totalExchanges);
		}
		String lower = countToken.toLowerCase(Locale.ROOT);
		if (lower.equals("all")) {
			return totalExchanges;
		}
		try {
			int value = Integer.parseInt(lower);
			return Math.max(1, Math.min(value, totalExchanges));
		} catch (NumberFormatException e) {
			return Math.min(5, totalExchanges);
		}
	}

	private static List<ChatTurn> sliceHistory(List<ChatTurn> history, int exchanges) {
		if (history.isEmpty()) {
			return history;
		}
		int totalExchanges = history.size() / 2;
		int requested = Math.max(1, Math.min(exchanges, totalExchanges));
		int startExchange = totalExchanges - requested;
		int startIndex = startExchange * 2;
		return history.subList(startIndex, history.size());
	}

	private static MutableText formatHistoryLine(String label, String text, long timestampMs) {
		String age = formatAge(System.currentTimeMillis() - timestampMs);
		String display = text == null ? "" : text;
		boolean truncated = display.length() > 100;
		if (truncated) {
			display = display.substring(0, 100) + "...";
		}
		MutableText line = Text.literal("[" + age + "] " + label + ": ").formatted(Formatting.GRAY);
		MutableText body = Text.literal(display).formatted(Formatting.WHITE);
		if (truncated && text != null) {
			body = body.styled(style -> style.withHoverEvent(
				new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(text))));
		}
		return line.append(body);
	}

	private static MutableText historyNavButton(String label, String countToken, int page) {
		String command = "/chat history " + countToken + " " + page;
		return Text.literal(label).styled(style -> style
			.withColor(Formatting.AQUA)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
	}

	private static Path writeChatExport(UUID playerId, List<ChatTurn> history, String format) {
		try {
			Path dir = exportDir(playerId);
			Files.createDirectories(dir);
			String timestamp = formatTimestamp(System.currentTimeMillis());
			Path out = dir.resolve(timestamp + "." + format);
			String content = format.equals("json")
				? GSON.toJson(history)
				: formatChatText(history, timestamp);
			Files.writeString(out, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			Files.writeString(dir.resolve("latest." + format), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			return out;
		} catch (Exception e) {
			LOGGER.warn("Failed to export chat log for {}: {}", playerId, e.getMessage());
			return null;
		}
	}

	private static Path exportDir(UUID playerId) {
		return Path.of("run", "chat-logs", playerId.toString());
	}

	private static String formatTimestamp(long timestampMs) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
		return formatter.format(Instant.ofEpochMilli(timestampMs));
	}

	private static String formatChatText(List<ChatTurn> history, String timestamp) {
		StringBuilder out = new StringBuilder();
		out.append("Chat export ").append(timestamp).append("\n");
		for (ChatTurn turn : history) {
			String age = formatTimestamp(turn.timestampMs);
			out.append("[").append(age).append("] ");
			if (turn.role.equals("user")) {
				out.append("You: ");
			} else {
				String mode = turn.mode == null || turn.mode.isBlank() ? "" : " (" + turn.mode + ")";
				out.append("AI").append(mode).append(": ");
			}
			out.append(turn.text).append("\n");
			if (!turn.commands.isEmpty()) {
				out.append("Commands: ").append(String.join(" | ", turn.commands)).append("\n");
			}
			if (turn.searchUsed) {
				out.append("Search: true\n");
			}
			if (turn.responseMs > 0) {
				out.append("Response: ").append(turn.responseMs).append(" ms\n");
			}
		}
		return out.toString();
	}

	private static Path deathDir() {
		return Path.of("run", "ai-deaths");
	}

	private static Path deathPath(UUID playerId) {
		return deathDir().resolve(playerId.toString() + ".json");
	}

	private static void loadDeaths(UUID playerId) {
		try {
			Path path = deathPath(playerId);
			if (!Files.exists(path)) {
				return;
			}
			String json = Files.readString(path, StandardCharsets.UTF_8);
			DeathRecord[] records = GSON.fromJson(json, DeathRecord[].class);
			if (records == null) {
				return;
			}
			List<DeathRecord> list = new ArrayList<>();
			for (DeathRecord record : records) {
				if (record != null) {
					list.add(record);
				}
			}
			long now = System.currentTimeMillis();
			pruneDeaths(list, now);
			if (!list.isEmpty()) {
				DEATHS.put(playerId, list);
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to load deaths for {}: {}", playerId, e.getMessage());
		}
	}

	private static void saveDeaths(UUID playerId) {
		try {
			List<DeathRecord> list = DEATHS.get(playerId);
			if (list == null || list.isEmpty()) {
				Files.deleteIfExists(deathPath(playerId));
				return;
			}
			Files.createDirectories(deathDir());
			String json = GSON.toJson(list);
			Files.writeString(
				deathPath(playerId),
				json,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
		} catch (Exception e) {
			LOGGER.warn("Failed to save deaths for {}: {}", playerId, e.getMessage());
		}
	}

	private static Path settingsDir() {
		return Path.of("run", "ai-settings");
	}

	private static Path playerSettingsPath(UUID playerId) {
		return settingsDir().resolve(playerId.toString() + ".json");
	}

	private static Path globalSettingsPath() {
		return settingsDir().resolve("global.json");
	}

	private static void loadPlayerSettings(UUID playerId) {
		try {
			Path path = playerSettingsPath(playerId);
			if (!Files.exists(path)) {
				return;
			}
			String json = Files.readString(path, StandardCharsets.UTF_8);
			JsonObject obj = GSON.fromJson(json, JsonObject.class);
			if (obj == null) {
				return;
			}
			if (obj.has("debug")) {
				if (obj.get("debug").getAsBoolean()) {
					COMMAND_DEBUG_ENABLED.add(playerId);
				} else {
					COMMAND_DEBUG_ENABLED.remove(playerId);
				}
			}
			if (obj.has("soundsEnabled")) {
				boolean enabled = obj.get("soundsEnabled").getAsBoolean();
				if (enabled) {
					SOUND_DISABLED.remove(playerId);
				} else {
					SOUND_DISABLED.add(playerId);
				}
			}
			if (obj.has("particles")) {
				ParticleSetting setting = parseParticleSetting(obj.get("particles").getAsString());
				if (setting != null) {
					PARTICLE_SETTINGS.put(playerId, setting);
				}
			}
			if (obj.has("retries")) {
				int retries = obj.get("retries").getAsInt();
				int clamped = Math.max(MIN_COMMAND_RETRIES, Math.min(MAX_COMMAND_RETRIES, retries));
				COMMAND_RETRY_LIMITS.put(playerId, clamped);
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to load settings for {}: {}", playerId, e.getMessage());
		}
	}

	private static void savePlayerSettings(UUID playerId) {
		try {
			JsonObject obj = new JsonObject();
			obj.addProperty("debug", COMMAND_DEBUG_ENABLED.contains(playerId));
			obj.addProperty("soundsEnabled", !SOUND_DISABLED.contains(playerId));
			ParticleSetting particles = PARTICLE_SETTINGS.getOrDefault(playerId, ParticleSetting.ON);
			obj.addProperty("particles", particles.name().toLowerCase(Locale.ROOT));
			obj.addProperty("retries", COMMAND_RETRY_LIMITS.getOrDefault(playerId, DEFAULT_COMMAND_RETRIES));
			Files.createDirectories(settingsDir());
			Files.writeString(
				playerSettingsPath(playerId),
				GSON.toJson(obj),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
		} catch (Exception e) {
			LOGGER.warn("Failed to save settings for {}: {}", playerId, e.getMessage());
		}
	}

	private static void loadGlobalSettings() {
		try {
			Path path = globalSettingsPath();
			if (!Files.exists(path)) {
				return;
			}
			String json = Files.readString(path, StandardCharsets.UTF_8);
			JsonObject obj = GSON.fromJson(json, JsonObject.class);
			if (obj != null && obj.has("sidebarEnabled")) {
				SIDEBAR_ENABLED = obj.get("sidebarEnabled").getAsBoolean();
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to load global settings: {}", e.getMessage());
		}
	}

	private static void saveGlobalSettings() {
		try {
			JsonObject obj = new JsonObject();
			obj.addProperty("sidebarEnabled", SIDEBAR_ENABLED);
			Files.createDirectories(settingsDir());
			Files.writeString(
				globalSettingsPath(),
				GSON.toJson(obj),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
		} catch (Exception e) {
			LOGGER.warn("Failed to save global settings: {}", e.getMessage());
		}
	}

	private static ModelPreference resolveModelPreference(UUID playerId) {
		ModelPreference cached = MODEL_PREFERENCES.get(playerId);
		if (cached != null) {
			return cached;
		}
		ModelPreference stored = readModelPreference(playerId);
		if (stored != null) {
			MODEL_PREFERENCES.put(playerId, stored);
			return stored;
		}
		return ModelPreference.AUTO;
	}

	private static ModelPreference parseModelPreference(String token) {
		if (token == null) {
			return null;
		}
		String lower = token.toLowerCase(Locale.ROOT);
		return switch (lower) {
			case "flash" -> ModelPreference.FLASH;
			case "flash-thinking", "flash_thinking" -> ModelPreference.FLASH_THINKING;
			case "pro" -> ModelPreference.PRO;
			case "auto" -> ModelPreference.AUTO;
			default -> null;
		};
	}

	private static ModelPreference readModelPreference(UUID playerId) {
		try {
			Path path = modelPath(playerId);
			if (!Files.exists(path)) {
				return null;
			}
			String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
			return parseModelPreference(raw);
		} catch (Exception e) {
			LOGGER.warn("Failed to read model preference for {}: {}", playerId, e.getMessage());
			return null;
		}
	}

	private static void saveModelPreference(UUID playerId, ModelPreference preference) {
		try {
			Files.createDirectories(modelDir());
			Files.writeString(
				modelPath(playerId),
				preference.storage,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
		} catch (Exception e) {
			LOGGER.warn("Failed to save model preference for {}: {}", playerId, e.getMessage());
		}
	}

	private static Path modelDir() {
		return Path.of("run", "ai-models");
	}

	private static Path modelPath(UUID playerId) {
		return modelDir().resolve(playerId.toString() + ".txt");
	}

	private static ModelChoice selectModel(ServerPlayerEntity player, String prompt, String context, List<ChatTurn> history) {
		if (player == null) {
			return ModelChoice.FLASH;
		}
		ModelPreference preference = resolveModelPreference(player.getUuid());
		if (preference != ModelPreference.AUTO) {
			return preference.toChoice();
		}
		String lower = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
		int words = prompt == null ? 0 : prompt.trim().split("\\s+").length;
		int tokens = estimateTokens(context);
		if (history != null) {
			for (ChatTurn turn : history) {
				tokens += estimateTokens(turn.text);
			}
		}
		if (tokens > 8000 || containsAny(lower, "analyze", "explain in detail", "deep dive")) {
			return ModelChoice.PRO;
		}
		if (words > 20 || containsAny(lower, "plan", "how should", "steps", "strategy")) {
			return ModelChoice.FLASH_THINKING;
		}
		return ModelChoice.FLASH;
	}

	private static Path keyDir() {
		return Path.of("run", "ai-keys");
	}

	private static Path keyPath(UUID playerId) {
		return keyDir().resolve(playerId.toString() + ".txt");
	}

	private static Path defaultKeyPath() {
		return keyDir().resolve("server.txt");
	}

	private static void writeApiKey(UUID playerId, String key) {
		try {
			Files.createDirectories(keyDir());
			Files.writeString(
				keyPath(playerId),
				key,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
		} catch (Exception e) {
			LOGGER.warn("Failed to write API key for {}: {}", playerId, e.getMessage());
		}
	}

	private static String readApiKey(UUID playerId) {
		try {
			Path path = keyPath(playerId);
			if (!Files.exists(path)) {
				return null;
			}
			return Files.readString(path, StandardCharsets.UTF_8).trim();
		} catch (Exception e) {
			LOGGER.warn("Failed to read API key for {}: {}", playerId, e.getMessage());
			return null;
		}
	}

	private static void deleteApiKey(UUID playerId) {
		try {
			Files.deleteIfExists(keyPath(playerId));
		} catch (Exception e) {
			LOGGER.warn("Failed to delete API key for {}: {}", playerId, e.getMessage());
		}
	}

	private static void writeDefaultApiKey(String key) {
		try {
			Files.createDirectories(keyDir());
			Files.writeString(
				defaultKeyPath(),
				key,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
		} catch (Exception e) {
			LOGGER.warn("Failed to write default API key: {}", e.getMessage());
		}
	}

	private static String readDefaultApiKey() {
		try {
			Path path = defaultKeyPath();
			if (!Files.exists(path)) {
				return null;
			}
			return Files.readString(path, StandardCharsets.UTF_8).trim();
		} catch (Exception e) {
			LOGGER.warn("Failed to read default API key: {}", e.getMessage());
			return null;
		}
	}

	private static void deleteDefaultApiKey() {
		try {
			Files.deleteIfExists(defaultKeyPath());
		} catch (Exception e) {
			LOGGER.warn("Failed to delete default API key: {}", e.getMessage());
		}
	}
}
