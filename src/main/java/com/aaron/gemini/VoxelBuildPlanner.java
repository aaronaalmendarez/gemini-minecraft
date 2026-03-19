package com.aaron.gemini;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.Registries;

final class VoxelBuildPlanner {
	static final int DEFAULT_SITE_RADIUS = 12;
	private static final int MAX_SITE_RADIUS = 24;
	private static final int MAX_BUILD_CUBOIDS = 96;
	private static final int MAX_BUILD_BLOCKS = 384;
	private static final int MAX_TOTAL_VOLUME = 32_768;
	private static final int MAX_HORIZONTAL_OFFSET = 32;
	private static final int MAX_VERTICAL_OFFSET = 24;
	private static final int MAX_SITE_SCAN_DOWN = 16;
	private static final int MAX_SITE_SCAN_UP = 6;
	private static final int MAX_SITE_SCAN_HEIGHT = 6;
	private static final int MAX_AUTO_FOUNDATION_COLUMNS = 24;
	private static final int MAX_AUTO_FIX_SHIFT = 3;
	private static final String DEFAULT_FOUNDATION_BLOCK = "minecraft:stone_bricks";
	private static final Map<String, StoredAnchorSet> STORED_ANCHORS = new ConcurrentHashMap<>();
	private static final String LAST_BUILD_KEY = "last_build";

	private VoxelBuildPlanner() {
	}

	static BuildPlan parseBuildPlan(JsonObject obj) {
		if (obj == null) {
			return null;
		}
		if (obj.has("build_plan") && obj.get("build_plan").isJsonObject()) {
			return parsePlanObject(obj.getAsJsonObject("build_plan"), null);
		}
		return parsePlanObject(obj, null);
	}

	private static BuildPlan parsePlanObject(JsonObject planObj, BuildPlan defaults) {
		if (planObj == null) {
			return null;
		}
		String summary = firstString(planObj, "label", "summary", "description", "message");
		if ((summary == null || summary.isBlank()) && defaults != null) {
			summary = defaults.summary();
		}
		String anchor = firstString(planObj, "anchor");
		if ((anchor == null || anchor.isBlank()) && defaults != null) {
			anchor = defaults.anchor();
		}
		String coordMode = normalizeCoordMode(firstString(planObj, "coordMode", "coordinateMode"), defaults == null ? "player" : defaults.coordMode());
		int rotation = parseRotation(planObj, defaults == null ? 0 : defaults.rotationDegrees());
		JsonObject optionsObj = planObj.has("options") && planObj.get("options").isJsonObject() ? planObj.getAsJsonObject("options") : null;
		if (optionsObj != null && !hasRotation(planObj)) {
			rotation = parseRotation(optionsObj, rotation);
		}
		int version = readInt(planObj, "version", defaults == null ? 1 : defaults.version());
		GridPoint origin = parsePoint(planObj, "origin");
		if (origin == null && defaults != null) {
			origin = defaults.origin();
		}
		GridPoint offset = parsePoint(planObj, "offset");
		if (offset == null && defaults != null) {
			offset = defaults.offset();
		}
		boolean autoFix = defaults == null || defaults.autoFix();
		if (planObj.has("autoFix") && planObj.get("autoFix").isJsonPrimitive()) {
			autoFix = planObj.get("autoFix").getAsBoolean();
		}
		boolean snapToGround = readBoolean(planObj, "snapToGround", defaults != null && defaults.snapToGround());
		boolean flattenTerrain = readBoolean(planObj, "flattenTerrain", defaults != null && defaults.flattenTerrain());
		boolean clearVegetation = readBoolean(planObj, "clearVegetation", defaults != null && defaults.clearVegetation());
		boolean phaseReorder = readBoolean(optionsObj, "phaseReorder", defaults != null && defaults.phaseReorder());
		boolean dryRun = readBoolean(optionsObj, "dryRun", defaults != null && defaults.dryRun());
		boolean batchUndo = readBoolean(optionsObj, "batchUndo", defaults == null || defaults.batchUndo());
		boolean rotateBlockStates = readBoolean(optionsObj, "rotateBlockStates", defaults == null || defaults.rotateBlockStates());

		Map<String, String> palette = new LinkedHashMap<>();
		if (defaults != null && defaults.palette() != null) {
			palette.putAll(defaults.palette());
		}
		palette.putAll(parsePalette(planObj));
		Map<String, GridPoint> anchors = new LinkedHashMap<>();
		if (defaults != null && defaults.anchors() != null) {
			anchors.putAll(defaults.anchors());
		}
		anchors.putAll(parseAnchors(planObj));
		List<Volume> clearVolumes = new ArrayList<>();
		List<Cuboid> cuboids = new ArrayList<>();
		List<BlockPlacement> blocks = new ArrayList<>();

		parseVolumeArray(planObj, "clear", clearVolumes);
		parseCuboidArray(planObj, "cuboids", cuboids);
		parseBlockArray(planObj, "blocks", blocks);

		for (var entry : planObj.entrySet()) {
			String key = entry.getKey();
			if (isReservedTopLevelField(key)) {
				continue;
			}
			JsonElement value = entry.getValue();
			if (value == null || value.isJsonNull()) {
				continue;
			}
			if (value.isJsonObject()) {
				JsonObject child = value.getAsJsonObject();
				if (!isLikelyImplicitGeometryKey(key, child)) {
					continue;
				}
				Cuboid cuboid = parseCuboid(child, key);
				if (cuboid != null) {
					cuboids.add(cuboid);
					continue;
				}
				BlockPlacement block = parseBlock(child, key);
				if (block != null) {
					blocks.add(block);
				}
				continue;
			}
			if (value.isJsonArray()) {
				JsonArray array = value.getAsJsonArray();
				for (JsonElement element : array) {
					if (!element.isJsonObject()) {
						continue;
					}
					JsonObject child = element.getAsJsonObject();
					if (!isLikelyImplicitGeometryKey(key, child) && !looksLikeGeometryObject(child)) {
						continue;
					}
					Cuboid cuboid = parseCuboid(child, key);
					if (cuboid != null) {
						cuboids.add(cuboid);
						continue;
					}
					BlockPlacement block = parseBlock(child, key);
					if (block != null) {
						blocks.add(block);
					}
				}
			}
		}

		List<BuildStep> steps = parseStepArray(planObj, new BuildPlan(summary, version, anchor, coordMode, origin, offset, rotation, autoFix, snapToGround, flattenTerrain, clearVegetation, phaseReorder, dryRun, batchUndo, rotateBlockStates, palette, anchors, List.of(), List.of(), List.of(), List.of()));
		return new BuildPlan(summary, version, anchor, coordMode, origin, offset, rotation, autoFix, snapToGround, flattenTerrain, clearVegetation, phaseReorder, dryRun, batchUndo, rotateBlockStates, palette, anchors, clearVolumes, cuboids, blocks, steps);
	}

	static CompiledBuild compile(ServerPlayerEntity player, BuildPlan plan) {
		return compile(player, plan, 0);
	}

	private static CompiledBuild compile(ServerPlayerEntity player, BuildPlan plan, int autoFixPass) {
		if (player == null) {
			return new CompiledBuild(false, List.of(), "No build executed.", List.of(), "Build plans require a player context.", 0, 0, new GridPoint(0, 0, 0), List.of(), false);
		}
		if (plan == null) {
			return new CompiledBuild(false, List.of(), "No build executed.", List.of(), "Missing build_plan.", 0, 0, toGridPoint(player.getBlockPos()), List.of(), false);
		}
		String originError = validateOriginRequirements(plan);
		if (originError != null) {
			return new CompiledBuild(false, List.of(), "No build executed.", List.of(), originError, 0, 0, toGridPoint(player.getBlockPos()), List.of(), false);
		}

		List<String> repairs = new ArrayList<>();
		BlockPos resolvedOrigin = resolveOrigin(player, plan, repairs);
		CompileAccumulator accumulator = new CompileAccumulator(resolvedOrigin);
		applyTerrainAdjustments(player.getServerWorld(), plan, resolvedOrigin, repairs, accumulator);
		resolvedOrigin = accumulator.resolvedOrigin;
		CompiledBuild failure = compilePlanInto(player, plan, resolvedOrigin, repairs, accumulator);
		if (failure != null) {
			return failure;
		}
		SupportRepair supportRepair = repairSupportColumns(player.getServerWorld(), accumulator, plan.autoFix());
		if (supportRepair.autoShiftDown() > 0) {
			BuildPlan shiftedPlan = shiftWholePlan(plan, -supportRepair.autoShiftDown());
			List<String> prefixRepairs = List.of("Auto-lowered the build by " + supportRepair.autoShiftDown() + " block(s) to rest on nearby terrain.");
			return prependRepairs(compile(player, shiftedPlan, autoFixPass + 1), prefixRepairs);
		}
		if (!supportRepair.valid()
				&& plan.autoFix()
				&& autoFixPass < 2
				&& !supportRepair.issues().isEmpty()
				&& supportRepair.issues().stream().allMatch(issue -> "floating".equalsIgnoreCase(issue.issue()))) {
			List<String> autoFixRepairs = new ArrayList<>();
			BuildPlan shiftedPlan = shiftPlanForIssues(plan, supportRepair.issues(), autoFixRepairs);
			if (shiftedPlan != null) {
				return prependRepairs(compile(player, shiftedPlan, autoFixPass + 1), autoFixRepairs);
			}
		}
		if (!supportRepair.valid()) {
			return new CompiledBuild(false, List.of(), "No build executed.", repairs, supportRepair.error(), accumulator.appliedRotation, Math.max(1, accumulator.phases), toGridPoint(resolvedOrigin), supportRepair.issues(), supportRepair.autoFixAvailable());
		}
		repairs.addAll(supportRepair.repairs());
		List<String> commands = new ArrayList<>(supportRepair.commands());
		commands.addAll(accumulator.commands);
		if (commands.isEmpty()) {
			return new CompiledBuild(false, List.of(), "No build executed.", repairs,
					"Build plan did not contain any valid clear volumes, cuboids, or blocks.", accumulator.appliedRotation, Math.max(1, accumulator.phases), toGridPoint(resolvedOrigin), supportRepair.issues(), supportRepair.autoFixAvailable());
		}

		String summary = plan.summary() == null || plan.summary().isBlank()
				? "Executing structured build plan."
				: plan.summary().trim();
		if (accumulator.phases > 1) {
			summary = summary + " (" + accumulator.phases + " phases)";
		}
		return new CompiledBuild(true, commands, summary, repairs, "", accumulator.appliedRotation, Math.max(1, accumulator.phases), toGridPoint(resolvedOrigin), supportRepair.issues(), supportRepair.autoFixAvailable());
	}

	private static String validateOriginRequirements(BuildPlan plan) {
		String coordMode = normalizeCoordMode(plan.coordMode(), "player");
		if ("absolute".equals(coordMode) && plan.origin() == null) {
			return "coordMode=absolute requires an explicit origin.";
		}
		if ("anchor".equals(coordMode) && (plan.anchor() == null || plan.anchor().isBlank())) {
			return "coordMode=anchor requires an anchor reference such as last_build:door.";
		}
		return null;
	}

	private static CompiledBuild prependRepairs(CompiledBuild compiled, List<String> prefixRepairs) {
		if (compiled == null || prefixRepairs == null || prefixRepairs.isEmpty()) {
			return compiled;
		}
		List<String> mergedRepairs = new ArrayList<>(prefixRepairs);
		mergedRepairs.addAll(compiled.repairs());
		return new CompiledBuild(
				compiled.valid(),
				compiled.commands(),
				compiled.summary(),
				mergedRepairs,
				compiled.error(),
				compiled.appliedRotation(),
				compiled.phases(),
				compiled.resolvedOrigin(),
				compiled.issues(),
				compiled.autoFixAvailable()
		);
	}

	static String summarizeBuildSite(ServerPlayerEntity player, int requestedRadius) {
		BuildSiteDetails details = inspectBuildSite(player, requestedRadius);
		if (details == null) {
			return "BuildSite: unavailable";
		}
		List<String> topSurfaceSummary = new ArrayList<>();
		for (SurfaceCount surface : details.surfaceCounts()) {
			topSurfaceSummary.add(surface.blockId() + "=" + surface.count());
		}
		return "BuildSite radius " + details.radius()
				+ ": use relative coordinates where player block position is 0,0,0. "
				+ "Ground range y=" + details.minDy() + ".." + details.maxDy() + " relative. "
				+ "Headroom clear in first " + MAX_SITE_SCAN_HEIGHT + " blocks above ground: " + details.clearPercent() + "%. "
				+ "Surface sample: " + String.join(", ", topSurfaceSummary) + ". "
				+ "Water columns: " + details.waterColumns() + "/" + details.totalColumns() + ".";
	}

