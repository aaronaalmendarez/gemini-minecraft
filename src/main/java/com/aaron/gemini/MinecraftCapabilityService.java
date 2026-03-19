package com.aaron.gemini;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

final class MinecraftCapabilityService {
	private static final int DEFAULT_BLOCK_SCAN_RADIUS = 24;
	private static final int MAX_BLOCK_SCAN_POSITIONS = 512_000;
	private static final int MAX_BLOCK_SCAN_RESULTS = 12;
	private static final int MAX_CONTAINER_RESULTS = 8;

	private MinecraftCapabilityService() {
	}

	static ServiceResult<SessionInfo> session(MinecraftServer server, boolean bridgeEnabled, int port) {
		if (server == null) {
			return ServiceResult.error("NO_SERVER", "Minecraft server is not available.");
		}
		ServiceResult<ActivePlayerContext> playerResult = resolveActivePlayer(server);
		ActivePlayerContext active = playerResult.data();
		SessionInfo session = new SessionInfo(
			bridgeEnabled,
			port,
			true,
			server.isSingleplayer(),
			server.getSaveProperties().getLevelName(),
			active == null ? null : active.player()
		);
		return playerResult.error() == null
			? ServiceResult.ok(session)
			: new ServiceResult<>(session, playerResult.error());
	}

