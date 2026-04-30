package me.cortex.voxy.server.compat;

import me.cortex.voxy.server.VoxyServerMod;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional integration with Sable (dev.ryanhcode.sable). Sable manages its own
 * SubLevel tracking distance separately from vanilla's entity tracker;
 * physics structures (Aeronautica assemblies, etc.) are streamed via Sable's
 * own pipeline -- they do not appear in {@code level.getEntities(...)} and
 * cannot be force-tracked through {@code ChunkMap.entityMap}.
 *
 * Sable's tracking distance is governed by {@code SableConfig.SUB_LEVEL_TRACKING_RANGE}
 * (NeoForge ModConfigSpec, default 320 blocks). When voxy-server's LOD radius
 * is larger than that, SubLevels stop streaming long before LOD terrain does
 * and physics structures appear to "vanish" past the Sable range. We lift
 * Sable's range to match {@code lodStreamRadius * 32} so the two stay in sync.
 *
 * Reflection is used to avoid a hard compile-time dependency on Sable; if the
 * mod isn't installed (or its config class isn't reachable for any reason)
 * the helper logs a warning at most once and otherwise no-ops.
 *
 * Lift-only: if the user has manually set a value higher than what we'd want,
 * we leave it alone. Never lowers an existing setting.
 */
public final class SableIntegration {
	private static final String SABLE_MOD_ID = "sable";
	private static final String SABLE_CONFIG_CLASS = "dev.ryanhcode.sable.SableConfig";
	private static final String FIELD_NAME = "SUB_LEVEL_TRACKING_RANGE";

	private static volatile boolean lookupAttempted;
	private static volatile Object trackingRangeValue; // ModConfigSpec.DoubleValue
	private static volatile boolean reflectionWarned;

	private SableIntegration() {}

	public static boolean isPresent() {
		return FabricLoader.getInstance().isModLoaded(SABLE_MOD_ID);
	}

	/**
	 * Current Sable tracking range in blocks, or {@code -1} if Sable isn't loaded
	 * or the value isn't reachable via reflection.
	 */
	public static double currentRange() {
		Object value = lookup();
		if (value == null) return -1;
		try {
			return (double) value.getClass().getMethod("getAsDouble").invoke(value);
		} catch (Throwable t) {
			return -1;
		}
	}

	/**
	 * Lift Sable's tracking range to {@code desiredBlocks} if its current value
	 * is lower. No-op if Sable isn't loaded or reflection fails.
	 *
	 * @return {@code true} if the value was changed; {@code false} if Sable
	 *         isn't loaded, the lookup failed, or the current value was
	 *         already at or above {@code desiredBlocks}.
	 */
	public static boolean liftTrackingRange(double desiredBlocks) {
		if (!isPresent()) return false;
		Object value = lookup();
		if (value == null) return false;
		try {
			Method getAsDouble = value.getClass().getMethod("getAsDouble");
			Method set = value.getClass().getMethod("set", Object.class);
			double current = (double) getAsDouble.invoke(value);
			if (current >= desiredBlocks) {
				VoxyServerMod.LOGGER.info(
					"[Sable] sub_level_tracking_range={} already >= desired={}, leaving alone",
					current, desiredBlocks);
				return false;
			}
			set.invoke(value, desiredBlocks);
			VoxyServerMod.LOGGER.info(
				"[Sable] sub_level_tracking_range bumped {} -> {} (matching lodStreamRadius)",
				current, desiredBlocks);
			return true;
		} catch (Throwable t) {
			warnReflectionFailure(t);
			return false;
		}
	}

	private static Object lookup() {
		if (lookupAttempted) return trackingRangeValue;
		synchronized (SableIntegration.class) {
			if (lookupAttempted) return trackingRangeValue;
			if (isPresent()) {
				try {
					Class<?> configClass = Class.forName(SABLE_CONFIG_CLASS);
					Field field = configClass.getField(FIELD_NAME);
					trackingRangeValue = field.get(null);
				} catch (Throwable t) {
					warnReflectionFailure(t);
				}
			}
			lookupAttempted = true;
			return trackingRangeValue;
		}
	}

	private static void warnReflectionFailure(Throwable t) {
		if (reflectionWarned) return;
		reflectionWarned = true;
		VoxyServerMod.LOGGER.warn(
			"[Sable] Sable mod is loaded but reflection on {}.{} failed ({}); SubLevel range will not be lifted",
			SABLE_CONFIG_CLASS, FIELD_NAME, t.getClass().getSimpleName() + ": " + t.getMessage());
	}
}
