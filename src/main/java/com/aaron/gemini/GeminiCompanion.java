package com.aaron.gemini;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.util.math.BlockPos;
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
import net.minecraft.item.Item;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.util.Identifier;
import net.minecraft.nbt.NbtCompound;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.damage.DamageSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

public class GeminiCompanion implements ModInitializer {
public static final String MOD_ID = "gemini-ai-companion";
	public static final Identifier CONFIG_PACKET_C2S = Identifier.of(MOD_ID, "config_c2s");
	public static final Identifier CONFIG_PACKET_S2C = Identifier.of(MOD_ID, "config_s2c");
	public static final Identifier AUDIO_PACKET_C2S = Identifier.of(MOD_ID, "audio_c2s");
	public static final Identifier VOICE_STATE_C2S = Identifier.of(MOD_ID, "voice_state_c2s");
	public static final Identifier VISION_REQUEST_S2C = Identifier.of(MOD_ID, "vision_request_s2c");
	public static final Identifier VISION_PACKET_C2S = Identifier.of(MOD_ID, "vision_c2s");
	public static final Identifier SETTINGS_PACKET_C2S = Identifier.of(MOD_ID, "settings_c2s");
	public static final Identifier SETTINGS_APPLY_S2C = Identifier.of(MOD_ID, "settings_apply_s2c");
	public static final Identifier CHAT_FORWARD_C2S = Identifier.of(MOD_ID, "chat_forward_c2s");
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String API_KEY_ENV = "GEMINI_API_KEY";
	private static final String GEMINI_ENDPOINT_BASE =
		"https://generativelanguage.googleapis.com/v1beta/models/";
	private static final String SYSTEM_PROMPT =
		"You are an assistant inside Minecraft. Classify the user's request into one mode: ASK, PLAN, COMMAND, CONTINUE, or TOOL. " +
		"Return a JSON object with fields: mode (ASK|PLAN|COMMAND|CONTINUE|TOOL), message (plain text), commands (array), and optional build_plan (object) and optional highlights (array). " +
		"For COMMAND mode, include one or more Minecraft commands in commands, or include build_plan, or include both. For other modes, use an empty commands array and omit build_plan. " +
		"If you use build_plan, prefer commands=[] unless you truly need extra direct commands outside the structure build. " +
		"Use build_plan for structures, buildings, blocky sculptures, interiors, or any multi-block voxel output. Prefer build_plan over long raw setblock/fill command lists. " +
		"build_plan coordinates are RELATIVE to the player's block position where x=0,y=0,z=0. Positive x=east, positive y=up, positive z=south. " +
		"build_plan may include palette, clear, cuboids, blocks, optional steps, and optional rotate (0|90|180|270 or cw/ccw). cuboids use from/to OR start+size OR location+size plus block/material and optional fill=hollow|outline|solid or hollow:true. blocks use pos plus block/material and optional properties/state. steps is an array of phased sub-plans such as foundation, walls, roof, details, or redstone. " +
		"Valid cuboid example: build_plan={summary:\"Small rotated hut\",rotate:90,palette:{\"custom\":\"yourmod:ore\"},cuboids:[{name:\"floor\",block:\"oak_planks\",from:{x:0,y:0,z:0},to:{x:4,y:0,z:4}},{name:\"walls\",block:\"oak_planks\",start:{x:0,y:1,z:0},size:{x:5,y:3,z:5},hollow:true}],blocks:[{name:\"door\",block:\"oak_door\",pos:{x:2,y:1,z:0},properties:{facing:\"south\"}}]}. " +
		"For larger or terrain-sensitive builds, prefer steps with an explicit foundation phase before walls or roof. " +
		"Do not invent unsupported shape DSLs or prose-only plans. If you use build_plan, include at least one valid cuboid, block, or clear volume. " +
		"For CONTINUE, return the next step needed to finish the task; do not include commands. " +
		"For multi-step tasks that require iterative steps (e.g. scanning, gathering, crafting), prefer CONTINUE with the next step. " +
		"For TOOL mode, put skill commands in commands (e.g. chat skill inventory). The system will execute them and provide outputs; then answer in ASK/PLAN/COMMAND. " +
		"If you say you will scan, search, check, inspect, locate, or look up something, you MUST use TOOL mode before answering. " +
		"You may optionally include a highlights array to visually mark blocks: " +
		"highlights=[{x,y,z,label,color,durationMs}]. Colors: red, green, blue, gold, purple, white. " +
		"All highlights are always visible through blocks (x-ray by default). " +
		"Avoid markdown, bullet lists, or code fences. Only include the JSON you were asked for (no extra prose). " +
		"You can access the full player inventory context when it is provided; do not claim you lack inventory access. " +
		"If you are unsure about the player's items or equipment, proactively issue /chat skill inventory before answering. " +
		"If you need extra data, you may issue skill commands in commands exactly like a normal command (no leading slash). " +
		"Skill usage examples: chat skill inventory; chat skill nearby; chat skill stats; chat skill players; chat skill settings; " +
		"chat skill buildsite 16; " +
		"chat skill settings video; chat skill settings controls; chat skill lookup mainhand; chat skill lookup slot 4; " +
		"chat skill nbt mainhand; chat skill nbt slot 7; chat skill blocks minecraft:diamond_ore 32; " +
		"chat skill blocks #minecraft:logs 24; chat skill containers 32; chat skill containers minecraft:barrel 24; " +
		"chat skill blockdata 12 64 -30; chat skill blockdata nearest minecraft:chest 24; chat skill blockdata minecraft:chest nearest 24; " +
		"chat skill recipe minecraft:hopper; chat skill smelt minecraft:raw_iron. " +
		"When you receive skill output, treat it as trusted context and answer directly using it. You do not need to repeat the full data, only relevant parts. " +
		"For direct world-edit requests like build, place, construct, spawn, fill, setblock, or create, do NOT search inventory, containers, or materials unless the user explicitly asks for a survival-friendly, gathered, craftable, or inventory-limited solution. In those cases, prefer COMMAND with build_plan or direct commands. " +
		"If you already have enough information to complete a build request, return COMMAND immediately. Do not ask for confirmation after you can already perform the build, and do not switch back to ASK for the same build unless execution failed or the user explicitly asked for options first. " +
		"If a user asks about what is inside a chest/container, always use chat skill blockdata nearest minecraft:chest <radius> first. " +
		"If a user asks where a chest/container is, use chat skill containers <radius> and then highlight the nearest match. " +
		"If a user asks to highlight/find a block or ore, use chat skill blocks <block or #tag> <radius>. " +
		"For terrain-sensitive or larger structures, prefer TOOL mode with chat skill buildsite <radius> before returning COMMAND mode with build_plan. For small known builds like shrines, huts, statues, pillars, or simple rooms, skip buildsite unless the user asks you to fit the terrain or choose a location. " +
		"To change client settings or keybinds, use /chat setsetting <key> <value> and wait for the confirmation prompt. " +
		"Do NOT use any /options command. " +
		"chat skill recipe <item> returns all matching recipes across vanilla and modded recipe types with ingredients. " +
		"chat skill smelt <item> returns cooking recipes only. " +
		"If the user asks about what they see or to analyze the view, you will receive the player's current screenshot automatically. " +
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
	private static final Map<UUID, String> VOICE_STATE = new ConcurrentHashMap<>();
	private static final Map<UUID, String> VISION_STATE = new ConcurrentHashMap<>();
	private static final Map<UUID, List<ChatTurn>> CHAT_HISTORY = new ConcurrentHashMap<>();
	private static final Map<UUID, String> API_KEYS = new ConcurrentHashMap<>();
	private static final Map<UUID, List<DeathRecord>> DEATHS = new ConcurrentHashMap<>();
	private static final Map<UUID, UndoBatch> LAST_UNDO_BATCHES = new ConcurrentHashMap<>();
	private static final Map<UUID, String> LAST_PROMPT = new ConcurrentHashMap<>();
	private static final Map<UUID, RequestState> REQUEST_STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, VisionRequest> PENDING_VISION = new ConcurrentHashMap<>();
	private static final Map<Long, PendingMcpCapture> PENDING_MCP_CAPTURES = new ConcurrentHashMap<>();
	private static final Map<String, PendingMcpCommandBatch> PENDING_MCP_COMMAND_BATCHES = new ConcurrentHashMap<>();
	private static final Map<String, CachedMcpBuildPlan> PENDING_MCP_BUILD_PREVIEWS = new ConcurrentHashMap<>();
	private static final Map<UUID, String> LAST_MCP_COMMAND_BATCH_BY_PLAYER = new ConcurrentHashMap<>();
	private static final Map<UUID, SettingsSnapshot> SETTINGS_SNAPSHOTS = new ConcurrentHashMap<>();
	private static final Map<UUID, PendingSettingChange> PENDING_SETTING_CHANGES = new ConcurrentHashMap<>();
	private static final Set<UUID> AI_WHITELIST = ConcurrentHashMap.newKeySet();
	private static PermissionMode PERMISSION_MODE = PermissionMode.OPS;
	private static boolean SETUP_COMPLETE = false;
	private static final int DEFAULT_MCP_BRIDGE_PORT = 7766;
	private static final boolean MCP_LOOPBACK_ONLY = true;
	private static boolean MCP_BRIDGE_ENABLED = false;
	private static int MCP_BRIDGE_PORT = DEFAULT_MCP_BRIDGE_PORT;
	private static String MCP_BRIDGE_TOKEN = "";
	private static final SecureRandom MCP_TOKEN_RANDOM = new SecureRandom();
	private static volatile McpBridgeServer MCP_BRIDGE;
	private static volatile MinecraftServer ACTIVE_SERVER;
	private static volatile long LAST_SERVER_TICK_MS = 0L;
	private static final Map<UUID, Long> SETUP_WARN_COOLDOWN = new ConcurrentHashMap<>();
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
	private static final int MAX_VISION_BYTES = 2_500_000;
	private static final long VISION_TIMEOUT_MS = 15_000L;
	private static final AtomicLong MCP_CAPTURE_REQUEST_IDS = new AtomicLong(1L);
	private static final AtomicLong MCP_COMMAND_BATCH_IDS = new AtomicLong(1L);
	private static final AtomicLong MCP_BUILD_PLAN_IDS = new AtomicLong(1L);
	private static final Set<UUID> COMMAND_DEBUG_ENABLED = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Integer> COMMAND_RETRY_LIMITS = new ConcurrentHashMap<>();
	private static final int MAX_CONTEXT_TOKENS = 16000;
	private static final int COMMAND_MODIFICATION_LIMIT = 2_097_152;
	private static final int DEFAULT_BLOCK_SCAN_RADIUS = 24;
	private static final int MAX_BLOCK_SCAN_POSITIONS = 512_000;
	private static final int MAX_BLOCK_SCAN_RESULTS = 12;
	private static final int MAX_CONTAINER_RESULTS = 8;
	private static final int MAX_CONTINUE_STEPS = 5;
	private static final int MAX_TOOL_STEPS = 4;
	private static final int MAX_UNDO_SNAPSHOT_BLOCKS = 65_536;
	private static final int MAX_MCP_DELAYED_COMMANDS = 128;
	private static final int MAX_MCP_DELAY_TICKS = 20 * 60;
	private static final long MCP_COMMAND_BATCH_RETENTION_MS = 5L * 60L * 1000L;
	private static final long MCP_BUILD_PLAN_RETENTION_MS = 10L * 60L * 1000L;
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
				.then(CommandManager.literal("vision")
					.then(CommandManager.argument("text", StringArgumentType.greedyString())
						.executes(context -> handleChatCommand(context.getSource(), StringArgumentType.getString(context, "text"), true)))
					.executes(context -> handleChatCommand(context.getSource(), "Analyze my current view.", true)))
				.then(CommandManager.literal("setup")
					.executes(context -> runSetupWizard(context.getSource())))
				.then(CommandManager.literal("setsetting")
					.then(CommandManager.argument("key", StringArgumentType.word())
						.then(CommandManager.argument("value", StringArgumentType.greedyString())
							.executes(context -> requestSettingChange(
								context.getSource(),
								StringArgumentType.getString(context, "key"),
								StringArgumentType.getString(context, "value")))))
					.then(CommandManager.literal("confirm")
						.executes(context -> confirmSettingChange(context.getSource())))
					.then(CommandManager.literal("cancel")
						.executes(context -> cancelSettingChange(context.getSource()))))
				.then(CommandManager.literal("perms")
					.then(CommandManager.argument("mode", StringArgumentType.word())
						.executes(context -> setPermissionMode(context.getSource(), StringArgumentType.getString(context, "mode")))))
				.then(CommandManager.literal("allow")
					.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(context -> allowPlayer(context.getSource(), StringArgumentType.getString(context, "player")))))
				.then(CommandManager.literal("deny")
					.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(context -> denyPlayer(context.getSource(), StringArgumentType.getString(context, "player")))))
				.then(CommandManager.literal("status")
					.executes(context -> showSetupStatus(context.getSource())))
				.then(CommandManager.argument("text", StringArgumentType.greedyString())
					.executes(context -> handleChatCommand(context.getSource(), StringArgumentType.getString(context, "text"), false))));
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
				.then(CommandManager.literal("mcp")
					.then(CommandManager.literal("enable")
						.executes(context -> setMcpBridgeEnabled(context.getSource(), true)))
					.then(CommandManager.literal("disable")
						.executes(context -> setMcpBridgeEnabled(context.getSource(), false)))
					.then(CommandManager.literal("status")
						.executes(context -> showMcpBridgeStatus(context.getSource())))
					.then(CommandManager.literal("setup")
						.executes(context -> showMcpBridgeSetupOptions(context.getSource()))
						.then(CommandManager.argument("client", StringArgumentType.word())
							.executes(context -> showMcpBridgeSetup(
								context.getSource(),
								StringArgumentType.getString(context, "client")))))
					.then(CommandManager.literal("token")
						.then(CommandManager.literal("regen")
							.executes(context -> regenerateMcpBridgeToken(context.getSource())))
						.then(CommandManager.literal("copy")
							.executes(context -> copyMcpBridgeToken(context.getSource())))
						.then(CommandManager.literal("show")
							.executes(context -> showMcpBridgeToken(context.getSource()))))));
			dispatcher.register(CommandManager.literal("chat")
				.then(CommandManager.literal("skill")
					.then(CommandManager.literal("inventory")
						.executes(context -> runChatSkill(context.getSource(), "inventory", null)))
					.then(CommandManager.literal("nearby")
						.executes(context -> runChatSkill(context.getSource(), "nearby", null)))
					.then(CommandManager.literal("blocks")
						.then(CommandManager.argument("target", StringArgumentType.string())
							.executes(context -> runChatSkill(
								context.getSource(),
								"blocks",
								StringArgumentType.getString(context, "target")))
							.then(CommandManager.argument("radius", IntegerArgumentType.integer(1))
								.executes(context -> runChatSkill(
									context.getSource(),
									"blocks",
									StringArgumentType.getString(context, "target") + " " + IntegerArgumentType.getInteger(context, "radius"))))))
					.then(CommandManager.literal("containers")
						.executes(context -> runChatSkill(context.getSource(), "containers", null))
						.then(CommandManager.argument("filter", StringArgumentType.string())
							.executes(context -> runChatSkill(
								context.getSource(),
								"containers",
								StringArgumentType.getString(context, "filter")))
							.then(CommandManager.argument("radius", IntegerArgumentType.integer(1))
								.executes(context -> runChatSkill(
									context.getSource(),
									"containers",
									StringArgumentType.getString(context, "filter") + " " + IntegerArgumentType.getInteger(context, "radius")))))
						.then(CommandManager.argument("radius", IntegerArgumentType.integer(1))
							.executes(context -> runChatSkill(
								context.getSource(),
								"containers",
								String.valueOf(IntegerArgumentType.getInteger(context, "radius"))))))
					.then(CommandManager.literal("blockdata")
						.executes(context -> runChatSkill(context.getSource(), "blockdata", null))
						.then(CommandManager.argument("args", StringArgumentType.greedyString())
							.executes(context -> runChatSkill(
								context.getSource(),
								"blockdata",
								StringArgumentType.getString(context, "args")))))
					.then(CommandManager.literal("players")
						.executes(context -> runChatSkill(context.getSource(), "players", null)))
					.then(CommandManager.literal("stats")
						.executes(context -> runChatSkill(context.getSource(), "stats", null)))
					.then(CommandManager.literal("buildsite")
						.executes(context -> runChatSkill(context.getSource(), "buildsite", null))
						.then(CommandManager.argument("radius", IntegerArgumentType.integer(4))
							.executes(context -> runChatSkill(
								context.getSource(),
								"buildsite",
								String.valueOf(IntegerArgumentType.getInteger(context, "radius"))))))
					.then(CommandManager.literal("settings")
						.executes(context -> runChatSkill(context.getSource(), "settings", null))
						.then(CommandManager.argument("category", StringArgumentType.word())
							.executes(context -> runChatSkill(
								context.getSource(),
								"settings",
								StringArgumentType.getString(context, "category")))))
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
					.then(CommandManager.literal("lookup")
						.executes(context -> runChatSkill(context.getSource(), "lookup", null))
						.then(CommandManager.argument("target", StringArgumentType.word())
							.executes(context -> runChatSkill(
								context.getSource(),
								"lookup",
								StringArgumentType.getString(context, "target")))))
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

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ACTIVE_SERVER = server;
			LAST_SERVER_TICK_MS = System.currentTimeMillis();
			applyCommandModificationLimit(server);
			startOrRestartMcpBridge(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			stopMcpBridge();
			LAST_SERVER_TICK_MS = 0L;
			if (ACTIVE_SERVER == server) {
				ACTIVE_SERVER = null;
			}
			PENDING_MCP_COMMAND_BATCHES.clear();
			PENDING_MCP_BUILD_PREVIEWS.clear();
			LAST_MCP_COMMAND_BATCH_BY_PLAYER.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			LAST_SERVER_TICK_MS = System.currentTimeMillis();
			cleanupPendingMcpCaptures(LAST_SERVER_TICK_MS);
			processPendingMcpCommandBatches(server, LAST_SERVER_TICK_MS);
			cleanupPendingMcpBuildPreviews(LAST_SERVER_TICK_MS);
			if (THINKING_TICKS.isEmpty()) {
				// Still allow mode indicators to render.
			}

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				long now = System.currentTimeMillis();
				UUID id = player.getUuid();
				VisionRequest pendingVision = PENDING_VISION.get(id);
				if (pendingVision != null && now - pendingVision.createdMs > VISION_TIMEOUT_MS) {
					PENDING_VISION.remove(id);
					VISION_STATE.remove(id);
					player.sendMessage(Text.literal("Vision capture timed out."), false);
				}
				String vision = VISION_STATE.get(id);
				if (vision != null) {
					AiStats stats = getStats(id);
					stats.state = vision;
					updateSidebar(player, stats);
					if ("LOOKING".equals(vision)) {
						player.sendMessage(Text.literal("Capturing view...").formatted(Formatting.AQUA), true);
					} else if ("ANALYZING".equals(vision)) {
						player.sendMessage(Text.literal("Analyzing view...").formatted(Formatting.AQUA), true);
					}
					continue;
				}
				String voice = VOICE_STATE.get(id);
				if (voice != null) {
					AiStats stats = getStats(id);
					stats.state = voice;
					updateSidebar(player, stats);
					if ("LISTENING".equals(voice)) {
						player.sendMessage(Text.literal("Listening...").formatted(Formatting.AQUA), true);
					} else if ("TRANSCRIBING".equals(voice)) {
						player.sendMessage(Text.literal("Transcribing...").formatted(Formatting.AQUA), true);
					}
					continue;
				}
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
			if (!player.getServer().isSingleplayer() && !SETUP_COMPLETE && player.hasPermissionLevel(2)) {
				player.sendMessage(Text.literal("Gemini AI is not configured. Run /chat setup to begin.").formatted(Formatting.YELLOW), false);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.getPlayer().getUuid();
			saveDeaths(playerId);
			savePlayerSettings(playerId);
			failPendingMcpCapturesForPlayer(playerId, "Player disconnected before vision capture completed.");
		});

		loadGlobalSettings();
		PayloadTypeRegistry.playC2S().register(ConfigPayloadC2S.ID, ConfigPayloadC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(ConfigPayloadS2C.ID, ConfigPayloadS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(AudioPayloadC2S.ID, AudioPayloadC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(VoiceStatePayloadC2S.ID, VoiceStatePayloadC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(VisionRequestPayloadS2C.ID, VisionRequestPayloadS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(VisionPayloadC2S.ID, VisionPayloadC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(SettingsPayloadC2S.ID, SettingsPayloadC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(SettingsApplyPayloadS2C.ID, SettingsApplyPayloadS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(HighlightsPayloadS2C.ID, HighlightsPayloadS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(ChatForwardPayloadC2S.ID, ChatForwardPayloadC2S.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ConfigPayloadC2S.ID, (payload, context) -> {
			handleConfigPacket(context.server(), context.player(), payload.json());
		});
		ServerPlayNetworking.registerGlobalReceiver(AudioPayloadC2S.ID, (payload, context) -> {
			context.server().execute(() -> handleVoicePayload(context.player(), payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(VoiceStatePayloadC2S.ID, (payload, context) -> {
			context.server().execute(() -> handleVoiceState(context.player(), payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(VisionPayloadC2S.ID, (payload, context) -> {
			context.server().execute(() -> handleVisionPayload(context.player(), payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(SettingsPayloadC2S.ID, (payload, context) -> {
			context.server().execute(() -> handleSettingsPayload(context.player(), payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(ChatForwardPayloadC2S.ID, (payload, context) -> {
			context.server().execute(() -> handleChatForward(context.player(), payload));
		});
	}

	private static void handleChatForward(ServerPlayerEntity player, ChatForwardPayloadC2S payload) {
		if (player == null || payload == null) {
			return;
		}
		String text = payload.text();
		if (text == null || text.isBlank()) {
			return;
		}
		String trimmed = text.trim();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1).trim();
		}
		if (!trimmed.toLowerCase(Locale.ROOT).startsWith("chat")) {
			return;
		}
		player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), trimmed);
	}

	private static int handleChatCommand(ServerCommandSource source, String prompt, boolean forceVision) {
		if (!isSetupComplete(source)) {
			ServerPlayerEntity player = source.getPlayer();
			if (player != null) {
				sendSetupReminder(player);
			} else {
				source.sendFeedback(() -> Text.literal("Gemini AI is not configured. Run /chat setup to begin."), false);
			}
			return 0;
		}
		if (!canUseAi(source)) {
			return 0;
		}
		String apiKey = resolveApiKey(source);
		if (apiKey == null || apiKey.isBlank()) {
			source.sendError(Text.literal("No API key set. Use /chatkey <key>, /chatkey default <key>, or set GEMINI_API_KEY."));
			return 0;
		}

		ServerPlayerEntity player = source.getPlayer();
		if (player != null && isContextFull(player.getUuid())) {
			sendContextLimitMessage(player);
			return 0;
		}
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
		if (player != null && (forceVision || isVisionQuery(prompt))) {
			requestVisionCapture(source, player, apiKey, prompt, contextSnapshot, null);
			return 1;
		}
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
			VOICE_STATE.remove(player.getUuid());
			return;
		}
		byte[] audio = payload.data();
		if (audio == null || audio.length == 0) {
			player.sendMessage(Text.literal("No audio received."), false);
			VOICE_STATE.remove(player.getUuid());
			return;
		}
		if (audio.length > MAX_VOICE_BYTES) {
			player.sendMessage(Text.literal("Voice clip too large. Try a shorter clip."), false);
			VOICE_STATE.remove(player.getUuid());
			return;
		}
		THINKING_TICKS.put(player.getUuid(), 0);
		registerRequest(player.getUuid());
		sendCancelPrompt(player);
		VOICE_STATE.put(player.getUuid(), "TRANSCRIBING");
		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> handleVoiceFlow(player, apiKey, payload));
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state != null) {
			state.future = future;
		}
	}

	private static void handleVoiceState(ServerPlayerEntity player, VoiceStatePayloadC2S payload) {
		if (player == null || payload == null) {
			return;
		}
		String state = payload.state() == null ? "" : payload.state().toUpperCase(Locale.ROOT);
		if ("LISTENING".equals(state)) {
			String apiKey = resolveApiKey(player.getCommandSource());
			if (apiKey == null || apiKey.isBlank()) {
				player.sendMessage(Text.literal("No API key set. Use /chatkey <key> or /chatkey default <key>."), false);
				VOICE_STATE.remove(player.getUuid());
				return;
			}
			VOICE_STATE.put(player.getUuid(), "LISTENING");
			return;
		}
		if ("IDLE".equals(state)) {
			VOICE_STATE.remove(player.getUuid());
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
				VOICE_STATE.remove(player.getUuid());
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
				VOICE_STATE.remove(player.getUuid());
			});
			return;
		}
		if (isContextFull(player.getUuid())) {
			player.getServer().execute(() -> {
				THINKING_TICKS.remove(player.getUuid());
				STATUS_STATE.remove(player.getUuid());
				VOICE_STATE.remove(player.getUuid());
				sendContextLimitMessage(player);
			});
			return;
		}
		VOICE_STATE.remove(player.getUuid());
		LAST_PROMPT.put(player.getUuid(), cleaned);
		player.getServer().execute(() -> player.sendMessage(Text.literal("You (voice): " + cleaned).formatted(Formatting.GRAY), false));
		boolean inventoryQuery = isInventoryQuery(cleaned);
		String contextSnapshot = buildContext(player.getCommandSource(), player, cleaned, inventoryQuery);
		if (isVisionQuery(cleaned)) {
			requestVisionCapture(player.getCommandSource(), player, apiKey, cleaned, contextSnapshot, null);
			return;
		}
		handleGeminiFlow(player.getCommandSource(), player, apiKey, cleaned, contextSnapshot, null);
	}

	private static void handleVisionPayload(ServerPlayerEntity player, VisionPayloadC2S payload) {
		if (player == null || payload == null) {
			return;
		}
		PendingMcpCapture pendingCapture = PENDING_MCP_CAPTURES.remove(payload.requestId());
		if (pendingCapture != null) {
			if (!pendingCapture.playerId().equals(player.getUuid())) {
				pendingCapture.future().complete(new McpVisionCaptureResult(false, payload.mimeType(), payload.lookAt(), "", 0, "Vision capture returned for the wrong player.", ""));
				return;
			}
			byte[] capture = payload.data();
			if (capture == null || capture.length == 0) {
				pendingCapture.future().complete(new McpVisionCaptureResult(false, payload.mimeType(), payload.lookAt(), "", 0, "Vision capture failed.", ""));
				return;
			}
			if (capture.length > MAX_VISION_BYTES) {
				pendingCapture.future().complete(new McpVisionCaptureResult(false, payload.mimeType(), payload.lookAt(), "", capture.length, "Vision image too large. Try lowering your resolution.", ""));
				return;
			}
			String savedPath = saveVisionCapturePng(player.getUuid(), capture, "mcp");
			pendingCapture.future().complete(new McpVisionCaptureResult(
				true,
				payload.mimeType(),
				payload.lookAt(),
				Base64.getEncoder().encodeToString(capture),
				capture.length,
				"Captured current view.",
				savedPath
			));
			return;
		}
		VisionRequest request = PENDING_VISION.remove(player.getUuid());
		if (request == null) {
			VISION_STATE.remove(player.getUuid());
			return;
		}
		if (payload.lookAt() != null && !payload.lookAt().isBlank()) {
			request = new VisionRequest(
				request.prompt,
				request.context + "\n" + payload.lookAt(),
				request.apiKey,
				request.overrideModel,
				request.createdMs
			);
		}
		byte[] image = payload.data();
		if (image == null || image.length == 0) {
			player.sendMessage(Text.literal("Vision capture failed."), false);
			VISION_STATE.remove(player.getUuid());
			return;
		}
		if (image.length > MAX_VISION_BYTES) {
			player.sendMessage(Text.literal("Vision image too large. Try lowering your resolution."), false);
			VISION_STATE.remove(player.getUuid());
			return;
		}
		String savedPath = saveVisionCapturePng(player.getUuid(), image, "vision");
		if (!savedPath.isBlank()) {
			player.sendMessage(Text.literal("Vision PNG saved: " + savedPath), false);
		}
		VISION_STATE.put(player.getUuid(), "ANALYZING");
		VisionRequest finalRequest = request;
		CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
			handleGeminiVisionFlow(player, finalRequest, payload.mimeType(), image)
		);
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state != null) {
			state.future = future;
		}
	}

	private static void handleSettingsPayload(ServerPlayerEntity player, SettingsPayloadC2S payload) {
		if (player == null || payload == null) {
			return;
		}
		JsonObject obj = GSON.fromJson(payload.json(), JsonObject.class);
		if (obj == null) {
			return;
		}
		Map<String, String> video = readSettingsMap(obj, "video");
		Map<String, String> controls = readSettingsMap(obj, "controls");
		long updated = obj.has("updatedMs") ? obj.get("updatedMs").getAsLong() : System.currentTimeMillis();
		SettingsSnapshot snapshot = new SettingsSnapshot(video, controls, updated);
		SETTINGS_SNAPSHOTS.put(player.getUuid(), snapshot);
	}

	private static Map<String, String> readSettingsMap(JsonObject obj, String key) {
		Map<String, String> map = new LinkedHashMap<>();
		if (!obj.has(key) || !obj.get(key).isJsonObject()) {
			return map;
		}
		JsonObject section = obj.getAsJsonObject(key);
		for (var entry : section.entrySet()) {
			map.put(entry.getKey(), entry.getValue().getAsString());
		}
		return map;
	}

	private static void handleGeminiVisionFlow(ServerPlayerEntity player, VisionRequest request, String mimeType, byte[] imageBytes) {
		RequestState state = REQUEST_STATES.get(player.getUuid());
		if (state != null && state.cancelled.get()) {
			VISION_STATE.remove(player.getUuid());
			return;
		}
		String prompt = request.prompt;
		String context = request.context;
		List<ChatTurn> history = getHistory(player.getUuid());
		long startNs = System.nanoTime();
		ModelChoice modelChoice = request.overrideModel != null ? request.overrideModel : selectModel(player, prompt, context, history);

		AiStats stats = getStats(player.getUuid());
		stats.state = "ANALYZING";
		stats.contextSize = history.size();
		stats.tokenPercent = estimateTokenPercent(context, history, prompt);
		updateSidebar(player, stats);

		ModeMessage reply = callGeminiVisionSafely(request.apiKey, prompt, context, history, mimeType, imageBytes, modelChoice);
		reply = applyXrayOverride(reply, prompt);
		if (state != null && state.cancelled.get()) {
			VISION_STATE.remove(player.getUuid());
			return;
		}

		reply = maybeRequestToolFromReply(player, request.apiKey, prompt, context, history, reply, modelChoice);
		if ("TOOL".equals(reply.mode) && player != null) {
			reply = handleToolMode(player, request.apiKey, prompt, context, history, reply, modelChoice);
		}

		reply = handleContinueLoop(player, request.apiKey, prompt, context, history, reply, modelChoice);

		final boolean[] planAlreadySent = new boolean[] { false };
		if ("PLAN".equals(reply.mode)) {
			ModeMessage planSnapshot = reply;
			player.getServer().execute(() -> {
				MutableText prefix = buildModeChip("PLAN");
				player.sendMessage(prefix.append(Text.literal(planSnapshot.message)), false);
				MODE_STATE.put(player.getUuid(), new ModeState(modeLabel("PLAN"), modeColor("PLAN"), 60));
				AiStats statsInner = getStats(player.getUuid());
				statsInner.mode = "PLAN";
				statsInner.state = "PLANNING";
				updateSidebar(player, statsInner);
			});
			setStatus(player, "Iterating on the plan...", Formatting.AQUA);
			planAlreadySent[0] = true;
			String planInstruction =
				"Convert the plan above into COMMAND mode. Return mode COMMAND, message \"Initiating plan.\", and include commands, build_plan, or both. " +
				"Plan: " + reply.message;
			ModeMessage planCommands = callGeminiSafely(request.apiKey, prompt, context, history, planInstruction, modelChoice);
			if ("COMMAND".equals(planCommands.mode)) {
				reply = planCommands;
			}
		}

		if ("COMMAND".equals(reply.mode)) {
			reply = handleCommandMode(player.getCommandSource(), player, request.apiKey, prompt, context, history, reply, modelChoice);
		}
		reply = handleContinueLoop(player, request.apiKey, prompt, context, history, reply, modelChoice);

		long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
		ModeMessage finalReply = reply;
		String contextForStats = context;
		player.getServer().execute(() -> {
			VISION_STATE.remove(player.getUuid());
			if (state != null && state.cancelled.get()) {
				REQUEST_STATES.remove(player.getUuid());
				return;
			}
			THINKING_TICKS.remove(player.getUuid());
			STATUS_STATE.remove(player.getUuid());
			if (!finalReply.message.startsWith("Error:")) {
				MODE_STATE.put(player.getUuid(), new ModeState(modeLabel(finalReply.mode), modeColor(finalReply.mode), 60));
			}
			ModeMessage displayReply = finalReply;
			if ("COMMAND".equals(displayReply.mode) && !hasActionPayload(displayReply, player)) {
				displayReply = new ModeMessage("ASK", displayReply.message, List.of(), displayReply.searchUsed, displayReply.sources, displayReply.highlights);
			}
			AiStats statsDone = getStats(player.getUuid());
			statsDone.mode = displayReply.mode;
			statsDone.lastResponseMs = elapsedMs;
			statsDone.recordResponseTime(elapsedMs);
			statsDone.state = displayReply.searchUsed ? "SEARCHING" : resolveState(displayReply.message);
			if (planAlreadySent[0] && "COMMAND".equals(displayReply.mode)) {
				statsDone.state = "EXECUTING";
			}
			statsDone.contextSize = history.size();
			statsDone.tokenPercent = estimateTokenPercent(contextForStats, history, prompt);
			if (!displayReply.message.startsWith("Error:")) {
				statsDone.sessionTokens += estimateTokens(displayReply.message);
			}
			updateSidebar(player, statsDone);

			sendHighlights(player, displayReply.highlights);

			if (displayReply.message.startsWith("Error:")) {
				player.sendMessage(Text.literal(displayReply.message), false);
			} else {
				boolean skipMessage = planAlreadySent[0] && "PLAN".equals(displayReply.mode);
				if (!skipMessage) {
					MutableText message = Text.literal(displayReply.message);
					if (displayReply.mode.equals("ASK")) {
						player.sendMessage(message, false);
					} else {
						MutableText prefix = buildModeChip(displayReply.mode);
						player.sendMessage(prefix.append(message), false);
					}
				}
			}
			emitFeedbackEffects(player, displayReply);
		});
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

	private static boolean isSetupComplete(ServerCommandSource source) {
		if (source.getServer().isSingleplayer()) {
			return true;
		}
		if (SETUP_COMPLETE) {
			return true;
		}
		if (source.hasPermissionLevel(2)) {
			source.sendFeedback(() -> Text.literal("Gemini AI is not configured yet. Run /chat setup to begin."), false);
		} else {
			source.sendFeedback(() -> Text.literal("Gemini AI is not configured. Ask an OP to run /chat setup."), false);
		}
		return false;
	}

	private static boolean canUseAi(ServerCommandSource source) {
		if (source.getServer().isSingleplayer()) {
			return true;
		}
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			return false;
		}
		return switch (PERMISSION_MODE) {
			case ALL -> true;
			case WHITELIST -> AI_WHITELIST.contains(player.getUuid()) || source.hasPermissionLevel(2);
			default -> source.hasPermissionLevel(2);
		};
	}

	private static int runSetupWizard(ServerCommandSource source) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only server operators can run setup."));
			return 0;
		}
		MutableText line = Text.literal("Gemini AI Setup: Who can use the AI? ").formatted(Formatting.AQUA);
		line.append(Text.literal("[Ops]").formatted(Formatting.GOLD)
			.styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat perms ops"))));
		line.append(Text.literal(" "));
		line.append(Text.literal("[Whitelist]").formatted(Formatting.GREEN)
			.styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat perms whitelist"))));
		line.append(Text.literal(" "));
		line.append(Text.literal("[Everyone]").formatted(Formatting.LIGHT_PURPLE)
			.styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat perms all"))));
		source.sendFeedback(() -> line, false);
		source.sendFeedback(() -> Text.literal("Then set the server API key: /chatkey default <key>"), false);
		return 1;
	}

	private static int setPermissionMode(ServerCommandSource source, String mode) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only server operators can change permissions."));
			return 0;
		}
		PermissionMode parsed = parsePermissionMode(mode);
		if (parsed == null) {
			source.sendError(Text.literal("Invalid mode. Use ops, whitelist, or all."));
			return 0;
		}
		PERMISSION_MODE = parsed;
		SETUP_COMPLETE = true;
		saveGlobalSettings();
		source.sendFeedback(() -> Text.literal("Permission mode set to " + parsed.name().toLowerCase(Locale.ROOT) + "."), false);
		return 1;
	}

	private static int allowPlayer(ServerCommandSource source, String name) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only server operators can edit the whitelist."));
			return 0;
		}
		ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
		if (target == null) {
			source.sendError(Text.literal("Player not found: " + name));
			return 0;
		}
		AI_WHITELIST.add(target.getUuid());
		saveGlobalSettings();
		source.sendFeedback(() -> Text.literal("Added " + target.getName().getString() + " to the AI whitelist."), false);
		return 1;
	}

	private static int denyPlayer(ServerCommandSource source, String name) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only server operators can edit the whitelist."));
			return 0;
		}
		ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
		if (target == null) {
			source.sendError(Text.literal("Player not found: " + name));
			return 0;
		}
		AI_WHITELIST.remove(target.getUuid());
		saveGlobalSettings();
		source.sendFeedback(() -> Text.literal("Removed " + target.getName().getString() + " from the AI whitelist."), false);
		return 1;
	}

	private static int showSetupStatus(ServerCommandSource source) {
		String mode = PERMISSION_MODE.name().toLowerCase(Locale.ROOT);
		String setup = SETUP_COMPLETE ? "complete" : "not configured";
		source.sendFeedback(() -> Text.literal("Setup: " + setup + ", permissions: " + mode), false);
		return 1;
	}

	private static int requestSettingChange(ServerCommandSource source, String key, String value) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		if (key == null || key.isBlank() || value == null || value.isBlank()) {
			source.sendError(Text.literal("Usage: /chat setsetting <key> <value>"));
			return 0;
		}
		String normalizedKey = normalizeSettingKey(key.trim());
		PENDING_SETTING_CHANGES.put(player.getUuid(), new PendingSettingChange(normalizedKey, value.trim()));
		player.sendMessage(Text.literal("Setting change requested").formatted(Formatting.AQUA), false);
		MutableText details = Text.literal("Apply ").formatted(Formatting.GRAY)
			.append(Text.literal(normalizedKey).formatted(Formatting.AQUA))
			.append(Text.literal(" = ").formatted(Formatting.GRAY))
			.append(Text.literal(value.trim()).formatted(Formatting.YELLOW))
			.append(Text.literal("? ").formatted(Formatting.GRAY));
		details.append(Text.literal("[Confirm]").formatted(Formatting.GREEN)
			.styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat setsetting confirm"))));
		details.append(Text.literal(" "));
		details.append(Text.literal("[Cancel]").formatted(Formatting.RED)
			.styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat setsetting cancel"))));
		player.sendMessage(details, false);
		return 1;
	}

	private static String normalizeSettingKey(String key) {
		if (key == null) {
			return "";
		}
		String trimmed = key.trim();
		if (trimmed.startsWith("key.")) {
			return "key_key." + trimmed.substring("key.".length());
		}
		if (trimmed.startsWith("key_") && !trimmed.startsWith("key_key.")) {
			return "key_key." + trimmed.substring("key_".length());
		}
		return trimmed;
	}

	private static int confirmSettingChange(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		PendingSettingChange change = PENDING_SETTING_CHANGES.remove(player.getUuid());
		if (change == null) {
			source.sendError(Text.literal("No pending setting change."));
			return 0;
		}
		ServerPlayNetworking.send(player, new SettingsApplyPayloadS2C(change.key(), change.value()));
		player.sendMessage(Text.literal("Applying setting on client..."), false);
		return 1;
	}

	private static int cancelSettingChange(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) {
			source.sendError(Text.literal("This command must be run by a player."));
			return 0;
		}
		PendingSettingChange change = PENDING_SETTING_CHANGES.remove(player.getUuid());
		if (change == null) {
			source.sendFeedback(() -> Text.literal("No pending setting change."), false);
			return 0;
		}
		player.sendMessage(Text.literal("Setting change cancelled."), false);
		return 1;
	}

	private static PermissionMode parsePermissionMode(String value) {
		if (value == null) {
			return null;
		}
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "ops", "op" -> PermissionMode.OPS;
			case "whitelist", "list" -> PermissionMode.WHITELIST;
			case "all", "everyone", "any" -> PermissionMode.ALL;
			default -> null;
		};
	}

	private static boolean isVisionQuery(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return false;
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		if (containsAny(
			lower,
			"what do you see",
			"what can you see",
			"look at my screen",
			"look around",
			"what's in front",
			"what is in front",
			"scan my screen",
			"scan this",
			"analyze my view",
			"analyze the view",
			"what am i looking at",
			"what's on screen",
			"what is on screen",
			"see this",
			"vision",
			"screenshot",
			"screen shot",
			"look at this",
			"look here",
			"see what i'm seeing",
			"see what i see"
		)) {
			return true;
		}
		boolean mentionsView = containsAny(lower, "screen", "view", "look", "see", "sight", "camera", "observe");
		boolean mentionsAsk = containsAny(lower, "what is", "what's", "what am", "tell me", "describe", "analyze", "identify", "explain", "show me");
		return mentionsView && mentionsAsk;
	}

	private static void requestVisionCapture(ServerCommandSource source, ServerPlayerEntity player, String apiKey, String prompt, String context, ModelChoice overrideModel) {
		if (player == null) {
			source.sendError(Text.literal("Vision capture requires a player."));
			return;
		}
		long now = System.currentTimeMillis();
		PENDING_VISION.put(player.getUuid(), new VisionRequest(prompt, context, apiKey, overrideModel, now));
		VISION_STATE.put(player.getUuid(), "LOOKING");
		THINKING_TICKS.remove(player.getUuid());
		sendCancelPrompt(player);
		ServerPlayNetworking.send(player, new VisionRequestPayloadS2C(now));
	}

	private static void sendDebugMessage(ServerPlayerEntity player, String message) {
		if (player == null || message == null || message.isBlank()) {
			return;
		}
		if (!COMMAND_DEBUG_ENABLED.contains(player.getUuid())) {
			return;
		}
		player.sendMessage(Text.literal("[Debug] " + message).formatted(Formatting.GRAY), false);
		LOGGER.info("Debug [{}]: {}", player.getName().getString(), message);
	}

	private static String limitDebugOutput(String text) {
		if (text == null) {
			return "";
		}
		int max = 320;
		String trimmed = text.trim();
		if (trimmed.length() <= max) {
			return trimmed;
		}
		return trimmed.substring(0, max) + "...";
	}

	private static List<String> filterExecutableCommands(List<String> commands, ServerPlayerEntity player) {
		if (commands == null || commands.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		int filtered = 0;
		for (String raw : commands) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String sanitized = sanitizeCommand(raw);
			if (sanitized.isBlank()) {
				continue;
			}
			if (isSkillCommand(sanitized) || isSettingChangeCommand(sanitized)) {
				out.add(sanitized);
				continue;
			}
			if (isLikelySentence(sanitized)) {
				filtered++;
				continue;
			}
			out.add(sanitized);
		}
		if (filtered > 0) {
			sendDebugMessage(player, "Filtered " + filtered + " non-command line(s).");
		}
		return out;
	}

	private static boolean isLikelySentence(String command) {
		if (command == null) {
			return true;
		}
		String lower = command.trim().toLowerCase(Locale.ROOT);
		if (lower.isBlank()) {
			return true;
		}
		if (lower.endsWith(".") || lower.endsWith("?")) {
			return true;
		}
		if (lower.startsWith("i ") || lower.startsWith("im ") || lower.startsWith("i'm")
			|| lower.startsWith("i am") || lower.startsWith("i will") || lower.startsWith("i'll")
			|| lower.startsWith("scanning") || lower.startsWith("searching") || lower.startsWith("looking")
			|| lower.startsWith("expanding") || lower.startsWith("checking")) {
			return true;
		}
		if (lower.contains(" for you") || lower.contains("please") || lower.contains("i am ")) {
			return true;
		}
		return false;
	}

	private static boolean isContainerSearchIntent(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (!containsAny(lower, "chest", "barrel", "shulker", "container", "containers", "storage", "crate", "hopper", "minecart")) {
			return false;
		}
		return containsAny(lower, "search", "scan", "scanning", "looking", "locate", "find", "finding", "nearest", "closest", "expand", "expanding");
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
		PENDING_VISION.remove(player.getUuid());
		VISION_STATE.remove(player.getUuid());
		failPendingMcpCapturesForPlayer(player.getUuid(), "Request cancelled.");
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

	private static void cleanupPendingMcpCaptures(long nowMs) {
		if (PENDING_MCP_CAPTURES.isEmpty()) {
			return;
		}
		for (var entry : PENDING_MCP_CAPTURES.entrySet()) {
			PendingMcpCapture pending = entry.getValue();
			if (pending == null) {
				continue;
			}
			if (nowMs - pending.createdMs() <= VISION_TIMEOUT_MS) {
				continue;
			}
			if (PENDING_MCP_CAPTURES.remove(entry.getKey(), pending)) {
				pending.future().complete(new McpVisionCaptureResult(false, "image/png", "", "", 0, "Vision capture timed out.", ""));
			}
		}
	}

	private static void failPendingMcpCapturesForPlayer(UUID playerId, String message) {
		if (playerId == null || PENDING_MCP_CAPTURES.isEmpty()) {
			return;
		}
		for (var entry : PENDING_MCP_CAPTURES.entrySet()) {
			PendingMcpCapture pending = entry.getValue();
			if (pending == null || !playerId.equals(pending.playerId())) {
				continue;
			}
			if (PENDING_MCP_CAPTURES.remove(entry.getKey(), pending)) {
				pending.future().complete(new McpVisionCaptureResult(false, "image/png", "", "", 0, message, ""));
			}
		}
	}

	private static void processPendingMcpCommandBatches(MinecraftServer server, long nowMs) {
		if (server == null || PENDING_MCP_COMMAND_BATCHES.isEmpty()) {
			return;
		}
		for (var entry : PENDING_MCP_COMMAND_BATCHES.entrySet()) {
			PendingMcpCommandBatch batch = entry.getValue();
			if (batch == null) {
				continue;
			}
			if (batch.isExpired(nowMs)) {
				PENDING_MCP_COMMAND_BATCHES.remove(entry.getKey(), batch);
				continue;
			}
			batch.process(server, nowMs);
		}
	}

	private static void cleanupPendingMcpBuildPreviews(long nowMs) {
		if (PENDING_MCP_BUILD_PREVIEWS.isEmpty()) {
			return;
		}
		for (var entry : PENDING_MCP_BUILD_PREVIEWS.entrySet()) {
			CachedMcpBuildPlan preview = entry.getValue();
			if (preview != null && preview.isExpired(nowMs)) {
				PENDING_MCP_BUILD_PREVIEWS.remove(entry.getKey(), preview);
			}
		}
	}

	static McpBatchStatusResult getMcpBatchStatus(ServerPlayerEntity player, String requestedBatchId) {
		if (player == null) {
			return new McpBatchStatusResult(false, "", false, true, 0, 0, 0, 0, List.of(), "No active player is available.", false, "No batch.");
		}
		String batchId = requestedBatchId == null || requestedBatchId.isBlank()
			? LAST_MCP_COMMAND_BATCH_BY_PLAYER.get(player.getUuid())
			: requestedBatchId.trim();
		if (batchId == null || batchId.isBlank()) {
			return new McpBatchStatusResult(false, "", false, true, 0, 0, 0, 0, List.of(), "No MCP command batch is available for this player.", false, "No batch.");
		}
		PendingMcpCommandBatch batch = PENDING_MCP_COMMAND_BATCHES.get(batchId);
		if (batch == null) {
			return new McpBatchStatusResult(false, batchId, false, true, 0, 0, 0, 0, List.of(), "Unknown or expired batch id.", false, "No batch.");
		}
		return batch.snapshot();
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

	private static int setMcpBridgeEnabled(ServerCommandSource source, boolean enabled) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only operators can manage MCP bridge settings."));
			return 0;
		}
		MCP_BRIDGE_ENABLED = enabled;
		if (enabled && (MCP_BRIDGE_TOKEN == null || MCP_BRIDGE_TOKEN.isBlank())) {
			MCP_BRIDGE_TOKEN = generateMcpBridgeToken();
		}
		saveGlobalSettings();
		if (ACTIVE_SERVER != null) {
			startOrRestartMcpBridge(ACTIVE_SERVER);
		}
		String message = enabled
			? "MCP bridge enabled on 127.0.0.1:" + MCP_BRIDGE_PORT + "."
			: "MCP bridge disabled. Health endpoint remains local-only on 127.0.0.1:" + MCP_BRIDGE_PORT + ".";
		source.sendFeedback(() -> Text.literal(message), false);
		if (enabled) {
			source.sendFeedback(() -> buildCopyableMcpTokenLine("MCP bridge token", MCP_BRIDGE_TOKEN), false);
		}
		return 1;
	}

	private static int regenerateMcpBridgeToken(ServerCommandSource source) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only operators can manage MCP bridge settings."));
			return 0;
		}
		MCP_BRIDGE_TOKEN = generateMcpBridgeToken();
		saveGlobalSettings();
		source.sendFeedback(() -> Text.literal("MCP bridge token regenerated."), false);
		source.sendFeedback(() -> buildCopyableMcpTokenLine("MCP bridge token", MCP_BRIDGE_TOKEN), false);
		return 1;
	}

	private static int showMcpBridgeStatus(ServerCommandSource source) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only operators can view MCP bridge status."));
			return 0;
		}
		MinecraftCapabilityService.ServiceResult<MinecraftCapabilityService.SessionInfo> session =
			MinecraftCapabilityService.session(source.getServer(), MCP_BRIDGE_ENABLED, MCP_BRIDGE_PORT);
		source.sendFeedback(() -> Text.literal("MCP bridge: " + (MCP_BRIDGE_ENABLED ? "ENABLED" : "DISABLED")), false);
		source.sendFeedback(() -> Text.literal("Bind: 127.0.0.1:" + MCP_BRIDGE_PORT + " (loopback only)"), false);
		source.sendFeedback(() -> Text.literal("Token: " + redactMcpBridgeToken(MCP_BRIDGE_TOKEN)), false);
		if (session.data() != null && session.data().activePlayer() != null) {
			var player = session.data().activePlayer();
			source.sendFeedback(() -> Text.literal("Active player: " + player.name() + " @ " + player.x() + "," + player.y() + "," + player.z()), false);
		} else if (session.error() != null) {
			source.sendFeedback(() -> Text.literal("Active player: " + session.error().code() + " - " + session.error().message()), false);
		} else {
			source.sendFeedback(() -> Text.literal("Active player: unavailable"), false);
		}
		return 1;
	}

	private static int showMcpBridgeSetupOptions(ServerCommandSource source) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only operators can view MCP setup instructions."));
			return 0;
		}
		source.sendFeedback(() -> Text.literal("MCP setup targets: codex, claude-desktop, claude-code, gemini-cli, opencode, generic"), false);
		source.sendFeedback(() -> Text.literal("Use /chat mcp setup <client> to get a ready-to-copy config block."), false);
		if (!MCP_BRIDGE_ENABLED) {
			source.sendFeedback(() -> Text.literal("Bridge is currently disabled. Run /chat mcp enable first."), false);
		}
		return 1;
	}

	private static int showMcpBridgeSetup(ServerCommandSource source, String client) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only operators can view MCP setup instructions."));
			return 0;
		}
		if (client == null || client.isBlank()) {
			return showMcpBridgeSetupOptions(source);
		}
		Path projectRoot = resolveMcpProjectRootPath();
		Path nodeScriptPath = projectRoot.resolve("run-mcp-sidecar-node.js").normalize();
		if (!Files.exists(nodeScriptPath)) {
			source.sendError(Text.literal("Could not find run-mcp-sidecar-node.js at " + nodeScriptPath));
			return 0;
		}
		String nodeCommand = resolveNodeCommand();
		McpSetupBundle bundle = buildMcpSetupBundle(client, nodeCommand, nodeScriptPath.toString(), projectRoot.toString());
		if (bundle == null) {
			source.sendError(Text.literal("Unknown MCP client '" + client + "'. Try: codex, claude-desktop, claude-code, gemini-cli, opencode, generic"));
			return 0;
		}
		source.sendFeedback(() -> Text.literal("MCP setup for " + bundle.label() + ":"), false);
		source.sendFeedback(() -> buildCopyableTextLine("Config block", bundle.label(), bundle.payload(), "Copy " + bundle.label() + " MCP setup"), false);
		for (String line : bundle.payload().split("\\R")) {
			source.sendFeedback(() -> Text.literal(line).formatted(Formatting.GRAY), false);
		}
		source.sendFeedback(() -> Text.literal("This config auto-reads the bridge token from the local project settings. Keep the MCP sidecar on the same machine as Minecraft."), false);
		if (!MCP_BRIDGE_ENABLED) {
			source.sendFeedback(() -> Text.literal("Bridge is currently disabled. Run /chat mcp enable first."), false);
		}
		return 1;
	}

	private static int showMcpBridgeToken(ServerCommandSource source) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only operators can view MCP bridge tokens."));
			return 0;
		}
		if (MCP_BRIDGE_TOKEN == null || MCP_BRIDGE_TOKEN.isBlank()) {
			MCP_BRIDGE_TOKEN = generateMcpBridgeToken();
			saveGlobalSettings();
		}
		source.sendFeedback(() -> buildCopyableMcpTokenLine("MCP bridge token", MCP_BRIDGE_TOKEN), false);
		return 1;
	}

	private static int copyMcpBridgeToken(ServerCommandSource source) {
		if (!source.hasPermissionLevel(2)) {
			source.sendError(Text.literal("Only operators can copy MCP bridge tokens."));
			return 0;
		}
		if (MCP_BRIDGE_TOKEN == null || MCP_BRIDGE_TOKEN.isBlank()) {
			MCP_BRIDGE_TOKEN = generateMcpBridgeToken();
			saveGlobalSettings();
		}
		source.sendFeedback(() -> buildCopyableMcpTokenLine("Click to copy MCP bridge token", MCP_BRIDGE_TOKEN), false);
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

	public record VoiceStatePayloadC2S(String state) implements CustomPayload {
		public static final Id<VoiceStatePayloadC2S> ID = new Id<>(VOICE_STATE_C2S);
		public static final PacketCodec<RegistryByteBuf, VoiceStatePayloadC2S> CODEC =
			PacketCodec.tuple(PacketCodecs.STRING, VoiceStatePayloadC2S::state, VoiceStatePayloadC2S::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record VisionRequestPayloadS2C(long requestId) implements CustomPayload {
		public static final Id<VisionRequestPayloadS2C> ID = new Id<>(VISION_REQUEST_S2C);
		public static final PacketCodec<RegistryByteBuf, VisionRequestPayloadS2C> CODEC =
			PacketCodec.tuple(PacketCodecs.VAR_LONG, VisionRequestPayloadS2C::requestId, VisionRequestPayloadS2C::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record VisionPayloadC2S(long requestId, String mimeType, String lookAt, byte[] data) implements CustomPayload {
		public static final Id<VisionPayloadC2S> ID = new Id<>(VISION_PACKET_C2S);
		public static final PacketCodec<RegistryByteBuf, VisionPayloadC2S> CODEC =
			PacketCodec.tuple(
				PacketCodecs.VAR_LONG, VisionPayloadC2S::requestId,
				PacketCodecs.STRING, VisionPayloadC2S::mimeType,
				PacketCodecs.STRING, VisionPayloadC2S::lookAt,
				PacketCodecs.BYTE_ARRAY, VisionPayloadC2S::data,
				VisionPayloadC2S::new
			);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

public record Highlight(double x, double y, double z, String label, int colorHex, long expiryMs) {}

	public record HighlightsPayloadS2C(List<Highlight> highlights) implements CustomPayload {
		public static final Identifier ID_VALUE = Identifier.of(MOD_ID, "highlights_s2c");
		public static final Id<HighlightsPayloadS2C> ID = new Id<>(ID_VALUE);
		public static final PacketCodec<RegistryByteBuf, HighlightsPayloadS2C> CODEC =
			PacketCodec.tuple(
				PacketCodecs.collection(ArrayList::new, PacketCodec.tuple(
					PacketCodecs.DOUBLE, Highlight::x,
					PacketCodecs.DOUBLE, Highlight::y,
					PacketCodecs.DOUBLE, Highlight::z,
					PacketCodecs.STRING, Highlight::label,
					PacketCodecs.INTEGER, Highlight::colorHex,
					PacketCodecs.VAR_LONG, Highlight::expiryMs,
					Highlight::new
				)),
				HighlightsPayloadS2C::highlights,
				HighlightsPayloadS2C::new
			);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record SettingsPayloadC2S(String json) implements CustomPayload {
		public static final Id<SettingsPayloadC2S> ID = new Id<>(SETTINGS_PACKET_C2S);
		public static final PacketCodec<RegistryByteBuf, SettingsPayloadC2S> CODEC =
			PacketCodec.tuple(
				PacketCodecs.STRING, SettingsPayloadC2S::json,
				SettingsPayloadC2S::new
			);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record SettingsApplyPayloadS2C(String key, String value) implements CustomPayload {
		public static final Id<SettingsApplyPayloadS2C> ID = new Id<>(SETTINGS_APPLY_S2C);
		public static final PacketCodec<RegistryByteBuf, SettingsApplyPayloadS2C> CODEC =
			PacketCodec.tuple(
				PacketCodecs.STRING, SettingsApplyPayloadS2C::key,
				PacketCodecs.STRING, SettingsApplyPayloadS2C::value,
				SettingsApplyPayloadS2C::new
			);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record ChatForwardPayloadC2S(String text) implements CustomPayload {
		public static final Id<ChatForwardPayloadC2S> ID = new Id<>(CHAT_FORWARD_C2S);
		public static final PacketCodec<RegistryByteBuf, ChatForwardPayloadC2S> CODEC =
			PacketCodec.tuple(PacketCodecs.STRING, ChatForwardPayloadC2S::text, ChatForwardPayloadC2S::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	private static int showChatHelp(ServerCommandSource source, String topic) {
		if (topic == null || topic.isBlank()) {
			source.sendFeedback(() -> Text.literal("Chat AI Help:"), false);
			source.sendFeedback(() -> Text.literal("/chat <text> - Ask the AI or issue tasks."), false);
			source.sendFeedback(() -> Text.literal("/chat vision [text] - Capture your view and ask with vision."), false);
			source.sendFeedback(() -> Text.literal("/chat clear | /chat new - Reset the chat context."), false);
			source.sendFeedback(() -> Text.literal("/chat cancel | /chat stop - Cancel the active request."), false);
			source.sendFeedback(() -> Text.literal("/chat undo - Undo the last AI command batch."), false);
			source.sendFeedback(() -> Text.literal("/chat history [count|all] [page] - Show recent exchanges."), false);
			source.sendFeedback(() -> Text.literal("/chat export [count|all] [txt|json] - Save chat log."), false);
			source.sendFeedback(() -> Text.literal("/chat config - Show settings menu."), false);
			source.sendFeedback(() -> Text.literal("/chat skill <inventory|nearby|blocks|containers|blockdata|players|stats|buildsite|lookup|nbt|recipe|smelt> - Run a skill."), false);
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
			source.sendFeedback(() -> Text.literal("/chat setup - Start server setup (OP only)."), false);
			source.sendFeedback(() -> Text.literal("/chat perms <ops|whitelist|all> - Set who can use AI (OP)."), false);
			source.sendFeedback(() -> Text.literal("/chat allow <player> | /chat deny <player> - Edit whitelist (OP)."), false);
			source.sendFeedback(() -> Text.literal("/chat mcp <enable|disable|status|setup <client>|token regen|token show|token copy> - Manage the local MCP bridge (OP)."), false);
			source.sendFeedback(() -> Text.literal("/chat status - Show setup status."), false);
			source.sendFeedback(() -> Text.literal("/chat setsetting <key> <value> - Request a client setting change."), false);
			source.sendFeedback(() -> Text.literal("/chathelp <command> - Show help for a command."), false);
			return 1;
		}

		String key = topic.toLowerCase(Locale.ROOT);
		switch (key) {
		case "chat":
			source.sendFeedback(() -> Text.literal("/chat <text> - Ask or command the AI."), false);
			source.sendFeedback(() -> Text.literal("Examples: /chat give me a diamond sword"), false);
			source.sendFeedback(() -> Text.literal("/chat vision [text] - Force a vision capture."), false);
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
			case "skill":
				source.sendFeedback(() -> Text.literal("/chat skill inventory - List inventory summary."), false);
				source.sendFeedback(() -> Text.literal("/chat skill nearby - List nearby entities."), false);
			source.sendFeedback(() -> Text.literal("/chat skill blocks <block|#tag> [radius] - Scan nearby blocks."), false);
			source.sendFeedback(() -> Text.literal("/chat skill containers [filter] [radius] - Scan nearby containers + contents."), false);
			source.sendFeedback(() -> Text.literal("/chat skill blockdata <x y z | nearest [block|#tag] [radius]> - Full block NBT."), false);
				source.sendFeedback(() -> Text.literal("/chat skill players - List online players with coords."), false);
				source.sendFeedback(() -> Text.literal("/chat skill settings [video|controls|all] - Read full options + keybinds."), false);
				source.sendFeedback(() -> Text.literal("/chat skill stats - Show health, armor, buffs, XP."), false);
				source.sendFeedback(() -> Text.literal("/chat skill buildsite [radius] - Summarize local terrain for structured builds."), false);
				source.sendFeedback(() -> Text.literal("/chat skill lookup <mainhand|offhand|slot N> - Item tooltip analysis."), false);
				source.sendFeedback(() -> Text.literal("/chat skill nbt <mainhand|offhand|slot N> - Inspect components."), false);
				source.sendFeedback(() -> Text.literal("/chat skill recipe <item> | /chat skill smelt <item> - Find recipes."), false);
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
			case "settings":
				source.sendFeedback(() -> Text.literal("Settings are read-only by default."), false);
				source.sendFeedback(() -> Text.literal("Changing settings requires /chat setsetting <key> <value> and confirmation."), false);
				break;
			case "setup":
				source.sendFeedback(() -> Text.literal("/chat setup - Run initial server setup (OP only)."), false);
				source.sendFeedback(() -> Text.literal("/chat perms <ops|whitelist|all> - Set who can use AI (OP)."), false);
				break;
			case "setsetting":
				source.sendFeedback(() -> Text.literal("/chat setsetting <key> <value> - Change a client setting with confirmation."), false);
				source.sendFeedback(() -> Text.literal("Uses options.txt; changes apply client-side after confirmation."), false);
				break;
			case "perms":
			case "allow":
			case "deny":
			case "mcp":
			case "status":
				source.sendFeedback(() -> Text.literal("/chat perms <ops|whitelist|all> - Permission mode (OP)."), false);
				source.sendFeedback(() -> Text.literal("/chat allow <player> | /chat deny <player> - Whitelist control."), false);
				source.sendFeedback(() -> Text.literal("/chat mcp <enable|disable|status|setup <client>|token regen|token show|token copy> - MCP bridge control."), false);
				source.sendFeedback(() -> Text.literal("Setup targets: codex, claude-desktop, claude-code, gemini-cli, opencode, generic"), false);
				source.sendFeedback(() -> Text.literal("/chat status - Show setup status."), false);
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

		reply = maybeRequestToolFromReply(player, apiKey, prompt, context, history, reply, modelChoice);
		if ("TOOL".equals(reply.mode) && player != null) {
			reply = handleToolMode(player, apiKey, prompt, context, history, reply, modelChoice);
		}

		reply = handleContinueLoop(player, apiKey, prompt, context, history, reply, modelChoice);
		reply = applyXrayOverride(reply, prompt);

		final boolean[] planAlreadySent = new boolean[] { false };
		if ("PLAN".equals(reply.mode) && player != null) {
			ModeMessage planSnapshot = reply;
			source.getServer().execute(() -> {
				if (state != null && state.cancelled.get()) {
					return;
				}
				MutableText prefix = buildModeChip("PLAN");
				source.sendFeedback(() -> prefix.append(Text.literal(planSnapshot.message)), false);
				MODE_STATE.put(player.getUuid(), new ModeState(modeLabel("PLAN"), modeColor("PLAN"), 60));
				AiStats stats = getStats(player.getUuid());
				stats.mode = "PLAN";
				stats.state = "PLANNING";
				updateSidebar(player, stats);
			});
			setStatus(player, "Iterating on the plan...", Formatting.AQUA);
			planAlreadySent[0] = true;
			String planInstruction =
				"Convert the plan above into COMMAND mode. Return mode COMMAND, message \"Initiating plan.\", and include commands, build_plan, or both. " +
				"Plan: " + reply.message;
			ModeMessage planCommands = callGeminiSafely(apiKey, prompt, context, history, planInstruction, modelChoice);
			if ("COMMAND".equals(planCommands.mode)) {
				reply = planCommands;
			}
		}

		if ("COMMAND".equals(reply.mode) && player != null) {
			reply = handleCommandMode(source, player, apiKey, prompt, context, history, reply, modelChoice);
		}
		reply = handleContinueLoop(player, apiKey, prompt, context, history, reply, modelChoice);
		reply = applyXrayOverride(reply, prompt);

		long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
		ModeMessage finalReply = reply;
		ModeMessage displayReply = finalReply;
		if (player != null && "COMMAND".equals(displayReply.mode)
			&& !hasActionPayload(displayReply, player)) {
			displayReply = new ModeMessage("ASK", displayReply.message, List.of(), displayReply.searchUsed, displayReply.sources, displayReply.highlights);
		}
		ModeMessage finalDisplayReply = displayReply;
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
				stats.mode = finalDisplayReply.mode;
				stats.lastResponseMs = elapsedMs;
				stats.recordResponseTime(elapsedMs);
				stats.state = finalDisplayReply.searchUsed ? "SEARCHING" : resolveState(finalDisplayReply.message);
				if (planAlreadySent[0] && "COMMAND".equals(finalDisplayReply.mode)) {
					stats.state = "EXECUTING";
				}
				stats.contextSize = history.size();
				stats.tokenPercent = estimateTokenPercent(contextForStats, history, prompt);
				if (!finalDisplayReply.message.startsWith("Error:")) {
					stats.sessionTokens += estimateTokens(finalDisplayReply.message);
				}
				updateSidebar(player, stats);
			}

			if (player != null) {
				sendHighlights(player, finalDisplayReply.highlights);
			}

			if (finalDisplayReply.message.startsWith("Error:")) {
				source.sendError(Text.literal(finalDisplayReply.message));
			} else {
				boolean skipMessage = planAlreadySent[0] && "PLAN".equals(finalDisplayReply.mode);
				if (!skipMessage) {
					MutableText message = Text.literal(finalDisplayReply.message);
					if (finalDisplayReply.mode.equals("ASK")) {
						MutableText prefix = buildModeChip("ASK");
						source.sendFeedback(() -> prefix.append(message), false);
					} else {
						MutableText prefix = buildModeChip(finalDisplayReply.mode);
						source.sendFeedback(() -> prefix.append(message), false);
						if (finalDisplayReply.mode.equals("COMMAND") && shouldShowCommandDebug(player)
							&& finalDisplayReply.commands != null && !finalDisplayReply.commands.isEmpty()) {
							List<String> filtered = filterSkillCommands(finalDisplayReply.commands);
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
				appendHistory(player, prompt, finalReply, elapsedMs);
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

	private static boolean hasActionPayload(ModeMessage message, ServerPlayerEntity player) {
		if (message == null) {
			return false;
		}
		if (isMeaningfulBuildPlan(message.buildPlan())) {
			return true;
		}
		return !filterExecutableCommands(message.commands, player).isEmpty();
	}

	private static boolean isMeaningfulBuildPlan(VoxelBuildPlanner.BuildPlan buildPlan) {
		if (buildPlan == null) {
			return false;
		}
		if ((buildPlan.clearVolumes() != null && !buildPlan.clearVolumes().isEmpty())
			|| (buildPlan.cuboids() != null && !buildPlan.cuboids().isEmpty())
			|| (buildPlan.blocks() != null && !buildPlan.blocks().isEmpty())) {
			return true;
		}
		if (buildPlan.steps() != null) {
			for (VoxelBuildPlanner.BuildStep step : buildPlan.steps()) {
				if (step != null && isMeaningfulBuildPlan(step.plan())) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isRequestStillActive(ServerPlayerEntity player, RequestState expectedState) {
		if (player == null) {
			return false;
		}
		RequestState live = REQUEST_STATES.get(player.getUuid());
		return expectedState != null && live == expectedState && !expectedState.cancelled.get();
	}

	private static String buildPreviewRetryContext(VoxelBuildPlanner.CompiledBuild compiledBuild, List<String> previewCommands, String prefix) {
		List<String> parts = new ArrayList<>();
		if (prefix != null && !prefix.isBlank()) {
			parts.add(prefix.trim());
		}
		if (compiledBuild != null) {
			if (compiledBuild.summary() != null && !compiledBuild.summary().isBlank()) {
				parts.add("Preview summary: " + compiledBuild.summary());
			}
			if (compiledBuild.resolvedOrigin() != null) {
				VoxelBuildPlanner.GridPoint origin = compiledBuild.resolvedOrigin();
				parts.add("Resolved origin: " + origin.x() + ", " + origin.y() + ", " + origin.z());
			}
			if (compiledBuild.appliedRotation() != 0) {
				parts.add("Applied rotation: " + compiledBuild.appliedRotation());
			}
			if (compiledBuild.phases() > 0) {
				parts.add("Phase count: " + compiledBuild.phases());
			}
			if (compiledBuild.autoFixAvailable()) {
				parts.add("Auto-fix is available for this preview.");
			}
			if (compiledBuild.issues() != null && !compiledBuild.issues().isEmpty()) {
				List<String> issueParts = new ArrayList<>();
				for (int i = 0; i < Math.min(4, compiledBuild.issues().size()); i++) {
					VoxelBuildPlanner.SupportIssue issue = compiledBuild.issues().get(i);
					issueParts.add(issue.cuboid() + ": " + issue.issue() + ", gap=" + issue.gapBelow() + ", suggestedY=" + issue.suggestedY());
				}
				parts.add("Preview issues: " + String.join(" | ", issueParts));
			}
			if (compiledBuild.repairs() != null && !compiledBuild.repairs().isEmpty()) {
				parts.add("Preview repairs: " + String.join(" | ", compiledBuild.repairs()));
			}
			if (compiledBuild.error() != null && !compiledBuild.error().isBlank()) {
				parts.add("Preview error: " + compiledBuild.error());
			}
		}
		if (previewCommands != null && !previewCommands.isEmpty()) {
			int previewCount = Math.min(6, previewCommands.size());
			parts.add("Validated preview commands: " + String.join(" | ", previewCommands.subList(0, previewCount)));
			if (previewCommands.size() > previewCount) {
				parts.add("Preview command count: " + previewCommands.size());
			}
		}
		return String.join(". ", parts);
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
		RequestState requestState = player == null ? null : REQUEST_STATES.get(player.getUuid());
		for (int attempt = 1; attempt <= retryLimit; attempt++) {
			if (!isRequestStillActive(player, requestState)) {
				return new ModeMessage("ASK", "", List.of(), false, List.of(), List.of());
			}
			setRetryStats(player, attempt - 1);
			List<String> executableCommands = new ArrayList<>();
			VoxelBuildPlanner.CompiledBuild compiledBuild = null;
			PreparedCommands prepared = null;
			if (isMeaningfulBuildPlan(current.buildPlan())) {
				compiledBuild = VoxelBuildPlanner.compile(player, current.buildPlan());
				if (!compiledBuild.valid()) {
					if (!isRequestStillActive(player, requestState)) {
						return new ModeMessage("ASK", "", List.of(), false, List.of(), List.of());
					}
					if (attempt == retryLimit) {
						LOGGER.info("AI build retry exhausted for player {}. Errors: {}", player.getName().getString(), compiledBuild.error());
						return new ModeMessage("COMMAND", "AI could not produce a valid structured build after several tries.", List.of(), false, List.of(), List.of());
					}
					LOGGER.info("AI build retry {}/{} for player {}. Errors: {}", attempt, retryLimit, player.getName().getString(), compiledBuild.error());
					setStatus(player, "AI encountered a build-plan error, retrying (" + attempt + "/" + retryLimit + ")...", Formatting.RED);
					String schemaHint =
						" Supported build_plan schema: " +
						"cuboids:[{block:\"oak_planks\",from:{x:0,y:0,z:0},to:{x:4,y:2,z:4}}] or " +
						"cuboids:[{block:\"oak_planks\",location:{x:0,y:0,z:0},size:{x:5,y:3,z:5},fill:\"hollow\"}] or " +
						"blocks:[{block:\"oak_door\",pos:{x:2,y:1,z:0},properties:{facing:\"south\"}}] or " +
						"steps:[{phase:\"foundation\",plan:{cuboids:[{block:\"stone_bricks\",from:{x:0,y:0,z:0},to:{x:4,y:0,z:4}}]}},{phase:\"walls\",plan:{cuboids:[{block:\"oak_planks\",start:{x:0,y:1,z:0},size:{x:5,y:3,z:5},hollow:true}]}}].";
					String errorContext = buildPreviewRetryContext(compiledBuild, List.of(), "Build preview failed.") + ". " + schemaHint;
					current = callGeminiSafely(apiKey, prompt, context, history, errorContext, modelChoice);
					if (!"COMMAND".equals(current.mode)) {
						return current;
					}
					continue;
				}
				executableCommands.addAll(compiledBuild.commands());
			}
			executableCommands.addAll(filterExecutableCommands(current.commands, player));
			if (executableCommands.isEmpty()) {
				return new ModeMessage("ASK", current.message, List.of(), current.searchUsed, current.sources, current.highlights);
			}
			prepared = prepareCommandsForExecution(player, executableCommands);
			CommandResult validation = validateCommands(player, prepared.executeCommands);
			if (!validation.success) {
				if (!isRequestStillActive(player, requestState)) {
					return new ModeMessage("ASK", "", List.of(), false, List.of(), List.of());
				}
				if (attempt == retryLimit) {
					LOGGER.info("AI retry exhausted for player {}. Validation errors: {}", player.getName().getString(), validation.errorSummary);
					return new ModeMessage("COMMAND", "AI could not produce valid commands after several tries.", List.of(), false, List.of(), List.of());
				}

				LOGGER.info("AI command retry {}/{} for player {}. Validation errors: {}", attempt, retryLimit, player.getName().getString(), validation.errorSummary);
				setStatus(player, "AI encountered an error, retrying (" + attempt + "/" + retryLimit + ")...", Formatting.RED);
				String errorContext = compiledBuild != null
					? buildPreviewRetryContext(compiledBuild, prepared.executeCommands, "Build preview validation failed. Command errors: " + validation.errorSummary)
					: "Command errors: " + validation.errorSummary;
				current = callGeminiSafely(apiKey, prompt, context, history, errorContext, modelChoice);
				if (!"COMMAND".equals(current.mode)) {
					return current;
				}
				continue;
			}
			if (!isRequestStillActive(player, requestState)) {
				return new ModeMessage("ASK", "", List.of(), false, List.of(), List.of());
			}

			CommandResult result = executeCommands(player, prepared.executeCommands);
			if (player != null) {
				AiStats stats = getStats(player.getUuid());
				stats.lastCommandOutput = limitOutput(filterUserOutputs(result.outputs));
				updateSidebar(player, stats);
			}
			if (result.success) {
				if (!prepared.undoCommands.isEmpty() || !prepared.undoSnapshots.isEmpty()) {
					LAST_UNDO_BATCHES.put(player.getUuid(), new UndoBatch(prepared.undoCommands, prepared.undoSnapshots));
				}
				if (compiledBuild != null && current.buildPlan() != null) {
					VoxelBuildPlanner.rememberAnchors(compiledBuild, current.buildPlan());
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
				String replyMessage = current.message;
				if ((replyMessage == null || replyMessage.isBlank() || "Executing commands.".equals(replyMessage))
					&& compiledBuild != null && compiledBuild.summary() != null && !compiledBuild.summary().isBlank()) {
					replyMessage = compiledBuild.summary();
				}
				return new ModeMessage(
					current.mode,
					replyMessage,
					executableCommands,
					current.searchUsed,
					current.sources,
					current.highlights,
					current.buildPlan()
				);
			}

			if (attempt == retryLimit) {
				LOGGER.info("AI retry exhausted for player {}. Last errors: {}", player.getName().getString(), result.errorSummary);
				return new ModeMessage("COMMAND", "AI could not complete the command after several tries.", List.of(), false, List.of(), List.of());
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

	private static ModeMessage handleToolMode(
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
		Set<String> seenToolBatches = new java.util.HashSet<>();
		while ("TOOL".equals(current.mode) && steps < MAX_TOOL_STEPS) {
			steps++;
			if (player == null) {
				return new ModeMessage("ASK", current.message, List.of(), current.searchUsed, current.sources, current.highlights);
			}
			if (current.commands == null || current.commands.isEmpty()) {
				return new ModeMessage("ASK", "No tool commands provided.", List.of(), current.searchUsed, current.sources, current.highlights);
			}
			List<String> normalizedCommands = new ArrayList<>();
			for (String raw : current.commands) {
				String normalized = normalizeToolCommand(raw);
				if (normalized != null && !normalized.isBlank()) {
					normalizedCommands.add(normalized);
				}
			}
			String batchSignature = String.join(" | ", normalizedCommands);
			if (!batchSignature.isBlank() && !seenToolBatches.add(batchSignature)) {
				String duplicateContext =
					"Tool commands already executed in this request: " + batchSignature + ". " +
					"Do not request the same tools again. Use the existing tool results and respond with ASK, PLAN, or COMMAND.";
				current = callGeminiSafely(apiKey, prompt, context, history, duplicateContext, modelChoice);
				if (!"TOOL".equals(current.mode)) {
					return current;
				}
				return new ModeMessage("ASK", "I already checked that. Try a more specific request.", List.of(), current.searchUsed, current.sources, current.highlights);
			}
			List<String> outputs = new ArrayList<>();
			for (String raw : current.commands) {
				String normalized = normalizeToolCommand(raw);
				if (normalized == null) {
					continue;
				}
				sendDebugMessage(player, "Tool cmd: /" + normalized);
				String out = stripSkillPrefix(executeSkillCommandOnServerThread(player, normalized, true));
				if (!out.isBlank()) {
					outputs.add(out);
				}
			}
			if (outputs.isEmpty()) {
				return new ModeMessage("ASK", "Tool produced no output.", List.of(), current.searchUsed, current.sources, current.highlights);
			}
			String toolContext = "Tool output: " + String.join(" | ", outputs) + "\nUse this to answer the user. Respond with ASK/PLAN/COMMAND.";
			current = callGeminiSafely(apiKey, prompt, context, history, toolContext, modelChoice);
		}
		if ("TOOL".equals(current.mode)) {
			return new ModeMessage("ASK", "I couldn't complete the tool workflow. Try a more specific request.", List.of(), current.searchUsed, current.sources, current.highlights);
		}
		return current;
	}

	private static ModeMessage maybeRequestToolFromReply(
		ServerPlayerEntity player,
		String apiKey,
		String prompt,
		String context,
		List<ChatTurn> history,
		ModeMessage reply,
		ModelChoice modelChoice
	) {
		if (reply == null || player == null) {
			return reply;
		}
		if ("TOOL".equals(reply.mode)) {
			return reply;
		}
		if (reply.commands != null && !reply.commands.isEmpty()) {
			return reply;
		}
		if (isDirectBuildRequest(prompt) && !wantsMaterialAcquisition(prompt) && !shouldAutoToolForBuildSite(reply.message)) {
			return reply;
		}
		if (!shouldRequestToolFollowup(reply.message)) {
			return reply;
		}
		String toolPrompt =
			"You said you would scan/check/locate something. " +
			"Provide TOOL commands only. " +
			"Return JSON with mode TOOL and commands array. " +
			"Do not answer the user yet.";
		ModeMessage toolReply = callGeminiSafely(apiKey, prompt, context, history, toolPrompt, modelChoice);
		if ("TOOL".equals(toolReply.mode) && toolReply.commands != null && !toolReply.commands.isEmpty()) {
			return toolReply;
		}
		return reply;
	}

	private static boolean shouldRequestToolFollowup(String message) {
		if (message == null || message.isBlank()) {
			return false;
		}
		String lower = message.toLowerCase(Locale.ROOT);
		if (!containsAny(lower, "scan", "scanning", "search", "searching", "check", "checking", "inspect", "inspecting",
			"look", "looking", "find", "finding", "locate", "locating", "nearest", "contents", "inside")) {
			return false;
		}
		return true;
	}

	private static boolean shouldAutoToolForBuildSite(String message) {
		if (message == null || message.isBlank()) {
			return false;
		}
		String lower = message.toLowerCase(Locale.ROOT);
		return containsAny(lower, "flat", "terrain", "area", "spot", "site", "build site", "buildsite", "ground", "nearby area");
	}

	private static boolean isDirectBuildRequest(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return false;
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		return containsAny(lower,
			"build ", "build me", "construct", "make a ", "make an ", "create a ", "create an ",
			"place ", "spawn ", "setblock", "fill ", "generate a ", "generate an ", "put a "
		);
	}

	private static boolean wantsMaterialAcquisition(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return false;
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		return containsAny(lower,
			"survival", "survival-friendly", "without cheats", "legit", "gather", "collect",
			"craftable", "use what i have", "using my inventory", "using nearby materials",
			"don't spawn", "don't cheat", "find the materials", "get the materials", "materials first"
		);
	}

	private static String normalizeToolCommand(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1).trim();
		}
		int cut = trimmed.indexOf('(');
		if (cut >= 0) {
			trimmed = trimmed.substring(0, cut).trim();
		}
		trimmed = trimmed.replaceAll("[\\]\\)\\.,;]+$", "").trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (lower.startsWith("chat skill")) {
			return trimmed;
		}
		if (lower.startsWith("skill ")) {
			return "chat " + trimmed;
		}
		return "chat skill " + trimmed;
	}

	private static ModeMessage handleContinueLoop(
		ServerPlayerEntity player,
		String apiKey,
		String prompt,
		String context,
		List<ChatTurn> history,
		ModeMessage reply,
		ModelChoice modelChoice
	) {
		if (reply == null) {
			return reply;
		}
		ModeMessage current = reply;
		if ("PLAN".equals(current.mode) && shouldAutoContinue(prompt, current.message)) {
			current = new ModeMessage("CONTINUE", current.message, List.of(), current.searchUsed, current.sources, List.of());
		}
		if ("COMMAND".equals(current.mode) && current.buildPlan() == null && (current.commands == null || current.commands.isEmpty())
			&& shouldAutoContinue(prompt, current.message)) {
			current = new ModeMessage("CONTINUE", current.message, List.of(), current.searchUsed, current.sources, List.of());
		}
		if (!"CONTINUE".equals(current.mode)) {
			return current;
		}
		int steps = 0;
		while ("CONTINUE".equals(current.mode) && steps < MAX_CONTINUE_STEPS) {
			steps++;
			if (player != null) {
				setStatus(player, "Iterating on the plan...", Formatting.AQUA);
			}
			String continueContext =
				"Previous step: " + current.message + "\n" +
				"Continue the task. If more steps are needed, respond with mode CONTINUE. " +
				"Otherwise respond with ASK, PLAN, or COMMAND.";
			current = callGeminiSafely(apiKey, prompt, context, history, continueContext, modelChoice);
		}
		if ("CONTINUE".equals(current.mode)) {
			return new ModeMessage(
				"ASK",
				"I couldn't complete the task after several steps. Try a more specific request.",
				List.of(),
				current.searchUsed,
				current.sources,
				List.of()
			);
		}
		return current;
	}

	private static boolean shouldAutoContinue(String prompt, String replyMessage) {
		String combined = ((prompt == null ? "" : prompt) + " " + (replyMessage == null ? "" : replyMessage))
			.toLowerCase(Locale.ROOT);
		if (combined.isBlank()) {
			return false;
		}
		if (containsAny(combined,
			"continue", "keep going", "step by step", "do it", "do it again", "and then",
			"first", "next", "after that", "then", "scan the area", "scan", "scanning",
			"search", "searching", "harvest", "collect", "craft", "gather", "find"
		)) {
			return true;
		}
		return false;
	}

	private static CommandResult executeCommands(ServerPlayerEntity player, List<String> commands) {
		if (commands == null || commands.isEmpty()) {
			return new CommandResult(true, "", List.of());
		}

		List<String> errors = new ArrayList<>();
		List<String> outputs = new ArrayList<>();
		int applied = 0;
		int failed = 0;
		int settingsChanged = 0;
		for (String command : commands) {
			if (command == null || command.isBlank()) {
				continue;
			}

			String sanitized = sanitizeCommand(command);
			if (isSettingChangeCommand(sanitized)) {
				boolean ok = applySettingCommand(player, sanitized);
				if (!ok) {
					errors.add("Command error: invalid setting change.");
					failed++;
				} else {
					applied++;
					settingsChanged++;
				}
				continue;
			}
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
				failed++;
				continue;
			}
			applied++;
			if (commandIndicatesAirReplace(sanitized, result.outputs)) {
				errors.add("Command error: target slot was empty.");
				failed++;
				continue;
			}
		}

		if (player != null && (applied > 0 || failed > 0)) {
			String summary = "Applied " + applied + " command" + (applied == 1 ? "" : "s");
			if (settingsChanged > 0) {
				summary += " (" + settingsChanged + " setting" + (settingsChanged == 1 ? "" : "s") + ")";
			}
			if (failed > 0) {
				summary += ", failed " + failed + ".";
				player.sendMessage(Text.literal(summary).formatted(Formatting.RED), false);
			} else {
				player.sendMessage(Text.literal(summary + ".").formatted(Formatting.GREEN), false);
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

	private static boolean isSettingChangeCommand(String command) {
		if (command == null) {
			return false;
		}
		return command.startsWith("chat setsetting");
	}

	private static boolean applySettingCommand(ServerPlayerEntity player, String command) {
		if (player == null) {
			return false;
		}
		String[] parts = command.split("\\s+", 3);
		if (parts.length < 3) {
			player.sendMessage(Text.literal("Usage: /chat setsetting <key> <value>"), false);
			return false;
		}
		String remainder = parts[2];
		String[] kv = remainder.split("\\s+", 2);
		if (kv.length < 2) {
			player.sendMessage(Text.literal("Usage: /chat setsetting <key> <value>"), false);
			return false;
		}
		return requestSettingChange(player.getCommandSource(), kv[0], kv[1]) > 0;
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
				var result = MinecraftCapabilityService.inventory(player);
				yield prefix + (result.error() != null ? "Inventory error: " + result.error().message() : MinecraftCapabilityService.formatInventory(result.data()));
			}
			case "nearby" -> {
				var result = MinecraftCapabilityService.nearbyEntities(player);
				yield prefix + (result.error() != null ? "Nearby entities error: " + result.error().message() : MinecraftCapabilityService.formatNearby(result.data()));
			}
			case "blocks" -> {
				String target = parts.length >= 4 ? parts[3] : "";
				int radius = parts.length >= 5 ? parseInteger(parts[4], DEFAULT_BLOCK_SCAN_RADIUS) : DEFAULT_BLOCK_SCAN_RADIUS;
				var result = MinecraftCapabilityService.scanBlocks(player, target, radius);
				yield prefix + (result.error() != null ? "Blocks error: " + result.error().message() : MinecraftCapabilityService.formatBlocks(result.data()));
			}
			case "containers" -> {
				String filter = null;
				int radius = DEFAULT_BLOCK_SCAN_RADIUS;
				if (parts.length >= 4) {
					if (isInteger(parts[3])) {
						radius = parseInteger(parts[3], DEFAULT_BLOCK_SCAN_RADIUS);
					} else {
						filter = parts[3];
						if (parts.length >= 5) {
							radius = parseInteger(parts[4], DEFAULT_BLOCK_SCAN_RADIUS);
						}
					}
				}
				var result = MinecraftCapabilityService.scanContainers(player, filter, radius);
				yield prefix + (result.error() != null ? "Containers error: " + result.error().message() : MinecraftCapabilityService.formatContainers(result.data()));
			}
			case "blockdata" -> {
				String args = parts.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 3, parts.length)) : "";
				var result = MinecraftCapabilityService.blockData(player, args);
				yield prefix + (result.error() != null ? "BlockData error: " + result.error().message() : MinecraftCapabilityService.formatBlockData(result.data()));
			}
			case "players" -> {
				var result = MinecraftCapabilityService.players(player.getServer());
				yield prefix + (result.error() != null ? "Players error: " + result.error().message() : MinecraftCapabilityService.formatPlayers(result.data()));
			}
			case "stats" -> {
				var result = MinecraftCapabilityService.stats(player);
				yield prefix + (result.error() != null ? "Stats error: " + result.error().message() : MinecraftCapabilityService.formatStats(result.data()));
			}
			case "buildsite" -> {
				int radius = parts.length >= 4 ? parseInteger(parts[3], VoxelBuildPlanner.DEFAULT_SITE_RADIUS) : VoxelBuildPlanner.DEFAULT_SITE_RADIUS;
				var result = MinecraftCapabilityService.buildsite(player, radius);
				yield prefix + (result.error() != null ? "BuildSite error: " + result.error().message() : MinecraftCapabilityService.formatBuildsite(result.data()));
			}
			case "settings" -> {
				String category = parts.length >= 4 ? parts[3].toLowerCase(Locale.ROOT) : "summary";
				yield prefix + buildSettingsSkillOutput(player, category);
			}
			case "recipe", "smelt" -> {
				String target = parts.length >= 4 ? parts[3].toLowerCase(Locale.ROOT) : "";
				var result = MinecraftCapabilityService.recipe(player, target, skill.equals("smelt"));
				yield prefix + (result.error() != null ? "Recipe error: " + result.error().message() : MinecraftCapabilityService.formatRecipe(result.data(), target));
			}
			case "nbt" -> {
				String target = parts.length >= 4 ? parts[3].toLowerCase(Locale.ROOT) : "mainhand";
				var result = MinecraftCapabilityService.itemComponents(player, target);
				yield prefix + (result.error() != null ? "NBT: empty" : MinecraftCapabilityService.formatItemComponents(result.data()));
			}
			case "lookup" -> {
				String target = parts.length >= 4 ? parts[3].toLowerCase(Locale.ROOT) : "mainhand";
				var result = MinecraftCapabilityService.itemLookup(player, target);
				yield prefix + (result.error() != null ? "Lookup: empty" : MinecraftCapabilityService.formatItemLookup(result.data()));
			}
			default -> prefix + "error: unknown skill";
		};
	}

	private static String executeSkillCommandOnServerThread(ServerPlayerEntity player, String command, boolean internal) {
		if (player == null) {
			return (internal ? "SKILL: " : "") + "error: no player";
		}
		try {
			var future = new java.util.concurrent.CompletableFuture<String>();
			player.getServer().execute(() -> future.complete(executeSkillCommand(player, command, internal)));
			return future.get(3, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			return executeSkillCommand(player, command, internal);
		}
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

	private static String buildPlayerListContext(ServerPlayerEntity viewer) {
		if (viewer == null) {
			return "";
		}
		List<ServerPlayerEntity> players = viewer.getServer().getPlayerManager().getPlayerList();
		if (players.isEmpty()) {
			return "";
		}
		List<String> entries = new ArrayList<>();
		for (ServerPlayerEntity player : players) {
			String name = player.getName().getString();
			BlockPos pos = player.getBlockPos();
			String dim = player.getWorld().getRegistryKey().getValue().toString();
			entries.add(name + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " (" + dim + ")");
			if (entries.size() >= 12) {
				break;
			}
		}
		if (players.size() > entries.size()) {
			entries.add("+" + (players.size() - entries.size()) + " more");
		}
		return String.join(", ", entries);
	}

	private static String buildSettingsSkillOutput(ServerPlayerEntity player, String category) {
		if (player == null) {
			return "Settings: unavailable";
		}
		SettingsSnapshot snapshot = SETTINGS_SNAPSHOTS.get(player.getUuid());
		if (snapshot == null) {
			return "Settings: unavailable (client not synced)";
		}
		String mode = category == null ? "summary" : category.toLowerCase(Locale.ROOT);
		switch (mode) {
			case "video", "graphics" -> {
				return "Settings video: " + formatSettingsMap(snapshot.video);
			}
			case "controls", "keys", "keybinds" -> {
				return "Settings controls: " + formatSettingsMap(snapshot.controls);
			}
			case "all", "full" -> {
				return "Settings video: " + formatSettingsMap(snapshot.video) + " | controls: " + formatSettingsMap(snapshot.controls);
			}
			default -> {
				return "Settings: video=" + formatSettingsMap(snapshot.video) + " | controls=" + formatSettingsMap(snapshot.controls);
			}
		}
	}

	private static String formatSettingsMap(Map<String, String> map) {
		if (map == null || map.isEmpty()) {
			return "none";
		}
		StringBuilder out = new StringBuilder();
		for (var entry : map.entrySet()) {
			if (!out.isEmpty()) {
				out.append(", ");
			}
			out.append(entry.getKey()).append("=").append(entry.getValue());
			if (out.length() > 900) {
				out.append("...");
				break;
			}
		}
		return out.toString();
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

	private static String formatLookupEntry(ServerPlayerEntity player, String label, ItemStack stack) {
		if (!isStackPresent(stack)) {
			return "Lookup: empty";
		}
		String id = Registries.ITEM.getId(stack.getItem()).toString();
		TooltipType type = (player != null && player.isCreative()) ? TooltipType.ADVANCED : TooltipType.BASIC;
		List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, player, type);
		List<String> lines = new ArrayList<>();
		for (Text line : tooltip) {
			String text = line.getString();
			if (text != null && !text.isBlank()) {
				lines.add(text.trim());
			}
			if (lines.size() >= 12) {
				break;
			}
		}
		if (tooltip.size() > lines.size()) {
			lines.add("...");
		}
		StringBuilder entry = new StringBuilder();
		entry.append("Lookup ").append(label).append(": ").append(id).append(" x").append(stack.getCount());
		if (!lines.isEmpty()) {
			entry.append(" | Tooltip: ").append(String.join(" / ", lines));
		}
		return entry.toString();
	}

	private static String buildBlockScanSkillOutput(ServerPlayerEntity player, String rawTarget, int radius) {
		if (player == null) {
			return "Blocks: unavailable";
		}
		if (rawTarget == null || rawTarget.isBlank()) {
			return "Blocks error: missing target";
		}
		BlockTarget target = resolveBlockTarget(rawTarget);
		if (target == null) {
			return "Blocks error: unknown block " + rawTarget;
		}
		BlockScanResult result = scanForBlocks(player, target, radius, MAX_BLOCK_SCAN_POSITIONS, MAX_BLOCK_SCAN_RESULTS);
		String label = target.label();
		if (result.totalMatches() == 0) {
			return "Blocks " + label + " within " + result.radius() + " blocks: none";
		}
		List<BlockMatch> matches = new ArrayList<>(result.matches());
		matches.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		List<String> entries = new ArrayList<>();
		for (BlockMatch match : matches) {
			BlockPos pos = match.pos();
			String blockId = Registries.BLOCK.getId(match.state().getBlock()).toString();
			String stateInfo = formatBlockState(match.state());
			String entry = blockId + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
				+ " (" + formatDistance(match.distance()) + ")";
			if (!stateInfo.isBlank()) {
				entry += " {" + stateInfo + "}";
			}
			BlockEntity entity = player.getServerWorld().getBlockEntity(pos);
			if (entity != null) {
				String typeId = Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).toString();
				entry += " be=" + typeId;
			}
			entries.add(entry);
		}
		StringBuilder out = new StringBuilder();
		out.append("Blocks ").append(label).append(" within ").append(result.radius()).append(" blocks");
		if (result.clamped()) {
			out.append(" (clamped)");
		}
		out.append(": total ").append(result.totalMatches()).append(", showing ").append(matches.size()).append(": ");
		out.append(String.join("; ", entries));
		return out.toString();
	}

	private static String buildContainerSkillOutput(ServerPlayerEntity player, String filter, int radius) {
		if (player == null) {
			return "Containers: unavailable";
		}
		BlockTarget target = null;
		if (filter != null && !filter.isBlank()) {
			target = resolveBlockTarget(filter);
			if (target == null) {
				return "Containers error: unknown block " + filter;
			}
		}
		ContainerScanResult result = scanForContainers(player, target, radius, MAX_BLOCK_SCAN_POSITIONS, MAX_CONTAINER_RESULTS);
		if (result.totalMatches() == 0) {
			return "Containers within " + result.radius() + " blocks: none";
		}
		List<ContainerMatch> matches = new ArrayList<>(result.matches());
		matches.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		List<String> entries = new ArrayList<>();
		for (ContainerMatch match : matches) {
			BlockPos pos = match.pos();
			String blockId = Registries.BLOCK.getId(match.state().getBlock()).toString();
			String entry = blockId + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
				+ " (" + formatDistance(match.distance()) + ")";
			if (match.summary() != null && !match.summary().isBlank()) {
				entry += " items: " + match.summary();
			}
			entries.add(entry);
		}
		StringBuilder out = new StringBuilder();
		out.append("Containers within ").append(result.radius()).append(" blocks");
		if (result.clamped()) {
			out.append(" (clamped)");
		}
		out.append(": total ").append(result.totalMatches()).append(", showing ").append(matches.size()).append(": ");
		out.append(String.join("; ", entries));
		return out.toString();
	}

	private static String buildBlockDataSkillOutput(ServerPlayerEntity player, String args) {
		if (player == null) {
			return "BlockData: unavailable";
		}
		String trimmed = args == null ? "" : args.trim();
		if (trimmed.isBlank()) {
			return "BlockData error: missing target (use x y z or nearest)";
		}
		String[] parts = trimmed.split("\\s+");
		if (parts.length >= 3 && isInteger(parts[0]) && isInteger(parts[1]) && isInteger(parts[2])) {
			BlockPos pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			return formatBlockEntityData(player, pos);
		}

		boolean nearest = false;
		String filter = null;
		int radius = DEFAULT_BLOCK_SCAN_RADIUS;
		for (String raw : parts) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String token = raw.toLowerCase(Locale.ROOT).replace(",", "");
			if ("nearest".equals(token) || "closest".equals(token)) {
				nearest = true;
				continue;
			}
			if (isInteger(token) && radius == DEFAULT_BLOCK_SCAN_RADIUS) {
				radius = parseInteger(token, radius);
				continue;
			}
			if (filter == null) {
				filter = raw;
			}
		}
		if (!nearest && filter == null && parts.length >= 1) {
			filter = parts[0];
		}

		BlockTarget target = null;
		if (filter != null && !filter.isBlank()) {
			target = resolveBlockTarget(filter);
			if (target == null) {
				return "BlockData error: unknown block " + filter;
			}
		}

		ContainerMatch nearestMatch = findNearestContainer(player, target, radius, MAX_BLOCK_SCAN_POSITIONS);
		if (nearestMatch == null) {
			String label = target == null ? "containers" : target.label();
			return "BlockData: no " + label + " within " + Math.max(1, radius) + " blocks";
		}
		return formatBlockEntityData(player, nearestMatch.pos());
	}

	private static ContainerMatch findNearestContainer(ServerPlayerEntity player, BlockTarget target, int requestedRadius, int maxPositions) {
		if (player == null) {
			return null;
		}
		ServerWorld world = player.getServerWorld();
		BlockPos center = player.getBlockPos();
		int radius = Math.max(1, requestedRadius);
		int maxRadius = maxScanRadius(maxPositions);
		if (radius > maxRadius) {
			radius = maxRadius;
		}

		ContainerMatch nearest = null;
		BlockPos min = center.add(-radius, -radius, -radius);
		BlockPos max = center.add(radius, radius, radius);
		for (BlockPos pos : BlockPos.iterate(min, max)) {
			BlockEntity entity = world.getBlockEntity(pos);
			if (!(entity instanceof Inventory inventory)) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (target != null && !target.matches(state)) {
				continue;
			}
			double distance = Math.sqrt(player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
			String summary = summarizeInventory(inventory);
			if (nearest == null || distance < nearest.distance()) {
				nearest = new ContainerMatch(pos.toImmutable(), state, distance, summary);
			}
		}
		return nearest;
	}

	private static String formatBlockEntityData(ServerPlayerEntity player, BlockPos pos) {
		ServerWorld world = player.getServerWorld();
		BlockEntity entity = world.getBlockEntity(pos);
		if (entity == null) {
			return "BlockData: no block entity at " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
		}
		String id = Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).toString();
		NbtCompound nbt = new NbtCompound();
		try {
			nbt = entity.createNbt(player.getRegistryManager());
		} catch (Throwable ignored) {
			// fallback below
		}
		String nbtText = nbt.asString();
		nbtText = truncate(nbtText, 900);
		String summary = "";
		if (entity instanceof Inventory inventory) {
			summary = summarizeInventory(inventory);
		}
		String entry = "BlockData " + id + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ": " + nbtText;
		if (!summary.isBlank()) {
			entry += " | items: " + summary;
		}
		return entry;
	}

	private static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		if (text.length() <= max) {
			return text;
		}
		return text.substring(0, Math.max(0, max - 3)) + "...";
	}

	private static BlockScanResult scanForBlocks(ServerPlayerEntity player, BlockTarget target, int requestedRadius, int maxPositions, int maxResults) {
		ServerWorld world = player.getServerWorld();
		BlockPos center = player.getBlockPos();
		int radius = Math.max(1, requestedRadius);
		int maxRadius = maxScanRadius(maxPositions);
		boolean clamped = false;
		if (radius > maxRadius) {
			radius = maxRadius;
			clamped = true;
		}

		List<BlockMatch> matches = new ArrayList<>();
		int total = 0;
		BlockPos min = center.add(-radius, -radius, -radius);
		BlockPos max = center.add(radius, radius, radius);
		for (BlockPos pos : BlockPos.iterate(min, max)) {
			BlockState state = world.getBlockState(pos);
			if (!target.matches(state)) {
				continue;
			}
			total++;
			double distance = Math.sqrt(player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
			if (matches.size() < maxResults) {
				matches.add(new BlockMatch(pos.toImmutable(), state, distance));
			} else {
				int farthestIndex = -1;
				double farthestDistance = -1.0;
				for (int i = 0; i < matches.size(); i++) {
					double d = matches.get(i).distance();
					if (d > farthestDistance) {
						farthestDistance = d;
						farthestIndex = i;
					}
				}
				if (distance < farthestDistance && farthestIndex >= 0) {
					matches.set(farthestIndex, new BlockMatch(pos.toImmutable(), state, distance));
				}
			}
		}
		return new BlockScanResult(matches, total, radius, clamped);
	}

	private static ContainerScanResult scanForContainers(ServerPlayerEntity player, BlockTarget target, int requestedRadius, int maxPositions, int maxResults) {
		ServerWorld world = player.getServerWorld();
		BlockPos center = player.getBlockPos();
		int radius = Math.max(1, requestedRadius);
		int maxRadius = maxScanRadius(maxPositions);
		boolean clamped = false;
		if (radius > maxRadius) {
			radius = maxRadius;
			clamped = true;
		}

		List<ContainerMatch> matches = new ArrayList<>();
		int total = 0;
		BlockPos min = center.add(-radius, -radius, -radius);
		BlockPos max = center.add(radius, radius, radius);
		for (BlockPos pos : BlockPos.iterate(min, max)) {
			BlockState state = world.getBlockState(pos);
			if (target != null && !target.matches(state)) {
				continue;
			}
			BlockEntity entity = world.getBlockEntity(pos);
			if (!(entity instanceof Inventory inventory)) {
				continue;
			}
			total++;
			double distance = Math.sqrt(player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
			String summary = summarizeInventory(inventory);
			if (matches.size() < maxResults) {
				matches.add(new ContainerMatch(pos.toImmutable(), state, distance, summary));
			} else {
				int farthestIndex = -1;
				double farthestDistance = -1.0;
				for (int i = 0; i < matches.size(); i++) {
					double d = matches.get(i).distance();
					if (d > farthestDistance) {
						farthestDistance = d;
						farthestIndex = i;
					}
				}
				if (distance < farthestDistance && farthestIndex >= 0) {
					matches.set(farthestIndex, new ContainerMatch(pos.toImmutable(), state, distance, summary));
				}
			}
		}
		return new ContainerScanResult(matches, total, radius, clamped);
	}

	private static int maxScanRadius(int maxPositions) {
		double root = Math.cbrt(Math.max(1, maxPositions));
		int radius = (int) Math.floor((root - 1.0) / 2.0);
		return Math.max(1, radius);
	}

	private static String summarizeInventory(Inventory inventory) {
		if (inventory == null) {
			return "empty";
		}
		Map<String, Integer> totals = new HashMap<>();
		int filled = 0;
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			filled++;
			String id = Registries.ITEM.getId(stack.getItem()).toString();
			totals.merge(id, stack.getCount(), Integer::sum);
		}
		if (totals.isEmpty()) {
			return "empty";
		}
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < sorted.size() && i < 3; i++) {
			var entry = sorted.get(i);
			parts.add(entry.getKey() + " x" + entry.getValue());
		}
		String summary = String.join(", ", parts);
		if (totals.size() > parts.size()) {
			summary += " (+" + (totals.size() - parts.size()) + " more types)";
		}
		return summary + " (" + filled + " stacks)";
	}

	private static String formatBlockState(BlockState state) {
		if (state == null || state.getEntries().isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (var entry : state.getEntries().entrySet()) {
			if (!out.isEmpty()) {
				out.append(", ");
			}
			out.append(entry.getKey().getName()).append("=").append(entry.getValue());
			if (out.length() > 120) {
				out.append("...");
				break;
			}
		}
		return out.toString();
	}

	private static String formatDistance(double distance) {
		return String.format(Locale.ROOT, "%.1f", distance);
	}

	private static BlockTarget resolveBlockTarget(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String token = raw.trim().toLowerCase(Locale.ROOT);
		if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
			token = token.substring(1, token.length() - 1);
		}
		boolean isTag = token.startsWith("#");
		String idText = isTag ? token.substring(1) : token;
		String resolved = null;
		if (!idText.contains(":")) {
			resolved = "minecraft:" + idText;
		} else {
			resolved = idText;
		}
		Identifier id = Identifier.tryParse(resolved);
		if (id != null && isTag) {
			TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, id);
			return new BlockTarget("#" + id, null, tag, true);
		}
		if (id != null && Registries.BLOCK.containsId(id)) {
			return new BlockTarget(id.toString(), Registries.BLOCK.get(id), null, false);
		}

		String guess = tryResolveBlockId(token, idText);
		if (guess != null) {
			Identifier guessId = Identifier.tryParse(guess);
			if (guessId != null && Registries.BLOCK.containsId(guessId)) {
				String label = guess.equals(token) ? guess : guess + " (auto from " + token + ")";
				return new BlockTarget(label, Registries.BLOCK.get(guessId), null, false);
			}
		}

		List<String> suggestions = findRegistryMatches(Registries.BLOCK.getIds(), tokenizePrompt(token), 1);
		if (!suggestions.isEmpty()) {
			String suggestion = suggestions.get(0);
			Identifier suggestionId = Identifier.tryParse(suggestion);
			if (suggestionId != null && Registries.BLOCK.containsId(suggestionId)) {
				return new BlockTarget(suggestion + " (auto from " + token + ")", Registries.BLOCK.get(suggestionId), null, false);
			}
		}
		return null;
	}

	private static String tryResolveBlockId(String token, String idText) {
		String normalized = idText;
		if (normalized.contains(" ")) {
			normalized = normalized.replace(' ', '_');
		}
		String candidate = normalized.contains(":") ? normalized : "minecraft:" + normalized;
		if (Identifier.tryParse(candidate) != null && Registries.BLOCK.containsId(Identifier.tryParse(candidate))) {
			return candidate;
		}
		if (normalized.endsWith("s")) {
			String singular = normalized.substring(0, normalized.length() - 1);
			String singularCandidate = singular.contains(":") ? singular : "minecraft:" + singular;
			if (Identifier.tryParse(singularCandidate) != null && Registries.BLOCK.containsId(Identifier.tryParse(singularCandidate))) {
				return singularCandidate;
			}
		}
		if (token.contains("chest")) {
			return "minecraft:chest";
		}
		if (token.contains("barrel")) {
			return "minecraft:barrel";
		}
		if (token.contains("shulker")) {
			return "minecraft:shulker_box";
		}
		return null;
	}

	private static boolean isInteger(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		try {
			Integer.parseInt(value);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static int parseInteger(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
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
		UndoBatch batch = LAST_UNDO_BATCHES.get(player.getUuid());
		if (batch == null || (batch.commands().isEmpty() && batch.snapshots().isEmpty())) {
			source.sendFeedback(() -> Text.literal("Nothing to undo."), false);
			return 1;
		}
		CommandResult result = batch.commands().isEmpty()
			? new CommandResult(true, "", List.of())
			: executeCommands(player, batch.commands());
		RestoreResult restore = batch.snapshots().isEmpty()
			? new RestoreResult(true, 0, "")
			: restoreSnapshots(player.getServer(), batch.snapshots());
		LAST_UNDO_BATCHES.remove(player.getUuid());
		if (result.success && restore.success()) {
			String message = restore.restoredBlocks() > 0
				? "Undo complete. Restored " + restore.restoredBlocks() + " block" + (restore.restoredBlocks() == 1 ? "" : "s") + "."
				: "Undo complete.";
			source.sendFeedback(() -> Text.literal(message), false);
			return 1;
		}
		List<String> issues = new ArrayList<>();
		if (!result.success && result.errorSummary != null && !result.errorSummary.isBlank()) {
			issues.add(result.errorSummary);
		}
		if (!restore.success() && restore.errorSummary() != null && !restore.errorSummary().isBlank()) {
			issues.add(restore.errorSummary());
		}
		source.sendFeedback(() -> Text.literal("Undo completed with issues: " + String.join(" | ", issues)), false);
		return 1;
	}

	static boolean isMcpBridgeEnabled() {
		return MCP_BRIDGE_ENABLED;
	}

	static long getLastServerTickMs() {
		return LAST_SERVER_TICK_MS;
	}

	static String getMcpBridgeToken() {
		return MCP_BRIDGE_TOKEN == null ? "" : MCP_BRIDGE_TOKEN;
	}

	static CompletableFuture<McpVisionCaptureResult> requestMcpCaptureView(ServerPlayerEntity player) {
		if (player == null) {
			return CompletableFuture.completedFuture(new McpVisionCaptureResult(false, "image/png", "", "", 0, "No active player is available.", ""));
		}
		failPendingMcpCapturesForPlayer(player.getUuid(), "Superseded by a newer vision capture request.");
		long requestId = MCP_CAPTURE_REQUEST_IDS.getAndIncrement();
		CompletableFuture<McpVisionCaptureResult> future = new CompletableFuture<>();
		PENDING_MCP_CAPTURES.put(requestId, new PendingMcpCapture(player.getUuid(), requestId, System.currentTimeMillis(), future));
		ServerPlayNetworking.send(player, new VisionRequestPayloadS2C(requestId));
		return future;
	}

	static McpActionResult executeMcpCommands(ServerPlayerEntity player, List<McpCommandSpec> commands) {
		if (player == null) {
			return new McpActionResult(false, 0, List.of(), List.of(), "No active player is available.", false, "No commands executed.", 0, 0);
		}
		List<McpCommandSpec> normalizedSpecs = normalizeMcpCommandSpecs(commands);
		if (normalizedSpecs.isEmpty()) {
			return new McpActionResult(false, 0, List.of(), List.of(), "No commands provided.", false, "No commands executed.", 0, 0);
		}
		List<String> normalizedCommands = normalizedSpecs.stream().map(McpCommandSpec::command).toList();
		PreparedCommands prepared = prepareCommandsForExecution(player, normalizedCommands);
		CommandResult validation = validateCommands(player, prepared.executeCommands);
		if (!validation.success) {
			return new McpActionResult(false, 0, List.of(), List.of(), validation.errorSummary, false, "No commands executed.", 0, 0);
		}
		boolean hasDelays = normalizedSpecs.stream().anyMatch(spec -> spec.delayTicks() > 0);
		if (hasDelays && prepared.executeCommands.stream().anyMatch(command -> isSkillCommand(command) || isSettingChangeCommand(command))) {
			return new McpActionResult(false, 0, List.of(), List.of(), "Delayed MCP command batches only support real Minecraft commands, not chat skills or setting changes.", false, "No commands executed.", 0, 0);
		}
		if (hasDelays) {
			return scheduleMcpCommandBatch(player, normalizedSpecs, prepared);
		}
		CommandResult execution = executeCommands(player, prepared.executeCommands);
		if (execution.success) {
			LAST_UNDO_BATCHES.put(player.getUuid(), new UndoBatch(prepared.undoCommands, prepared.undoSnapshots));
		}
		return new McpActionResult(
			execution.success,
			execution.success ? prepared.executeCommands.size() : 0,
			List.of(),
			filterUserOutputs(execution.outputs),
			execution.success ? "" : execution.errorSummary,
			execution.success && (!prepared.undoCommands.isEmpty() || !prepared.undoSnapshots.isEmpty()),
			execution.success ? "Executed " + prepared.executeCommands.size() + " command(s)." : "No commands executed.",
			0,
			0,
			List.of(),
			false,
			"",
			"",
			new VoxelBuildPlanner.GridPoint(0, 0, 0),
			List.of(),
			false
		);
	}

	private static List<McpCommandSpec> normalizeMcpCommandSpecs(List<McpCommandSpec> commands) {
		if (commands == null || commands.isEmpty()) {
			return List.of();
		}
		List<McpCommandSpec> normalized = new ArrayList<>();
		for (McpCommandSpec spec : commands) {
			if (spec == null || spec.command() == null || spec.command().isBlank()) {
				continue;
			}
			int delayTicks = Math.max(0, Math.min(MAX_MCP_DELAY_TICKS, spec.delayTicks()));
			String command = sanitizeCommand(spec.command());
			if (command.isBlank()) {
				continue;
			}
			normalized.add(new McpCommandSpec(command, delayTicks));
			if (normalized.size() >= MAX_MCP_DELAYED_COMMANDS) {
				break;
			}
		}
		return normalized;
	}

	private static McpActionResult scheduleMcpCommandBatch(ServerPlayerEntity player, List<McpCommandSpec> specs, PreparedCommands prepared) {
		String batchId = "mcp-batch-" + MCP_COMMAND_BATCH_IDS.getAndIncrement();
		List<ScheduledMcpCommand> scheduled = new ArrayList<>();
		for (int i = 0; i < prepared.executeCommands.size(); i++) {
			int delayTicks = i < specs.size() ? specs.get(i).delayTicks() : 0;
			scheduled.add(new ScheduledMcpCommand(prepared.executeCommands.get(i), delayTicks));
		}
		PendingMcpCommandBatch batch = new PendingMcpCommandBatch(
			batchId,
			player.getUuid(),
			System.currentTimeMillis(),
			scheduled,
			prepared.undoCommands,
			prepared.undoSnapshots
		);
		PENDING_MCP_COMMAND_BATCHES.put(batchId, batch);
		LAST_MCP_COMMAND_BATCH_BY_PLAYER.put(player.getUuid(), batchId);
		player.sendMessage(Text.literal("Scheduled delayed MCP batch " + batchId + " with " + prepared.executeCommands.size() + " command(s).").formatted(Formatting.AQUA), false);
		return new McpActionResult(
			true,
			prepared.executeCommands.size(),
			List.of(),
			List.of(),
			"",
			false,
			"Scheduled " + prepared.executeCommands.size() + " command(s) as delayed batch " + batchId + ".",
			0,
			0,
			prepared.executeCommands,
			true,
			batchId,
			"",
			new VoxelBuildPlanner.GridPoint(0, 0, 0),
			List.of(),
			false
		);
	}

	static McpActionResult executeMcpBuildPlan(ServerPlayerEntity player, JsonObject requestBody) {
		if (player == null) {
			return new McpActionResult(false, 0, List.of(), List.of(), "No active player is available.", false, "No build executed.", 0, 0);
		}
		JsonObject root = requestBody;
		if (root == null) {
			root = new JsonObject();
		}
		String executePlanId = root.has("executePlanId") && root.get("executePlanId").isJsonPrimitive() ? root.get("executePlanId").getAsString() : null;
		if (executePlanId == null || executePlanId.isBlank()) {
			executePlanId = root.has("planId") && root.get("planId").isJsonPrimitive() ? root.get("planId").getAsString() : null;
		}
		if (executePlanId != null && !executePlanId.isBlank()) {
			CachedMcpBuildPlan cached = PENDING_MCP_BUILD_PREVIEWS.get(executePlanId.trim());
			if (cached == null || !cached.playerId().equals(player.getUuid()) || cached.isExpired(System.currentTimeMillis())) {
				return new McpActionResult(false, 0, List.of(), List.of(), "Unknown or expired planId.", false, "No build executed.", 0, 0);
			}
			PreparedCommands prepared = prepareCommandsForExecution(player, cached.executeCommands());
			CommandResult validation = validateCommands(player, prepared.executeCommands);
			if (!validation.success) {
				return new McpActionResult(false, 0, cached.repairs(), List.of(), validation.errorSummary, false, cached.summary(), cached.appliedRotation(), cached.phaseCount(), List.of(), false, "", cached.planId(), cached.resolvedOrigin(), cached.issues(), cached.autoFixAvailable());
			}
			CommandResult execution = executeCommands(player, prepared.executeCommands);
			if (execution.success) {
				LAST_UNDO_BATCHES.put(player.getUuid(), new UndoBatch(prepared.undoCommands, prepared.undoSnapshots));
				VoxelBuildPlanner.rememberAnchors(new VoxelBuildPlanner.CompiledBuild(
					true,
					cached.executeCommands(),
					cached.summary(),
					cached.repairs(),
					"",
					cached.appliedRotation(),
					cached.phaseCount(),
					cached.resolvedOrigin(),
					cached.issues(),
					cached.autoFixAvailable()
				), cached.plan());
			}
			return new McpActionResult(
				execution.success,
				execution.success ? prepared.executeCommands.size() : 0,
				cached.repairs(),
				filterUserOutputs(execution.outputs),
				execution.success ? "" : execution.errorSummary,
				execution.success && (!prepared.undoCommands.isEmpty() || !prepared.undoSnapshots.isEmpty()),
				cached.summary(),
				cached.appliedRotation(),
				cached.phaseCount(),
				List.of(),
				false,
				"",
				cached.planId(),
				cached.resolvedOrigin(),
				cached.issues(),
				cached.autoFixAvailable()
			);
		}
		if (!root.has("build_plan")) {
			JsonObject wrapped = new JsonObject();
			wrapped.add("build_plan", root.deepCopy());
			root = wrapped;
		}
		VoxelBuildPlanner.BuildPlan plan = VoxelBuildPlanner.parseBuildPlan(root);
		VoxelBuildPlanner.CompiledBuild compiled = VoxelBuildPlanner.compile(player, plan);
		if (!compiled.valid()) {
			return new McpActionResult(false, 0, compiled.repairs(), List.of(), compiled.error(), false, compiled.summary(), compiled.appliedRotation(), compiled.phases(), List.of(), false, "", "", compiled.resolvedOrigin(), compiled.issues(), compiled.autoFixAvailable());
		}
		PreparedCommands prepared = prepareCommandsForExecution(player, compiled.commands());
		CommandResult validation = validateCommands(player, prepared.executeCommands);
		if (!validation.success) {
			return new McpActionResult(false, 0, compiled.repairs(), List.of(), validation.errorSummary, false, compiled.summary(), compiled.appliedRotation(), compiled.phases(), List.of(), false, "", "", compiled.resolvedOrigin(), compiled.issues(), compiled.autoFixAvailable());
		}
		CommandResult execution = executeCommands(player, prepared.executeCommands);
		if (execution.success) {
			LAST_UNDO_BATCHES.put(player.getUuid(), new UndoBatch(prepared.undoCommands, prepared.undoSnapshots));
			VoxelBuildPlanner.rememberAnchors(compiled, plan);
		}
		return new McpActionResult(
			execution.success,
			execution.success ? prepared.executeCommands.size() : 0,
			compiled.repairs(),
			filterUserOutputs(execution.outputs),
			execution.success ? "" : execution.errorSummary,
			execution.success && (!prepared.undoCommands.isEmpty() || !prepared.undoSnapshots.isEmpty()),
			compiled.summary(),
			compiled.appliedRotation(),
			compiled.phases(),
			List.of(),
			false,
			"",
			"",
			compiled.resolvedOrigin(),
			compiled.issues(),
			compiled.autoFixAvailable()
		);
	}

	static McpActionResult previewMcpBuildPlan(ServerPlayerEntity player, JsonObject requestBody) {
		if (player == null) {
			return new McpActionResult(false, 0, List.of(), List.of(), "No active player is available.", false, "No preview generated.", 0, 0);
		}
		JsonObject root = requestBody;
		if (root == null) {
			root = new JsonObject();
		}
		if (!root.has("build_plan")) {
			JsonObject wrapped = new JsonObject();
			wrapped.add("build_plan", root.deepCopy());
			root = wrapped;
		}
		VoxelBuildPlanner.BuildPlan plan = VoxelBuildPlanner.parseBuildPlan(root);
		VoxelBuildPlanner.CompiledBuild compiled = VoxelBuildPlanner.compile(player, plan);
		if (!compiled.valid()) {
			return new McpActionResult(false, 0, compiled.repairs(), List.of(), compiled.error(), false, compiled.summary(), compiled.appliedRotation(), compiled.phases(), List.of(), false, "", "", compiled.resolvedOrigin(), compiled.issues(), compiled.autoFixAvailable());
		}
		PreparedCommands prepared = prepareCommandsForExecution(player, compiled.commands());
		CommandResult validation = validateCommands(player, prepared.executeCommands);
		if (!validation.success) {
			return new McpActionResult(false, 0, compiled.repairs(), List.of(), validation.errorSummary, false, compiled.summary(), compiled.appliedRotation(), compiled.phases(), prepared.executeCommands, false, "", "", compiled.resolvedOrigin(), compiled.issues(), compiled.autoFixAvailable());
		}
		String planId = "plan-" + Long.toUnsignedString(MCP_BUILD_PLAN_IDS.getAndIncrement(), 36);
		PENDING_MCP_BUILD_PREVIEWS.put(planId, new CachedMcpBuildPlan(
			planId,
			player.getUuid(),
			System.currentTimeMillis(),
			plan,
			prepared.executeCommands,
			compiled.summary(),
			compiled.appliedRotation(),
			compiled.phases(),
			compiled.repairs(),
			compiled.resolvedOrigin(),
			compiled.issues(),
			compiled.autoFixAvailable()
		));
		return new McpActionResult(
			true,
			prepared.executeCommands.size(),
			compiled.repairs(),
			List.of(),
			"",
			false,
			"Preview ready. " + prepared.executeCommands.size() + " command(s) would execute.",
			compiled.appliedRotation(),
			compiled.phases(),
			prepared.executeCommands,
			false,
			"",
			planId,
			compiled.resolvedOrigin(),
			compiled.issues(),
			compiled.autoFixAvailable()
		);
	}

	static McpActionResult executeMcpUndo(ServerPlayerEntity player) {
		if (player == null) {
			return new McpActionResult(false, 0, List.of(), List.of(), "No active player is available.", false, "Nothing to undo.", 0, 0);
		}
		UndoBatch batch = LAST_UNDO_BATCHES.get(player.getUuid());
		if (batch == null || (batch.commands().isEmpty() && batch.snapshots().isEmpty())) {
			return new McpActionResult(false, 0, List.of(), List.of(), "Nothing to undo.", false, "Nothing to undo.", 0, 0);
		}
		CommandResult result = batch.commands().isEmpty()
			? new CommandResult(true, "", List.of())
			: executeCommands(player, batch.commands());
		RestoreResult restore = batch.snapshots().isEmpty()
			? new RestoreResult(true, 0, "")
			: restoreSnapshots(player.getServer(), batch.snapshots());
		LAST_UNDO_BATCHES.remove(player.getUuid());
		boolean success = result.success && restore.success();
		String summary = restore.restoredBlocks() > 0
			? "Undo complete. Restored " + restore.restoredBlocks() + " block" + (restore.restoredBlocks() == 1 ? "" : "s") + "."
			: "Undo complete.";
		String error = "";
		if (!success) {
			List<String> issues = new ArrayList<>();
			if (!result.success && result.errorSummary != null && !result.errorSummary.isBlank()) {
				issues.add(result.errorSummary);
			}
			if (!restore.success() && restore.errorSummary() != null && !restore.errorSummary().isBlank()) {
				issues.add(restore.errorSummary());
			}
			error = String.join(" | ", issues);
		}
		return new McpActionResult(success, restore.restoredBlocks(), List.of(), filterUserOutputs(result.outputs), error, false, summary, 0, 0);
	}

	static McpActionResult executeMcpHighlights(ServerPlayerEntity player, List<Highlight> highlights) {
		if (player == null) {
			return new McpActionResult(false, 0, List.of(), List.of(), "No active player is available.", false, "No highlights emitted.", 0, 0);
		}
		if (highlights == null || highlights.isEmpty()) {
			return new McpActionResult(false, 0, List.of(), List.of(), "No highlights were provided.", false, "No highlights emitted.", 0, 0);
		}
		sendHighlights(player, highlights);
		return new McpActionResult(true, highlights.size(), List.of(), List.of(), "", false, "Sent " + highlights.size() + " highlight(s).", 0, 0);
	}

	private static synchronized void startOrRestartMcpBridge(MinecraftServer server) {
		if (server == null) {
			return;
		}
		stopMcpBridge();
		try {
			MCP_BRIDGE = new McpBridgeServer(server, MCP_BRIDGE_PORT);
			MCP_BRIDGE.start();
			LOGGER.info("MCP bridge listening on 127.0.0.1:{} (enabled={})", MCP_BRIDGE_PORT, MCP_BRIDGE_ENABLED);
		} catch (Exception e) {
			MCP_BRIDGE = null;
			LOGGER.warn("Failed to start MCP bridge on port {}: {}", MCP_BRIDGE_PORT, e.getMessage());
		}
	}

	private static synchronized void stopMcpBridge() {
		if (MCP_BRIDGE != null) {
			MCP_BRIDGE.stop();
			MCP_BRIDGE = null;
		}
	}

	private static String generateMcpBridgeToken() {
		byte[] bytes = new byte[24];
		MCP_TOKEN_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String redactMcpBridgeToken(String token) {
		if (token == null || token.isBlank()) {
			return "(missing)";
		}
		if (token.length() <= 10) {
			return "********";
		}
		return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
	}

	private static Path resolveMcpProjectRootPath() {
		List<Path> candidates = new ArrayList<>();
		Path cwd = Path.of("").toAbsolutePath().normalize();
		candidates.add(cwd);
		Path current = cwd;
		for (int i = 0; i < 6; i++) {
			Path parent = current.getParent();
			if (parent == null || parent.equals(current)) {
				break;
			}
			candidates.add(parent);
			current = parent;
		}
		try {
			URI codeUri = GeminiCompanion.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			Path codePath = Path.of(codeUri).toAbsolutePath().normalize();
			if (Files.isRegularFile(codePath)) {
				codePath = codePath.getParent();
			}
			if (codePath != null) {
				candidates.add(codePath);
				current = codePath;
				for (int i = 0; i < 6; i++) {
					Path parent = current.getParent();
					if (parent == null || parent.equals(current)) {
						break;
					}
					candidates.add(parent);
					current = parent;
				}
			}
		} catch (Exception ignored) {
		}
		for (Path candidate : candidates) {
			if (candidate != null && Files.exists(candidate.resolve("run-mcp-sidecar-node.js"))) {
				return candidate;
			}
		}
		return cwd;
	}

	private static String resolveNodeCommand() {
		Path pinnedWindowsNode = Path.of("C:\\Program Files\\nodejs\\node.exe");
		if (Files.exists(pinnedWindowsNode)) {
			return pinnedWindowsNode.toString();
		}
		return "node";
	}

	private static McpSetupBundle buildMcpSetupBundle(String rawClient, String nodeCommand, String nodeScriptPath, String projectRoot) {
		String client = rawClient.toLowerCase(Locale.ROOT).trim();
		String normalized = client.replace("_", "-");
		String tomlNode = quoteToml(nodeCommand);
		String tomlScript = quoteToml(nodeScriptPath);
		String tomlRoot = quoteToml(projectRoot);
		String jsonNode = quoteJson(nodeCommand);
		String jsonScript = quoteJson(nodeScriptPath);
		String jsonRoot = quoteJson(projectRoot);
		String cliNode = quoteCli(nodeCommand);
		String cliScript = quoteCli(nodeScriptPath);
		String cliRoot = quoteCli(projectRoot);
		return switch (normalized) {
			case "codex", "codex-cli" -> new McpSetupBundle("Codex CLI", String.join("\n",
				"[mcp_servers.gemini-minecraft]",
				"command = " + tomlNode,
				"args = [",
				"  " + tomlScript + ",",
				"  \"--project-root\",",
				"  " + tomlRoot,
				"]",
				"startup_timeout_sec = 60"));
			case "claude-desktop", "claude", "generic", "json" -> new McpSetupBundle("Claude Desktop / Generic JSON", String.join("\n",
				"{",
				"  \"mcpServers\": {",
				"    \"gemini-minecraft\": {",
				"      \"command\": " + jsonNode + ",",
				"      \"args\": [",
				"        " + jsonScript + ",",
				"        \"--project-root\",",
				"        " + jsonRoot,
				"      ]",
				"    }",
				"  }",
				"}"));
			case "claude-code" -> new McpSetupBundle("Claude Code", "claude mcp add gemini-minecraft -- " + cliNode + " " + cliScript + " --project-root " + cliRoot);
			case "gemini-cli", "gemini" -> new McpSetupBundle("Gemini CLI", "gemini mcp add gemini-minecraft " + cliNode + " --trust -- " + cliScript + " --project-root " + cliRoot);
			case "opencode" -> new McpSetupBundle("OpenCode", String.join("\n",
				"{",
				"  \"mcp\": {",
				"    \"gemini-minecraft\": {",
				"      \"type\": \"local\",",
				"      \"enabled\": true,",
				"      \"command\": [",
				"        " + jsonNode + ",",
				"        " + jsonScript + ",",
				"        \"--project-root\",",
				"        " + jsonRoot,
				"      ]",
				"    }",
				"  }",
				"}"));
			default -> null;
		};
	}

	private static String quoteToml(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String quoteJson(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String quoteCli(String value) {
		return "\"" + value.replace("\"", "\\\"") + "\"";
	}

	private static Text buildCopyableMcpTokenLine(String label, String token) {
		return buildCopyableTextLine(label, redactMcpBridgeToken(token), token, "Copy MCP bridge token")
			.styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(token))));
	}

	private static MutableText buildCopyableTextLine(String label, String visibleValue, String clipboardValue, String hoverText) {
		MutableText line = Text.literal(label + ": ").formatted(Formatting.GRAY);
		MutableText value = Text.literal(visibleValue).formatted(Formatting.WHITE);
		MutableText copy = Text.literal("[Copy]").formatted(Formatting.AQUA)
			.styled(style -> style
				.withUnderline(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, clipboardValue))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hoverText))));
		return line.append(value).append(Text.literal(" ")).append(copy);
	}

	private record McpSetupBundle(String label, String payload) {}

	private static PreparedCommands prepareCommandsForExecution(ServerPlayerEntity player, List<String> commands) {
		List<String> execute = new ArrayList<>();
		List<String> undo = new ArrayList<>();
		if (commands == null) {
			return new PreparedCommands(execute, undo, List.of());
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
		List<BlockUndoSnapshot> snapshots = captureUndoSnapshots(player, execute);
		return new PreparedCommands(execute, undo, snapshots);
	}

	private static List<BlockUndoSnapshot> captureUndoSnapshots(ServerPlayerEntity player, List<String> commands) {
		if (player == null || commands == null || commands.isEmpty()) {
			return List.of();
		}
		ServerWorld world = player.getServerWorld();
		LinkedHashMap<BlockPos, BlockUndoSnapshot> snapshots = new LinkedHashMap<>();
		for (String raw : commands) {
			String command = sanitizeCommand(raw);
			if (command.isBlank()) {
				continue;
			}
			AffectedBlocks affected = collectAffectedBlocks(player, command, MAX_UNDO_SNAPSHOT_BLOCKS - snapshots.size());
			if (affected == null) {
				continue;
			}
			if (affected.overflow()) {
				LOGGER.info("Skipping block snapshot undo for oversized command: {}", command);
				return List.of();
			}
			for (BlockPos pos : affected.positions()) {
				if (snapshots.containsKey(pos)) {
					continue;
				}
				BlockState state = world.getBlockState(pos);
				BlockEntity entity = world.getBlockEntity(pos);
				NbtCompound nbt = entity == null ? null : entity.createNbt(player.getRegistryManager()).copy();
				snapshots.put(pos.toImmutable(), new BlockUndoSnapshot(world.getRegistryKey(), pos.toImmutable(), state, nbt));
			}
		}
		return snapshots.isEmpty() ? List.of() : new ArrayList<>(snapshots.values());
	}

	private static AffectedBlocks collectAffectedBlocks(ServerPlayerEntity player, String command, int remainingBudget) {
		if (player == null || command == null || command.isBlank() || remainingBudget <= 0) {
			return null;
		}
		String[] parts = command.split("\\s+");
		if (parts.length < 4) {
			return null;
		}
		String root = parts[0].toLowerCase(Locale.ROOT);
		if ("setblock".equals(root)) {
			BlockPos pos = parseCommandBlockPos(player, parts, 1);
			if (pos == null) {
				return null;
			}
			return new AffectedBlocks(List.of(pos), false);
		}
		if (!"fill".equals(root) || parts.length < 7) {
			return null;
		}
		BlockPos from = parseCommandBlockPos(player, parts, 1);
		BlockPos to = parseCommandBlockPos(player, parts, 4);
		if (from == null || to == null) {
			return null;
		}
		int minX = Math.min(from.getX(), to.getX());
		int minY = Math.min(from.getY(), to.getY());
		int minZ = Math.min(from.getZ(), to.getZ());
		int maxX = Math.max(from.getX(), to.getX());
		int maxY = Math.max(from.getY(), to.getY());
		int maxZ = Math.max(from.getZ(), to.getZ());
		long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		if (volume > remainingBudget) {
			return new AffectedBlocks(List.of(), true);
		}
		String mode = parts.length >= 9 ? parts[8].toLowerCase(Locale.ROOT) : "";
		List<BlockPos> positions = new ArrayList<>((int) volume);
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
					if ("outline".equals(mode) && !boundary) {
						continue;
					}
					positions.add(new BlockPos(x, y, z));
				}
			}
		}
		return new AffectedBlocks(positions, false);
	}

	private static BlockPos parseCommandBlockPos(ServerPlayerEntity player, String[] parts, int startIndex) {
		if (player == null || parts == null || startIndex < 0 || parts.length <= startIndex + 2) {
			return null;
		}
		BlockPos base = player.getBlockPos();
		Integer x = parseCoordinateToken(parts[startIndex], base.getX());
		Integer y = parseCoordinateToken(parts[startIndex + 1], base.getY());
		Integer z = parseCoordinateToken(parts[startIndex + 2], base.getZ());
		if (x == null || y == null || z == null) {
			return null;
		}
		return new BlockPos(x, y, z);
	}

	private static Integer parseCoordinateToken(String token, int base) {
		if (token == null || token.isBlank()) {
			return null;
		}
		String trimmed = token.trim();
		if (trimmed.startsWith("^")) {
			return null;
		}
		try {
			if (trimmed.startsWith("~")) {
				if (trimmed.length() == 1) {
					return base;
				}
				double offset = Double.parseDouble(trimmed.substring(1));
				return base + (int) Math.floor(offset);
			}
			return (int) Math.floor(Double.parseDouble(trimmed));
		} catch (Exception e) {
			return null;
		}
	}

	private static RestoreResult restoreSnapshots(MinecraftServer server, List<BlockUndoSnapshot> snapshots) {
		if (server == null || snapshots == null || snapshots.isEmpty()) {
			return new RestoreResult(true, 0, "");
		}
		int restored = 0;
		List<String> errors = new ArrayList<>();
		for (BlockUndoSnapshot snapshot : snapshots) {
			ServerWorld world = server.getWorld(snapshot.worldKey());
			if (world == null) {
				errors.add("Missing world for block snapshot at " + snapshot.pos().toShortString());
				continue;
			}
			try {
				world.setBlockState(snapshot.pos(), snapshot.state(), 3);
				if (snapshot.blockEntityNbt() != null) {
					BlockEntity entity = world.getBlockEntity(snapshot.pos());
					if (entity != null) {
						entity.read(snapshot.blockEntityNbt().copy(), server.getRegistryManager());
						entity.markDirty();
						world.getChunkManager().markForUpdate(snapshot.pos());
					}
				}
				restored++;
			} catch (Exception e) {
				errors.add("Failed to restore " + snapshot.pos().toShortString() + ": " + e.getMessage());
			}
		}
		return new RestoreResult(errors.isEmpty(), restored, String.join(" | ", errors));
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
		if (server == null) {
			return new CommandResult(false, "Minecraft server is unavailable.", List.of());
		}
		if (server.isOnThread()) {
			return executeCommandNow(server, player, command);
		}
		var future = new java.util.concurrent.CompletableFuture<CommandResult>();
		server.execute(() -> future.complete(executeCommandNow(server, player, command)));

		try {
			return future.get();
		} catch (Exception e) {
			String msg = e.getMessage() == null ? ("Command failed: " + command) : e.getMessage();
			return new CommandResult(false, msg, List.of());
		}
	}

	private static CommandResult executeCommandNow(MinecraftServer server, ServerPlayerEntity player, String command) {
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
			return new CommandResult(false, msg, messages);
		}

		String errorMessage = findErrorMessage(messages);
		if (errorMessage != null) {
			return new CommandResult(false, "Command error: " + errorMessage, messages);
		}
		return new CommandResult(true, "", messages);
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
			if ("options".equals(first)) {
				errors.add("Forbidden command: options. Use /chat setsetting <key> <value> instead.");
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
		trimmed = decodeUnicodeEscapes(trimmed);
		trimmed = normalizeEffectGiveDuration(trimmed);
		return trimmed;
	}

	private static String decodeUnicodeEscapes(String value) {
		if (value == null || !value.contains("\\u")) {
			return value == null ? "" : value;
		}
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (ch == '\\' && i + 5 < value.length() && value.charAt(i + 1) == 'u') {
				String hex = value.substring(i + 2, i + 6);
				try {
					out.append((char) Integer.parseInt(hex, 16));
					i += 5;
					continue;
				} catch (NumberFormatException ignored) {
					// fall through
				}
			}
			out.append(ch);
		}
		return out.toString();
	}

	private static String normalizeEffectGiveDuration(String command) {
		if (command == null || command.isBlank()) {
			return command == null ? "" : command;
		}
		String[] parts = command.split("\\s+");
		if (parts.length < 5 || !"effect".equals(parts[0]) || !"give".equals(parts[1])) {
			return command;
		}
		try {
			int duration = Integer.parseInt(parts[4]);
			if (duration >= 1) {
				return command;
			}
			parts[4] = "1";
			return String.join(" ", parts);
		} catch (NumberFormatException ignored) {
			return command;
		}
	}

	private static void applyGeminiResponseSchema(JsonObject generationConfig) {
		if (generationConfig == null) {
			return;
		}
		generationConfig.addProperty("responseMimeType", "application/json");
		generationConfig.add("responseJsonSchema", buildModelResponseJsonSchema());
	}

	private static JsonObject buildModelResponseJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", false);

		JsonObject properties = new JsonObject();
		properties.add("mode", enumSchema("string", "ASK", "PLAN", "COMMAND", "CONTINUE", "TOOL"));
		properties.add("message", primitiveSchema("string"));
		properties.add("commands", arraySchema(primitiveSchema("string")));
		properties.add("highlights", arraySchema(buildHighlightJsonSchema()));
		properties.add("build_plan", buildPlanJsonSchema());
		schema.add("properties", properties);

		JsonArray required = new JsonArray();
		required.add("mode");
		required.add("message");
		required.add("commands");
		schema.add("required", required);
		addPropertyOrdering(schema, "mode", "message", "commands", "build_plan", "highlights");
		return schema;
	}

	private static JsonObject buildHighlightJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", false);
		JsonObject properties = new JsonObject();
		properties.add("x", primitiveSchema("number"));
		properties.add("y", primitiveSchema("number"));
		properties.add("z", primitiveSchema("number"));
		properties.add("label", primitiveSchema("string"));
		properties.add("color", primitiveSchema("string"));
		properties.add("durationMs", primitiveSchema("integer"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("x");
		required.add("y");
		required.add("z");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject buildPlanJsonSchema() {
		return buildPlanJsonSchema(true);
	}

	private static JsonObject buildPlanJsonSchema(boolean includeSteps) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);

		JsonObject properties = new JsonObject();
		properties.add("label", primitiveSchema("string"));
		properties.add("version", primitiveSchema("integer"));
		properties.add("summary", primitiveSchema("string"));
		properties.add("description", primitiveSchema("string"));
		properties.add("anchor", primitiveSchema("string"));
		properties.add("anchors", buildStringToPointMapJsonSchema());
		properties.add("coordMode", enumSchema("string", "player", "absolute", "anchor"));
		properties.add("origin", buildPointJsonSchema());
		properties.add("offset", buildPointJsonSchema());
		properties.add("autoFix", primitiveSchema("boolean"));
		properties.add("snapToGround", primitiveSchema("boolean"));
		properties.add("flattenTerrain", primitiveSchema("boolean"));
		properties.add("clearVegetation", primitiveSchema("boolean"));
		properties.add("rotate", buildRotateJsonSchema());
		properties.add("rotation", buildRotateJsonSchema());
		properties.add("options", buildPlanOptionsJsonSchema());
		properties.add("palette", buildPaletteJsonSchema());
		JsonArray clearAnyOf = new JsonArray();
		clearAnyOf.add(arraySchema(buildVolumeJsonSchema()));
		clearAnyOf.add(buildVolumeJsonSchema());
		properties.add("clear", anyOfSchema(clearAnyOf));
		properties.add("cuboids", arraySchema(buildCuboidJsonSchema()));
		properties.add("blocks", arraySchema(buildBlockPlacementJsonSchema()));
		if (includeSteps) {
			properties.add("steps", arraySchema(buildBuildStepJsonSchema()));
		}
		schema.add("properties", properties);
		addPropertyOrdering(schema, "label", "version", "summary", "description", "anchor", "anchors", "coordMode", "origin", "offset", "autoFix", "snapToGround", "flattenTerrain", "clearVegetation", "rotate", "rotation", "options", "palette", "clear", "cuboids", "blocks", "steps");
		return schema;
	}

	private static JsonObject buildBuildStepJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);
		JsonObject properties = new JsonObject();
		properties.add("type", primitiveSchema("string"));
		properties.add("phase", primitiveSchema("string"));
		properties.add("name", primitiveSchema("string"));
		properties.add("label", primitiveSchema("string"));
		properties.add("step", primitiveSchema("string"));
		properties.add("plan", buildPlanJsonSchema(false));
		schema.add("properties", properties);
		addPropertyOrdering(schema, "type", "phase", "plan", "name", "label", "step");
		return schema;
	}

	private static JsonObject buildStringToPointMapJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("additionalProperties", buildPointJsonSchema());
		return schema;
	}

	private static JsonObject buildPlanOptionsJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);
		JsonObject properties = new JsonObject();
		properties.add("phaseReorder", primitiveSchema("boolean"));
		properties.add("dryRun", primitiveSchema("boolean"));
		properties.add("batchUndo", primitiveSchema("boolean"));
		properties.add("rotateBlockStates", primitiveSchema("boolean"));
		properties.add("rotation", buildRotateJsonSchema());
		schema.add("properties", properties);
		addPropertyOrdering(schema, "phaseReorder", "dryRun", "batchUndo", "rotateBlockStates", "rotation");
		return schema;
	}

	private static JsonObject buildPaletteJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject blockDescriptor = new JsonObject();
		blockDescriptor.addProperty("type", "object");
		blockDescriptor.addProperty("additionalProperties", true);
		JsonObject descProps = new JsonObject();
		descProps.add("block", primitiveSchema("string"));
		descProps.add("material", primitiveSchema("string"));
		descProps.add("id", primitiveSchema("string"));
		blockDescriptor.add("properties", descProps);

		JsonArray anyOf = new JsonArray();
		anyOf.add(primitiveSchema("string"));
		anyOf.add(blockDescriptor);
		schema.add("additionalProperties", anyOfSchema(anyOf));
		return schema;
	}

	private static JsonObject buildVolumeJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);
		JsonObject properties = new JsonObject();
		properties.add("name", primitiveSchema("string"));
		properties.add("label", primitiveSchema("string"));
		properties.add("from", buildPointJsonSchema());
		properties.add("to", buildPointJsonSchema());
		properties.add("start", buildPointJsonSchema());
		properties.add("end", buildPointJsonSchema());
		properties.add("location", buildLocationJsonSchema());
		properties.add("size", buildSizeJsonSchema());
		properties.add("dimensions", buildSizeJsonSchema());
		properties.add("width", primitiveSchema("integer"));
		properties.add("height", primitiveSchema("integer"));
		properties.add("depth", primitiveSchema("integer"));
		properties.add("length", primitiveSchema("integer"));
		properties.add("enabled", primitiveSchema("boolean"));
		properties.add("dx", primitiveSchema("integer"));
		properties.add("dy", primitiveSchema("integer"));
		properties.add("dz", primitiveSchema("integer"));
		properties.add("offset", buildPointJsonSchema());
		properties.add("replaceWith", primitiveSchema("string"));
		schema.add("properties", properties);
		addPropertyOrdering(schema, "name", "label", "from", "to", "start", "end", "location", "size", "dimensions", "width", "height", "depth", "length");
		return schema;
	}

	private static JsonObject buildCuboidJsonSchema() {
		JsonObject schema = buildVolumeJsonSchema();
		JsonObject properties = schema.getAsJsonObject("properties");
		properties.add("block", primitiveSchema("string"));
		properties.add("material", primitiveSchema("string"));
		properties.add("id", primitiveSchema("string"));
		properties.add("fill", primitiveSchema("string"));
		properties.add("mode", primitiveSchema("string"));
		properties.add("hollow", primitiveSchema("boolean"));
		properties.add("properties", buildStringMapJsonSchema());
		properties.add("state", buildStringMapJsonSchema());
		addPropertyOrdering(schema, "name", "label", "block", "material", "id", "from", "to", "start", "end", "location", "size", "dimensions", "width", "height", "depth", "length", "fill", "mode", "hollow", "properties", "state");
		return schema;
	}

	private static JsonObject buildBlockPlacementJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);
		JsonObject properties = new JsonObject();
		properties.add("name", primitiveSchema("string"));
		properties.add("label", primitiveSchema("string"));
		properties.add("block", primitiveSchema("string"));
		properties.add("material", primitiveSchema("string"));
		properties.add("id", primitiveSchema("string"));
		properties.add("pos", buildPointJsonSchema());
			properties.add("location", buildPointJsonSchema());
			properties.add("properties", buildStringMapJsonSchema());
			properties.add("state", buildStringMapJsonSchema());
			schema.add("properties", properties);
		addPropertyOrdering(schema, "name", "label", "block", "material", "id", "pos", "location", "properties", "state");
		return schema;
	}

	private static JsonObject buildLocationJsonSchema() {
		JsonObject schema = buildPointJsonSchema();
		JsonObject properties = schema.getAsJsonObject("properties");
		properties.add("start_x", primitiveSchema("integer"));
		properties.add("start_y", primitiveSchema("integer"));
		properties.add("start_z", primitiveSchema("integer"));
		properties.add("end_x", primitiveSchema("integer"));
		properties.add("end_y", primitiveSchema("integer"));
		properties.add("end_z", primitiveSchema("integer"));
		properties.add("origin_x", primitiveSchema("integer"));
		properties.add("origin_y", primitiveSchema("integer"));
			properties.add("origin_z", primitiveSchema("integer"));
			properties.add("size", buildSizeJsonSchema());
			properties.add("dimensions", buildSizeJsonSchema());
		addPropertyOrdering(schema, "x", "y", "z", "dx", "dy", "dz", "origin_x", "origin_y", "origin_z", "pos_x", "pos_y", "pos_z", "start_x", "start_y", "start_z", "end_x", "end_y", "end_z", "size", "dimensions");
		return schema;
	}

	private static JsonObject buildPointJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);
		JsonObject properties = new JsonObject();
		properties.add("x", primitiveSchema("integer"));
		properties.add("y", primitiveSchema("integer"));
		properties.add("z", primitiveSchema("integer"));
		properties.add("dx", primitiveSchema("integer"));
		properties.add("dy", primitiveSchema("integer"));
		properties.add("dz", primitiveSchema("integer"));
		properties.add("origin_x", primitiveSchema("integer"));
		properties.add("origin_y", primitiveSchema("integer"));
		properties.add("origin_z", primitiveSchema("integer"));
			properties.add("pos_x", primitiveSchema("integer"));
			properties.add("pos_y", primitiveSchema("integer"));
			properties.add("pos_z", primitiveSchema("integer"));
			schema.add("properties", properties);
		addPropertyOrdering(schema, "x", "y", "z", "dx", "dy", "dz", "origin_x", "origin_y", "origin_z", "pos_x", "pos_y", "pos_z");
		return schema;
	}

	private static JsonObject buildSizeJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.addProperty("additionalProperties", true);
		JsonObject properties = new JsonObject();
		properties.add("x", primitiveSchema("integer"));
		properties.add("y", primitiveSchema("integer"));
		properties.add("z", primitiveSchema("integer"));
		properties.add("width", primitiveSchema("integer"));
		properties.add("height", primitiveSchema("integer"));
		properties.add("depth", primitiveSchema("integer"));
		properties.add("length", primitiveSchema("integer"));
			properties.add("w", primitiveSchema("integer"));
			properties.add("h", primitiveSchema("integer"));
			properties.add("d", primitiveSchema("integer"));
			schema.add("properties", properties);
		addPropertyOrdering(schema, "x", "y", "z", "width", "height", "depth", "length", "w", "h", "d");
		return schema;
	}

	private static JsonObject buildStringMapJsonSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("additionalProperties", primitiveSchema("string"));
		return schema;
	}

	private static JsonObject buildRotateJsonSchema() {
		JsonArray anyOf = new JsonArray();
		anyOf.add(enumSchema("integer", "0", "90", "180", "270"));
		anyOf.add(enumSchema(
			"string",
			"0", "90", "180", "270",
			"cw", "ccw",
			"clockwise", "counterclockwise", "counter-clockwise",
			"none"
		));
		return anyOfSchema(anyOf);
	}

	private static JsonObject primitiveSchema(String type) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", type);
		return schema;
	}

	private static JsonObject arraySchema(JsonObject itemSchema) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "array");
		schema.add("items", itemSchema);
		return schema;
	}

	private static JsonObject enumSchema(String type, String... values) {
		JsonObject schema = primitiveSchema(type);
		JsonArray enums = new JsonArray();
		for (String value : values) {
			if (value == null) {
				continue;
			}
			if ("integer".equals(type)) {
				enums.add(Integer.parseInt(value));
			} else {
				enums.add(value);
			}
		}
		schema.add("enum", enums);
		return schema;
	}

	private static JsonObject anyOfSchema(JsonArray anyOf) {
		JsonObject schema = new JsonObject();
		schema.add("anyOf", anyOf);
		return schema;
	}

	private static void addPropertyOrdering(JsonObject schema, String... keys) {
		if (schema == null || keys == null || keys.length == 0) {
			return;
		}
		JsonArray ordering = new JsonArray();
		for (String key : keys) {
			if (key == null || key.isBlank()) {
				continue;
			}
			ordering.add(key);
		}
		if (!ordering.isEmpty()) {
			schema.add("propertyOrdering", ordering);
		}
	}

	private static boolean shouldEnableGoogleSearch(String prompt, String errorContext) {
		String combined = ((prompt == null ? "" : prompt) + " " + (errorContext == null ? "" : errorContext)).toLowerCase(Locale.ROOT);
		if (combined.isBlank()) {
			return false;
		}
		return containsAny(
			combined,
			"latest", "current", "today", "news", "web", "website", "internet", "online",
			"google", "search", "look up", "lookup", "docs", "documentation", "release notes",
			"patch notes", "api", "version", "model", "pricing", "announcement"
		);
	}

	private static JsonArray buildGeminiTools(boolean includeGoogleSearch, boolean includeCodeExecution) {
		JsonArray tools = new JsonArray();
		if (includeGoogleSearch) {
			JsonObject googleSearch = new JsonObject();
			googleSearch.add("googleSearch", new JsonObject());
			tools.add(googleSearch);
		}
		if (includeCodeExecution) {
			JsonObject codeExecution = new JsonObject();
			codeExecution.add("codeExecution", new JsonObject());
			tools.add(codeExecution);
		}
		return tools;
	}

	private static JsonObject cloneJsonObject(JsonObject obj) {
		return obj == null ? new JsonObject() : GSON.fromJson(GSON.toJson(obj), JsonObject.class);
	}

	private static HttpResponse<String> sendGeminiRequest(String apiKey, String modelId, JsonObject request) throws Exception {
		String body = GSON.toJson(request);
		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create(GEMINI_ENDPOINT_BASE + modelId + ":generateContent?key=" + apiKey))
			.header("Content-Type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		return HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static JsonObject executeGeminiRequestWithFallbacks(
		String apiKey,
		String modelId,
		JsonObject request,
		boolean allowToolFallback,
		boolean allowSchemaFallback
	) throws Exception {
		JsonObject baseRequest = cloneJsonObject(request);
		HttpResponse<String> response = sendWithRetries(apiKey, modelId, baseRequest);
		if (isHttpSuccess(response.statusCode())) {
			return GSON.fromJson(response.body(), JsonObject.class);
		}

		if (allowToolFallback && baseRequest.has("tools")) {
			JsonObject noToolsRequest = cloneJsonObject(baseRequest);
			noToolsRequest.remove("tools");
			response = sendWithRetries(apiKey, modelId, noToolsRequest);
			if (isHttpSuccess(response.statusCode())) {
				return GSON.fromJson(response.body(), JsonObject.class);
			}
			baseRequest = noToolsRequest;
		}

		if (allowSchemaFallback && baseRequest.has("generationConfig") && baseRequest.get("generationConfig").isJsonObject()) {
			JsonObject noSchemaRequest = cloneJsonObject(baseRequest);
			JsonObject generationConfig = noSchemaRequest.getAsJsonObject("generationConfig");
			generationConfig.remove("responseJsonSchema");
			generationConfig.remove("responseMimeType");
			response = sendWithRetries(apiKey, modelId, noSchemaRequest);
			if (isHttpSuccess(response.statusCode())) {
				return GSON.fromJson(response.body(), JsonObject.class);
			}
		}

		throw new IllegalStateException("HTTP " + response.statusCode() + " - " + response.body());
	}

	private static HttpResponse<String> sendWithRetries(String apiKey, String modelId, JsonObject request) throws Exception {
		HttpResponse<String> last = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			last = sendGeminiRequest(apiKey, modelId, request);
			if (!isRetryableStatus(last.statusCode())) {
				return last;
			}
			if (attempt < 2) {
				Thread.sleep(350L * (attempt + 1));
			}
		}
		return last;
	}

	private static boolean isHttpSuccess(int statusCode) {
		return statusCode >= 200 && statusCode < 300;
	}

	private static boolean isRetryableStatus(int statusCode) {
		return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
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
			return new ModeMessage("ASK", "Error: " + e.getMessage(), List.of(), false, List.of(), List.of());
		}
	}

	private static ModeMessage callGeminiVisionSafely(
		String apiKey,
		String prompt,
		String context,
		List<ChatTurn> history,
		String mimeType,
		byte[] imageBytes,
		ModelChoice modelChoice
	) {
		try {
			return callGeminiVision(apiKey, prompt, context, history, mimeType, imageBytes, modelChoice);
		} catch (Exception e) {
			return new ModeMessage("ASK", "Error: " + e.getMessage(), List.of(), false, List.of(), List.of());
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
			applyGeminiResponseSchema(generationConfig);
			request.add("generationConfig", generationConfig);
			if (shouldEnableGoogleSearch(prompt, errorContext)) {
				request.add("tools", buildGeminiTools(true, false));
			}

			JsonObject json = executeGeminiRequestWithFallbacks(apiKey, effectiveChoice.modelId, request, true, true);
			JsonArray candidates = json.getAsJsonArray("candidates");
			if (candidates == null || candidates.isEmpty()) {
				return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
		}

		JsonObject first = candidates.get(0).getAsJsonObject();
		JsonObject responseContent = first.getAsJsonObject("content");
		if (responseContent == null) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
		}

		JsonArray responseParts = responseContent.getAsJsonArray("parts");
		if (responseParts == null || responseParts.isEmpty()) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
		}

		JsonObject firstPart = responseParts.get(0).getAsJsonObject();
		if (!firstPart.has("text")) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
		}

		String raw = firstPart.get("text").getAsString();
		SearchMetadata search = parseSearchMetadata(first);
		return parseModeMessage(raw, search.used, search.sources);
	}

	private static ModeMessage callGeminiVision(
		String apiKey,
		String prompt,
		String context,
		List<ChatTurn> history,
		String mimeType,
		byte[] imageBytes,
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
		JsonObject imagePart = new JsonObject();
		JsonObject inlineData = new JsonObject();
		inlineData.addProperty("mime_type", mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
		inlineData.addProperty("data", Base64.getEncoder().encodeToString(imageBytes));
		imagePart.add("inline_data", inlineData);
		parts.add(imagePart);

		JsonObject textPart = new JsonObject();
		StringBuilder userText = new StringBuilder();
		userText.append(context).append("\nUser: ").append(prompt);
		textPart.addProperty("text", userText.toString());
		parts.add(textPart);
		content.add("parts", parts);
		contents.add(content);
		request.add("contents", contents);

			ModelChoice effectiveChoice = modelChoice == null ? ModelChoice.FLASH : modelChoice;
			JsonObject generationConfig = new JsonObject();
			JsonObject thinkingConfig = new JsonObject();
			thinkingConfig.addProperty("thinkingLevel", effectiveChoice.thinkingLevel);
			generationConfig.add("thinkingConfig", thinkingConfig);
			applyGeminiResponseSchema(generationConfig);
			request.add("generationConfig", generationConfig);
			boolean includeSearch = shouldEnableGoogleSearch(prompt, null);
			JsonArray tools = buildGeminiTools(includeSearch, true);
			if (!tools.isEmpty()) {
				request.add("tools", tools);
			}

			JsonObject json = executeGeminiRequestWithFallbacks(apiKey, effectiveChoice.modelId, request, true, true);
			JsonArray candidates = json.getAsJsonArray("candidates");
			if (candidates == null || candidates.isEmpty()) {
				return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
		}

		JsonObject first = candidates.get(0).getAsJsonObject();
		JsonObject responseContent = first.getAsJsonObject("content");
		if (responseContent == null) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
		}

		JsonArray responseParts = responseContent.getAsJsonArray("parts");
		if (responseParts == null || responseParts.isEmpty()) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
		}

		JsonObject firstPart = responseParts.get(0).getAsJsonObject();
		if (!firstPart.has("text")) {
			return new ModeMessage("ASK", "No response.", List.of(), false, List.of(), List.of());
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
		JsonObject promptPart = new JsonObject();
		promptPart.addProperty("text", "Generate a transcript of the speech. Return only the transcript text.");
		parts.add(promptPart);
		JsonObject audioPart = new JsonObject();
		JsonObject inlineData = new JsonObject();
		inlineData.addProperty("mime_type", mimeType == null || mimeType.isBlank() ? "audio/wav" : mimeType);
		inlineData.addProperty("data", Base64.getEncoder().encodeToString(audioBytes));
		audioPart.add("inline_data", inlineData);
		parts.add(audioPart);
		content.add("parts", parts);
		contents.add(content);
		request.add("contents", contents);

		String modelId = "gemini-2.5-flash";
		HttpResponse<String> response = sendWithRetries(apiKey, modelId, request);
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("HTTP " + response.statusCode() + " - " + summarizeGeminiErrorBody(response.body()));
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
		StringBuilder transcript = new StringBuilder();
		for (JsonElement element : responseParts) {
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject responsePart = element.getAsJsonObject();
			if (responsePart.has("text")) {
				String text = responsePart.get("text").getAsString();
				if (!text.isBlank()) {
					if (transcript.length() > 0) {
						transcript.append('\n');
					}
					transcript.append(text.trim());
				}
			}
		}
		return transcript.toString();
	}

	private static String summarizeGeminiErrorBody(String body) {
		if (body == null || body.isBlank()) {
			return "Empty response body";
		}
		try {
			JsonObject json = GSON.fromJson(body, JsonObject.class);
			if (json != null && json.has("error") && json.get("error").isJsonObject()) {
				JsonObject error = json.getAsJsonObject("error");
				String message = error.has("message") ? error.get("message").getAsString() : body;
				String status = error.has("status") ? error.get("status").getAsString() : "";
				if (!status.isBlank() && !message.contains(status)) {
					return status + ": " + message;
				}
				return message;
			}
		} catch (Exception ignored) {
		}
		String flattened = body.replace('\n', ' ').replace('\r', ' ').trim();
		return flattened.length() > 240 ? flattened.substring(0, 240) + "..." : flattened;
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
		if (raw == null) {
			return new ModeMessage("ASK", "", List.of(), searchUsed, sources, List.of());
		}
		try {
			String trimmed = extractJsonObject(raw);
			JsonObject obj = lenientJsonObject(trimmed);
			if (obj == null || obj.isEmpty()) {
				throw new IllegalStateException("Empty JSON");
			}
			String mode = obj.has("mode") ? obj.get("mode").getAsString() : "ASK";
			String message = obj.has("message") ? obj.get("message").getAsString() : raw;
			List<String> commands = new ArrayList<>();
			if (obj.has("commands") && obj.get("commands").isJsonArray()) {
				for (var element : obj.getAsJsonArray("commands")) {
					commands.add(element.getAsString());
				}
			}
			VoxelBuildPlanner.BuildPlan buildPlan = VoxelBuildPlanner.parseBuildPlan(obj);
			if (!isMeaningfulBuildPlan(buildPlan)) {
				buildPlan = null;
			}
			List<Highlight> highlights = parseHighlights(obj);
			return new ModeMessage(normalizeMode(mode), message, commands, searchUsed, sources, highlights, buildPlan);
		} catch (Exception e) {
			return parseModeMessageLoose(raw, searchUsed, sources);
		}
	}

	private static ModeMessage parseModeMessageLoose(String raw, boolean searchUsed, List<SourceLink> sources) {
		String text = raw == null ? "" : raw.trim();
		List<String> commands = extractCommands(text);
		String detectedMode = detectExplicitMode(text);
		String mode = detectedMode;
		if (mode == null || mode.isBlank()) {
			if (!commands.isEmpty()) {
				boolean hasSkill = commands.stream().anyMatch(cmd -> cmd != null && cmd.toLowerCase(Locale.ROOT).startsWith("chat skill"));
				mode = hasSkill ? "TOOL" : "COMMAND";
			} else if (text.toLowerCase(Locale.ROOT).contains("continue")) {
				mode = "CONTINUE";
			} else if (text.toLowerCase(Locale.ROOT).contains("plan") || text.toLowerCase(Locale.ROOT).contains("step")) {
				mode = "PLAN";
			} else {
				mode = "ASK";
			}
		}
		String message = stripCommandText(text);
		if (message.isBlank()) {
			message = commands.isEmpty() ? text : "Executing commands.";
		}
		return new ModeMessage(normalizeMode(mode), message, commands, searchUsed, sources, List.of());
	}

	private static String detectExplicitMode(String text) {
		if (text == null) {
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.contains("mode: command") || lower.contains("node: command") || lower.contains("[command]") || lower.contains("command mode")) {
			return "COMMAND";
		}
		if (lower.contains("mode: plan") || lower.contains("node: plan") || lower.contains("[plan]") || lower.contains("plan mode")) {
			return "PLAN";
		}
		if (lower.contains("mode: continue") || lower.contains("node: continue") || lower.contains("[continue]")) {
			return "CONTINUE";
		}
		if (lower.contains("mode: tool") || lower.contains("node: tool") || lower.contains("[tool]")) {
			return "TOOL";
		}
		if (lower.contains("mode: ask") || lower.contains("node: ask") || lower.contains("[ask]")) {
			return "ASK";
		}
		return null;
	}

	private static List<String> extractCommands(String text) {
		List<String> commands = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return commands;
		}
		String[] lines = text.split("\\r?\\n");
		for (String line : lines) {
			if (line == null) {
				continue;
			}
			String trimmed = line.trim();
			if (trimmed.isBlank()) {
				continue;
			}
			String lower = trimmed.toLowerCase(Locale.ROOT);
			if (lower.startsWith("commands:") || lower.startsWith("command:")) {
				String payload = trimmed.substring(trimmed.indexOf(':') + 1).trim();
				commands.addAll(splitCommands(payload));
				continue;
			}
			String cleaned = stripBulletPrefix(trimmed);
			if (cleaned.startsWith("/")) {
				String cmd = cleaned.substring(1).trim();
				if (!cmd.isBlank()) {
					commands.add(cmd);
				}
				continue;
			}
		}
		if (commands.isEmpty()) {
			int idx = text.toLowerCase(Locale.ROOT).indexOf("commands:");
			if (idx >= 0) {
				String payload = text.substring(idx + "commands:".length()).trim();
				commands.addAll(splitCommands(payload));
			}
		}
		if (commands.isEmpty()) {
			Pattern pattern = Pattern.compile("(?:^|\\s)(/)?chat\\s+skill\\s+([^\\r\\n]+)", Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(text);
			while (matcher.find()) {
				String payload = matcher.group(2);
				if (payload == null) {
					continue;
				}
				String cleaned = payload.trim();
				int cut = cleaned.indexOf('(');
				if (cut >= 0) {
					cleaned = cleaned.substring(0, cut).trim();
				}
				cleaned = cleaned.replaceAll("[\\]\\)\\.,;]+$", "").trim();
				if (!cleaned.isBlank()) {
					commands.add("chat skill " + cleaned);
				}
			}
		}
		return commands;
	}

	private static List<String> splitCommands(String payload) {
		if (payload == null || payload.isBlank()) {
			return List.of();
		}
		String cleaned = payload.trim();
		if (cleaned.startsWith("/")) {
			cleaned = cleaned.substring(1);
		}
		List<String> out = new ArrayList<>();
		for (String part : cleaned.split("\\s*\\|\\s*|\\s*;\\s*")) {
			String cmd = part.trim();
			if (cmd.startsWith("/")) {
				cmd = cmd.substring(1).trim();
			}
			if (!cmd.isBlank()) {
				out.add(cmd);
			}
		}
		return out;
	}

	private static String stripCommandText(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String[] lines = text.split("\\r?\\n");
		List<String> keep = new ArrayList<>();
		for (String line : lines) {
			if (line == null) {
				continue;
			}
			String trimmed = line.trim();
			if (trimmed.isBlank()) {
				continue;
			}
			String lower = trimmed.toLowerCase(Locale.ROOT);
			if (lower.startsWith("commands:") || lower.startsWith("command:")) {
				continue;
			}
			String cleaned = stripBulletPrefix(trimmed);
			if (cleaned.startsWith("/")) {
				continue;
			}
			keep.add(trimmed);
		}
		return String.join(" ", keep).trim();
	}

	private static String stripBulletPrefix(String text) {
		if (text == null) {
			return "";
		}
		String trimmed = text.trim();
		if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
			return trimmed.substring(2).trim();
		}
		return trimmed;
	}

	private static List<Highlight> parseHighlights(JsonObject obj) {
		if (obj == null || !obj.has("highlights") || !obj.get("highlights").isJsonArray()) {
			return List.of();
		}
		long now = System.currentTimeMillis();
		List<Highlight> highlights = new ArrayList<>();
		for (JsonElement element : obj.getAsJsonArray("highlights")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject h = element.getAsJsonObject();
			if (!h.has("x") || !h.has("y") || !h.has("z")) {
				continue;
			}
			double x = h.get("x").getAsDouble();
			double y = h.get("y").getAsDouble();
			double z = h.get("z").getAsDouble();
			String label = h.has("label") ? h.get("label").getAsString() : "";
			String colorName = h.has("color") ? h.get("color").getAsString() : "white";
			long duration = h.has("durationMs") ? h.get("durationMs").getAsLong() : 10_000L;
			int color = parseHighlightColor(colorName);
			// Always render highlights through blocks.
			color = (color & 0x00FFFFFF) | 0xFE000000;
			highlights.add(new Highlight(x, y, z, label, color, now + Math.max(1000L, duration)));
		}
		return highlights;
	}

	private static ModeMessage applyXrayOverride(ModeMessage message, String prompt) {
		if (message == null || message.highlights == null || message.highlights.isEmpty()) {
			return message;
		}
		if (!shouldForceXray(prompt)) {
			return message;
		}
		List<Highlight> updated = forceXrayHighlights(message.highlights);
		return new ModeMessage(message.mode, message.message, message.commands, message.searchUsed, message.sources, updated, message.buildPlan);
	}

	private static boolean shouldForceXray(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return false;
		}
		String lower = prompt.toLowerCase(Locale.ROOT);
		return lower.contains("xray")
			|| lower.contains("x-ray")
			|| lower.contains("see through")
			|| lower.contains("through blocks")
			|| lower.contains("through walls")
			|| lower.contains("behind walls")
			|| lower.contains("wallhack")
			|| lower.contains("visible through");
	}

	private static List<Highlight> forceXrayHighlights(List<Highlight> highlights) {
		List<Highlight> updated = new ArrayList<>(highlights.size());
		for (Highlight h : highlights) {
			int color = (h.colorHex() & 0x00FFFFFF) | 0xFE000000;
			updated.add(new Highlight(h.x(), h.y(), h.z(), h.label(), color, h.expiryMs()));
		}
		return updated;
	}

	private static int parseHighlightColor(String colorName) {
		if (colorName == null) {
			return 0xFFFFFFFF;
		}
		String lower = colorName.trim().toLowerCase(Locale.ROOT);
		return switch (lower) {
			case "red" -> 0xFFFF3B30;
			case "green" -> 0xFF34C759;
			case "blue" -> 0xFF0A84FF;
			case "gold", "yellow" -> 0xFFFFC300;
			case "purple" -> 0xFFBF5AF2;
			case "white" -> 0xFFFFFFFF;
			default -> {
				try {
					String clean = lower.startsWith("#") ? lower.substring(1) : lower;
					int value = (int) Long.parseLong(clean, 16);
					if (clean.length() <= 6) {
						yield 0xFF000000 | value;
					}
					yield value;
				} catch (Exception e) {
					yield 0xFFFFFFFF;
				}
			}
		};
	}

	private static void sendHighlights(ServerPlayerEntity player, List<Highlight> highlights) {
		if (player == null || highlights == null || highlights.isEmpty()) {
			return;
		}
		ServerPlayNetworking.send(player, new HighlightsPayloadS2C(highlights));
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
		if (upper.equals("PLAN") || upper.equals("COMMAND") || upper.equals("CONTINUE") || upper.equals("TOOL")) {
			return upper;
		}
		return "ASK";
	}

	private static Formatting modeColor(String mode) {
		return switch (mode) {
			case "PLAN" -> Formatting.AQUA;
			case "COMMAND" -> Formatting.GOLD;
			case "CONTINUE" -> Formatting.AQUA;
			case "TOOL" -> Formatting.LIGHT_PURPLE;
			default -> Formatting.GREEN;
		};
	}

	private static MutableText buildModeChip(String mode) {
		String label = mode == null ? "ASK" : mode.toUpperCase(Locale.ROOT);
		Formatting color = modeColor(label);
		return Text.literal("[" + label + "] ").formatted(color);
	}

	private static String modeLabel(String mode) {
		return switch (mode) {
			case "PLAN" -> "PLAN MODE";
			case "COMMAND" -> "COMMAND MODE";
			case "CONTINUE" -> "CONTINUE MODE";
			case "TOOL" -> "TOOL MODE";
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
		var registry = player.getServerWorld().getRegistryManager().get(RegistryKeys.STRUCTURE);
		BlockPos pos = player.getBlockPos();
		for (var entry : registry.getEntrySet()) {
			Structure structure = entry.getValue();
			StructureStart start = accessor.getStructureContaining(pos, structure);
			if (start != null && start.hasChildren()) {
				return entry.getKey().getValue().toString();
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
		if (!force && !containsAny(lower, "craft", "recipe", "make", "need", "have", "inventory", "items", "materials", "show inventory", "what do i have")) {
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

	private static String detectBlockTargetFromPrompt(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return null;
		}
		List<String> tokens = tokenizePrompt(prompt);
		for (String token : tokens) {
			if (!token.startsWith("#")) {
				continue;
			}
			Identifier id = Identifier.tryParse(token.substring(1));
			if (id != null) {
				return token;
			}
		}
		for (String token : tokens) {
			if (!token.contains(":")) {
				continue;
			}
			Identifier id = Identifier.tryParse(token);
			if (id != null && Registries.BLOCK.containsId(id)) {
				return token;
			}
		}
		for (int i = 0; i < tokens.size(); i++) {
			String first = tokens.get(i);
			if (first.isBlank() || first.startsWith("#") || first.contains(":")) {
				continue;
			}
			String single = "minecraft:" + first;
			Identifier singleId = Identifier.tryParse(single);
			if (singleId != null && Registries.BLOCK.containsId(singleId)) {
				return single;
			}
			if (i + 1 < tokens.size()) {
				String pair = "minecraft:" + first + "_" + tokens.get(i + 1);
				Identifier pairId = Identifier.tryParse(pair);
				if (pairId != null && Registries.BLOCK.containsId(pairId)) {
					return pair;
				}
			}
			if (i + 2 < tokens.size()) {
				String triple = "minecraft:" + first + "_" + tokens.get(i + 1) + "_" + tokens.get(i + 2);
				Identifier tripleId = Identifier.tryParse(triple);
				if (tripleId != null && Registries.BLOCK.containsId(tripleId)) {
					return triple;
				}
			}
		}
		if (prompt.contains("logs") || prompt.contains("log")) {
			return "#minecraft:logs";
		}
		return null;
	}

	private static List<String> tokenizePrompt(String prompt) {
		String[] raw = prompt.toLowerCase(Locale.ROOT).split("[^a-z0-9_:#]+");
		List<String> tokens = new ArrayList<>();
		for (String part : raw) {
			if (part != null && !part.isBlank()) {
				tokens.add(part);
			}
		}
		return tokens;
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

	private record BlockMatch(BlockPos pos, BlockState state, double distance) {}

	private record BlockScanResult(List<BlockMatch> matches, int totalMatches, int radius, boolean clamped) {}

	private record ContainerMatch(BlockPos pos, BlockState state, double distance, String summary) {}

	private record ContainerScanResult(List<ContainerMatch> matches, int totalMatches, int radius, boolean clamped) {}

	private record BlockTarget(String label, Block block, TagKey<Block> tag, boolean isTag) {
		boolean matches(BlockState state) {
			if (state == null) {
				return false;
			}
			if (isTag && tag != null) {
				return state.isIn(tag);
			}
			return block != null && state.getBlock() == block;
		}
	}

	private record ModeMessage(
		String mode,
		String message,
		List<String> commands,
		boolean searchUsed,
		List<SourceLink> sources,
		List<Highlight> highlights,
		VoxelBuildPlanner.BuildPlan buildPlan
	) {
		private ModeMessage(
			String mode,
			String message,
			List<String> commands,
			boolean searchUsed,
			List<SourceLink> sources,
			List<Highlight> highlights
		) {
			this(mode, message, commands, searchUsed, sources, highlights, null);
		}
	}

	private record CommandResult(boolean success, String errorSummary, List<String> outputs) {}

	record McpCommandSpec(String command, int delayTicks) {}

	private record PreparedCommands(List<String> executeCommands, List<String> undoCommands, List<BlockUndoSnapshot> undoSnapshots) {}

	private record UndoBatch(List<String> commands, List<BlockUndoSnapshot> snapshots) {}

	private record ScheduledMcpCommand(String command, int delayTicks) {}

	private record BlockUndoSnapshot(RegistryKey<World> worldKey, BlockPos pos, BlockState state, NbtCompound blockEntityNbt) {}

	private record AffectedBlocks(List<BlockPos> positions, boolean overflow) {}

	private record RestoreResult(boolean success, int restoredBlocks, String errorSummary) {}

	record McpActionResult(
		boolean success,
		int applied,
		List<String> repairs,
		List<String> outputs,
		String error,
		boolean undoAvailable,
		String summary,
		int appliedRotation,
		int phaseCount,
		List<String> previewCommands,
		boolean pending,
		String batchId,
		String planId,
		VoxelBuildPlanner.GridPoint resolvedOrigin,
		List<VoxelBuildPlanner.SupportIssue> issues,
		boolean autoFixAvailable
	) {
		McpActionResult(
			boolean success,
			int applied,
			List<String> repairs,
			List<String> outputs,
			String error,
			boolean undoAvailable,
			String summary,
			int appliedRotation,
			int phaseCount
		) {
			this(success, applied, repairs, outputs, error, undoAvailable, summary, appliedRotation, phaseCount, List.of(), false, "", "", new VoxelBuildPlanner.GridPoint(0, 0, 0), List.of(), false);
		}

		McpActionResult(
			boolean success,
			int applied,
			List<String> repairs,
			List<String> outputs,
			String error,
			boolean undoAvailable,
			String summary,
			int appliedRotation,
			int phaseCount,
			List<String> previewCommands
		) {
			this(success, applied, repairs, outputs, error, undoAvailable, summary, appliedRotation, phaseCount, previewCommands, false, "", "", new VoxelBuildPlanner.GridPoint(0, 0, 0), List.of(), false);
		}
	}

	record McpBatchStatusResult(
		boolean success,
		String batchId,
		boolean pending,
		boolean completed,
		int totalCommands,
		int applied,
		int failed,
		int nextIndex,
		List<String> outputs,
		String error,
		boolean undoAvailable,
		String summary
	) {}

	record McpVisionCaptureResult(
		boolean success,
		String mimeType,
		String lookAt,
		String imageBase64,
		int byteLength,
		String summary,
		String imagePath
	) {}

	private record PendingMcpCapture(UUID playerId, long requestId, long createdMs, CompletableFuture<McpVisionCaptureResult> future) {}

	private record CachedMcpBuildPlan(
		String planId,
		UUID playerId,
		long createdMs,
		VoxelBuildPlanner.BuildPlan plan,
		List<String> executeCommands,
		String summary,
		int appliedRotation,
		int phaseCount,
		List<String> repairs,
		VoxelBuildPlanner.GridPoint resolvedOrigin,
		List<VoxelBuildPlanner.SupportIssue> issues,
		boolean autoFixAvailable
	) {
		boolean isExpired(long nowMs) {
			return nowMs - createdMs > MCP_BUILD_PLAN_RETENTION_MS;
		}
	}

	private static final class PendingMcpCommandBatch {
		private final String batchId;
		private final UUID playerId;
		private final long createdMs;
		private final List<ScheduledMcpCommand> commands;
		private final List<String> undoCommands;
		private final List<BlockUndoSnapshot> undoSnapshots;
		private final List<String> outputs = new ArrayList<>();
		private final List<String> errors = new ArrayList<>();
		private int nextIndex = 0;
		private int applied = 0;
		private int failed = 0;
		private long nextDueMs;
		private boolean completed = false;
		private boolean undoStored = false;

		private PendingMcpCommandBatch(
			String batchId,
			UUID playerId,
			long createdMs,
			List<ScheduledMcpCommand> commands,
			List<String> undoCommands,
			List<BlockUndoSnapshot> undoSnapshots
		) {
			this.batchId = batchId;
			this.playerId = playerId;
			this.createdMs = createdMs;
			this.commands = commands == null ? List.of() : List.copyOf(commands);
			this.undoCommands = undoCommands == null ? List.of() : List.copyOf(undoCommands);
			this.undoSnapshots = undoSnapshots == null ? List.of() : List.copyOf(undoSnapshots);
			this.nextDueMs = createdMs + (this.commands.isEmpty() ? 0L : this.commands.get(0).delayTicks() * 50L);
		}

		private synchronized boolean isExpired(long nowMs) {
			return completed && nowMs - createdMs > MCP_COMMAND_BATCH_RETENTION_MS;
		}

		private synchronized McpBatchStatusResult snapshot() {
			String error = errors.isEmpty() ? "" : String.join(" | ", errors);
			String summary;
			if (!completed) {
				summary = "Batch " + batchId + " is pending (" + nextIndex + "/" + commands.size() + " commands processed).";
			} else if (failed > 0) {
				summary = "Batch " + batchId + " completed with " + applied + " applied and " + failed + " failed.";
			} else {
				summary = "Batch " + batchId + " completed successfully.";
			}
			return new McpBatchStatusResult(
				error.isEmpty(),
				batchId,
				!completed,
				completed,
				commands.size(),
				applied,
				failed,
				nextIndex,
				List.copyOf(outputs),
				error,
				undoStored,
				summary
			);
		}

		private synchronized void process(MinecraftServer server, long nowMs) {
			if (completed || server == null || nowMs < nextDueMs) {
				return;
			}
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
			if (player == null) {
				errors.add("Player disconnected before delayed command batch completed.");
				completed = true;
				return;
			}
			while (!completed && nextIndex < commands.size() && nowMs >= nextDueMs) {
				ScheduledMcpCommand scheduled = commands.get(nextIndex);
				CommandResult result = executeCommandNow(server, player, scheduled.command());
				if (result.outputs() != null && !result.outputs().isEmpty()) {
					outputs.addAll(result.outputs());
				}
				if (!result.success()) {
					errors.add(result.errorSummary());
					failed++;
				} else {
					applied++;
					if (commandIndicatesAirReplace(scheduled.command(), result.outputs())) {
						errors.add("Command error: target slot was empty.");
						failed++;
					}
				}
				nextIndex++;
				if (nextIndex >= commands.size()) {
					completed = true;
					break;
				}
				nextDueMs += Math.max(0L, commands.get(nextIndex).delayTicks() * 50L);
			}
			if (completed && applied > 0 && !undoStored && (!undoCommands.isEmpty() || !undoSnapshots.isEmpty())) {
				LAST_UNDO_BATCHES.put(playerId, new UndoBatch(undoCommands, undoSnapshots));
				undoStored = true;
			}
			if (completed) {
				if (failed > 0) {
					player.sendMessage(Text.literal("Delayed MCP batch " + batchId + " finished with " + applied + " applied and " + failed + " failed.").formatted(Formatting.RED), false);
				} else {
					player.sendMessage(Text.literal("Delayed MCP batch " + batchId + " completed.").formatted(Formatting.GREEN), false);
				}
			}
		}
	}

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

	private static void appendHistory(ServerPlayerEntity player, String userMessage, ModeMessage assistantMessage, long responseMs) {
		UUID playerId = player == null ? null : player.getUuid();
		List<ChatTurn> history = new ArrayList<>(playerId == null ? List.of() : CHAT_HISTORY.getOrDefault(playerId, List.of()));
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
		boolean trimmed = false;
		while (history.size() > MAX_HISTORY_TURNS * 2) {
			history.remove(0);
			trimmed = true;
		}
		if (playerId != null) {
			CHAT_HISTORY.put(playerId, history);
			int turnCount = history.size() / 2;
			AiStats stats = getStats(playerId);
			if (turnCount >= MAX_HISTORY_TURNS - 1 && stats.lastContextWarnTurn != turnCount) {
				stats.lastContextWarnTurn = turnCount;
				sendContextWarning(player, turnCount);
			}
			if (trimmed && stats.lastResetWarnTurn != turnCount) {
				stats.lastResetWarnTurn = turnCount;
				player.sendMessage(Text.literal("Context refreshed — oldest messages were removed.").formatted(Formatting.AQUA), false);
			}
		}
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
		private int lastContextWarnTurn = -1;
		private int lastResetWarnTurn = -1;

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

	private static boolean isContextFull(UUID playerId) {
		if (playerId == null) {
			return false;
		}
		return getTurnCount(playerId) >= MAX_HISTORY_TURNS;
	}

	private static void sendSetupReminder(ServerPlayerEntity player) {
		long now = System.currentTimeMillis();
		long last = SETUP_WARN_COOLDOWN.getOrDefault(player.getUuid(), 0L);
		if (now - last < 8000L) {
			return;
		}
		SETUP_WARN_COOLDOWN.put(player.getUuid(), now);
		player.sendMessage(Text.literal("Gemini AI is not configured yet. Run /chat setup to begin.").formatted(Formatting.YELLOW), false);
	}

	private static int getTurnCount(UUID playerId) {
		List<ChatTurn> history = CHAT_HISTORY.getOrDefault(playerId, List.of());
		return history.size() / 2;
	}

	private static void sendContextLimitMessage(ServerPlayerEntity player) {
		if (player == null) {
			return;
		}
		MutableText base = Text.literal("Context full (")
			.formatted(Formatting.YELLOW)
			.append(Text.literal(MAX_HISTORY_TURNS + "/" + MAX_HISTORY_TURNS).formatted(Formatting.GOLD))
			.append(Text.literal("). Please clear to continue. ").formatted(Formatting.YELLOW));
		MutableText clear = Text.literal("[Clear]").formatted(Formatting.LIGHT_PURPLE)
			.styled(style -> style
				.withUnderline(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat clear"))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Reset context"))));
		MutableText export = Text.literal(" [Export]").formatted(Formatting.AQUA)
			.styled(style -> style
				.withUnderline(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat export 10 txt"))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Save recent chat"))));
		player.sendMessage(base.append(clear).append(export), false);
	}

	private static void sendContextWarning(ServerPlayerEntity player, int turnCount) {
		if (player == null) {
			return;
		}
		int remaining = Math.max(0, MAX_HISTORY_TURNS - turnCount);
		MutableText base = Text.literal("Context limit: ").formatted(Formatting.YELLOW)
			.append(Text.literal(turnCount + "/" + MAX_HISTORY_TURNS).formatted(Formatting.GOLD))
			.append(Text.literal(" turns. ").formatted(Formatting.YELLOW));
		if (remaining <= 1) {
			base.append(Text.literal("Next reply will refresh context. ").formatted(Formatting.RED));
		} else {
			base.append(Text.literal(remaining + " turns left before refresh. ").formatted(Formatting.YELLOW));
		}
		MutableText export = Text.literal("[Export]").formatted(Formatting.AQUA)
			.styled(style -> style
				.withUnderline(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat export 10 txt"))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Save recent chat"))));
		MutableText clear = Text.literal(" [Clear]").formatted(Formatting.LIGHT_PURPLE)
			.styled(style -> style
				.withUnderline(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chat clear"))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Reset context"))));
		player.sendMessage(base.append(export).append(clear), false);
	}

	private record SourceLink(String label, String url) {}

	private enum PermissionMode {
		OPS,
		WHITELIST,
		ALL
	}

	private enum ParticleSetting {
		ON,
		OFF,
		MINIMAL
	}

	private enum ModelPreference {
		AUTO("auto", "Auto"),
		FLASH("flash", "Flash"),
		FLASH_THINKING("flash-thinking", "Flash Thinking"),
		PRO("pro", "3.1 Pro Preview");

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
		PRO("gemini-3.1-pro-preview", "high");

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
	private record VisionRequest(String prompt, String context, String apiKey, ModelChoice overrideModel, long createdMs) {}
	private record SettingsSnapshot(Map<String, String> video, Map<String, String> controls, long updatedMs) {}
	private record PendingSettingChange(String key, String value) {}

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

	private static Path visionCaptureDir() {
		return Path.of("run", "vision-captures");
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

	private static String saveVisionCapturePng(UUID playerId, byte[] pngBytes, String prefix) {
		if (pngBytes == null || pngBytes.length == 0) {
			return "";
		}
		try {
			Path dir = visionCaptureDir();
			Files.createDirectories(dir);
			String safePrefix = (prefix == null || prefix.isBlank()) ? "vision" : prefix.replaceAll("[^a-zA-Z0-9_-]", "_");
			String playerPart = playerId == null ? "unknown" : playerId.toString().replace("-", "").substring(0, 8);
			String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
			Path out = dir.resolve(safePrefix + "-" + playerPart + "-" + timestamp + ".png");
			Files.write(out, pngBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			return out.toAbsolutePath().normalize().toString();
		} catch (Exception e) {
			LOGGER.warn("Failed to save vision PNG: {}", e.getMessage());
			return "";
		}
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
			if (obj == null) {
				return;
			}
			if (obj.has("sidebarEnabled")) {
				SIDEBAR_ENABLED = obj.get("sidebarEnabled").getAsBoolean();
			}
			if (obj.has("setupComplete")) {
				SETUP_COMPLETE = obj.get("setupComplete").getAsBoolean();
			}
			if (obj.has("permissionMode")) {
				PermissionMode parsed = parsePermissionMode(obj.get("permissionMode").getAsString());
				if (parsed != null) {
					PERMISSION_MODE = parsed;
				}
			}
			if (obj.has("whitelist") && obj.get("whitelist").isJsonArray()) {
				AI_WHITELIST.clear();
				for (JsonElement element : obj.getAsJsonArray("whitelist")) {
					try {
						AI_WHITELIST.add(UUID.fromString(element.getAsString()));
					} catch (Exception ignored) {
					}
				}
			}
			if (obj.has("mcpBridgeEnabled")) {
				MCP_BRIDGE_ENABLED = obj.get("mcpBridgeEnabled").getAsBoolean();
			}
			if (obj.has("mcpPort")) {
				int parsed = obj.get("mcpPort").getAsInt();
				if (parsed > 0 && parsed <= 65535) {
					MCP_BRIDGE_PORT = parsed;
				}
			}
			if (obj.has("mcpToken")) {
				MCP_BRIDGE_TOKEN = obj.get("mcpToken").getAsString();
			}
			if (obj.has("mcpLoopbackOnly")) {
				// v1 is fixed to loopback-only; persisted for external inspection only.
			}
			if (MCP_BRIDGE_ENABLED && (MCP_BRIDGE_TOKEN == null || MCP_BRIDGE_TOKEN.isBlank())) {
				MCP_BRIDGE_TOKEN = generateMcpBridgeToken();
				saveGlobalSettings();
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to load global settings: {}", e.getMessage());
		}
	}

	private static void saveGlobalSettings() {
		try {
			JsonObject obj = new JsonObject();
			obj.addProperty("sidebarEnabled", SIDEBAR_ENABLED);
			obj.addProperty("setupComplete", SETUP_COMPLETE);
			obj.addProperty("permissionMode", PERMISSION_MODE.name().toLowerCase(Locale.ROOT));
			JsonArray whitelist = new JsonArray();
			for (UUID id : AI_WHITELIST) {
				whitelist.add(id.toString());
			}
			obj.add("whitelist", whitelist);
			obj.addProperty("mcpBridgeEnabled", MCP_BRIDGE_ENABLED);
			obj.addProperty("mcpPort", MCP_BRIDGE_PORT);
			obj.addProperty("mcpToken", MCP_BRIDGE_TOKEN == null ? "" : MCP_BRIDGE_TOKEN);
			obj.addProperty("mcpLoopbackOnly", MCP_LOOPBACK_ONLY);
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

	private static void applyCommandModificationLimit(MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			world.getGameRules()
				.get(GameRules.COMMAND_MODIFICATION_BLOCK_LIMIT)
				.set(COMMAND_MODIFICATION_LIMIT, server);
		}
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
