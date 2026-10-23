package dev.litepaths;

import org.bukkit.Location;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional protection bridges use reflection so servers without these plugins have no linkage dependency. */
final class ProtectionChecks {
    private ProtectionChecks() {}

    static boolean worldGuardAllows(Location location) {
        try {
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(wg);
            Class<?> platformApi = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform");
            Object container = platformApi.getMethod("getRegionContainer").invoke(platform);
            Class<?> adapter = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter");
            Object adaptedLocation = adapter.getMethod("adapt", Location.class).invoke(null, location);
            Class<?> containerApi = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer");
            Object query = containerApi.getMethod("createQuery").invoke(container);
            Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Field buildField = flagsClass.getField("BUILD");
            Object buildFlag = buildField.get(null);
            Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
            Object flags = Array.newInstance(stateFlagClass, 1);
            Array.set(flags, 0, buildFlag);
            Class<?> queryApi = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery");
            Method testState = java.util.Arrays.stream(queryApi.getMethods())
                    .filter(method -> method.getName().equals("testState") && method.getParameterCount() == 3
                            && method.getParameterTypes()[2].isArray()).findFirst().orElseThrow();
            // Treat the location as an unassociated actor, so claimed regions stay protected regardless of player.
            return Boolean.TRUE.equals(testState.invoke(query, adaptedLocation, null, flags));
        } catch (ClassNotFoundException ignored) {
            return true;
        } catch (Throwable ignored) {
            return false; // Fail closed if WorldGuard is present but its API cannot be queried.
        }
    }

    static boolean townyAllows(Location location) {
        try {
            Class<?> api = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            Object instance = api.getMethod("getInstance").invoke(null);
            Object wilderness = api.getMethod("isWilderness", Location.class).invoke(instance, location);
            return Boolean.TRUE.equals(wilderness);
        } catch (ClassNotFoundException ignored) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean griefPreventionAllows(Location location) {
        try {
            Class<?> gp = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Field instanceField = gp.getField("instance");
            Object instance = instanceField.get(null);
            Object store = gp.getField("dataStore").get(instance);
            Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
            Method getClaimAt = store.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
            Object claim = getClaimAt.invoke(store, location, true, null);
            return claim == null;
        } catch (ClassNotFoundException ignored) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
