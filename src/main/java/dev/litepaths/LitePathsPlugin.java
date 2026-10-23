package dev.litepaths;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LitePathsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int DATA_MAGIC = 0x4C505448; // LPTH
    private static final int DATA_VERSION = 1;
    private static final Pattern TAG_NAME = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final Map<BlockKey, Integer> wear = new HashMap<>();
    private final Map<UUID, Location> lastSamples = new HashMap<>();

    private final Map<UUID, Location> pendingRoutes = new HashMap<>();
    private final Queue<Location> pendingWork = new ArrayDeque<>();
    private final Set<BlockKey> queuedBlocks = new HashSet<>();
    private Path dataFile;
    private BukkitTask flushTask;
    private BukkitTask workTask;
    private BukkitTask updateTask;
    private volatile boolean dirty;
    private long revision;
    private volatile String lastNotifiedRelease = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = getDataFolder().toPath().resolve("usage.dat");
        loadWear();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("litepaths").setExecutor(this);
        getCommand("litepaths").setTabCompleter(this);
        startTasks();
        scheduleUpdateChecks();
        getLogger().info("LitePaths enabled; loaded " + wear.size() + " wear locations.");
    }

    @Override
    public void onDisable() {
        cancel(flushTask); cancel(workTask); cancel(updateTask);
        saveWear();
    }

    private void startTasks() {
        cancel(flushTask); cancel(workTask);
        workTask = Bukkit.getScheduler().runTaskTimer(this, this::processWork, 1L, 1L);
        long flush = Math.max(30, getConfig().getLong("storage.flush-interval-seconds", 120));
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveWear, flush * 20L, flush * 20L);
    }

    private void cancel(BukkitTask task) { if (task != null) task.cancel(); }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("enabled", true)) return;
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        Player player = event.getPlayer();
        if (player.isFlying() && !getConfig().getBoolean("tracking.count-flying", false)) return;
        if (player.isInsideVehicle() && !getConfig().getBoolean("tracking.count-vehicles", false)) return;
        if (player.isSprinting() && !getConfig().getBoolean("tracking.count-sprinting", true)) return;
        if (player.isSwimming()) return;
        Location previous = lastSamples.get(player.getUniqueId());
        double distance = getConfig().getDouble("tracking.sample-distance", 1.0);
        if (previous != null && previous.getWorld() == event.getTo().getWorld()
                && previous.distanceSquared(event.getTo()) < distance * distance) return;
        lastSamples.put(player.getUniqueId(), event.getTo().clone());
        Location ground = groundAt(event.getTo());
        if (ground != null) enqueue(ground);
    }

    private Location groundAt(Location feet) {
        World world = feet.getWorld();
        if (world == null) return null;
        int x = feet.getBlockX(), z = feet.getBlockZ();
        int y = feet.getBlockY();
        for (int offset = 0; offset >= -2; offset--) {
            Block surface = world.getBlockAt(x, y + offset - 1, z);
            Block above = surface.getRelative(0, 1, 0);
            if (surface.isPassable() || !above.isPassable()) continue;
            if (getConfig().getBoolean("paths.require-clearance", true)
                    && !surface.getRelative(0, 2, 0).isPassable()) continue;
            return surface.getLocation();
        }
        return null;
    }

    private boolean isEligible(Block block) {
        if (!getConfig().getStringList("paths.eligible-blocks").contains(block.getType().name())) return false;
        return !isProtected(block.getLocation());
    }

    private boolean isEligibleOrWorn(Block block) {
        return (getConfig().getStringList("paths.eligible-blocks").contains(block.getType().name())
                || getConfig().getStringList("paths.stages").contains(block.getType().name()))
                && !isProtected(block.getLocation());
    }

    private void enqueue(Location location) {
        int configuredMax = getConfig().getInt("storage.max-locations", 100000);
        int queueLimit = Math.max(512, Math.min(10000, configuredMax > 0 ? configuredMax : 10000));
        BlockKey key = BlockKey.of(location.getBlock());
        if (pendingWork.size() < queueLimit && queuedBlocks.add(key)) pendingWork.add(location.clone());
    }

    private boolean isProtected(Location location) {
        // Claim integrations are optional. Conservative failure: if an installed integration cannot be queried,
        // skip the location rather than risk changing claimed land.
        if (getConfig().getBoolean("protection.worldguard", true) && enabled("WorldGuard")
                && !ProtectionChecks.worldGuardAllows(location)) return true;
        if (getConfig().getBoolean("protection.towny", true) && enabled("Towny")
                && !ProtectionChecks.townyAllows(location)) return true;
        if (getConfig().getBoolean("protection.griefprevention", true) && enabled("GriefPrevention")
                && !ProtectionChecks.griefPreventionAllows(location)) return true;
        return false;
    }

    private boolean enabled(String name) {
        return Bukkit.getPluginManager().getPlugin(name) != null && Bukkit.getPluginManager().isPluginEnabled(name);
    }

    private void processWork() {
        int budget = Math.max(1, getConfig().getInt("tracking.blocks-per-tick", 24));
        for (int i = 0; i < budget && !pendingWork.isEmpty(); i++) {
            Location location = pendingWork.poll();
            if (location == null || location.getWorld() == null) continue;
            queuedBlocks.remove(BlockKey.of(location.getBlock()));
            Block block = location.getBlock();
            if (!isEligibleOrWorn(block)) continue;
            BlockKey key = BlockKey.of(block);
            int threshold = Math.max(1, getConfig().getInt("tracking.samples-per-stage", 80));
            int count;
            synchronized (wear) {
                int max = getConfig().getInt("storage.max-locations", 100000);
                if (max > 0 && !wear.containsKey(key) && wear.size() >= max) continue;
                count = wear.getOrDefault(key, 0) + 1;
                wear.put(key, count);
                revision++;
                dirty = true;
            }
            int stage = count / threshold;
            List<String> stages = getConfig().getStringList("paths.stages");
            if (stage > 0 && stage <= stages.size() && count % threshold == 0) {
                int variation = getConfig().getInt("paths.variation-percent", 12);
                if (ThreadLocalRandom.current().nextInt(100) >= variation) {
                    Material target = Material.matchMaterial(stages.get(stage - 1));
                    if (target != null && isEligibleOrWorn(block)) block.setType(target, false);
                }
            }
        }
    }

    private void createRoute(Player player, Location first, Location second) {
        if (first.getWorld() != second.getWorld()) { player.sendMessage("§cBoth endpoints must be in the same world."); return; }
        double length = first.distance(second);
        int steps = Math.max(1, (int) Math.ceil(length * 2));
        if (steps > 10000) { player.sendMessage("§cThat route is too long (maximum 5,000 blocks)."); return; }
        int added = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location point = first.clone().add((second.getX() - first.getX()) * t,
                    (second.getY() - first.getY()) * t, (second.getZ() - first.getZ()) * t);
            Location ground = groundAt(point);
            if (ground == null || !isEligible(ground.getBlock())) continue;
            BlockKey key = BlockKey.of(ground.getBlock());
            int max = getConfig().getInt("storage.max-locations", 100000);
            synchronized (wear) {
                if (max > 0 && wear.size() >= max && !wear.containsKey(key)) continue;
                wear.put(key, Math.max(0, getConfig().getInt("tracking.samples-per-stage", 80) - 1));
                revision++;
                dirty = true;
            }
            enqueue(ground);
            added++;
        }
        player.sendMessage("§aQueued " + added + " eligible route points. Protected or unsuitable blocks were skipped.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("litepaths.admin")) { sender.sendMessage("§cYou do not have permission."); return true; }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "toggle" -> {
                boolean enabled = !getConfig().getBoolean("enabled", true);
                getConfig().set("enabled", enabled); saveConfig();
                sender.sendMessage("§aAutomatic path wear " + (enabled ? "enabled" : "disabled") + ".");
            }
            case "reload" -> {
                reloadConfig(); startTasks(); scheduleUpdateChecks();
                sender.sendMessage("§aLitePaths configuration reloaded.");
            }
            case "status" -> sender.sendMessage("§aLitePaths: "
                    + (getConfig().getBoolean("enabled", true) ? "enabled" : "disabled")
                    + "§a | tracked locations: " + wear.size()
                    + " | Towny: " + pluginState("Towny")
                    + " | WorldGuard: " + pluginState("WorldGuard")
                    + " | GriefPrevention: " + pluginState("GriefPrevention"));
            case "create" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cUse this command in-game."); return true; }
                Location first = pendingRoutes.remove(player.getUniqueId());
                if (first == null) {
                    pendingRoutes.put(player.getUniqueId(), player.getLocation().getBlock().getLocation());
                    player.sendMessage("§eFirst endpoint saved. Walk to the other endpoint and run /litepaths create again.");
                } else createRoute(player, first, player.getLocation().getBlock().getLocation());
            }
            case "cancel" -> {
                if (sender instanceof Player player) {
                    pendingRoutes.remove(player.getUniqueId()); player.sendMessage("§aPending route cancelled.");
                } else sender.sendMessage("§cUse this command in-game.");
            }
            default -> sender.sendMessage("§e/litepaths <toggle|reload|status|create|cancel>");
        }
        return true;
    }

    private String pluginState(String name) { return enabled(name) ? "active" : "not installed"; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        return List.of("toggle", "reload", "status", "create", "cancel").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }

    private void saveWear() {
        if (!dirty && Files.exists(dataFile)) return;
        Map<BlockKey, Integer> snapshot;
        long savedRevision;
        synchronized (wear) {
            snapshot = new HashMap<>(wear);
            savedRevision = revision;
        }
        try {
            Files.createDirectories(dataFile.getParent());
            Path temp = dataFile.resolveSibling("usage.dat.tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(DATA_MAGIC); out.writeInt(DATA_VERSION); out.writeInt(snapshot.size());
                for (Map.Entry<BlockKey, Integer> entry : snapshot.entrySet()) {
                    BlockKey key = entry.getKey();
                    out.writeUTF(key.world); out.writeInt(key.x); out.writeInt(key.y); out.writeInt(key.z);
                    out.writeInt(entry.getValue());
                }
            }
            Files.move(temp, dataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            synchronized (wear) { if (revision == savedRevision) dirty = false; }
        } catch (IOException ex) { getLogger().warning("Could not save usage data: " + ex.getMessage()); }
    }

    private void loadWear() {
        if (!Files.exists(dataFile)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            if (in.readInt() != DATA_MAGIC || in.readInt() != DATA_VERSION) throw new IOException("Unknown data format");
            int count = in.readInt();
            int max = getConfig().getInt("storage.max-locations", 100000);
            if (count < 0 || count > 5_000_000) throw new IOException("Invalid record count");
            for (int i = 0; i < count; i++) {
                BlockKey key = new BlockKey(in.readUTF(), in.readInt(), in.readInt(), in.readInt());
                int value = in.readInt();
                if (max <= 0 || wear.size() < max) wear.put(key, value);
            }
        } catch (IOException ex) { getLogger().warning("Could not load usage data: " + ex.getMessage()); }
    }

    private void scheduleUpdateChecks() {
        cancel(updateTask);
        if (!getConfig().getBoolean("update-check.enabled", true)) return;
        String repository = getConfig().getString("update-check.repository", "").trim();
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return;
        long hours = Math.max(1, getConfig().getLong("update-check.interval-hours", 12));
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> checkRelease(repository), 40L, hours * 60L * 60L * 20L);
    }

    private void checkRelease(String repository) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "LitePaths-UpdateChecker").GET().build();
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            response.thenAccept(result -> {
                if (result.statusCode() != 200) return;
                Matcher matcher = TAG_NAME.matcher(result.body());
                if (!matcher.find()) return;
                String latest = normalizeVersion(matcher.group(1));
                String current = normalizeVersion(getPluginMeta().getVersion());
                if (compareVersions(latest, current) <= 0 || matcher.group(1).equals(lastNotifiedRelease)) return;
                lastNotifiedRelease = matcher.group(1);
                Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.isOp() || p.hasPermission("litepaths.admin"))
                        .forEach(p -> p.sendMessage("§e[LitePaths] Update available: " + matcher.group(1)
                                + " — https://github.com/" + repository + "/releases/latest")));
            }).exceptionally(ex -> null);
        } catch (Exception ignored) { /* Update checks must never affect gameplay. */ }
    }

    private String normalizeVersion(String input) {
        return input.toLowerCase(Locale.ROOT).replaceFirst("^[v\\s]+", "").split("[-+]", 2)[0];
    }

    private int compareVersions(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long av = i < a.length ? parseVersionPart(a[i]) : 0;
            long bv = i < b.length ? parseVersionPart(b[i]) : 0;
            int comparison = Long.compare(av, bv);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private long parseVersionPart(String value) {
        Matcher digits = Pattern.compile("^\\d+").matcher(value);
        if (!digits.find()) return 0;
        try { return Long.parseLong(digits.group()); }
        catch (NumberFormatException ignored) { return Long.MAX_VALUE; }
    }

    private record BlockKey(String world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID().toString(), block.getX(), block.getY(), block.getZ());
        }
    }
}