	static ServiceResult<ActivePlayerContext> resolveActivePlayer(MinecraftServer server) {
		if (server == null) {
			return ServiceResult.error("NO_SERVER", "Minecraft server is not available.");
		}
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		if (players.isEmpty()) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active local player is available.");
		}
		if (players.size() > 1) {
			return ServiceResult.error("AMBIGUOUS_PLAYER_CONTEXT", "More than one player is online; MCP v1 requires a single active player.");
		}
		ServerPlayerEntity player = players.get(0);
		return ServiceResult.ok(new ActivePlayerContext(playerInfo(player), player));
	}

	static ServiceResult<InventorySummary> inventory(ServerPlayerEntity player) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		PlayerInventory inventory = player.getInventory();
		List<InventoryItem> items = new ArrayList<>();
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (!isStackPresent(stack)) {
				continue;
			}
			String components = summarizeComponents(stack.getComponents().toString());
			items.add(new InventoryItem(
				inventorySlotLabel(i),
				i,
				Registries.ITEM.getId(stack.getItem()).toString(),
				stack.getCount(),
				components == null || components.equals("[]") ? "" : components
			));
		}
		return ServiceResult.ok(new InventorySummary(items, items.size(), inventory.size(), items.isEmpty()));
	}

	static ServiceResult<NearbySummary> nearbyEntities(ServerPlayerEntity player) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		double radius = 32.0;
		Box box = player.getBoundingBox().expand(radius);
		Map<String, MutableEntityGroup> groups = new LinkedHashMap<>();
		for (Entity entity : player.getWorld().getOtherEntities(player, box)) {
			String label = labelForEntity(entity);
			double distance = Math.sqrt(player.squaredDistanceTo(entity));
			boolean hostile = entity instanceof HostileEntity;
			MutableEntityGroup group = groups.get(label);
			if (group == null) {
				group = new MutableEntityGroup(label, hostile, distance);
				groups.put(label, group);
			}
			group.count++;
			if (distance < group.nearestDistance) {
				group.nearestDistance = distance;
			}
		}
		List<NearbyEntityGroup> result = new ArrayList<>();
		List<MutableEntityGroup> ordered = new ArrayList<>(groups.values());
		ordered.sort((a, b) -> Double.compare(a.nearestDistance, b.nearestDistance));
		for (MutableEntityGroup group : ordered) {
			result.add(new NearbyEntityGroup(group.label, group.hostile, group.count, group.nearestDistance));
			if (result.size() >= 10) {
				break;
			}
		}
		return ServiceResult.ok(new NearbySummary(radius, result));
	}

	static ServiceResult<BlockScanInfo> scanBlocks(ServerPlayerEntity player, String rawTarget, int requestedRadius) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		if (rawTarget == null || rawTarget.isBlank()) {
			return ServiceResult.error("INVALID_ARGUMENT", "Missing block target.");
		}
		BlockTarget target = resolveBlockTarget(rawTarget);
		if (target == null) {
			return ServiceResult.error("UNKNOWN_BLOCK", "Unknown block target: " + rawTarget);
		}
		BlockScan scan = scanForBlocks(player, target, requestedRadius, MAX_BLOCK_SCAN_POSITIONS, MAX_BLOCK_SCAN_RESULTS);
		List<BlockMatchInfo> matches = new ArrayList<>();
		for (BlockMatch match : scan.matches()) {
			BlockPos pos = match.pos();
			BlockEntity entity = player.getServerWorld().getBlockEntity(pos);
			matches.add(new BlockMatchInfo(
				Registries.BLOCK.getId(match.state().getBlock()).toString(),
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				match.distance(),
				formatBlockState(match.state()),
				entity == null ? "" : Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).toString()
			));
		}
		return ServiceResult.ok(new BlockScanInfo(target.label(), scan.radius(), scan.clamped(), scan.totalMatches(), matches));
	}

	static ServiceResult<ContainerScanInfo> scanContainers(ServerPlayerEntity player, String filter, int requestedRadius) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		BlockTarget target = null;
		if (filter != null && !filter.isBlank()) {
			target = resolveBlockTarget(filter);
			if (target == null) {
				return ServiceResult.error("UNKNOWN_BLOCK", "Unknown container filter: " + filter);
			}
		}
		ContainerScan scan = scanForContainers(player, target, requestedRadius, MAX_BLOCK_SCAN_POSITIONS, MAX_CONTAINER_RESULTS);
		List<ContainerMatchInfo> matches = new ArrayList<>();
		for (ContainerMatch match : scan.matches()) {
			BlockPos pos = match.pos();
			matches.add(new ContainerMatchInfo(
				Registries.BLOCK.getId(match.state().getBlock()).toString(),
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				match.distance(),
				match.summary()
			));
		}
		return ServiceResult.ok(new ContainerScanInfo(target == null ? "" : target.label(), scan.radius(), scan.clamped(), scan.totalMatches(), matches));
	}

	static ServiceResult<BlockDataInfo> blockData(ServerPlayerEntity player, String args) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		String trimmed = args == null ? "" : args.trim();
		if (trimmed.isBlank()) {
			return ServiceResult.error("INVALID_ARGUMENT", "Missing block target.");
		}
		String[] parts = trimmed.split("\\s+");
		if (parts.length >= 3 && isInteger(parts[0]) && isInteger(parts[1]) && isInteger(parts[2])) {
			return ServiceResult.ok(readBlockData(player, new BlockPos(
				Integer.parseInt(parts[0]),
				Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2])
			)));
		}

		boolean nearest = false;
		String filter = null;
		int radius = DEFAULT_BLOCK_SCAN_RADIUS;
		for (String raw : parts) {
			String token = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace(",", "");
			if (token.isBlank()) {
				continue;
			}
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
				return ServiceResult.error("UNKNOWN_BLOCK", "Unknown block target: " + filter);
			}
		}
		ContainerMatch nearestMatch = findNearestContainer(player, target, radius, MAX_BLOCK_SCAN_POSITIONS);
		if (nearestMatch == null) {
			String label = target == null ? "containers" : target.label();
			return ServiceResult.error("NOT_FOUND", "No " + label + " found within " + Math.max(1, radius) + " blocks.");
		}
		return ServiceResult.ok(readBlockData(player, nearestMatch.pos()));
	}

	static ServiceResult<PlayerListInfo> players(MinecraftServer server) {
		if (server == null) {
			return ServiceResult.error("NO_SERVER", "Minecraft server is not available.");
		}
		List<PlayerInfo> players = new ArrayList<>();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			players.add(playerInfo(player));
		}
		return ServiceResult.ok(new PlayerListInfo(players));
	}

	static ServiceResult<PlayerStatsInfo> stats(ServerPlayerEntity player) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		List<StatusEffectInfo> effects = new ArrayList<>();
		Collection<StatusEffectInstance> activeEffects = player.getStatusEffects();
		for (StatusEffectInstance effect : activeEffects) {
			effects.add(new StatusEffectInfo(
				Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString(),
				effect.getAmplifier() + 1,
				effect.getDuration()
			));
		}
		return ServiceResult.ok(new PlayerStatsInfo(
			player.getHealth(),
			player.getMaxHealth(),
			player.getAbsorptionAmount(),
			player.getArmor(),
			player.getHungerManager().getFoodLevel(),
			player.getHungerManager().getSaturationLevel(),
			player.experienceLevel,
			player.experienceProgress,
			effects
		));
	}

	static ServiceResult<BuildSiteInfo> buildsite(ServerPlayerEntity player, int requestedRadius) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		VoxelBuildPlanner.BuildSiteDetails details = VoxelBuildPlanner.inspectBuildSite(player, requestedRadius);
		if (details == null) {
			return ServiceResult.error("UNAVAILABLE", "Build site summary is unavailable.");
		}
		List<SurfaceCountInfo> counts = new ArrayList<>();
		for (VoxelBuildPlanner.SurfaceCount count : details.surfaceCounts()) {
			counts.add(new SurfaceCountInfo(count.blockId(), count.count()));
		}
		return ServiceResult.ok(new BuildSiteInfo(
			details.radius(),
			details.minDy(),
			details.maxDy(),
			details.clearPercent(),
			details.waterColumns(),
			details.totalColumns(),
			counts
		));
	}

	static ServiceResult<RecipeInfo> recipe(ServerPlayerEntity player, String itemToken, boolean smeltOnly) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		if (itemToken == null || itemToken.isBlank()) {
			return ServiceResult.error("INVALID_ARGUMENT", "Missing item token.");
		}
		ItemStack outputStack = resolveItemStack(itemToken);
		if (outputStack == null || outputStack.isEmpty()) {
			List<String> suggestions = findRegistryMatches(Registries.ITEM.getIds(), List.of(itemToken), 5);
			String message = suggestions.isEmpty()
				? "Unknown item: " + itemToken
				: "Unknown item: " + itemToken + ". Suggestions: " + String.join(", ", suggestions);
			return ServiceResult.error("UNKNOWN_ITEM", message);
		}
		RecipeManager manager = player.getServerWorld().getRecipeManager();
		List<String> recipes = new ArrayList<>();
		collectRecipesForAllTypes(manager, player, outputStack, smeltOnly, recipes);
		return ServiceResult.ok(new RecipeInfo(
			Registries.ITEM.getId(outputStack.getItem()).toString(),
			smeltOnly,
			recipes
		));
	}

	static ServiceResult<ItemInspection> itemLookup(ServerPlayerEntity player, String target) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		ItemStack stack = resolveInspectionTarget(player, target);
		if (!isStackPresent(stack)) {
			return ServiceResult.error("EMPTY_ITEM_TARGET", "The requested item target is empty.");
		}
		TooltipType type = player.isCreative() ? TooltipType.ADVANCED : TooltipType.BASIC;
		List<String> lines = new ArrayList<>();
		for (Text line : stack.getTooltip(Item.TooltipContext.DEFAULT, player, type)) {
			String text = line.getString();
			if (text != null && !text.isBlank()) {
				lines.add(text.trim());
			}
			if (lines.size() >= 12) {
				break;
			}
		}
		return ServiceResult.ok(new ItemInspection(
			normalizeInspectionLabel(target),
			Registries.ITEM.getId(stack.getItem()).toString(),
			stack.getCount(),
			summarizeComponents(stack.getComponents().toString()),
			lines
		));
	}

	static ServiceResult<ItemInspection> itemComponents(ServerPlayerEntity player, String target) {
		if (player == null) {
			return ServiceResult.error("NO_ACTIVE_PLAYER", "No active player is available.");
		}
		ItemStack stack = resolveInspectionTarget(player, target);
		if (!isStackPresent(stack)) {
			return ServiceResult.error("EMPTY_ITEM_TARGET", "The requested item target is empty.");
		}
		return ServiceResult.ok(new ItemInspection(
			normalizeInspectionLabel(target),
			Registries.ITEM.getId(stack.getItem()).toString(),
			stack.getCount(),
			summarizeComponents(stack.getComponents().toString()),
			List.of()
		));
	}

	static String formatInventory(InventorySummary summary) {
		if (summary == null || summary.empty()) {
			return "Inventory: Empty";
		}
		List<String> entries = new ArrayList<>();
		for (InventoryItem item : summary.items()) {
			StringBuilder entry = new StringBuilder();
			entry.append(item.slotLabel()).append("[").append(item.slot()).append("] ")
				.append(item.itemId()).append(" x").append(item.count());
			if (item.components() != null && !item.components().isBlank()) {
				entry.append(" components=").append(item.components());
			}
			entries.add(entry.toString());
		}
		return "Inventory: " + String.join(", ", entries);
	}

	static String formatNearby(NearbySummary summary) {
		if (summary == null || summary.groups().isEmpty()) {
			return "Nearby entities: none";
		}
		List<String> entries = new ArrayList<>();
		for (NearbyEntityGroup group : summary.groups()) {
			String entry = group.count() + " " + group.label();
			if (group.hostile()) {
				entry += " (nearest: " + Math.round(group.nearestDistance()) + " blocks)";
			}
			entries.add(entry);
		}
		return "Nearby entities: " + String.join(", ", entries);
	}

	static String formatBlocks(BlockScanInfo info) {
		if (info == null) {
			return "Blocks: unavailable";
		}
		if (info.totalMatches() == 0) {
			return "Blocks " + info.target() + " within " + info.radius() + " blocks: none";
		}
		List<String> entries = new ArrayList<>();
		for (BlockMatchInfo match : info.matches()) {
			String entry = match.blockId() + " @ " + match.x() + "," + match.y() + "," + match.z()
				+ " (" + formatDistance(match.distance()) + ")";
			if (match.state() != null && !match.state().isBlank()) {
				entry += " {" + match.state() + "}";
			}
			if (match.blockEntityId() != null && !match.blockEntityId().isBlank()) {
				entry += " be=" + match.blockEntityId();
			}
			entries.add(entry);
		}
		StringBuilder out = new StringBuilder();
		out.append("Blocks ").append(info.target()).append(" within ").append(info.radius()).append(" blocks");
		if (info.clamped()) {
			out.append(" (clamped)");
		}
		out.append(": total ").append(info.totalMatches()).append(", showing ").append(info.matches().size()).append(": ");
		out.append(String.join("; ", entries));
		return out.toString();
	}

	static String formatContainers(ContainerScanInfo info) {
		if (info == null) {
			return "Containers: unavailable";
		}
		if (info.totalMatches() == 0) {
			return "Containers within " + info.radius() + " blocks: none";
		}
		List<String> entries = new ArrayList<>();
		for (ContainerMatchInfo match : info.matches()) {
			String entry = match.blockId() + " @ " + match.x() + "," + match.y() + "," + match.z()
				+ " (" + formatDistance(match.distance()) + ")";
			if (match.summary() != null && !match.summary().isBlank()) {
				entry += " items: " + match.summary();
			}
			entries.add(entry);
		}
		StringBuilder out = new StringBuilder();
		out.append("Containers within ").append(info.radius()).append(" blocks");
		if (info.clamped()) {
			out.append(" (clamped)");
		}
		out.append(": total ").append(info.totalMatches()).append(", showing ").append(info.matches().size()).append(": ");
		out.append(String.join("; ", entries));
		return out.toString();
	}

	static String formatBlockData(BlockDataInfo info) {
		if (info == null) {
			return "BlockData: unavailable";
		}
		String entry = "BlockData " + info.blockEntityId() + " @ " + info.x() + "," + info.y() + "," + info.z() + ": " + info.nbt();
		if (info.inventorySummary() != null && !info.inventorySummary().isBlank()) {
			entry += " | items: " + info.inventorySummary();
		}
		return entry;
	}

	static String formatPlayers(PlayerListInfo info) {
		if (info == null || info.players().isEmpty()) {
			return "Players: none";
		}
		List<String> entries = new ArrayList<>();
		for (PlayerInfo player : info.players()) {
			entries.add(player.name() + " @ " + player.x() + "," + player.y() + "," + player.z() + " (" + player.dimension() + ")");
			if (entries.size() >= 12) {
				break;
			}
		}
		if (info.players().size() > entries.size()) {
			entries.add("+" + (info.players().size() - entries.size()) + " more");
		}
		return "Players: " + String.join(", ", entries);
	}

	static String formatStats(PlayerStatsInfo info) {
		if (info == null) {
			return "Stats: unknown";
		}
		List<String> effects = new ArrayList<>();
		for (StatusEffectInfo effect : info.effects()) {
			effects.add(effect.id() + " x" + effect.amplifier() + " (" + effect.durationTicks() + "t)");
		}
		String effectsText = effects.isEmpty() ? "none" : String.join(", ", effects);
		return "Stats: health " + formatStat(info.health()) + "/" + formatStat(info.maxHealth())
			+ " absorption " + formatStat(info.absorption())
			+ " armor " + info.armor()
			+ " food " + info.food()
			+ " saturation " + formatStat(info.saturation())
			+ " xp level " + info.xpLevel()
			+ " xp progress " + String.format(Locale.ROOT, "%.2f", info.xpProgress())
			+ " effects [" + effectsText + "]";
	}

	static String formatBuildsite(BuildSiteInfo info) {
		if (info == null) {
			return "BuildSite: unavailable";
		}
		List<String> topSurfaceSummary = new ArrayList<>();
		for (SurfaceCountInfo surface : info.surfaceCounts()) {
			topSurfaceSummary.add(surface.blockId() + "=" + surface.count());
		}
		return "BuildSite radius " + info.radius()
			+ ": use relative coordinates where player block position is 0,0,0. "
			+ "Ground range y=" + info.minDy() + ".." + info.maxDy() + " relative. "
			+ "Headroom clear in first 6 blocks above ground: " + info.clearPercent() + "%. "
			+ "Surface sample: " + String.join(", ", topSurfaceSummary) + ". "
			+ "Water columns: " + info.waterColumns() + "/" + info.totalColumns() + ".";
	}

	static String formatRecipe(RecipeInfo info, String requestedToken) {
		if (info == null) {
			return "Recipe: unknown";
		}
		if (info.recipes().isEmpty()) {
			return "Recipes for " + requestedToken + ": none found";
		}
		return "Recipes for " + requestedToken + ": " + String.join(" | ", info.recipes());
	}

	static String formatItemLookup(ItemInspection info) {
		if (info == null) {
			return "Lookup: empty";
		}
		StringBuilder entry = new StringBuilder();
		entry.append("Lookup ").append(info.label()).append(": ").append(info.itemId()).append(" x").append(info.count());
		if (!info.tooltip().isEmpty()) {
			entry.append(" | Tooltip: ").append(String.join(" / ", info.tooltip()));
		}
		return entry.toString();
	}

	static String formatItemComponents(ItemInspection info) {
		if (info == null) {
			return "NBT: empty";
		}
		StringBuilder entry = new StringBuilder();
		entry.append("NBT ").append(info.label()).append(": ").append(info.itemId()).append(" x").append(info.count());
		if (info.components() != null && !info.components().isBlank() && !info.components().equals("[]")) {
			entry.append(" components=").append(info.components());
		}
		return entry.toString();
	}

	private static PlayerInfo playerInfo(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		return new PlayerInfo(
			player.getName().getString(),
			player.getUuidAsString(),
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			player.getWorld().getRegistryKey().getValue().toString()
		);
	}

	private static ItemStack resolveInspectionTarget(ServerPlayerEntity player, String target) {
		String normalized = normalizeInspectionLabel(target);
		if ("mainhand".equals(normalized)) {
			return player.getMainHandStack();
		}
		if ("offhand".equals(normalized)) {
			return player.getOffHandStack();
		}
		if (normalized.startsWith("slot")) {
			String index = normalized.length() > 4 ? normalized.substring(4).trim() : "";
			if (index.isBlank()) {
				return ItemStack.EMPTY;
			}
			return stackAtSlot(player, parseSlotIndex(index));
		}
		return stackAtSlot(player, parseSlotIndex(normalized));
	}

	private static String normalizeInspectionLabel(String label) {
		if (label == null || label.isBlank()) {
			return "mainhand";
		}
		return label.toLowerCase(Locale.ROOT).trim();
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

	private static BlockDataInfo readBlockData(ServerPlayerEntity player, BlockPos pos) {
		ServerWorld world = player.getServerWorld();
		BlockEntity entity = world.getBlockEntity(pos);
		if (entity == null) {
			return new BlockDataInfo("", pos.getX(), pos.getY(), pos.getZ(), "no block entity", "");
		}
		String id = Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).toString();
		NbtCompound nbt = new NbtCompound();
		try {
			nbt = entity.createNbt(player.getRegistryManager());
		} catch (Throwable ignored) {
		}
		String summary = "";
		if (entity instanceof Inventory inventory) {
			summary = summarizeInventory(inventory);
		}
		return new BlockDataInfo(id, pos.getX(), pos.getY(), pos.getZ(), truncate(nbt.asString(), 900), summary);
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

	private static BlockScan scanForBlocks(ServerPlayerEntity player, BlockTarget target, int requestedRadius, int maxPositions, int maxResults) {
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
		return new BlockScan(matches, total, radius, clamped);
	}

	private static ContainerScan scanForContainers(ServerPlayerEntity player, BlockTarget target, int requestedRadius, int maxPositions, int maxResults) {
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
		return new ContainerScan(matches, total, radius, clamped);
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
		String resolved = idText.contains(":") ? idText : "minecraft:" + idText;
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
		String normalized = idText.contains(" ") ? idText.replace(' ', '_') : idText;
		String candidate = normalized.contains(":") ? normalized : "minecraft:" + normalized;
		Identifier candidateId = Identifier.tryParse(candidate);
		if (candidateId != null && Registries.BLOCK.containsId(candidateId)) {
			return candidate;
		}
		if (normalized.endsWith("s")) {
			String singular = normalized.substring(0, normalized.length() - 1);
			String singularCandidate = singular.contains(":") ? singular : "minecraft:" + singular;
			Identifier singularId = Identifier.tryParse(singularCandidate);
			if (singularId != null && Registries.BLOCK.containsId(singularId)) {
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

	private static List<String> findRegistryMatches(Iterable<Identifier> ids, List<String> tokens, int limit) {
		List<String> matches = new ArrayList<>();
		for (Identifier id : ids) {
			String value = id.toString();
			String path = id.getPath().toLowerCase(Locale.ROOT);
			String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
			for (String token : tokens) {
				if (path.contains(token) || namespace.contains(token) || value.contains(token)) {
					matches.add(value);
					if (matches.size() >= limit) {
						return matches;
					}
					break;
				}
			}
		}
		return matches;
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
			} catch (Exception ignored) {
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
		return typeId + ":" + entry.id() + " -> " + resultId + " x" + result.getCount() + " [" + formatIngredients(recipe) + "]";
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
		if (stack == null || stack.getCount() <= 0) {
			return false;
		}
		return stack.getItem() != Items.AIR;
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

	private static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		if (text.length() <= max) {
			return text;
		}
		return text.substring(0, Math.max(0, max - 3)) + "...";
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

	private static String formatDistance(double distance) {
		return String.format(Locale.ROOT, "%.1f", distance);
	}

	private static String formatStat(float value) {
		return String.format(Locale.ROOT, "%.1f", value);
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

	record ServiceError(String code, String message) {}

	record ServiceResult<T>(T data, ServiceError error) {
		static <T> ServiceResult<T> ok(T data) {
			return new ServiceResult<>(data, null);
		}

		static <T> ServiceResult<T> error(String code, String message) {
			return new ServiceResult<>(null, new ServiceError(code, message));
		}
	}

	record ActivePlayerContext(PlayerInfo player, ServerPlayerEntity serverPlayer) {}
	record SessionInfo(boolean enabled, int port, boolean loopbackOnly, boolean singleplayer, String worldName, PlayerInfo activePlayer) {}
	record PlayerInfo(String name, String uuid, int x, int y, int z, String dimension) {}
	record InventoryItem(String slotLabel, int slot, String itemId, int count, String components) {}
	record InventorySummary(List<InventoryItem> items, int filledSlots, int totalSlots, boolean empty) {}
	record NearbyEntityGroup(String label, boolean hostile, int count, double nearestDistance) {}
	record NearbySummary(double radius, List<NearbyEntityGroup> groups) {}
	record BlockMatchInfo(String blockId, int x, int y, int z, double distance, String state, String blockEntityId) {}
	record BlockScanInfo(String target, int radius, boolean clamped, int totalMatches, List<BlockMatchInfo> matches) {}
	record ContainerMatchInfo(String blockId, int x, int y, int z, double distance, String summary) {}
	record ContainerScanInfo(String filter, int radius, boolean clamped, int totalMatches, List<ContainerMatchInfo> matches) {}
	record BlockDataInfo(String blockEntityId, int x, int y, int z, String nbt, String inventorySummary) {}
	record PlayerListInfo(List<PlayerInfo> players) {}
	record StatusEffectInfo(String id, int amplifier, int durationTicks) {}
	record PlayerStatsInfo(float health, float maxHealth, float absorption, int armor, int food, float saturation, int xpLevel, float xpProgress, List<StatusEffectInfo> effects) {}
	record SurfaceCountInfo(String blockId, int count) {}
	record BuildSiteInfo(int radius, int minDy, int maxDy, int clearPercent, int waterColumns, int totalColumns, List<SurfaceCountInfo> surfaceCounts) {}
	record RecipeInfo(String itemId, boolean smeltOnly, List<String> recipes) {}
	record ItemInspection(String label, String itemId, int count, String components, List<String> tooltip) {}

	private static final class MutableEntityGroup {
		private final String label;
		private final boolean hostile;
		private int count;
		private double nearestDistance;

		private MutableEntityGroup(String label, boolean hostile, double nearestDistance) {
			this.label = label;
			this.hostile = hostile;
			this.nearestDistance = nearestDistance;
		}
	}

	private record BlockMatch(BlockPos pos, BlockState state, double distance) {}
	private record BlockScan(List<BlockMatch> matches, int totalMatches, int radius, boolean clamped) {}
	private record ContainerMatch(BlockPos pos, BlockState state, double distance, String summary) {}
	private record ContainerScan(List<ContainerMatch> matches, int totalMatches, int radius, boolean clamped) {}
	private record BlockTarget(String label, Block block, TagKey<Block> tag, boolean isTag) {
		private boolean matches(BlockState state) {
			if (state == null) {
				return false;
			}
			if (isTag) {
				return tag != null && state.isIn(tag);
			}
			return block != null && state.isOf(block);
		}
	}
}
