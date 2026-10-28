package ru.grief.rtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class RtpPlugin extends JavaPlugin implements TabExecutor, Listener {

    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.COBWEB, Material.WITHER_ROSE, Material.LAVA, Material.WATER,
            Material.BEDROCK, Material.TNT
    );

    private final Map<UUID, Long> nearCooldown = new HashMap<>();
    private final Map<UUID, Long> baseCooldown = new HashMap<>();
    private final Map<UUID, Long> healCooldown = new HashMap<>();
    private final Map<UUID, Long> feedCooldown = new HashMap<>();
    private final Map<UUID, Long> backCooldown = new HashMap<>();
    private final Map<UUID, Location> backLocation = new HashMap<>();
    private final Set<UUID> busy = new java.util.HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        getCommand("rtpnear").setExecutor(this);
        getCommand("rtpnear").setTabCompleter(this);
        getCommand("rtpbase").setExecutor(this);
        getCommand("rtpbase").setTabCompleter(this);
        getCommand("uprefix").setExecutor(this);
        getCommand("uprefix").setTabCompleter(this);
        getCommand("heal").setExecutor(this);
        getCommand("heal").setTabCompleter(this);
        getCommand("feed").setExecutor(this);
        getCommand("feed").setTabCompleter(this);
        getCommand("back").setExecutor(this);
        getCommand("back").setTabCompleter(this);
        getCommand("player").setExecutor(this);
        getCommand("player").setTabCompleter(this);
        getCommand("prefix").setExecutor(this);
        getCommand("prefix").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        // Через тик, когда все плагины уже загружены, перебиваем чужие /heal и /feed
        Bukkit.getScheduler().runTask(this, () -> overrideCommands("heal", "feed", "prefix"));
    }

    private void overrideCommands(String... names) {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        for (String n : names) {
            PluginCommand ours = getCommand(n);
            if (ours == null) continue;
            known.put(n, ours);
        }
        for (Player pl : Bukkit.getOnlinePlayers()) pl.updateCommands();
    }

    private String msg(String key) {
        String v = getConfig().getString("messages." + key);
        return ChatColor.translateAlternateColorCodes('&', v != null ? v : key);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("uprefix") || name.equals("prefix")) {
            return handlePrefixCmd(sender, args, name);
        }

        if (name.equals("player")) {
            return handleMask(sender, args);
        }

        if (name.equals("back")) {
            return handleBack(sender);
        }

        if (name.equals("heal") || name.equals("feed")) {
            return handleHealFeed(sender, args, name.equals("heal"));
        }

        if (name.equals("rtpnear") && args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rtp.reload")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("only-player"));
            return true;
        }

        World world = Bukkit.getWorld(getConfig().getString("world", "grief-1"));
        if (world == null) {
            p.sendMessage(msg("no-world"));
            return true;
        }

        if (name.equals("rtpnear")) {
            handleNear(p, world);
        } else {
            handleBase(p, world, args);
        }
        return true;
    }

    // ---------------------------------------------------------------- /back

    private boolean backWorldAllowed(World w) {
        return w != null && getConfig().getStringList("back.worlds").contains(w.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Location loc = p.getLocation();
        if (backWorldAllowed(loc.getWorld())) {
            backLocation.put(p.getUniqueId(), loc.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        TeleportCause cause = e.getCause();
        if (cause == TeleportCause.NETHER_PORTAL || cause == TeleportCause.END_PORTAL
                || cause == TeleportCause.END_GATEWAY || cause == TeleportCause.ENDER_PEARL
                || cause == TeleportCause.CHORUS_FRUIT) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getWorld() == null || !backWorldAllowed(from.getWorld())) return;
        // мелкие перемещения (до 5 блоков в том же мире) не запоминаем
        if (to != null && from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) < 25) return;
        backLocation.put(e.getPlayer().getUniqueId(), from.clone());
    }

    private boolean handleBack(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("only-player"));
            return true;
        }
        if (!p.hasPermission("grief.back")) {
            p.sendMessage(msg("no-permission"));
            return true;
        }
        if (!backWorldAllowed(p.getWorld())) {
            p.sendMessage(msg("back-world"));
            return true;
        }
        Location target = backLocation.get(p.getUniqueId());
        if (target == null || target.getWorld() == null
                || Bukkit.getWorld(target.getWorld().getName()) == null
                || !backWorldAllowed(target.getWorld())) {
            p.sendMessage(msg("back-none"));
            return true;
        }
        if (!checkCooldown(p, backCooldown)) return true;

        Location dest = target.clone();
        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());
        int cd = getConfig().getInt("back.cooldown-seconds", 300);
        p.teleportAsync(dest).thenAccept(ok -> {
            if (ok && p.isOnline()) {
                p.sendMessage(msg("back-done"));
                backCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cd * 1000L);
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- /heal /feed

    private boolean handleHealFeed(CommandSender sender, String[] args, boolean heal) {
        String perm = heal ? "grief.heal" : "grief.feed";
        String key = heal ? "heal" : "feed";
        Map<UUID, Long> cdMap = heal ? healCooldown : feedCooldown;

        Player target;
        boolean other = args.length >= 1;
        if (other) {
            if (!sender.hasPermission(perm + ".others")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(msg("player-offline"));
                return true;
            }
        } else {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(msg(key + "-usage"));
                return true;
            }
            if (!self.hasPermission(perm)) {
                self.sendMessage(msg("no-permission"));
                return true;
            }
            target = self;
        }

        Player caster = sender instanceof Player sp ? sp : null;
        if (caster != null && !checkCooldown(caster, cdMap)) return true;

        if (heal) {
            var maxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            target.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
            target.setFireTicks(0);
        } else {
            target.setFoodLevel(20);
            target.setSaturation(20f);
        }

        if (caster != null) {
            int cd = getConfig().getInt(key + ".cooldown-seconds", heal ? 60 : 30);
            cdMap.put(caster.getUniqueId(), System.currentTimeMillis() + cd * 1000L);
        }

        if (other && !target.equals(caster)) {
            sender.sendMessage(msg(key + "-other").replace("%player%", target.getName()));
            target.sendMessage(msg(key + "-by").replace("%player%", sender.getName()));
        } else {
            target.sendMessage(msg(key + "-self"));
        }
        return true;
    }

    // ---------------------------------------------------------------- data.yml

    private File dataFile;
    private YamlConfiguration data;

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- TAB

    private void tabSet(String player, String prop, String value) {
        // значение в кавычках, чтобы TAB сохранил пробел в конце
        String line = "tab player " + player + " " + prop + (value == null ? "" : " \"" + value + "\"");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
    }

    private List<String> props(String path, List<String> def) {
        List<String> l = getConfig().getStringList(path);
        return l.isEmpty() ? def : l;
    }

    /** Пересчитывает свойства TAB игрока: маска /player > /prefix (чат) > /uprefix (таб, над головой). */
    private void refreshTab(Player t) {
        String id = "players." + t.getUniqueId();
        String up = data.getString(id + ".uprefix");
        String chat = data.getString(id + ".prefix");
        boolean mask = data.getBoolean(id + ".mask");

        List<String> upProps = props("uprefix.properties", List.of("tabprefix", "tagprefix"));
        List<String> chatProps = props("prefix.properties", List.of("chatprefix"));
        List<String> maskProps = props("player.properties", List.of("tabprefix", "tagprefix", "chatprefix"));

        // оформление ника после префикса (белый жирный); цвет идёт перед жирным, иначе &f сбросит жирность
        String nameStyle = getConfig().getString("name-style", "&f&l");

        Map<String, String> want = new java.util.LinkedHashMap<>();
        for (String p : upProps) want.put(p, null);
        for (String p : chatProps) want.put(p, null);
        for (String p : maskProps) want.put(p, null);

        if (up != null) {
            String v = up + getConfig().getString("uprefix.suffix", " ") + nameStyle;
            for (String p : upProps) want.put(p, v);
        }
        if (chat != null) {
            String v = chat + getConfig().getString("prefix.suffix", " ") + nameStyle;
            for (String p : chatProps) want.put(p, v);
        }
        if (mask) {
            String v = getConfig().getString("player.text", "&f&lИгрок")
                    + getConfig().getString("player.suffix", " &r");
            for (String p : maskProps) want.put(p, v);
        }
        want.forEach((prop, val) -> tabSet(t.getName(), prop, val));
    }

    // ---------------------------------------------------------------- /player on|off

    private boolean handleMask(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("only-player"));
            return true;
        }
        if (!p.hasPermission("grief.player")) {
            p.sendMessage(msg("no-permission"));
            return true;
        }
        if (args.length != 1 || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            p.sendMessage(msg("player-usage"));
            return true;
        }
        boolean on = args[0].equalsIgnoreCase("on");
        data.set("players." + p.getUniqueId() + ".mask", on ? Boolean.TRUE : null);
        saveData();
        refreshTab(p);
        p.sendMessage(msg(on ? "player-on" : "player-off"));
        return true;
    }

    // ---------------------------------------------------------------- /uprefix и /prefix

    /** kind = "uprefix" (таб + над головой) или "prefix" (только чат). */
    private boolean handlePrefixCmd(CommandSender sender, String[] args, String kind) {
        boolean isU = kind.equals("uprefix");
        String usePerm = isU ? "uprefix.use" : "grief.prefix";
        String adminPerm = isU ? "uprefix.admin" : "grief.prefix.admin";
        boolean admin = sender.hasPermission(adminPerm);

        if (args.length == 0) {
            sender.sendMessage(msg(kind + "-usage"));
            return true;
        }

        if (isU && args[0].equalsIgnoreCase("reload") && args.length == 1 && admin) {
            reloadConfig();
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        // set <игрок> <текст>  (админ)
        if (args[0].equalsIgnoreCase("set") && args.length >= 3) {
            if (!admin) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            applyPrefix(sender, args[1], text, kind);
            return true;
        }

        // reset [игрок]
        if (args[0].equalsIgnoreCase("reset")) {
            if (args.length >= 2) {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                applyPrefix(sender, args[1], null, kind);
                return true;
            }
            if (!(sender instanceof Player p)) {
                sender.sendMessage(msg("only-player"));
                return true;
            }
            if (!p.hasPermission(usePerm)) {
                p.sendMessage(msg("no-permission"));
                return true;
            }
            applyPrefix(sender, p.getName(), null, kind);
            return true;
        }

        // <текст>
        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("only-player"));
            return true;
        }
        if (!p.hasPermission(usePerm)) {
            p.sendMessage(msg("no-permission"));
            return true;
        }
        applyPrefix(sender, p.getName(), String.join(" ", args), kind);
        return true;
    }

    private void applyPrefix(CommandSender sender, String targetName, String text, String kind) {
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) {
            sender.sendMessage(msg("player-offline"));
            return;
        }

        String key = "players." + t.getUniqueId() + "." + kind;
        if (text == null) {
            data.set(key, null);
        } else {
            text = text.trim();
            String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text));
            int maxLen = getConfig().getInt(kind + ".max-length", 16);
            if (plain.isEmpty()) {
                sender.sendMessage(msg(kind + "-usage"));
                return;
            }
            if (plain.length() > maxLen) {
                sender.sendMessage(msg("uprefix-too-long").replace("%max%", String.valueOf(maxLen)));
                return;
            }
            // без переводов строк, ; \\ кавычек и % (защита от инъекций и плейсхолдеров)
            if (!text.matches("[^\\r\\n;\\\\\"%]+")) {
                sender.sendMessage(msg("uprefix-bad-chars"));
                return;
            }
            data.set(key, text);
        }
        saveData();
        refreshTab(t);

        if (text == null) {
            sender.sendMessage(msg(kind + "-reset"));
        } else {
            sender.sendMessage(msg(kind + "-set").replace("%prefix%",
                    ChatColor.translateAlternateColorCodes('&', text)));
        }
    }

    // ---------------------------------------------------------------- /rtpnear

    private void handleNear(Player p, World world) {
        if (!p.hasPermission("rtp.near")) {
            p.sendMessage(msg("no-permission"));
            return;
        }
        if (!checkCooldown(p, nearCooldown)) return;

        List<Player> candidates = new ArrayList<>();
        for (Player other : world.getPlayers()) {
            if (other.getUniqueId().equals(p.getUniqueId())) continue;
            if (other.getGameMode() == GameMode.SPECTATOR) continue;
            if (isVanished(other)) continue;
            candidates.add(other);
        }
        if (candidates.isEmpty()) {
            p.sendMessage(msg("no-players"));
            return;
        }

        Location center = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).getLocation();
        int min = getConfig().getInt("near.min-distance", 60);
        int max = getConfig().getInt("near.max-distance", 180);

        teleportAround(p, world, center, min, max, "done-near", nearCooldown,
                getConfig().getInt("near.cooldown-seconds", 300));
    }

    // ---------------------------------------------------------------- /rtpbase

    private void handleBase(Player p, World world, String[] args) {
        if (!p.hasPermission("rtp.base")) {
            p.sendMessage(msg("no-permission"));
            return;
        }
        if (!checkCooldown(p, baseCooldown)) return;

        Location base = null;

        if (args.length >= 1) {
            if (!p.hasPermission("rtp.base.target")) {
                p.sendMessage(msg("no-permission"));
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null) {
                p.sendMessage(msg("player-not-found"));
                return;
            }
            base = bedIn(target, world);
            if (base == null) {
                p.sendMessage(msg("no-bed"));
                return;
            }
        } else {
            long activeMs = getConfig().getLong("base.active-days", 30) * 86_400_000L;
            long now = System.currentTimeMillis();
            int maxChecked = getConfig().getInt("base.max-checked-players", 40);

            List<OfflinePlayer> pool = new ArrayList<>();
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getUniqueId().equals(p.getUniqueId())) continue;
                if (!op.isOnline() && now - op.getLastPlayed() > activeMs) continue;
                pool.add(op);
            }
            Collections.shuffle(pool);

            int checked = 0;
            for (OfflinePlayer op : pool) {
                if (checked++ >= maxChecked) break;
                Location bed = bedIn(op, world);
                if (bed != null) {
                    base = bed;
                    break;
                }
            }
            if (base == null) {
                p.sendMessage(msg("no-bases"));
                return;
            }
        }

        int min = getConfig().getInt("base.min-distance", 50);
        int max = getConfig().getInt("base.max-distance", 120);

        teleportAround(p, world, base, min, max, "done-base", baseCooldown,
                getConfig().getInt("base.cooldown-seconds", 300));
    }

    private Location bedIn(OfflinePlayer op, World world) {
        @SuppressWarnings("deprecation")
        Location bed = op.getBedSpawnLocation();
        if (bed == null || bed.getWorld() == null || !bed.getWorld().equals(world)) return null;
        return bed;
    }

    // ---------------------------------------------------------------- teleport

    private void teleportAround(Player p, World world, Location center, int min, int max,
                                String doneKey, Map<UUID, Long> cdMap, int cdSeconds) {
        if (!busy.add(p.getUniqueId())) return;
        p.sendMessage(msg("searching"));

        int attempts = getConfig().getInt("attempts", 20);
        findSpot(world, center.getBlockX(), center.getBlockZ(), min, max, attempts, p, loc -> {
            busy.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (loc == null) {
                p.sendMessage(msg("no-safe"));
                return;
            }
            loc.setYaw(p.getLocation().getYaw());
            loc.setPitch(p.getLocation().getPitch());
            p.teleportAsync(loc).thenAccept(ok -> {
                if (ok && p.isOnline()) {
                    p.sendMessage(msg(doneKey));
                    cdMap.put(p.getUniqueId(), System.currentTimeMillis() + cdSeconds * 1000L);
                }
            });
        });
    }

    private void findSpot(World world, int cx, int cz, int min, int max, int attemptsLeft,
                          Player p, Consumer<Location> callback) {
        if (attemptsLeft <= 0 || !p.isOnline()) {
            callback.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        double dist = min + r.nextDouble() * Math.max(0, max - min);
        int x = (int) Math.floor(cx + Math.cos(angle) * dist);
        int z = (int) Math.floor(cz + Math.sin(angle) * dist);

        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
            findSpot(world, cx, cz, min, max, attemptsLeft - 1, p, callback);
            return;
        }

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Location safe = safeLocation(world, x, z);
            if (safe != null) {
                callback.accept(safe);
            } else {
                findSpot(world, cx, cz, min, max, attemptsLeft - 1, p, callback);
            }
        }).exceptionally(ex -> {
            findSpot(world, cx, cz, min, max, attemptsLeft - 1, p, callback);
            return null;
        });
    }

    private Location safeLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 3) return null;

        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);

        if (!ground.getType().isSolid() || UNSAFE.contains(ground.getType())) return null;
        if (!feet.isPassable() || feet.isLiquid() || UNSAFE.contains(feet.getType())) return null;
        if (!head.isPassable() || head.isLiquid() || UNSAFE.contains(head.getType())) return null;

        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    // ---------------------------------------------------------------- helpers

    private boolean checkCooldown(Player p, Map<UUID, Long> map) {
        if (p.hasPermission("rtp.bypass.cooldown")) return true;
        Long until = map.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (until != null && until > now) {
            long left = (until - now + 999) / 1000;
            p.sendMessage(msg("cooldown").replace("%seconds%", String.valueOf(left)));
            return false;
        }
        return true;
    }

    private boolean isVanished(Player pl) {
        return pl.getMetadata("vanished").stream().anyMatch(m -> m.asBoolean());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("player")) {
            if (args.length == 1) {
                for (String o : List.of("on", "off")) if (o.startsWith(args[0].toLowerCase())) out.add(o);
            }
            return out;
        }
        if (cmd.getName().equalsIgnoreCase("uprefix") || cmd.getName().equalsIgnoreCase("prefix")) {
            boolean isU = cmd.getName().equalsIgnoreCase("uprefix");
            boolean admin = sender.hasPermission(isU ? "uprefix.admin" : "grief.prefix.admin");
            if (args.length == 1) {
                out.add("reset");
                if (admin) {
                    out.add("set");
                    if (isU) out.add("reload");
                }
                out.removeIf(o -> !o.startsWith(args[0].toLowerCase()));
            } else if (args.length == 2 && admin
                    && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("reset"))) {
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
                }
            }
            return out;
        }
        if ((cmd.getName().equalsIgnoreCase("heal") || cmd.getName().equalsIgnoreCase("feed"))
                && args.length == 1
                && sender.hasPermission("grief." + cmd.getName().toLowerCase() + ".others")) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(pl.getName());
            }
            return out;
        }
        if (cmd.getName().equalsIgnoreCase("rtpbase") && args.length == 1
                && sender.hasPermission("rtp.base.target")) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(pl.getName());
            }
        }
        return out;
    }
}
