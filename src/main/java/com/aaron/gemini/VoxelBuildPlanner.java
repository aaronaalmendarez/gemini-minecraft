package com.aaron.gemini;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
	private static final String DEFAULT_FOUNDATION_BLOCK = "minecraft:stone_bricks";

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
		String summary = firstString(planObj, "summary", "description", "message");
		if ((summary == null || summary.isBlank()) && defaults != null) {
			summary = defaults.summary();
		}
		String anchor = firstString(planObj, "anchor");
		if ((anchor == null || anchor.isBlank()) && defaults != null) {
			anchor = defaults.anchor();
		}
		String coordMode = normalizeCoordMode(firstString(planObj, "coordMode", "coordinateMode"), defaults == null ? "player" : defaults.coordMode());
		int rotation = parseRotation(planObj, defaults == null ? 0 : defaults.rotationDegrees());
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

		Map<String, String> palette = new LinkedHashMap<>();
		if (defaults != null && defaults.palette() != null) {
			palette.putAll(defaults.palette());
		}
		palette.putAll(parsePalette(planObj));
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

		List<BuildStep> steps = parseStepArray(planObj, new BuildPlan(summary, anchor, coordMode, origin, offset, rotation, autoFix, palette, List.of(), List.of(), List.of(), List.of()));
		return new BuildPlan(summary, anchor, coordMode, origin, offset, rotation, autoFix, palette, clearVolumes, cuboids, blocks, steps);
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

		List<String> repairs = new ArrayList<>();
		BlockPos resolvedOrigin = resolveOrigin(player, plan, repairs);
		CompileAccumulator accumulator = new CompileAccumulator(resolvedOrigin);
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
			case "summary", "description", "message", "anchor", "coordMode", "coordinateMode", "origin", "offset", "autoFix", "palette", "clear", "cuboids", "blocks", "steps", "rotate", "rotation" -> true;
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
					stepPlan.anchor(),
					stepPlan.coordMode(),
					stepPlan.origin(),
					stepPlan.offset(),
					stepPlan.rotationDegrees(),
					stepPlan.autoFix(),
					stepPlan.palette(),
					stepPlan.clearVolumes(),
					stepPlan.cuboids(),
					stepPlan.blocks(),
					stepPlan.steps()
			)));
		}
		return steps;
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

	private static void parseVolumeArray(JsonObject obj, String key, List<Volume> out) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
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
		Bounds bounds = parseBounds(obj);
		if (bounds == null) {
			return null;
		}
		String name = firstString(obj, "name", "label");
		if (name == null || name.isBlank()) {
			name = fallbackName;
		}
		return new Volume(name, bounds.from(), bounds.to());
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
			case "player", "relative" -> "player";
			default -> fallback == null || fallback.isBlank() ? "player" : fallback;
		};
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
		if (palette != null && palette.containsKey(blockToken)) {
			repairs.add("Resolved palette alias '" + blockToken + "' to '" + palette.get(blockToken) + "'.");
			blockToken = palette.get(blockToken);
		}
		String normalizedToken = normalizeBlockToken(blockToken);
		if (!normalizedToken.equals(blockToken)) {
			repairs.add("Normalized block '" + blockToken + "' to '" + normalizedToken + "'.");
		}
		Identifier id = Identifier.tryParse(normalizedToken);
		if (id == null || !Registries.BLOCK.containsId(id)) {
			return new ResolvedBlock(null, null, false, "Unknown block '" + blockToken + "' in build plan entry '" + label + "'.");
		}
		Block block = Registries.BLOCK.get(id);
		BlockState state = block.getDefaultState();
		Map<String, String> safeProperties = new LinkedHashMap<>();
		if (properties != null) {
			for (var entry : properties.entrySet()) {
				String name = entry.getKey();
				String value = entry.getValue();
				if (name == null || value == null) {
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
		String anchor = plan.anchor() == null || plan.anchor().isBlank() ? "player" : plan.anchor().trim().toLowerCase(Locale.ROOT);
		if (!"player".equals(anchor)) {
			repairs.add("Unsupported build_plan.anchor '" + anchor + "'; using player.");
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
			accumulator.commands.add(fillCommand(start, end, "minecraft:air", null));
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
		orderedCuboids.sort(Comparator
				.comparingInt((Cuboid cuboid) -> cuboid.from().y())
				.thenComparingInt(cuboid -> cuboidOrderHint(cuboid.name()))
				.thenComparing(Cuboid::name, Comparator.nullsLast(String::compareToIgnoreCase)));
		for (Cuboid cuboid : orderedCuboids) {
			ResolvedBlock resolved = resolveBlock(cuboid.block(), rotateProperties(cuboid.properties(), rotation), plan.palette(), repairs, cuboid.name());
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
			ResolvedBlock resolved = resolveBlock(block.block(), rotateProperties(block.properties(), rotation), plan.palette(), repairs, block.name());
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
		Map<String, Integer> deltasByName = new LinkedHashMap<>();
		for (SupportIssue issue : issues) {
			if (issue == null || issue.cuboid() == null || issue.cuboid().isBlank() || issue.gapBelow() <= 0) {
				continue;
			}
			deltasByName.put(issue.cuboid(), -issue.gapBelow());
		}
		if (deltasByName.isEmpty()) {
			return null;
		}
		ShiftResult result = shiftPlanForIssuesRecursive(plan, deltasByName, repairs);
		return result.changed() ? result.plan() : null;
	}

	private static ShiftResult shiftPlanForIssuesRecursive(BuildPlan plan, Map<String, Integer> deltasByName, List<String> repairs) {
		List<Integer> directDeltas = new ArrayList<>();
		for (Cuboid cuboid : plan.cuboids()) {
			Integer delta = deltasByName.get(cuboid.name());
			if (delta != null && delta != 0) {
				directDeltas.add(delta);
			}
		}
		for (BlockPlacement block : plan.blocks()) {
			Integer delta = deltasByName.get(block.name());
			if (delta != null && delta != 0) {
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
			ShiftResult result = shiftPlanForIssuesRecursive(step.plan(), deltasByName, repairs);
			changed |= result.changed();
			shiftedSteps.add(new BuildStep(step.phase(), result.plan()));
		}
		if (!changed) {
			return new ShiftResult(plan, false);
		}
		return new ShiftResult(new BuildPlan(
				plan.summary(),
				plan.anchor(),
				plan.coordMode(),
				plan.origin(),
				plan.offset(),
				plan.rotationDegrees(),
				plan.autoFix(),
				plan.palette(),
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

	private static BuildPlan shiftWholePlan(BuildPlan plan, int deltaY) {
		if (plan == null || deltaY == 0) {
			return plan;
		}
		List<Volume> shiftedClear = new ArrayList<>();
		for (Volume volume : plan.clearVolumes()) {
			shiftedClear.add(new Volume(volume.name(), shiftPoint(volume.from(), deltaY), shiftPoint(volume.to(), deltaY)));
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
				plan.anchor(),
				plan.coordMode(),
				plan.origin(),
				plan.offset(),
				plan.rotationDegrees(),
				plan.autoFix(),
				plan.palette(),
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
			if (explicit == null) {
				repairs.add("Using player position as origin: " + playerOrigin.getX() + ", " + playerOrigin.getY() + ", " + playerOrigin.getZ() + ".");
				explicit = toGridPoint(playerOrigin);
			}
			BlockPos absoluteOrigin = new BlockPos(explicit.x(), explicit.y(), explicit.z());
			if (plan.offset() != null) {
				GridPoint offset = clampPoint(plan.offset(), repairs, "offset");
				absoluteOrigin = absoluteOrigin.add(offset.x(), offset.y(), offset.z());
			}
			return absoluteOrigin;
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

	record BuildPlan(
			String summary,
			String anchor,
			String coordMode,
			GridPoint origin,
			GridPoint offset,
			int rotationDegrees,
			boolean autoFix,
			Map<String, String> palette,
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

	record Volume(String name, GridPoint from, GridPoint to) {}

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
		private final BlockPos resolvedOrigin;
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