	static BuildSiteDetails inspectBuildSite(ServerPlayerEntity player, int requestedRadius) {
		if (player == null) {
			return null;
		}
		ServerWorld world = player.getServerWorld();
		BlockPos center = player.getBlockPos();
		int radius = Math.max(4, Math.min(MAX_SITE_RADIUS, requestedRadius <= 0 ? DEFAULT_SITE_RADIUS : requestedRadius));
		int baseY = center.getY();
		int columns = 0;
		int clearCells = 0;
		int minDy = Integer.MAX_VALUE;
		int maxDy = Integer.MIN_VALUE;
		Map<String, Integer> surfaceCounts = new LinkedHashMap<>();
		int waterColumns = 0;

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				columns++;
				BlockPos surface = findSurface(world, center.add(dx, 0, dz), baseY);
				int dy = surface.getY() - baseY;
				minDy = Math.min(minDy, dy);
				maxDy = Math.max(maxDy, dy);
				String surfaceId = Registries.BLOCK.getId(world.getBlockState(surface).getBlock()).toString();
				surfaceCounts.merge(surfaceId, 1, Integer::sum);
				if (surfaceId.contains("water")) {
					waterColumns++;
				}
				boolean clear = true;
				for (int y = 1; y <= MAX_SITE_SCAN_HEIGHT; y++) {
					if (!world.getBlockState(surface.up(y)).isAir()) {
						clear = false;
						break;
					}
				}
				if (clear) {
					clearCells++;
				}
			}
		}

		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(surfaceCounts.entrySet());
		sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		List<SurfaceCount> topSurfaceSummary = new ArrayList<>();
		for (int i = 0; i < Math.min(4, sorted.size()); i++) {
			Map.Entry<String, Integer> entry = sorted.get(i);
			topSurfaceSummary.add(new SurfaceCount(entry.getKey(), entry.getValue()));
		}

		int clearPercent = columns == 0 ? 0 : (int) Math.round((clearCells * 100.0) / columns);
		return new BuildSiteDetails(
				radius,
				minDy == Integer.MAX_VALUE ? 0 : minDy,
				maxDy == Integer.MIN_VALUE ? 0 : maxDy,
				clearPercent,
				waterColumns,
				columns,
				topSurfaceSummary
		);
	}

	private static boolean isReservedTopLevelField(String key) {
		return switch (key) {
			case "label", "version", "summary", "description", "message", "anchor", "anchors", "coordMode", "coordinateMode", "origin", "offset", "autoFix", "snapToGround", "flattenTerrain", "clearVegetation", "palette", "clear", "cuboids", "blocks", "steps", "rotate", "rotation", "options" -> true;
			default -> false;
		};
	}

	private static List<BuildStep> parseStepArray(JsonObject obj, BuildPlan defaults) {
		List<BuildStep> steps = new ArrayList<>();
		if (obj == null || !obj.has("steps") || !obj.get("steps").isJsonArray()) {
			return steps;
		}
		for (JsonElement element : obj.getAsJsonArray("steps")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject stepObj = element.getAsJsonObject();
			String semanticType = firstString(stepObj, "type");
			if (semanticType != null && !semanticType.isBlank()) {
				BuildStep semantic = parseSemanticStep(stepObj, defaults);
				if (semantic != null) {
					steps.add(semantic);
				}
				continue;
			}
			String phase = firstString(stepObj, "phase", "name", "label", "step");
			JsonObject planSource = stepObj.has("plan") && stepObj.get("plan").isJsonObject()
					? stepObj.getAsJsonObject("plan")
					: stepObj;
			BuildPlan stepPlan = parsePlanObject(planSource, defaults);
			if (stepPlan == null) {
				continue;
			}
			String summary = stepPlan.summary();
			if ((summary == null || summary.isBlank()) && phase != null && !phase.isBlank()) {
				summary = phase;
			}
			steps.add(new BuildStep(phase == null || phase.isBlank() ? "phase" : phase, new BuildPlan(
					summary,
					stepPlan.version(),
					stepPlan.anchor(),
					stepPlan.coordMode(),
					stepPlan.origin(),
					stepPlan.offset(),
					stepPlan.rotationDegrees(),
					stepPlan.autoFix(),
					stepPlan.snapToGround(),
					stepPlan.flattenTerrain(),
					stepPlan.clearVegetation(),
					stepPlan.phaseReorder(),
					stepPlan.dryRun(),
					stepPlan.batchUndo(),
					stepPlan.rotateBlockStates(),
					stepPlan.palette(),
					stepPlan.anchors(),
					stepPlan.clearVolumes(),
					stepPlan.cuboids(),
					stepPlan.blocks(),
					stepPlan.steps()
			)));
		}
		return steps;
	}

	private static BuildStep parseSemanticStep(JsonObject stepObj, BuildPlan defaults) {
		String type = firstString(stepObj, "type");
		if (type == null || type.isBlank()) {
			return null;
		}
		String label = firstString(stepObj, "label", "name", "phase", "step");
		if (label == null || label.isBlank()) {
			label = type;
		}
		String normalized = type.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "cuboid", "fill" -> semanticSingleCuboidStep(stepObj, defaults, label, false);
			case "hollow_cuboid" -> semanticSingleCuboidStep(stepObj, defaults, label, true);
			case "columns" -> semanticColumnsStep(stepObj, defaults, label);
			case "blocks" -> semanticBlocksStep(stepObj, defaults, label);
			case "windows" -> semanticWindowsStep(stepObj, defaults, label);
			case "repeat" -> semanticRepeatStep(stepObj, defaults, label);
			case "scatter" -> semanticScatterStep(stepObj, defaults, label);
			case "roof" -> semanticRoofStep(stepObj, defaults, label);
			default -> null;
		};
	}

	private static boolean isLikelyImplicitGeometryKey(String key, JsonObject child) {
		String lower = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
		if (lower.isBlank()) {
			return child != null && looksLikeGeometryObject(child);
		}
		if (containsAny(lower, "meta", "metadata", "config", "settings", "options", "prompt", "notes", "analysis", "critique", "repair", "repairs", "constraints", "style", "theme", "story")) {
			return false;
		}
		if (containsAny(lower,
				"wall", "walls", "floor", "floors", "roof", "roofs", "foundation", "base", "pillar", "column",
				"beam", "window", "windows", "door", "doors", "stairs", "stair", "room", "rooms", "tower",
				"detail", "details", "decor", "decoration", "feature", "features", "placement", "placements",
				"part", "parts", "element", "elements", "structure", "structures", "support", "supports",
				"block", "blocks", "cuboid", "cuboids", "clear", "volume", "volumes"
		)) {
			return true;
		}
		return child != null && looksLikeGeometryObject(child);
	}

	private static boolean looksLikeGeometryObject(JsonObject obj) {
		if (obj == null) {
			return false;
		}
		boolean hasBlock = firstString(obj, "block", "material", "id") != null;
		boolean hasBounds = parseBounds(obj) != null;
		boolean hasPos = parsePoint(obj, "pos") != null
				|| parsePoint(obj, "location") != null
				|| hasCoordinateKeys(obj, "x", "y", "z");
		return hasBlock && (hasBounds || hasPos);
	}

	private static boolean containsAny(String text, String... needles) {
		if (text == null || text.isBlank() || needles == null) {
			return false;
		}
		for (String needle : needles) {
			if (needle != null && !needle.isBlank() && text.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, String> parsePalette(JsonObject obj) {
		Map<String, String> palette = new LinkedHashMap<>();
		if (obj == null || !obj.has("palette") || !obj.get("palette").isJsonObject()) {
			return palette;
		}
		for (var entry : obj.getAsJsonObject("palette").entrySet()) {
			JsonElement value = entry.getValue();
			if (value == null || value.isJsonNull()) {
				continue;
			}
			if (value.isJsonPrimitive()) {
				palette.put(entry.getKey(), value.getAsString());
				continue;
			}
			if (value.isJsonObject()) {
				String block = firstString(value.getAsJsonObject(), "block", "material", "id");
				if (block != null && !block.isBlank()) {
					palette.put(entry.getKey(), block);
				}
			}
		}
		return palette;
	}

	private static Map<String, GridPoint> parseAnchors(JsonObject obj) {
		Map<String, GridPoint> anchors = new LinkedHashMap<>();
		if (obj == null || !obj.has("anchors") || !obj.get("anchors").isJsonObject()) {
			return anchors;
		}
		for (var entry : obj.getAsJsonObject("anchors").entrySet()) {
			if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
				continue;
			}
			GridPoint point = parseAnchorPoint(entry.getValue().getAsJsonObject());
			if (point != null) {
				anchors.put(entry.getKey(), point);
			}
		}
		return anchors;
	}

	private static void parseVolumeArray(JsonObject obj, String key, List<Volume> out) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return;
		}
		if (obj.get(key).isJsonObject()) {
			Volume volume = parseVolume(obj.getAsJsonObject(key), key);
			if (volume != null) {
				out.add(volume);
			}
			return;
		}
		if (!obj.get(key).isJsonArray()) {
			return;
		}
		for (JsonElement element : obj.getAsJsonArray(key)) {
			if (!element.isJsonObject()) {
				continue;
			}
			Volume volume = parseVolume(element.getAsJsonObject(), key);
			if (volume != null) {
				out.add(volume);
			}
		}
	}

	private static void parseCuboidArray(JsonObject obj, String key, List<Cuboid> out) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
			return;
		}
		for (JsonElement element : obj.getAsJsonArray(key)) {
			if (!element.isJsonObject()) {
				continue;
			}
			Cuboid cuboid = parseCuboid(element.getAsJsonObject(), key);
			if (cuboid != null) {
				out.add(cuboid);
			}
		}
	}

	private static void parseBlockArray(JsonObject obj, String key, List<BlockPlacement> out) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
			return;
		}
		for (JsonElement element : obj.getAsJsonArray(key)) {
			if (!element.isJsonObject()) {
				continue;
			}
			BlockPlacement block = parseBlock(element.getAsJsonObject(), key);
			if (block != null) {
				out.add(block);
			}
		}
	}

	private static Volume parseVolume(JsonObject obj, String fallbackName) {
		if (obj == null) {
			return null;
		}
		if (obj.has("enabled") && obj.get("enabled").isJsonPrimitive() && !obj.get("enabled").getAsBoolean()) {
			return null;
		}
		Bounds bounds = parseBounds(obj);
		if (bounds == null && (obj.has("dx") || obj.has("dy") || obj.has("dz"))) {
			GridPoint size = new GridPoint(
				Math.max(1, readInt(obj, "dx", 1)),
				Math.max(1, readInt(obj, "dy", 1)),
				Math.max(1, readInt(obj, "dz", 1))
			);
			GridPoint offset = parsePoint(obj, "offset");
			if (offset == null) {
				offset = new GridPoint(0, 0, 0);
			}
			bounds = boundsFromStartAndSize(offset, size);
		}
		if (bounds == null) {
			return null;
		}
		String name = firstString(obj, "name", "label");
		if (name == null || name.isBlank()) {
			name = fallbackName;
		}
		String replaceWith = firstString(obj, "replaceWith", "block", "material", "id");
		if (replaceWith == null || replaceWith.isBlank()) {
			replaceWith = "minecraft:air";
		}
		return new Volume(name, bounds.from(), bounds.to(), replaceWith);
	}

	private static Cuboid parseCuboid(JsonObject obj, String fallbackName) {
		Bounds bounds = parseBounds(obj);
		if (bounds == null) {
			return null;
		}
		String block = firstString(obj, "block", "material", "id");
		if (block == null || block.isBlank()) {
			return null;
		}
		String name = firstString(obj, "name", "label");
		if (name == null || name.isBlank()) {
			name = fallbackName;
		}
		Map<String, String> properties = parseStringMap(obj, "properties");
		if (properties.isEmpty()) {
			properties = parseStringMap(obj, "state");
		}
		String fillMode = firstString(obj, "fill", "mode");
		Boolean hollow = obj.has("hollow") && obj.get("hollow").isJsonPrimitive()
				? obj.get("hollow").getAsBoolean()
				: null;
		return new Cuboid(name, block, properties, bounds.from(), bounds.to(), fillMode, hollow);
	}

	private static BlockPlacement parseBlock(JsonObject obj, String fallbackName) {
		GridPoint pos = parsePoint(obj, "pos");
		if (pos == null) {
			pos = parsePoint(obj, "location");
		}
		if (pos == null && hasCoordinateKeys(obj, "x", "y", "z")) {
			pos = parsePointObject(obj, "x", "y", "z");
		}
		if (pos == null) {
			return null;
		}
		String block = firstString(obj, "block", "material", "id");
		if (block == null || block.isBlank()) {
			return null;
		}
		String name = firstString(obj, "name", "label");
		if (name == null || name.isBlank()) {
			name = fallbackName;
		}
		Map<String, String> properties = parseStringMap(obj, "properties");
		if (properties.isEmpty()) {
			properties = parseStringMap(obj, "state");
		}
		return new BlockPlacement(name, block, properties, pos);
	}

	private static BuildStep semanticSingleCuboidStep(JsonObject stepObj, BuildPlan defaults, String label, boolean hollow) {
		Cuboid cuboid = parseSemanticCuboid(stepObj, label, hollow);
		return cuboid == null ? null : semanticPlanStep(label, defaults, List.of(cuboid), List.of(), List.of());
	}

	private static BuildStep semanticColumnsStep(JsonObject stepObj, BuildPlan defaults, String label) {
		String block = firstString(stepObj, "block", "material", "id");
		if (block == null || block.isBlank() || !stepObj.has("positions") || !stepObj.get("positions").isJsonArray()) {
			return null;
		}
		int baseY = readInt(stepObj, "y", 0);
		int height = Math.max(1, readInt(stepObj, "height", readInt(stepObj, "dy", 1)));
		List<Cuboid> cuboids = new ArrayList<>();
		for (JsonElement element : stepObj.getAsJsonArray("positions")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject posObj = element.getAsJsonObject();
			if (!posObj.has("x") || !posObj.has("z")) {
				continue;
			}
			int x = readInt(posObj, "x", 0);
			int z = readInt(posObj, "z", 0);
			cuboids.add(new Cuboid(label + "@" + x + "," + z, block, Map.of(), new GridPoint(x, baseY, z), new GridPoint(x, baseY + height - 1, z), "", false));
		}
		return cuboids.isEmpty() ? null : semanticPlanStep(label, defaults, cuboids, List.of(), List.of());
	}

	private static BuildStep semanticBlocksStep(JsonObject stepObj, BuildPlan defaults, String label) {
		List<BlockPlacement> blocks = new ArrayList<>();
		if (stepObj.has("entries") && stepObj.get("entries").isJsonArray()) {
			for (JsonElement element : stepObj.getAsJsonArray("entries")) {
				if (!element.isJsonObject()) {
					continue;
				}
				BlockPlacement placement = parseBlock(element.getAsJsonObject(), label);
				if (placement != null) {
					blocks.add(placement);
				}
			}
		} else {
			BlockPlacement placement = parseBlock(stepObj, label);
			if (placement != null) {
				blocks.add(placement);
			}
		}
		return blocks.isEmpty() ? null : semanticPlanStep(label, defaults, List.of(), blocks, List.of());
	}

	private static BuildStep semanticWindowsStep(JsonObject stepObj, BuildPlan defaults, String label) {
		String block = firstString(stepObj, "block", "material", "id");
		if (block == null || block.isBlank() || !stepObj.has("placements") || !stepObj.get("placements").isJsonArray()) {
			return null;
		}
		List<Cuboid> cuboids = new ArrayList<>();
		for (JsonElement element : stepObj.getAsJsonArray("placements")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject placement = element.getAsJsonObject();
			int x = readInt(placement, "x", 0);
			int y = readInt(placement, "y", 0);
			int z = readInt(placement, "z", 0);
			int dx = Math.max(1, readInt(placement, "dx", 1));
			int dy = Math.max(1, readInt(placement, "dy", 1));
			int dz = Math.max(1, readInt(placement, "dz", 1));
			String face = firstString(placement, "face");
			if (face != null && !face.isBlank()) {
				switch (face.toLowerCase(Locale.ROOT)) {
					case "north", "south" -> dz = 1;
					case "east", "west" -> dx = 1;
					default -> {
					}
				}
			}
			cuboids.add(new Cuboid(label + "@" + x + "," + y + "," + z, block, Map.of(), new GridPoint(x, y, z), new GridPoint(x + dx - 1, y + dy - 1, z + dz - 1), "", false));
		}
		return cuboids.isEmpty() ? null : semanticPlanStep(label, defaults, cuboids, List.of(), List.of());
	}

	private static BuildStep semanticRepeatStep(JsonObject stepObj, BuildPlan defaults, String label) {
		String block = firstString(stepObj, "block", "material", "id");
		GridPoint start = parsePoint(stepObj, "start");
		GridPoint step = parsePoint(stepObj, "step");
		int count = Math.max(0, readInt(stepObj, "count", 0));
		int height = Math.max(1, readInt(stepObj, "height", 1));
		if (block == null || block.isBlank() || start == null || step == null || count <= 0) {
			return null;
		}
		List<Cuboid> cuboids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int x = start.x() + (step.x() * i);
			int y = start.y() + (step.y() * i);
			int z = start.z() + (step.z() * i);
			cuboids.add(new Cuboid(label + "#" + i, block, Map.of(), new GridPoint(x, y, z), new GridPoint(x, y + height - 1, z), "", false));
		}
		return semanticPlanStep(label, defaults, cuboids, List.of(), List.of());
	}

	private static BuildStep semanticScatterStep(JsonObject stepObj, BuildPlan defaults, String label) {
		JsonObject region = stepObj.has("region") && stepObj.get("region").isJsonObject() ? stepObj.getAsJsonObject("region") : null;
		Bounds bounds = region == null ? null : parseBounds(region);
		if (bounds == null) {
			return null;
		}
		List<String> blockOptions = new ArrayList<>();
		if (stepObj.has("blocks") && stepObj.get("blocks").isJsonArray()) {
			for (JsonElement element : stepObj.getAsJsonArray("blocks")) {
				if (element != null && element.isJsonPrimitive()) {
					blockOptions.add(element.getAsString());
				}
			}
		}
		if (blockOptions.isEmpty()) {
			String block = firstString(stepObj, "block", "material", "id");
			if (block != null && !block.isBlank()) {
				blockOptions.add(block);
			}
		}
		if (blockOptions.isEmpty()) {
			return null;
		}
		double density = Math.max(0.0D, Math.min(1.0D, readDouble(stepObj, "density", 0.25D)));
		String onlyOn = parseOnlyOnFilter(stepObj);
		List<BlockPlacement> placements = new ArrayList<>();
		for (int x = bounds.from().x(); x <= bounds.to().x(); x++) {
			for (int z = bounds.from().z(); z <= bounds.to().z(); z++) {
				long sample = (((long) x) * 341873128712L) ^ (((long) z) * 132897987541L) ^ bounds.from().y();
				double roll = ((sample >>> 12) & 0xFFFF) / 65535.0D;
				if (roll > density) {
					continue;
				}
				String block = blockOptions.get((int) Math.floorMod(sample, blockOptions.size()));
				Map<String, String> props = onlyOn.isBlank() ? Map.of() : Map.of("_onlyOn", onlyOn);
				placements.add(new BlockPlacement(label + "@" + x + "," + z, block, props, new GridPoint(x, bounds.from().y(), z)));
			}
		}
		return placements.isEmpty() ? null : semanticPlanStep(label, defaults, List.of(), placements, List.of());
	}

	private static BuildStep semanticRoofStep(JsonObject stepObj, BuildPlan defaults, String label) {
		String style = firstString(stepObj, "style");
		String block = firstString(stepObj, "block", "material", "id");
		Bounds bounds = parseBounds(stepObj);
		if (bounds == null && (stepObj.has("x") || stepObj.has("y") || stepObj.has("z"))) {
			int x = readInt(stepObj, "x", 0);
			int y = readInt(stepObj, "y", 0);
			int z = readInt(stepObj, "z", 0);
			int dx = Math.max(1, readInt(stepObj, "dx", 1));
			int dy = Math.max(1, readInt(stepObj, "dy", 1));
			int dz = Math.max(1, readInt(stepObj, "dz", 1));
			bounds = boundsFromStartAndSize(new GridPoint(x, y, z), new GridPoint(dx, dy, dz));
		}
		if (style == null || style.isBlank() || block == null || block.isBlank() || bounds == null) {
			return null;
		}
		List<Cuboid> cuboids = new ArrayList<>();
		List<BlockPlacement> blocks = new ArrayList<>();
		String stairBlock = firstString(stepObj, "stairBlock");
		String slabBlock = firstString(stepObj, "slabBlock");
		switch (style.trim().toLowerCase(Locale.ROOT)) {
			case "flat" -> cuboids.add(new Cuboid(label, slabBlock != null && !slabBlock.isBlank() ? slabBlock : block, Map.of(), bounds.from(), bounds.to(), "", false));
			case "gable" -> buildGableRoof(label, block, stairBlock, slabBlock, bounds, firstString(stepObj, "direction"), cuboids, blocks);
			case "pyramid", "hip", "dome" -> buildPyramidRoof(label, block, stairBlock, slabBlock, bounds, cuboids, blocks);
			default -> buildPyramidRoof(label, block, stairBlock, slabBlock, bounds, cuboids, blocks);
		}
		return semanticPlanStep(label, defaults, cuboids, blocks, List.of());
	}

	private static BuildStep semanticPlanStep(String label, BuildPlan defaults, List<Cuboid> cuboids, List<BlockPlacement> blocks, List<Volume> clearVolumes) {
		return new BuildStep(label, new BuildPlan(
			label,
			defaults == null ? 2 : defaults.version(),
			defaults == null ? null : defaults.anchor(),
			defaults == null ? "player" : defaults.coordMode(),
			defaults == null ? null : defaults.origin(),
			defaults == null ? null : defaults.offset(),
			defaults == null ? 0 : defaults.rotationDegrees(),
			defaults == null || defaults.autoFix(),
			defaults != null && defaults.snapToGround(),
			defaults != null && defaults.flattenTerrain(),
			defaults == null || defaults.clearVegetation(),
			defaults != null && defaults.phaseReorder(),
			defaults != null && defaults.dryRun(),
			defaults == null || defaults.batchUndo(),
			defaults == null || defaults.rotateBlockStates(),
			defaults == null ? Map.of() : defaults.palette(),
			defaults == null ? Map.of() : defaults.anchors(),
			clearVolumes,
			cuboids,
			blocks,
			List.of()
		));
	}

	private static Cuboid parseSemanticCuboid(JsonObject obj, String fallbackName, boolean hollow) {
		String block = firstString(obj, "block", "material", "id");
		if (block == null || block.isBlank()) {
			return null;
		}
		Bounds bounds = parseBounds(obj);
		if (bounds == null && (obj.has("x") || obj.has("y") || obj.has("z"))) {
			int x = readInt(obj, "x", 0);
			int y = readInt(obj, "y", 0);
			int z = readInt(obj, "z", 0);
			int dx = Math.max(1, readInt(obj, "dx", 1));
			int dy = Math.max(1, readInt(obj, "dy", 1));
			int dz = Math.max(1, readInt(obj, "dz", 1));
			bounds = boundsFromStartAndSize(new GridPoint(x, y, z), new GridPoint(dx, dy, dz));
		}
		if (bounds == null) {
			return null;
		}
		String name = firstString(obj, "name", "label");
		if (name == null || name.isBlank()) {
			name = fallbackName;
		}
		Map<String, String> properties = parseStringMap(obj, "properties");
		if (properties.isEmpty()) {
			properties = parseStringMap(obj, "state");
		}
		return new Cuboid(name, block, properties, bounds.from(), bounds.to(), hollow ? "hollow" : "", hollow);
	}

	private static String parseOnlyOnFilter(JsonObject stepObj) {
		if (stepObj == null || !stepObj.has("onlyOn") || !stepObj.get("onlyOn").isJsonArray()) {
			return "";
		}
		List<String> values = new ArrayList<>();
		for (JsonElement element : stepObj.getAsJsonArray("onlyOn")) {
			if (element != null && element.isJsonPrimitive()) {
				values.add(normalizeBlockToken(element.getAsString()));
			}
		}
		return String.join(",", values);
	}

	private static void buildPyramidRoof(
			String label,
			String block,
			String stairBlock,
			String slabBlock,
			Bounds bounds,
			List<Cuboid> cuboids,
			List<BlockPlacement> blocks
	) {
		int layers = Math.max(1, Math.min(bounds.to().y() - bounds.from().y() + 1, Math.min(bounds.to().x() - bounds.from().x() + 1, bounds.to().z() - bounds.from().z() + 1)));
		for (int layer = 0; layer < layers; layer++) {
			int minX = bounds.from().x() + layer;
			int maxX = bounds.to().x() - layer;
			int minZ = bounds.from().z() + layer;
			int maxZ = bounds.to().z() - layer;
			if (minX > maxX || minZ > maxZ) {
				break;
			}
			int y = bounds.from().y() + layer;
			String layerBlock = layer == layers - 1 && slabBlock != null && !slabBlock.isBlank() ? slabBlock : block;
			cuboids.add(new Cuboid(label + ":layer" + layer, layerBlock, Map.of(), new GridPoint(minX, y, minZ), new GridPoint(maxX, y, maxZ), "hollow", true));
			if (stairBlock != null && !stairBlock.isBlank()) {
				addRoofEdgeStairs(label + ":stairs" + layer, stairBlock, minX, maxX, minZ, maxZ, y, blocks);
			}
		}
	}

	private static void buildGableRoof(
			String label,
			String block,
			String stairBlock,
			String slabBlock,
			Bounds bounds,
			String direction,
			List<Cuboid> cuboids,
			List<BlockPlacement> blocks
	) {
		boolean northSouth = direction == null || direction.isBlank() || "north-south".equalsIgnoreCase(direction);
		int layers = northSouth
			? Math.max(1, (bounds.to().z() - bounds.from().z() + 2) / 2)
			: Math.max(1, (bounds.to().x() - bounds.from().x() + 2) / 2);
		for (int layer = 0; layer < layers; layer++) {
			int y = bounds.from().y() + layer;
			if (northSouth) {
				int z1 = bounds.from().z() + layer;
				int z2 = bounds.to().z() - layer;
				if (z1 > z2) {
					break;
				}
				String layerBlock = (z1 == z2 && slabBlock != null && !slabBlock.isBlank()) ? slabBlock : block;
				cuboids.add(new Cuboid(label + ":ridge" + layer + "a", layerBlock, Map.of(), new GridPoint(bounds.from().x(), y, z1), new GridPoint(bounds.to().x(), y, z1), "", false));
				if (z2 != z1) {
					cuboids.add(new Cuboid(label + ":ridge" + layer + "b", layerBlock, Map.of(), new GridPoint(bounds.from().x(), y, z2), new GridPoint(bounds.to().x(), y, z2), "", false));
				}
				if (stairBlock != null && !stairBlock.isBlank()) {
					for (int x = bounds.from().x(); x <= bounds.to().x(); x++) {
						blocks.add(new BlockPlacement(label + ":stairN" + layer + "@" + x, stairBlock, Map.of("facing", "north"), new GridPoint(x, y, z1)));
						if (z2 != z1) {
							blocks.add(new BlockPlacement(label + ":stairS" + layer + "@" + x, stairBlock, Map.of("facing", "south"), new GridPoint(x, y, z2)));
						}
					}
				}
			} else {
				int x1 = bounds.from().x() + layer;
				int x2 = bounds.to().x() - layer;
				if (x1 > x2) {
					break;
				}
				String layerBlock = (x1 == x2 && slabBlock != null && !slabBlock.isBlank()) ? slabBlock : block;
				cuboids.add(new Cuboid(label + ":ridge" + layer + "a", layerBlock, Map.of(), new GridPoint(x1, y, bounds.from().z()), new GridPoint(x1, y, bounds.to().z()), "", false));
				if (x2 != x1) {
					cuboids.add(new Cuboid(label + ":ridge" + layer + "b", layerBlock, Map.of(), new GridPoint(x2, y, bounds.from().z()), new GridPoint(x2, y, bounds.to().z()), "", false));
				}
				if (stairBlock != null && !stairBlock.isBlank()) {
					for (int z = bounds.from().z(); z <= bounds.to().z(); z++) {
						blocks.add(new BlockPlacement(label + ":stairW" + layer + "@" + z, stairBlock, Map.of("facing", "west"), new GridPoint(x1, y, z)));
						if (x2 != x1) {
							blocks.add(new BlockPlacement(label + ":stairE" + layer + "@" + z, stairBlock, Map.of("facing", "east"), new GridPoint(x2, y, z)));
						}
					}
				}
			}
		}
	}

	private static void addRoofEdgeStairs(String label, String stairBlock, int minX, int maxX, int minZ, int maxZ, int y, List<BlockPlacement> blocks) {
		for (int x = minX; x <= maxX; x++) {
			blocks.add(new BlockPlacement(label + ":north@" + x, stairBlock, Map.of("facing", "north"), new GridPoint(x, y, minZ)));
			blocks.add(new BlockPlacement(label + ":south@" + x, stairBlock, Map.of("facing", "south"), new GridPoint(x, y, maxZ)));
		}
		for (int z = minZ + 1; z < maxZ; z++) {
			blocks.add(new BlockPlacement(label + ":west@" + z, stairBlock, Map.of("facing", "west"), new GridPoint(minX, y, z)));
			blocks.add(new BlockPlacement(label + ":east@" + z, stairBlock, Map.of("facing", "east"), new GridPoint(maxX, y, z)));
		}
	}

	private static Bounds parseBounds(JsonObject obj) {
		if (obj == null) {
			return null;
		}
		GridPoint size = parseSize(obj);
		GridPoint from = parsePoint(obj, "from");
		GridPoint to = parsePoint(obj, "to");
		if (from != null && to != null) {
			return normalizeBounds(from, to);
		}
		if (from != null && size != null) {
			return boundsFromStartAndSize(from, size);
		}
		GridPoint start = parsePoint(obj, "start");
		GridPoint end = parsePoint(obj, "end");
		if (start != null && end != null) {
			return normalizeBounds(start, end);
		}
		if (start != null && size != null) {
			return boundsFromStartAndSize(start, size);
		}
		if (obj.has("location") && obj.get("location").isJsonObject()) {
			JsonObject location = obj.getAsJsonObject("location");
			GridPoint locationStart = parsePointObject(location, "start_x", "start_y", "start_z");
			GridPoint locationEnd = parsePointObject(location, "end_x", "end_y", "end_z");
			if (locationStart != null && locationEnd != null) {
				return normalizeBounds(locationStart, locationEnd);
			}
			if (locationStart != null && size != null) {
				return boundsFromStartAndSize(locationStart, size);
			}
			GridPoint locationOrigin = parseAnchorPoint(location);
			if (locationOrigin != null && size != null) {
				return boundsFromStartAndSize(locationOrigin, size);
			}
		}
		GridPoint directStart = parsePointObject(obj, "start_x", "start_y", "start_z");
		GridPoint directEnd = parsePointObject(obj, "end_x", "end_y", "end_z");
		if (directStart != null && directEnd != null) {
			return normalizeBounds(directStart, directEnd);
		}
		if (directStart != null && size != null) {
			return boundsFromStartAndSize(directStart, size);
		}
		GridPoint anchor = parseAnchorPoint(obj);
		if (anchor != null && size != null) {
			return boundsFromStartAndSize(anchor, size);
		}
		return null;
	}

	private static Bounds boundsFromStartAndSize(GridPoint start, GridPoint size) {
		if (start == null || size == null) {
			return null;
		}
		int width = Math.max(1, size.x());
		int height = Math.max(1, size.y());
		int depth = Math.max(1, size.z());
		GridPoint end = new GridPoint(
				start.x() + width - 1,
				start.y() + height - 1,
				start.z() + depth - 1
		);
		return normalizeBounds(start, end);
	}

	private static Bounds normalizeBounds(GridPoint a, GridPoint b) {
		return new Bounds(
				new GridPoint(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
				new GridPoint(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()))
		);
	}

	private static Bounds rotateBounds(Bounds bounds, int rotation) {
		if (bounds == null || rotation == 0) {
			return bounds;
		}
		GridPoint a = rotatePoint(bounds.from(), rotation);
		GridPoint b = rotatePoint(bounds.to(), rotation);
		GridPoint c = rotatePoint(new GridPoint(bounds.from().x(), bounds.from().y(), bounds.to().z()), rotation);
		GridPoint d = rotatePoint(new GridPoint(bounds.to().x(), bounds.to().y(), bounds.from().z()), rotation);
		int minX = Math.min(Math.min(a.x(), b.x()), Math.min(c.x(), d.x()));
		int minY = Math.min(Math.min(a.y(), b.y()), Math.min(c.y(), d.y()));
		int minZ = Math.min(Math.min(a.z(), b.z()), Math.min(c.z(), d.z()));
		int maxX = Math.max(Math.max(a.x(), b.x()), Math.max(c.x(), d.x()));
		int maxY = Math.max(Math.max(a.y(), b.y()), Math.max(c.y(), d.y()));
		int maxZ = Math.max(Math.max(a.z(), b.z()), Math.max(c.z(), d.z()));
		return new Bounds(new GridPoint(minX, minY, minZ), new GridPoint(maxX, maxY, maxZ));
	}

	private static Bounds clampBounds(GridPoint from, GridPoint to, List<String> repairs, String label) {
		GridPoint clampedFrom = clampPoint(from, repairs, label + ":from");
		GridPoint clampedTo = clampPoint(to, repairs, label + ":to");
		return normalizeBounds(clampedFrom, clampedTo);
	}

	private static GridPoint clampPoint(GridPoint point, List<String> repairs, String label) {
		if (point == null) {
			return new GridPoint(0, 0, 0);
		}
		int x = clamp(point.x(), -MAX_HORIZONTAL_OFFSET, MAX_HORIZONTAL_OFFSET);
		int y = clamp(point.y(), -MAX_VERTICAL_OFFSET, MAX_VERTICAL_OFFSET);
		int z = clamp(point.z(), -MAX_HORIZONTAL_OFFSET, MAX_HORIZONTAL_OFFSET);
		if (x != point.x() || y != point.y() || z != point.z()) {
			repairs.add(label + " was clamped into the safe build window.");
		}
		return new GridPoint(x, y, z);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String normalizeCoordMode(String raw, String fallback) {
		String normalized = raw == null || raw.isBlank() ? fallback : raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "absolute", "world" -> "absolute";
			case "anchor" -> "anchor";
			case "player", "relative" -> "player";
			default -> fallback == null || fallback.isBlank() ? "player" : fallback;
		};
	}

	private static boolean hasRotation(JsonObject obj) {
		return obj != null && ((obj.has("rotate") && obj.get("rotate").isJsonPrimitive()) || (obj.has("rotation") && obj.get("rotation").isJsonPrimitive()));
	}

	private static boolean readBoolean(JsonObject obj, String key, boolean fallback) {
		if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsBoolean();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static double readDouble(JsonObject obj, String key, double fallback) {
		if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsDouble();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static int parseRotation(JsonObject obj, int fallback) {
		if (obj == null) {
			return fallback;
		}
		String raw = firstString(obj, "rotate", "rotation");
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("degrees", "").replace("deg", "").trim();
		int parsed = switch (normalized) {
			case "0", "none" -> 0;
			case "90", "cw", "clockwise" -> 90;
			case "180", "flip", "half" -> 180;
			case "270", "ccw", "counterclockwise", "counter-clockwise" -> 270;
			default -> {
				try {
					yield Integer.parseInt(normalized);
				} catch (Exception e) {
					yield fallback;
				}
			}
		};
		parsed = ((parsed % 360) + 360) % 360;
		return switch (parsed) {
			case 0, 90, 180, 270 -> parsed;
			default -> fallback;
		};
	}

	private static int normalizeRotation(int rotation, List<String> repairs) {
		int normalized = ((rotation % 360) + 360) % 360;
		return switch (normalized) {
			case 0, 90, 180, 270 -> normalized;
			default -> {
				repairs.add("Unsupported rotation defaulted to 0.");
				yield 0;
			}
		};
	}

	private static GridPoint rotatePoint(GridPoint point, int rotation) {
		if (point == null) {
			return new GridPoint(0, 0, 0);
		}
		return switch (rotation) {
			case 90 -> new GridPoint(-point.z(), point.y(), point.x());
			case 180 -> new GridPoint(-point.x(), point.y(), -point.z());
			case 270 -> new GridPoint(point.z(), point.y(), -point.x());
			default -> point;
		};
	}

	private static GridPoint parsePoint(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
			return null;
		}
		JsonObject value = obj.getAsJsonObject(key);
		return parseAnchorPoint(value);
	}

	private static GridPoint parseAnchorPoint(JsonObject obj) {
		if (obj == null) {
			return null;
		}
		GridPoint direct = parsePointObject(obj, "x", "y", "z");
		if (direct != null) {
			return direct;
		}
		direct = parsePointObject(obj, "dx", "dy", "dz");
		if (direct != null) {
			return direct;
		}
		direct = parsePointObject(obj, "origin_x", "origin_y", "origin_z");
		if (direct != null) {
			return direct;
		}
		direct = parsePointObject(obj, "pos_x", "pos_y", "pos_z");
		if (direct != null) {
			return direct;
		}
		return null;
	}

	private static GridPoint parseSize(JsonObject obj) {
		if (obj == null) {
			return null;
		}
		GridPoint size = parseSizeObject(obj, "size");
		if (size != null) {
			return size;
		}
		size = parseSizeObject(obj, "dimensions");
		if (size != null) {
			return size;
		}
		if (obj.has("location") && obj.get("location").isJsonObject()) {
			size = parseSizeObject(obj.getAsJsonObject("location"), "size");
			if (size != null) {
				return size;
			}
			size = parseSizeObject(obj.getAsJsonObject("location"), "dimensions");
			if (size != null) {
				return size;
			}
		}
		if (obj.has("width") || obj.has("height") || obj.has("depth") || obj.has("length")) {
			return new GridPoint(
					readInt(obj, "width", readInt(obj, "w", 1)),
					readInt(obj, "height", readInt(obj, "h", 1)),
					readInt(obj, "depth", readInt(obj, "length", readInt(obj, "d", 1)))
			);
		}
		return null;
	}

	private static GridPoint parseSizeObject(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
			return null;
		}
		JsonObject value = obj.getAsJsonObject(key);
		if (hasCoordinateKeys(value, "x", "y", "z")) {
			return parsePointObject(value, "x", "y", "z");
		}
		if (value.has("width") || value.has("height") || value.has("depth") || value.has("length")) {
			return new GridPoint(
					readInt(value, "width", readInt(value, "w", 1)),
					readInt(value, "height", readInt(value, "h", 1)),
					readInt(value, "depth", readInt(value, "length", readInt(value, "d", 1)))
			);
		}
		return null;
	}

	private static GridPoint parsePointObject(JsonObject obj, String xKey, String yKey, String zKey) {
		if (obj == null || !hasCoordinateKeys(obj, xKey, yKey, zKey)) {
			return null;
		}
		return new GridPoint(
				obj.get(xKey).getAsInt(),
				obj.get(yKey).getAsInt(),
				obj.get(zKey).getAsInt()
		);
	}

	private static boolean hasCoordinateKeys(JsonObject obj, String xKey, String yKey, String zKey) {
		return obj.has(xKey) && obj.get(xKey).isJsonPrimitive()
				&& obj.has(yKey) && obj.get(yKey).isJsonPrimitive()
				&& obj.has(zKey) && obj.get(zKey).isJsonPrimitive();
	}

	private static int readInt(JsonObject obj, String key, int fallback) {
		if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static Map<String, String> rotateProperties(Map<String, String> properties, int rotation) {
		if (properties == null || properties.isEmpty() || rotation == 0) {
			return properties == null ? Map.of() : properties;
		}
		Map<String, String> rotated = new LinkedHashMap<>(properties);
		String facing = rotated.get("facing");
		if (facing != null) {
			rotated.put("facing", rotateFacing(facing, rotation));
		}
		String axis = rotated.get("axis");
		if (axis != null) {
			rotated.put("axis", rotateAxis(axis, rotation));
		}
		return rotated;
	}

	private static String rotateFacing(String facing, int rotation) {
		String lower = facing.toLowerCase(Locale.ROOT);
		return switch (rotation) {
			case 90 -> switch (lower) {
				case "north" -> "east";
				case "east" -> "south";
				case "south" -> "west";
				case "west" -> "north";
				default -> lower;
			};
			case 180 -> switch (lower) {
				case "north" -> "south";
				case "east" -> "west";
				case "south" -> "north";
				case "west" -> "east";
				default -> lower;
			};
			case 270 -> switch (lower) {
				case "north" -> "west";
				case "east" -> "north";
				case "south" -> "east";
				case "west" -> "south";
				default -> lower;
			};
			default -> lower;
		};
	}

	private static String rotateAxis(String axis, int rotation) {
		String lower = axis.toLowerCase(Locale.ROOT);
		if (rotation == 90 || rotation == 270) {
			return switch (lower) {
				case "x" -> "z";
				case "z" -> "x";
				default -> lower;
			};
		}
		return lower;
	}

	private static Map<String, String> parseStringMap(JsonObject obj, String key) {
		Map<String, String> map = new LinkedHashMap<>();
		if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
			return map;
		}
		for (var entry : obj.getAsJsonObject(key).entrySet()) {
			if (entry.getValue() == null || entry.getValue().isJsonNull()) {
				continue;
			}
			map.put(entry.getKey(), entry.getValue().getAsString());
		}
		return map;
	}

	private static String firstString(JsonObject obj, String... keys) {
		if (obj == null || keys == null) {
			return null;
		}
		for (String key : keys) {
			if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
				continue;
			}
			if (obj.get(key).isJsonPrimitive()) {
				return obj.get(key).getAsString();
			}
		}
		return null;
	}

	private static ResolvedBlock resolveBlock(
			String requested,
			Map<String, String> properties,
			Map<String, String> palette,
			List<String> repairs,
			String label
	) {
		String blockToken = requested == null ? "" : requested.trim();
		if (blockToken.isBlank()) {
			return new ResolvedBlock(null, null, false, "Build plan entry '" + label + "' is missing a block.");
		}
		ParsedBlockToken parsedToken = parseBlockToken(blockToken);
		String aliasBlock = resolvePaletteAlias(parsedToken.blockId(), palette);
		if (aliasBlock != null) {
			repairs.add("Resolved palette alias '" + parsedToken.blockId() + "' to '" + aliasBlock + "'.");
			parsedToken = new ParsedBlockToken(aliasBlock, parsedToken.properties());
		}
		String normalizedToken = normalizeBlockToken(parsedToken.blockId());
		if (!normalizedToken.equals(parsedToken.blockId())) {
			repairs.add("Normalized block '" + parsedToken.blockId() + "' to '" + normalizedToken + "'.");
		}
		Identifier id = Identifier.tryParse(normalizedToken);
		if (id == null || !Registries.BLOCK.containsId(id)) {
			return new ResolvedBlock(null, null, false, "Unknown block '" + blockToken + "' in build plan entry '" + label + "'.");
		}
		Block block = Registries.BLOCK.get(id);
		BlockState state = block.getDefaultState();
		Map<String, String> safeProperties = new LinkedHashMap<>();
		for (var entry : parsedToken.properties().entrySet()) {
			String name = entry.getKey();
			String value = entry.getValue();
			Property<?> property = block.getStateManager().getProperty(name);
			if (property == null) {
				return new ResolvedBlock(null, null, false, "Invalid block state '" + name + "' on '" + blockToken + "' in build plan entry '" + label + "'.");
			}
			Optional<?> parsed = property.parse(value.toLowerCase(Locale.ROOT));
			if (parsed.isEmpty()) {
				return new ResolvedBlock(null, null, false, "Invalid block state value '" + value + "' for '" + name + "' on '" + blockToken + "' in build plan entry '" + label + "'.");
			}
			state = applyProperty(state, property, parsed.get());
			safeProperties.put(name, propertyValueName(property, parsed.get()));
		}
		if (properties != null) {
			for (var entry : properties.entrySet()) {
				String name = entry.getKey();
				String value = entry.getValue();
				if (name == null || value == null || name.startsWith("_")) {
					continue;
				}
				Property<?> property = block.getStateManager().getProperty(name);
				if (property == null) {
					repairs.add("Dropped unsupported block state '" + name + "' from '" + id + "'.");
					continue;
				}
				Optional<?> parsed = property.parse(value.toLowerCase(Locale.ROOT));
				if (parsed.isEmpty()) {
					repairs.add("Dropped invalid value '" + value + "' for state '" + name + "' on '" + id + "'.");
					continue;
				}
				state = applyProperty(state, property, parsed.get());
				safeProperties.put(name, propertyValueName(property, parsed.get()));
			}
		}
		Map<String, String> canonicalProperties = new LinkedHashMap<>();
		for (var entry : state.getEntries().entrySet()) {
			canonicalProperties.put(entry.getKey().getName(), propertyValueName(entry.getKey(), entry.getValue()));
		}
		return new ResolvedBlock(id.toString(), canonicalProperties, true, "");
	}

	private static String resolvePaletteAlias(String token, Map<String, String> palette) {
		if (token == null || token.isBlank() || palette == null || palette.isEmpty()) {
			return null;
		}
		if (palette.containsKey(token)) {
			return palette.get(token);
		}
		if (token.startsWith("$")) {
			String stripped = token.substring(1);
			if (palette.containsKey(stripped)) {
				return palette.get(stripped);
			}
			if (palette.containsKey(token)) {
				return palette.get(token);
			}
		}
		return null;
	}

	private static ParsedBlockToken parseBlockToken(String token) {
		if (token == null) {
			return new ParsedBlockToken("", Map.of());
		}
		String trimmed = token.trim();
		int bracketStart = trimmed.indexOf('[');
		if (bracketStart < 0 || !trimmed.endsWith("]")) {
			return new ParsedBlockToken(trimmed, Map.of());
		}
		String blockId = trimmed.substring(0, bracketStart).trim();
		String rawStates = trimmed.substring(bracketStart + 1, trimmed.length() - 1).trim();
		Map<String, String> properties = new LinkedHashMap<>();
		if (!rawStates.isBlank()) {
			for (String entry : rawStates.split(",")) {
				String part = entry.trim();
				if (part.isBlank()) {
					continue;
				}
				int equalsIndex = part.indexOf('=');
				if (equalsIndex <= 0 || equalsIndex == part.length() - 1) {
					continue;
				}
				String key = part.substring(0, equalsIndex).trim();
				String value = part.substring(equalsIndex + 1).trim();
				if (!key.isBlank() && !value.isBlank()) {
					properties.put(key, value);
				}
			}
		}
		return new ParsedBlockToken(blockId, properties);
	}

	private static String normalizeBlockToken(String token) {
		String normalized = token.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
		if (!normalized.contains(":")) {
			normalized = "minecraft:" + normalized;
		}
		return normalized;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static BlockState applyProperty(BlockState state, Property property, Object value) {
		return state.with(property, (Comparable) value);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static String propertyValueName(Property property, Object value) {
		return property.name((Comparable) value);
	}

	private static String normalizeFillMode(String value, Boolean hollow, List<String> repairs, String label) {
		if (Boolean.TRUE.equals(hollow)) {
			return "hollow";
		}
		if (value == null || value.isBlank()) {
			return "";
		}
		String lower = value.trim().toLowerCase(Locale.ROOT);
		return switch (lower) {
			case "solid", "fill", "replace" -> "";
			case "hollow", "outline", "keep", "destroy", "strict" -> lower.equals("strict") ? "" : lower;
			default -> {
				repairs.add("Unknown fill mode '" + value + "' for '" + label + "'; using solid.");
				yield "";
			}
		};
	}

	private static void applyTerrainAdjustments(ServerWorld world, BuildPlan plan, BlockPos origin, List<String> repairs, CompileAccumulator accumulator) {
		if (world == null || plan == null || origin == null) {
			return;
		}
		Bounds footprint = computePlanFootprint(plan);
		if (footprint == null) {
			return;
		}
		BlockPos absoluteMin = toAbsolute(origin, footprint.from());
		BlockPos absoluteMax = toAbsolute(origin, footprint.to());
		if (plan.clearVegetation()) {
			List<BlockPos> vegetation = new ArrayList<>();
			for (int x = Math.min(absoluteMin.getX(), absoluteMax.getX()); x <= Math.max(absoluteMin.getX(), absoluteMax.getX()); x++) {
				for (int z = Math.min(absoluteMin.getZ(), absoluteMax.getZ()); z <= Math.max(absoluteMin.getZ(), absoluteMax.getZ()); z++) {
					for (int y = Math.min(absoluteMin.getY(), absoluteMax.getY()); y <= Math.max(absoluteMin.getY(), absoluteMax.getY()) + 2; y++) {
						BlockPos candidate = new BlockPos(x, y, z);
						BlockState state = world.getBlockState(candidate);
						if (!state.isAir() && state.isReplaceable() && state.getFluidState().isEmpty()) {
							vegetation.add(candidate);
						}
					}
				}
			}
			for (BlockPos pos : vegetation) {
				accumulator.commands.add("setblock " + coords(pos) + " minecraft:air");
				accumulator.occupiedBlocks.remove(pos);
			}
			if (!vegetation.isEmpty()) {
				repairs.add("Cleared " + vegetation.size() + " replaceable vegetation block(s) in the footprint.");
			}
		}
		if (plan.flattenTerrain()) {
			for (int x = Math.min(absoluteMin.getX(), absoluteMax.getX()); x <= Math.max(absoluteMin.getX(), absoluteMax.getX()); x++) {
				for (int z = Math.min(absoluteMin.getZ(), absoluteMax.getZ()); z <= Math.max(absoluteMin.getZ(), absoluteMax.getZ()); z++) {
					BlockPos surface = findSurface(world, new BlockPos(x, origin.getY(), z), origin.getY());
					if (surface.getY() > origin.getY()) {
						accumulator.commands.add(fillCommand(new BlockPos(x, origin.getY() + 1, z), surface, "minecraft:air", null));
					} else if (surface.getY() + 1 < origin.getY()) {
						accumulator.commands.add(fillCommand(new BlockPos(x, surface.getY() + 1, z), new BlockPos(x, origin.getY(), z), DEFAULT_FOUNDATION_BLOCK, null));
					}
				}
			}
			repairs.add("Flattened the terrain footprint to origin Y=" + origin.getY() + ".");
		}
		if (plan.snapToGround()) {
			BlockPos ground = findSurface(world, origin, origin.getY());
			int groundedY = ground.getY() + 1;
			if (groundedY != origin.getY()) {
				repairs.add("snapToGround adjusted origin Y from " + origin.getY() + " to " + groundedY + ". Set snapToGround:false to disable this.");
				accumulator.resolvedOrigin = new BlockPos(origin.getX(), groundedY, origin.getZ());
			}
		}
	}

	private static Bounds computePlanFootprint(BuildPlan plan) {
		MutableBounds bounds = new MutableBounds();
		includePlanBounds(plan, bounds);
		return bounds.toBounds();
	}

	private static void includePlanBounds(BuildPlan plan, MutableBounds bounds) {
		if (plan == null || bounds == null) {
			return;
		}
		for (Volume volume : plan.clearVolumes()) {
			bounds.include(volume.from());
			bounds.include(volume.to());
		}
		for (Cuboid cuboid : plan.cuboids()) {
			bounds.include(cuboid.from());
			bounds.include(cuboid.to());
		}
		for (BlockPlacement block : plan.blocks()) {
			bounds.include(block.pos());
		}
		for (BuildStep step : plan.steps()) {
			includePlanBounds(step.plan(), bounds);
		}
	}

	private static CompiledBuild compilePlanInto(
			ServerPlayerEntity player,
			BuildPlan plan,
			BlockPos origin,
			List<String> repairs,
			CompileAccumulator accumulator
	) {
		if (plan == null) {
			return new CompiledBuild(false, List.of(), "No build executed.", repairs, "Missing phased build plan.", 0, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
		}
		int rotation = normalizeRotation(plan.rotationDegrees(), repairs);
		accumulator.appliedRotation = rotation;

		boolean hasDirectOps = !plan.clearVolumes().isEmpty() || !plan.cuboids().isEmpty() || !plan.blocks().isEmpty();
		if (hasDirectOps) {
			accumulator.phases++;
		}

		for (Volume volume : plan.clearVolumes()) {
			Bounds bounds = clampBounds(volume.from(), volume.to(), repairs, "clear:" + volume.name());
			bounds = rotateBounds(bounds, rotation);
			accumulator.totalVolume += bounds.volume();
			if (accumulator.totalVolume > MAX_TOTAL_VOLUME) {
				return new CompiledBuild(false, List.of(), "No build executed.", repairs,
						"Build plan exceeds the maximum volume budget of " + MAX_TOTAL_VOLUME + " blocks.", rotation, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
			}
			BlockPos start = toAbsolute(origin, bounds.from());
			BlockPos end = toAbsolute(origin, bounds.to());
			accumulator.commands.add(fillCommand(start, end, volume.replaceWith(), null));
			removeTrackedBlocks(accumulator.occupiedBlocks, start, end);
		}

		if (plan.cuboids().size() > MAX_BUILD_CUBOIDS) {
			return new CompiledBuild(false, List.of(), "No build executed.", repairs,
					"Build plan has too many cuboids (" + plan.cuboids().size() + " > " + MAX_BUILD_CUBOIDS + ").", rotation, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
		}
		if (plan.blocks().size() > MAX_BUILD_BLOCKS) {
			return new CompiledBuild(false, List.of(), "No build executed.", repairs,
					"Build plan has too many single blocks (" + plan.blocks().size() + " > " + MAX_BUILD_BLOCKS + ").", rotation, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
		}

		List<Cuboid> orderedCuboids = new ArrayList<>(plan.cuboids());
		if (plan.phaseReorder()) {
			orderedCuboids.sort(Comparator
					.comparingInt((Cuboid cuboid) -> cuboid.from().y())
					.thenComparingInt(cuboid -> cuboidOrderHint(cuboid.name()))
					.thenComparing(Cuboid::name, Comparator.nullsLast(String::compareToIgnoreCase)));
		}
		for (Cuboid cuboid : orderedCuboids) {
			ResolvedBlock resolved = resolveBlock(cuboid.block(), plan.rotateBlockStates() ? rotateProperties(cuboid.properties(), rotation) : cuboid.properties(), plan.palette(), repairs, cuboid.name());
			if (!resolved.valid()) {
				return new CompiledBuild(false, List.of(), "No build executed.", repairs, resolved.error(), rotation, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
			}
			Bounds bounds = clampBounds(cuboid.from(), cuboid.to(), repairs, "cuboid:" + cuboid.name());
			bounds = rotateBounds(bounds, rotation);
			accumulator.totalVolume += bounds.volume();
			if (accumulator.totalVolume > MAX_TOTAL_VOLUME) {
				return new CompiledBuild(false, List.of(), "No build executed.", repairs,
						"Build plan exceeds the maximum volume budget of " + MAX_TOTAL_VOLUME + " blocks.", rotation, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
			}
			String fillMode = normalizeFillMode(cuboid.fillMode(), cuboid.hollow(), repairs, cuboid.name());
			BlockPos start = toAbsolute(origin, bounds.from());
			BlockPos end = toAbsolute(origin, bounds.to());
			accumulator.commands.add(fillCommand(start, end, resolved.blockString(), fillMode));
			trackCuboidPlacements(accumulator.occupiedBlocks, start, end, resolved.blockString(), fillMode);
			accumulator.supportTargets.add(new SupportTarget(cuboid.name(), start, end));
		}

		for (BlockPlacement block : plan.blocks()) {
			Map<String, String> rawProperties = block.properties();
			if (rawProperties != null && rawProperties.containsKey("_onlyOn")) {
				String onlyOn = rawProperties.get("_onlyOn");
				if (onlyOn != null && !onlyOn.isBlank()) {
					Set<String> supported = new HashSet<>();
					for (String token : onlyOn.split(",")) {
						if (token != null && !token.isBlank()) {
							supported.add(token.trim());
						}
					}
					BlockPos below = toAbsolute(origin, rotatePoint(block.pos(), rotation)).down();
					String blockBelow = Registries.BLOCK.getId(player.getServerWorld().getBlockState(below).getBlock()).toString();
					if (!supported.contains(blockBelow)) {
						continue;
					}
				}
			}
			ResolvedBlock resolved = resolveBlock(block.block(), plan.rotateBlockStates() ? rotateProperties(rawProperties, rotation) : rawProperties, plan.palette(), repairs, block.name());
			if (!resolved.valid()) {
				return new CompiledBuild(false, List.of(), "No build executed.", repairs, resolved.error(), rotation, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
			}
			GridPoint clampedPos = clampPoint(block.pos(), repairs, "block:" + block.name());
			clampedPos = rotatePoint(clampedPos, rotation);
			List<Placement> placements = singleBlockPlacements(origin, clampedPos, resolved, repairs, block.name());
			for (Placement placement : placements) {
				accumulator.commands.add("setblock " + coords(placement.pos()) + " " + placement.blockString());
				accumulator.occupiedBlocks.put(placement.pos().toImmutable(), placement.blockString());
				accumulator.supportTargets.add(new SupportTarget(block.name(), placement.pos(), placement.pos()));
			}
			accumulator.totalVolume += placements.size();
			if (accumulator.totalVolume > MAX_TOTAL_VOLUME) {
				return new CompiledBuild(false, List.of(), "No build executed.", repairs,
						"Build plan exceeds the maximum volume budget of " + MAX_TOTAL_VOLUME + " blocks.", rotation, Math.max(1, accumulator.phases), toGridPoint(origin), List.of(), false);
			}
		}

		for (BuildStep step : plan.steps()) {
			CompiledBuild failure = compilePlanInto(player, step.plan(), origin, repairs, accumulator);
			if (failure != null) {
				return failure;
			}
		}
		return null;
	}

	private static int cuboidOrderHint(String name) {
		String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
		if (containsAny(lower, "foundation", "base", "floor", "platform")) {
			return 0;
		}
		if (containsAny(lower, "wall", "pillar", "column", "frame")) {
			return 1;
		}
		if (containsAny(lower, "roof", "ceiling")) {
			return 2;
		}
		if (containsAny(lower, "detail", "window", "door", "trim", "decor")) {
			return 3;
		}
		return 4;
	}

	private static void removeTrackedBlocks(Map<BlockPos, String> occupiedBlocks, BlockPos start, BlockPos end) {
		if (occupiedBlocks.isEmpty()) {
			return;
		}
		int minX = Math.min(start.getX(), end.getX());
		int maxX = Math.max(start.getX(), end.getX());
		int minY = Math.min(start.getY(), end.getY());
		int maxY = Math.max(start.getY(), end.getY());
		int minZ = Math.min(start.getZ(), end.getZ());
		int maxZ = Math.max(start.getZ(), end.getZ());
		List<BlockPos> toRemove = new ArrayList<>();
		for (BlockPos pos : occupiedBlocks.keySet()) {
			if (pos.getX() >= minX && pos.getX() <= maxX
					&& pos.getY() >= minY && pos.getY() <= maxY
					&& pos.getZ() >= minZ && pos.getZ() <= maxZ) {
				toRemove.add(pos);
			}
		}
		for (BlockPos pos : toRemove) {
			occupiedBlocks.remove(pos);
		}
	}

	private static void trackCuboidPlacements(Map<BlockPos, String> occupiedBlocks, BlockPos start, BlockPos end, String blockString, String fillMode) {
		int minX = Math.min(start.getX(), end.getX());
		int maxX = Math.max(start.getX(), end.getX());
		int minY = Math.min(start.getY(), end.getY());
		int maxY = Math.max(start.getY(), end.getY());
		int minZ = Math.min(start.getZ(), end.getZ());
		int maxZ = Math.max(start.getZ(), end.getZ());
		boolean shellOnly = "hollow".equals(fillMode) || "outline".equals(fillMode);
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					if (shellOnly
							&& x > minX && x < maxX
							&& y > minY && y < maxY
							&& z > minZ && z < maxZ) {
						continue;
					}
					occupiedBlocks.put(new BlockPos(x, y, z), blockString);
				}
			}
		}
	}

	private static SupportRepair repairSupportColumns(ServerWorld world, CompileAccumulator accumulator, boolean autoFix) {
		if (world == null || accumulator.occupiedBlocks.isEmpty() || accumulator.supportTargets.isEmpty()) {
			return SupportRepair.success();
		}
		int globalMinY = accumulator.supportTargets.stream()
				.mapToInt(target -> Math.min(target.from().getY(), target.to().getY()))
				.min()
				.orElse(Integer.MAX_VALUE);
		List<SupportTarget> groundTargets = accumulator.supportTargets.stream()
				.filter(target -> Math.min(target.from().getY(), target.to().getY()) == globalMinY)
				.toList();
		List<SupportIssue> issues = new ArrayList<>();
		List<Pillar> pendingPillars = new ArrayList<>();
		List<Integer> nearGaps = new ArrayList<>();
		int totalColumns = 0;
		int nearGroundColumns = 0;
		int missingGroundColumns = 0;
		for (SupportTarget target : groundTargets) {
			TargetSupportStats stats = analyzeSupportTarget(world, accumulator.occupiedBlocks, target);
			totalColumns += stats.totalColumns();
			nearGroundColumns += stats.nearGroundColumns();
			missingGroundColumns += stats.missingGroundColumns();
			nearGaps.addAll(stats.nearGaps());
			pendingPillars.addAll(stats.pillars());
			if (stats.hasIssue()) {
				issues.add(new SupportIssue(target.name(), "floating", stats.maxGap(), stats.suggestedY()));
			}
		}
		boolean autoFixAvailable = !issues.isEmpty();
		if (autoFix && totalColumns > 0 && missingGroundColumns == 0 && ((nearGroundColumns * 1.0D) / totalColumns) >= 0.80D) {
			int commonGap = mostCommonPositiveGap(nearGaps);
			if (commonGap > 0 && commonGap <= 2) {
				return new SupportRepair(false, List.of(), List.of(), "Auto-shifting build to nearby terrain.", issues, true, commonGap);
			}
		}
		if (missingGroundColumns > 0) {
			return new SupportRepair(false, List.of(), List.of(), summarizeSupportIssues(issues, "Build plan leaves unsupported columns with no solid ground below."), issues, autoFixAvailable, 0);
		}
		if (pendingPillars.size() > MAX_AUTO_FOUNDATION_COLUMNS) {
			return new SupportRepair(false, List.of(), List.of(), summarizeSupportIssues(issues, "Build plan would require " + pendingPillars.size() + " support pillars."), issues, autoFixAvailable, 0);
		}
		if (pendingPillars.isEmpty()) {
			return new SupportRepair(true, List.of(), List.of(), "", issues, autoFixAvailable, 0);
		}
		List<String> repairs = new ArrayList<>();
		List<String> commands = new ArrayList<>();
		String foundationBlock = DEFAULT_FOUNDATION_BLOCK;
		for (Pillar pillar : pendingPillars) {
			commands.add(fillCommand(pillar.from(), pillar.to(), foundationBlock, null));
			trackCuboidPlacements(accumulator.occupiedBlocks, pillar.from(), pillar.to(), foundationBlock, "");
		}
		repairs.add("Added " + pendingPillars.size() + " support pillar(s) using '" + foundationBlock + "' to anchor the build.");
		return new SupportRepair(true, commands, repairs, "", issues, autoFixAvailable, 0);
	}

	private static String summarizeSupportIssues(List<SupportIssue> issues, String prefix) {
		if (issues == null || issues.isEmpty()) {
			return prefix;
		}
		List<String> details = new ArrayList<>();
		for (int i = 0; i < Math.min(3, issues.size()); i++) {
			SupportIssue issue = issues.get(i);
			details.add(issue.cuboid() + " gap=" + issue.gapBelow() + " suggestedY=" + issue.suggestedY());
		}
		return prefix + " Floating targets: " + String.join(", ", details) + ".";
	}

	private static int mostCommonPositiveGap(List<Integer> gaps) {
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		for (int gap : gaps) {
			if (gap > 0) {
				counts.merge(gap, 1, Integer::sum);
			}
		}
		return counts.entrySet().stream()
				.max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
				.map(Map.Entry::getKey)
				.orElse(0);
	}

	private static TargetSupportStats analyzeSupportTarget(ServerWorld world, Map<BlockPos, String> occupiedBlocks, SupportTarget target) {
		int minX = Math.min(target.from().getX(), target.to().getX());
		int maxX = Math.max(target.from().getX(), target.to().getX());
		int minZ = Math.min(target.from().getZ(), target.to().getZ());
		int maxZ = Math.max(target.from().getZ(), target.to().getZ());
		int baseY = Math.min(target.from().getY(), target.to().getY());
		int totalColumns = 0;
		int nearGroundColumns = 0;
		int missingGroundColumns = 0;
		int maxGap = 0;
		int suggestedY = baseY;
		List<Integer> nearGaps = new ArrayList<>();
		List<Pillar> pillars = new ArrayList<>();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				totalColumns++;
				BlockPos lowest = new BlockPos(x, baseY, z);
				if (hasSupportBelow(world, occupiedBlocks, lowest)) {
					nearGroundColumns++;
					continue;
				}
				BlockPos anchor = findSupportAnchor(world, occupiedBlocks, lowest.down());
				if (anchor == null) {
					missingGroundColumns++;
					maxGap = Math.max(maxGap, baseY - world.getBottomY());
					continue;
				}
				int gap = Math.max(0, baseY - (anchor.getY() + 1));
				suggestedY = Math.max(suggestedY, anchor.getY() + 1);
				maxGap = Math.max(maxGap, gap);
				if (gap <= 2) {
					nearGroundColumns++;
					nearGaps.add(gap);
				}
				if (anchor.getY() + 1 < baseY) {
					pillars.add(new Pillar(new BlockPos(x, anchor.getY() + 1, z), new BlockPos(x, baseY - 1, z)));
				}
			}
		}
		return new TargetSupportStats(totalColumns, nearGroundColumns, missingGroundColumns, maxGap, suggestedY, nearGaps, pillars);
	}

	private static boolean hasSupportBelow(ServerWorld world, Map<BlockPos, String> occupiedBlocks, BlockPos pos) {
		BlockPos below = pos.down();
		return occupiedBlocks.containsKey(below) || isSupportiveTerrainBlock(world, below);
	}

	private static BlockPos findSupportAnchor(ServerWorld world, Map<BlockPos, String> occupiedBlocks, BlockPos start) {
		for (int y = start.getY(); y >= world.getBottomY(); y--) {
			BlockPos candidate = new BlockPos(start.getX(), y, start.getZ());
			if (occupiedBlocks.containsKey(candidate) || isSupportiveTerrainBlock(world, candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean isSupportiveTerrainBlock(ServerWorld world, BlockPos pos) {
		if (world == null || pos == null) {
			return false;
		}
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}
		if (!state.getFluidState().isEmpty()) {
			return false;
		}
		if (state.isReplaceable()) {
			return false;
		}
		return state.isSideSolidFullSquare(world, pos, Direction.UP);
	}

	private static List<Placement> singleBlockPlacements(
			BlockPos origin,
			GridPoint pos,
			ResolvedBlock resolved,
			List<String> repairs,
			String label
	) {
		BlockPos absolute = toAbsolute(origin, pos);
		if (resolved.blockId().endsWith("_door")) {
			return expandDoorPlacements(absolute, resolved, repairs, label);
		}
		if (resolved.blockId().endsWith("_bed")) {
			return expandBedPlacements(absolute, resolved, repairs, label);
		}
		if (resolved.blockId().endsWith("_stairs")) {
			Map<String, String> props = new LinkedHashMap<>(resolved.properties());
			if (!props.containsKey("facing")) {
				props.put("facing", "north");
				repairs.add("Stairs '" + label + "' were missing facing; defaulted to north.");
			}
			props.putIfAbsent("half", "bottom");
			props.putIfAbsent("shape", "straight");
			props.putIfAbsent("waterlogged", "false");
			return List.of(new Placement(absolute, withProperties(resolved.blockId(), props)));
		}
		if (resolved.blockId().endsWith("_slab")) {
			Map<String, String> props = new LinkedHashMap<>(resolved.properties());
			props.putIfAbsent("type", "bottom");
			props.putIfAbsent("waterlogged", "false");
			return List.of(new Placement(absolute, withProperties(resolved.blockId(), props)));
		}
		if (resolved.blockId().contains("fence") || resolved.blockId().endsWith("_pane") || resolved.blockId().endsWith("_wall")) {
			Map<String, String> props = new LinkedHashMap<>(resolved.properties());
			props.putIfAbsent("waterlogged", "false");
			return List.of(new Placement(absolute, withProperties(resolved.blockId(), props)));
		}
		return List.of(new Placement(absolute, resolved.blockString()));
	}

	private static List<Placement> expandDoorPlacements(BlockPos base, ResolvedBlock resolved, List<String> repairs, String label) {
		Map<String, String> lowerProps = new LinkedHashMap<>(resolved.properties());
		if (!lowerProps.containsKey("facing")) {
			lowerProps.put("facing", "north");
			repairs.add("Door '" + label + "' was missing facing; defaulted to north.");
		}
		lowerProps.putIfAbsent("hinge", "left");
		lowerProps.putIfAbsent("open", "false");
		lowerProps.put("half", "lower");

		Map<String, String> upperProps = new LinkedHashMap<>(lowerProps);
		upperProps.put("half", "upper");

		String lower = withProperties(resolved.blockId(), lowerProps);
		String upper = withProperties(resolved.blockId(), upperProps);
		return List.of(
				new Placement(base, lower),
				new Placement(base.up(), upper)
		);
	}

	private static List<Placement> expandBedPlacements(BlockPos foot, ResolvedBlock resolved, List<String> repairs, String label) {
		Map<String, String> footProps = new LinkedHashMap<>(resolved.properties());
		String facing = footProps.getOrDefault("facing", "north").toLowerCase(Locale.ROOT);
		if (!List.of("north", "south", "east", "west").contains(facing)) {
			facing = "north";
			repairs.add("Bed '" + label + "' had invalid facing; defaulted to north.");
		}
		footProps.put("facing", facing);
		footProps.put("part", "foot");
		footProps.putIfAbsent("occupied", "false");

		Map<String, String> headProps = new LinkedHashMap<>(footProps);
		headProps.put("part", "head");

		BlockPos head = switch (facing) {
			case "south" -> foot.south();
			case "east" -> foot.east();
			case "west" -> foot.west();
			default -> foot.north();
		};
		String footBlock = withProperties(resolved.blockId(), footProps);
		String headBlock = withProperties(resolved.blockId(), headProps);
		return List.of(
				new Placement(foot, footBlock),
				new Placement(head, headBlock)
		);
	}

	private static String withProperties(String blockId, Map<String, String> properties) {
		if (properties == null || properties.isEmpty()) {
			return blockId;
		}
		List<String> entries = new ArrayList<>();
		List<String> keys = new ArrayList<>(properties.keySet());
		keys.sort(Comparator.naturalOrder());
		for (String key : keys) {
			String value = properties.get(key);
			if (value == null || value.isBlank()) {
				continue;
			}
			entries.add(key + "=" + value);
		}
		if (entries.isEmpty()) {
			return blockId;
		}
		return blockId + "[" + String.join(",", entries) + "]";
	}

	private static String fillCommand(BlockPos start, BlockPos end, String block, String fillMode) {
		StringBuilder out = new StringBuilder("fill ");
		out.append(coords(start)).append(" ").append(coords(end)).append(" ").append(block);
		if (fillMode != null && !fillMode.isBlank()) {
			out.append(" ").append(fillMode);
		}
		return out.toString();
	}

	private static String coords(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static BlockPos toAbsolute(BlockPos origin, GridPoint point) {
		return origin.add(point.x(), point.y(), point.z());
	}

	private static GridPoint toGridPoint(BlockPos pos) {
		return new GridPoint(pos.getX(), pos.getY(), pos.getZ());
	}

	private static BuildPlan shiftPlanForIssues(BuildPlan plan, List<SupportIssue> issues, List<String> repairs) {
		if (plan == null || issues == null || issues.isEmpty()) {
			return null;
		}
		ShiftResult result = shiftPlanForIssuesRecursive(plan, issues, repairs);
		return result.changed() ? result.plan() : null;
	}

	private static ShiftResult shiftPlanForIssuesRecursive(BuildPlan plan, List<SupportIssue> issues, List<String> repairs) {
		List<Integer> directDeltas = new ArrayList<>();
		Map<String, SupportIssue> issuesByName = new LinkedHashMap<>();
		for (SupportIssue issue : issues) {
			if (issue != null && issue.cuboid() != null && !issue.cuboid().isBlank()) {
				issuesByName.put(issue.cuboid(), issue);
			}
		}
		for (Cuboid cuboid : plan.cuboids()) {
			Integer delta = safeAutoFixDelta(issuesByName.get(cuboid.name()), Math.min(cuboid.from().y(), cuboid.to().y()));
			if (delta != null) {
				directDeltas.add(delta);
			}
		}
		for (BlockPlacement block : plan.blocks()) {
			Integer delta = safeAutoFixDelta(issuesByName.get(block.name()), block.pos().y());
			if (delta != null) {
				directDeltas.add(delta);
			}
		}
		if (!directDeltas.isEmpty()) {
			int shiftDelta = chooseShiftDelta(directDeltas);
			repairs.add("Auto-shifted build section by " + shiftDelta + " on Y to ground floating targets.");
			return new ShiftResult(shiftWholePlan(plan, shiftDelta), true);
		}

		boolean changed = false;
		List<BuildStep> shiftedSteps = new ArrayList<>();
		for (BuildStep step : plan.steps()) {
			ShiftResult result = shiftPlanForIssuesRecursive(step.plan(), issues, repairs);
			changed |= result.changed();
			shiftedSteps.add(new BuildStep(step.phase(), result.plan()));
		}
		if (!changed) {
			return new ShiftResult(plan, false);
		}
		return new ShiftResult(new BuildPlan(
				plan.summary(),
				plan.version(),
				plan.anchor(),
				plan.coordMode(),
				plan.origin(),
				plan.offset(),
				plan.rotationDegrees(),
				plan.autoFix(),
				plan.snapToGround(),
				plan.flattenTerrain(),
				plan.clearVegetation(),
				plan.phaseReorder(),
				plan.dryRun(),
				plan.batchUndo(),
				plan.rotateBlockStates(),
				plan.palette(),
				plan.anchors(),
				plan.clearVolumes(),
				plan.cuboids(),
				plan.blocks(),
				shiftedSteps
		), true);
	}

	private static int chooseShiftDelta(List<Integer> deltas) {
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		for (int delta : deltas) {
			counts.merge(delta, 1, Integer::sum);
		}
		return counts.entrySet().stream()
				.max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(entry -> Math.abs(entry.getKey())))
				.map(Map.Entry::getKey)
				.orElse(0);
	}

	private static Integer safeAutoFixDelta(SupportIssue issue, int currentY) {
		if (issue == null || issue.suggestedY() <= 0) {
			return null;
		}
		int delta = issue.suggestedY() - currentY;
		if (delta == 0) {
			return null;
		}
		if (Math.abs(delta) > MAX_AUTO_FIX_SHIFT) {
			return null;
		}
		return delta;
	}

	private static BuildPlan shiftWholePlan(BuildPlan plan, int deltaY) {
		if (plan == null || deltaY == 0) {
			return plan;
		}
		List<Volume> shiftedClear = new ArrayList<>();
		for (Volume volume : plan.clearVolumes()) {
			shiftedClear.add(new Volume(volume.name(), shiftPoint(volume.from(), deltaY), shiftPoint(volume.to(), deltaY), volume.replaceWith()));
		}
		List<Cuboid> shiftedCuboids = new ArrayList<>();
		for (Cuboid cuboid : plan.cuboids()) {
			shiftedCuboids.add(new Cuboid(
					cuboid.name(),
					cuboid.block(),
					cuboid.properties(),
					shiftPoint(cuboid.from(), deltaY),
					shiftPoint(cuboid.to(), deltaY),
					cuboid.fillMode(),
					cuboid.hollow()
			));
		}
		List<BlockPlacement> shiftedBlocks = new ArrayList<>();
		for (BlockPlacement block : plan.blocks()) {
			shiftedBlocks.add(new BlockPlacement(block.name(), block.block(), block.properties(), shiftPoint(block.pos(), deltaY)));
		}
		List<BuildStep> shiftedSteps = new ArrayList<>();
		for (BuildStep step : plan.steps()) {
			shiftedSteps.add(new BuildStep(step.phase(), shiftWholePlan(step.plan(), deltaY)));
		}
		return new BuildPlan(
				plan.summary(),
				plan.version(),
				plan.anchor(),
				plan.coordMode(),
				plan.origin(),
				plan.offset(),
				plan.rotationDegrees(),
				plan.autoFix(),
				plan.snapToGround(),
				plan.flattenTerrain(),
				plan.clearVegetation(),
				plan.phaseReorder(),
				plan.dryRun(),
				plan.batchUndo(),
				plan.rotateBlockStates(),
				plan.palette(),
				plan.anchors(),
				shiftedClear,
				shiftedCuboids,
				shiftedBlocks,
				shiftedSteps
		);
	}

	private static GridPoint shiftPoint(GridPoint point, int deltaY) {
		if (point == null || deltaY == 0) {
			return point;
		}
		return new GridPoint(point.x(), point.y() + deltaY, point.z());
	}

	private static BlockPos resolveOrigin(ServerPlayerEntity player, BuildPlan plan, List<String> repairs) {
		BlockPos playerOrigin = player.getBlockPos();
		String coordMode = normalizeCoordMode(plan.coordMode(), "player");
		if ("absolute".equals(coordMode)) {
			GridPoint explicit = plan.origin();
			BlockPos absoluteOrigin = new BlockPos(explicit.x(), explicit.y(), explicit.z());
			if (plan.offset() != null) {
				GridPoint offset = clampPoint(plan.offset(), repairs, "offset");
				absoluteOrigin = absoluteOrigin.add(offset.x(), offset.y(), offset.z());
			}
			return absoluteOrigin;
		}
		if ("anchor".equals(coordMode)) {
			StoredAnchorSet anchorSet = resolveStoredAnchorSet(plan.anchor());
			if (anchorSet != null) {
				String anchorName = parseAnchorName(plan.anchor());
				GridPoint point = anchorSet.anchors().get(anchorName);
				if (point != null) {
					BlockPos absoluteOrigin = new BlockPos(point.x(), point.y(), point.z());
					if (plan.offset() != null) {
						GridPoint offset = clampPoint(plan.offset(), repairs, "offset");
						absoluteOrigin = absoluteOrigin.add(offset.x(), offset.y(), offset.z());
					}
					return absoluteOrigin;
				}
			}
			repairs.add("Unknown anchor '" + plan.anchor() + "'; using player position as origin: " + playerOrigin.getX() + ", " + playerOrigin.getY() + ", " + playerOrigin.getZ() + ".");
			return playerOrigin;
		}
		GridPoint relative = plan.origin();
		if (relative == null) {
			relative = plan.offset();
		}
		if (relative == null) {
			repairs.add("Using player position as origin: " + playerOrigin.getX() + ", " + playerOrigin.getY() + ", " + playerOrigin.getZ() + ".");
			return playerOrigin;
		}
		GridPoint clamped = clampPoint(relative, repairs, "origin");
		return playerOrigin.add(clamped.x(), clamped.y(), clamped.z());
	}

	private static BlockPos findSurface(ServerWorld world, BlockPos pos, int baseY) {
		for (int dy = MAX_SITE_SCAN_UP; dy >= -MAX_SITE_SCAN_DOWN; dy--) {
			BlockPos candidate = new BlockPos(pos.getX(), baseY + dy, pos.getZ());
			if (isSurfaceCandidate(world, candidate)) {
				return candidate;
			}
		}
		return new BlockPos(pos.getX(), baseY - 1, pos.getZ());
	}

	private static boolean isSurfaceCandidate(ServerWorld world, BlockPos pos) {
		return isSupportiveTerrainBlock(world, pos);
	}

	static void rememberAnchors(CompiledBuild compiled, BuildPlan plan) {
		if (compiled == null || plan == null || compiled.resolvedOrigin() == null || plan.anchors() == null || plan.anchors().isEmpty()) {
			return;
		}
		Map<String, GridPoint> absoluteAnchors = new LinkedHashMap<>();
		int rotation = compiled.appliedRotation();
		GridPoint origin = compiled.resolvedOrigin();
		for (var entry : plan.anchors().entrySet()) {
			GridPoint point = rotatePoint(entry.getValue(), rotation);
			absoluteAnchors.put(entry.getKey(), new GridPoint(origin.x() + point.x(), origin.y() + point.y(), origin.z() + point.z()));
		}
		if (absoluteAnchors.isEmpty()) {
			return;
		}
		String label = plan.summary() == null || plan.summary().isBlank() ? LAST_BUILD_KEY : plan.summary().trim();
		StoredAnchorSet stored = new StoredAnchorSet(label, absoluteAnchors);
		STORED_ANCHORS.put(LAST_BUILD_KEY, stored);
		STORED_ANCHORS.put(label.toLowerCase(Locale.ROOT), stored);
	}

	private static StoredAnchorSet resolveStoredAnchorSet(String anchorRef) {
		if (anchorRef == null || anchorRef.isBlank()) {
			return null;
		}
		String normalized = anchorRef.trim();
		int split = normalized.indexOf(':');
		String buildKey = split <= 0 ? LAST_BUILD_KEY : normalized.substring(0, split).trim().toLowerCase(Locale.ROOT);
		return STORED_ANCHORS.get(buildKey);
	}

	private static String parseAnchorName(String anchorRef) {
		if (anchorRef == null || anchorRef.isBlank()) {
			return "";
		}
		int split = anchorRef.indexOf(':');
		return split < 0 ? anchorRef.trim() : anchorRef.substring(split + 1).trim();
	}

	record BuildPlan(
			String summary,
			int version,
			String anchor,
			String coordMode,
			GridPoint origin,
			GridPoint offset,
			int rotationDegrees,
			boolean autoFix,
			boolean snapToGround,
			boolean flattenTerrain,
			boolean clearVegetation,
			boolean phaseReorder,
			boolean dryRun,
			boolean batchUndo,
			boolean rotateBlockStates,
			Map<String, String> palette,
			Map<String, GridPoint> anchors,
			List<Volume> clearVolumes,
			List<Cuboid> cuboids,
			List<BlockPlacement> blocks,
			List<BuildStep> steps
	) {}

	record CompiledBuild(
			boolean valid,
			List<String> commands,
			String summary,
			List<String> repairs,
			String error,
			int appliedRotation,
			int phases,
			GridPoint resolvedOrigin,
			List<SupportIssue> issues,
			boolean autoFixAvailable
	) {}

	record GridPoint(int x, int y, int z) {}

	record Bounds(GridPoint from, GridPoint to) {
		long volume() {
			return (long) (to.x() - from.x() + 1)
					* (to.y() - from.y() + 1)
					* (to.z() - from.z() + 1);
		}
	}

	record Volume(String name, GridPoint from, GridPoint to, String replaceWith) {}

	record BuildStep(String phase, BuildPlan plan) {}

	record Cuboid(
			String name,
			String block,
			Map<String, String> properties,
			GridPoint from,
			GridPoint to,
			String fillMode,
			Boolean hollow
	) {}

	record BlockPlacement(String name, String block, Map<String, String> properties, GridPoint pos) {}

	record Placement(BlockPos pos, String blockString) {}

	record ColumnKey(int x, int z) {}

	record Pillar(BlockPos from, BlockPos to) {}

	record SupportRepair(
			boolean valid,
			List<String> commands,
			List<String> repairs,
			String error,
			List<SupportIssue> issues,
			boolean autoFixAvailable,
			int autoShiftDown
	) {
		static SupportRepair success() {
			return new SupportRepair(true, List.of(), List.of(), "", List.of(), false, 0);
		}

		static SupportRepair failure(String error) {
			return new SupportRepair(false, List.of(), List.of(), error, List.of(), false, 0);
		}
	}

	record SupportIssue(String cuboid, String issue, int gapBelow, int suggestedY) {}

	record SupportTarget(String name, BlockPos from, BlockPos to) {}

	record SurfaceCount(String blockId, int count) {}

	record ParsedBlockToken(String blockId, Map<String, String> properties) {}

	record StoredAnchorSet(String label, Map<String, GridPoint> anchors) {}

	record BuildSiteDetails(
			int radius,
			int minDy,
			int maxDy,
			int clearPercent,
			int waterColumns,
			int totalColumns,
			List<SurfaceCount> surfaceCounts
	) {}

	private static final class CompileAccumulator {
		private BlockPos resolvedOrigin;
		private final List<String> commands = new ArrayList<>();
		private final LinkedHashMap<BlockPos, String> occupiedBlocks = new LinkedHashMap<>();
		private final List<SupportTarget> supportTargets = new ArrayList<>();
		private long totalVolume = 0L;
		private int phases = 0;
		private int appliedRotation = 0;

		private CompileAccumulator(BlockPos resolvedOrigin) {
			this.resolvedOrigin = resolvedOrigin;
		}
	}

	private static final class MutableBounds {
		private GridPoint min;
		private GridPoint max;

		private void include(GridPoint point) {
			if (point == null) {
				return;
			}
			if (min == null) {
				min = point;
				max = point;
				return;
			}
			min = new GridPoint(Math.min(min.x(), point.x()), Math.min(min.y(), point.y()), Math.min(min.z(), point.z()));
			max = new GridPoint(Math.max(max.x(), point.x()), Math.max(max.y(), point.y()), Math.max(max.z(), point.z()));
		}

		private Bounds toBounds() {
			return min == null || max == null ? null : new Bounds(min, max);
		}
	}

	record TargetSupportStats(
			int totalColumns,
			int nearGroundColumns,
			int missingGroundColumns,
			int maxGap,
			int suggestedY,
			List<Integer> nearGaps,
			List<Pillar> pillars
	) {
		boolean hasIssue() {
			return missingGroundColumns > 0 || maxGap > 0;
		}
	}

	record ShiftResult(BuildPlan plan, boolean changed) {}

	record ResolvedBlock(
			String blockId,
			Map<String, String> properties,
			boolean valid,
			String error
	) {
		String blockString() {
			if (blockId == null || blockId.isBlank()) {
				return "";
			}
			return withProperties(blockId, properties);
		}
	}
}
