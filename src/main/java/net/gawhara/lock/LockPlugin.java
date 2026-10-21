package net.gawhara.lock;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GawharaLock - باسورد للسيرفر. اللي معاه الباسورد بس يلعب.
 * الأدمن بيحط الباسورد بـ /glockadmin set، واللاعبين بيدخلوه بـ /glock.
 */
public final class LockPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** اللاعبين المحبوسين => وقت دخولهم. */
    private final Map<UUID, Long> locked = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSubmit = new ConcurrentHashMap<>();
    /** اللي شاشة الباسورد مفتوحة عندهم دلوقتي. */
    private final Set<UUID> guiOpen = ConcurrentHashMap.newKeySet();
    /** بنفتح الشاشة بنفسنا (عشان نسمح بيها في InventoryOpenEvent). */
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    private NamespacedKey blindKey;
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lockoutUntil = new ConcurrentHashMap<>();
    private final Set<UUID> session = ConcurrentHashMap.newKeySet();

    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask task;

    // ------------------------------------------------------------------ تشغيل

    @Override
    public void onEnable() {
        saveDefaultConfig();
        blindKey = new NamespacedKey(this, "lock_blind");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"glock", "glockadmin"}) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        task = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        // لو السيرفر اتعمله reload والباسورد متفعّل
        for (Player p : Bukkit.getOnlinePlayers()) {
            checkJoin(p);
        }
        getLogger().info(hasPassword() ? "البوابة متفعّلة." : "مفيش باسورد لسه. استخدم /glockadmin set <باسورد>");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------ الباسورد والبيانات

    private boolean hasPassword() {
        return data.contains("password.hash");
    }

    private String version() {
        return data.getString("password.version", "");
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("مقدرتش أحفظ data.yml: " + e.getMessage());
        }
    }

    private void setPassword(String password) {
        byte[] salt = Hasher.newSalt();
        data.set("password.salt", Base64.getEncoder().encodeToString(salt));
        data.set("password.hash", Hasher.hash(password, salt));
        data.set("password.version", UUID.randomUUID().toString());
        data.set("verified", null); // أي حد معاه الباسورد القديم يدخله تاني
        session.clear();
        saveData();
    }

    private boolean checkPassword(String given) {
        String salt = data.getString("password.salt");
        String hash = data.getString("password.hash");
        if (salt == null || hash == null) {
            return false;
        }
        return Hasher.matches(given, Base64.getDecoder().decode(salt), hash);
    }

    private boolean permanent() {
        return !"session".equalsIgnoreCase(getConfig().getString("lock.remember", "permanent"));
    }

    private boolean isVerified(Player p) {
        if (p.hasPermission("gawharalock.bypass")) {
            return true;
        }
        if (!permanent()) {
            return session.contains(p.getUniqueId());
        }
        return version().equals(data.getString("verified." + p.getUniqueId() + ".version"));
    }

    private void markVerified(Player p) {
        session.add(p.getUniqueId());
        if (permanent()) {
            String path = "verified." + p.getUniqueId();
            data.set(path + ".version", version());
            data.set(path + ".name", p.getName());
            saveData();
        }
    }

    // ------------------------------------------------------------------ القفل

    private boolean isLocked(Player p) {
        return locked.containsKey(p.getUniqueId());
    }

    private void checkJoin(Player p) {
        if (hasPassword() && !isVerified(p)) {
            lock(p);
        } else {
            unblind(p); // تنضيف لو السيرفر وقع وهو محبوس
        }
    }

    private void lock(Player p) {
        UUID id = p.getUniqueId();
        if (locked.putIfAbsent(id, System.currentTimeMillis()) != null) {
            return;
        }
        if (getConfig().getBoolean("ui.black-screen", true)) {
            blind(p);
        }
        send(p, "locked");
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && isLocked(p) && !guiOpen.contains(id)) {
                openGui(p);
            }
        }, 10L);
    }

    private void unlock(Player p) {
        UUID id = p.getUniqueId();
        locked.remove(id);
        attempts.remove(id);
        unblind(p);
        if (guiOpen.contains(id)) {
            p.closeInventory();
        }
    }

    // ------------------------------------------------------------------ الشاشة السودا

    private void blind(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        p.getPersistentDataContainer().set(blindKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void unblind(Player p) {
        if (p.getPersistentDataContainer().has(blindKey, PersistentDataType.BYTE)) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.getPersistentDataContainer().remove(blindKey);
        }
    }

    private ItemStack inputItem() {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        meta.displayName(Component.text(" ")); // خانة فاضية: اللي هتكتبه هو الباسورد
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("ui.item-lore")) {
            lore.add(LEGACY.deserialize(line));
        }
        meta.lore(lore);
        paper.setItemMeta(meta);
        return paper;
    }

    /** يفتح شاشة الإدخال (سندان) والخلفية سودا. */
    private void openGui(Player p) {
        UUID id = p.getUniqueId();
        AnvilView view = MenuType.ANVIL.create(p, LEGACY.deserialize(getConfig().getString("ui.title", "Enter Password")));
        view.getTopInventory().setFirstItem(inputItem());
        opening.add(id);
        try {
            p.openInventory(view);
            guiOpen.add(id);
        } finally {
            opening.remove(id);
        }
    }

    private void setGuiTitle(Player p, String key, String... kv) {
        String t = getConfig().getString("ui." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            t = t.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        p.getOpenInventory().setTitle(LEGACY.deserialize(t));
    }

    /** اللاعب ضغط على الورقة: نقرا اللي كتبه ونتأكد منه. */
    private void submit(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSubmit.get(id);
        if (last != null && now - last < 700) {
            return; // نمنع الضغط المتكرر (التشفير بياخد شوية وقت)
        }
        lastSubmit.put(id, now);

        InventoryView open = p.getOpenInventory();
        String text = open instanceof AnvilView av ? av.getRenameText() : null;
        String given = text == null ? "" : text.strip();
        if (given.isEmpty()) {
            setGuiTitle(p, "title-empty");
            return;
        }

        if (checkPassword(given)) {
            unlock(p);
            markVerified(p);
            send(p, "success");
            return;
        }
        int max = Math.max(1, getConfig().getInt("lock.max-attempts", 3));
        int used = attempts.merge(id, 1, Integer::sum);
        if (used >= max) {
            long minutes = getConfig().getLong("lock.lockout-minutes", 5);
            if (minutes > 0) {
                lockoutUntil.put(id, now + minutes * 60_000L);
            }
            locked.remove(id);
            attempts.remove(id);
            p.kick(kickMessage("kick-wrong", minutes));
        } else {
            setGuiTitle(p, "title-wrong", "left", String.valueOf(max - used));
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long timeout = getConfig().getLong("lock.timeout-seconds", 60) * 1000L;
        for (Map.Entry<UUID, Long> en : locked.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                locked.remove(en.getKey());
                continue;
            }
            if (timeout > 0 && now - en.getValue() > timeout) {
                locked.remove(en.getKey());
                p.kick(LEGACY.deserialize(msg("kick-timeout")));
                continue;
            }
            if (!guiOpen.contains(en.getKey())) {
                openGui(p); // لو قفلها بـ ESC نفتحها تاني
            }
        }
    }

    // ------------------------------------------------------------------ رسايل

    private String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private void send(CommandSender to, String key, String... kv) {
        String body = msg(key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            body = body.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        to.sendMessage(LEGACY.deserialize(msg("prefix")).append(LEGACY.deserialize(body)));
    }

    private Component kickMessage(String key, long minutes) {
        return LEGACY.deserialize(msg(key).replace("{minutes}", String.valueOf(Math.max(1, minutes))));
    }

    // ------------------------------------------------------------------ أحداث الدخول والخروج

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Long until = lockoutUntil.get(e.getUniqueId());
        if (until == null) {
            return;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            lockoutUntil.remove(e.getUniqueId());
            return;
        }
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage("kick-lockout", (left + 59_999) / 60_000));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        checkJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        unblind(e.getPlayer());
        locked.remove(id);
        guiOpen.remove(id);
        lastSubmit.remove(id);
        attempts.remove(id);
        session.remove(id);
    }

    // ------------------------------------------------------------------ منع كل حاجة للمحبوس

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isLocked(e.getPlayer())) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back); // يتحرك بعينه بس
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
            send(e.getPlayer(), "locked");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        if (!isLocked(e.getPlayer())) {
            return;
        }
        String raw = e.getMessage();
        if (raw.length() > 1) {
            String cmd = raw.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
            int colon = cmd.indexOf(':');
            if (colon >= 0) {
                cmd = cmd.substring(colon + 1);
            }
            for (String allowed : getConfig().getStringList("lock.allowed-commands")) {
                if (allowed.equalsIgnoreCase(cmd)) {
                    return;
                }
            }
        }
        e.setCancelled(true);
        send(e.getPlayer(), "blocked-command");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isLocked(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !isLocked(p)) {
            return;
        }
        e.setCancelled(true);
        int raw = e.getRawSlot();
        if (guiOpen.contains(p.getUniqueId()) && raw >= 0 && raw <= 2) {
            submit(p); // ضغط على الورقة أو النتيجة
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && isLocked(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInvOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && isLocked(p) && !opening.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !guiOpen.remove(p.getUniqueId())) {
            return;
        }
        e.getView().getTopInventory().clear(); // الورقة متتحولش للاعب
        if (isLocked(p)) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && isLocked(p) && !guiOpen.contains(p.getUniqueId())) {
                    openGui(p); // ESC ميهربوش
                }
            }, 2L);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        if (e.getView().getPlayer() instanceof Player p && isLocked(p)) {
            ItemStack ok = new ItemStack(Material.LIME_DYE);
            ItemMeta meta = ok.getItemMeta();
            meta.displayName(LEGACY.deserialize(getConfig().getString("ui.confirm-name", "&aEnter")));
            ok.setItemMeta(meta);
            e.setResult(ok); // زرار تأكيد في خانة النتيجة
            e.getView().setRepairCost(0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isLocked(p)) {
            e.setCancelled(true); // المحبوس ميتأذيش
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isLocked(p)) {
            e.setCancelled(true); // ولا يضرب حد
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player p && isLocked(p)) {
            e.setCancelled(true); // الموبز متطاردوش
        }
    }

    // ------------------------------------------------------------------ الأوامر

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("glock")) {
            return handleLock(sender, args);
        }
        return handleAdmin(sender, args);
    }

    private boolean handleLock(CommandSender sender, String[] args) {
        if (!getConfig().getBoolean("lock.fallback-command", true)) {
            send(sender, "command-disabled");
            return true;
        }
        if (!(sender instanceof Player p)) {
            send(sender, "players-only");
            return true;
        }
        if (!hasPassword()) {
            send(p, "no-password-set");
            return true;
        }
        if (!isLocked(p)) {
            send(p, "already");
            return true;
        }
        if (args.length == 0) {
            send(p, "usage");
            return true;
        }
        String given = String.join(" ", args);

        if (checkPassword(given)) {
            unlock(p);
            markVerified(p);
            send(p, "success");
            return true;
        }

        int max = Math.max(1, getConfig().getInt("lock.max-attempts", 3));
        int used = attempts.merge(p.getUniqueId(), 1, Integer::sum);
        if (used >= max) {
            long minutes = getConfig().getLong("lock.lockout-minutes", 5);
            if (minutes > 0) {
                lockoutUntil.put(p.getUniqueId(), System.currentTimeMillis() + minutes * 60_000L);
            }
            locked.remove(p.getUniqueId());
            attempts.remove(p.getUniqueId());
            p.kick(kickMessage("kick-wrong", minutes));
        } else {
            send(p, "wrong", "left", String.valueOf(max - used));
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gawharalock.admin")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            send(sender, "admin-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (args.length < 2) {
                    send(sender, "admin-usage");
                    return true;
                }
                String pw = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                int min = getConfig().getInt("lock.min-length", 4);
                if (pw.length() < min) {
                    send(sender, "pw-too-short", "min", String.valueOf(min));
                    return true;
                }
                setPassword(pw);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.equals(sender)) {
                        markVerified(online); // اللي حط الباسورد داخل طبعًا
                        unlock(online);
                    } else if (!online.hasPermission("gawharalock.bypass")) {
                        lock(online);
                    }
                }
                send(sender, "pw-set");
            }
            case "off" -> {
                data.set("password", null);
                data.set("verified", null);
                session.clear();
                saveData();
                for (UUID id : new ArrayList<>(locked.keySet())) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        unlock(p);
                    }
                }
                locked.clear();
                send(sender, "pw-off");
            }
            case "status" -> {
                ConfigurationSection v = data.getConfigurationSection("verified");
                send(sender, "status",
                        "state", hasPassword() ? "ON" : "OFF",
                        "verified", String.valueOf(v == null ? 0 : v.getKeys(false).size()),
                        "waiting", String.valueOf(locked.size()));
            }
            case "list" -> {
                ConfigurationSection v = data.getConfigurationSection("verified");
                if (v == null || v.getKeys(false).isEmpty()) {
                    send(sender, "list-empty");
                    return true;
                }
                send(sender, "list-header", "count", String.valueOf(v.getKeys(false).size()));
                List<String> names = new ArrayList<>();
                for (String key : v.getKeys(false)) {
                    names.add(v.getString(key + ".name", key));
                }
                sender.sendMessage(Component.text("§7" + String.join("§8, §7", names)));
            }
            case "revoke" -> {
                if (args.length < 2) {
                    send(sender, "admin-usage");
                    return true;
                }
                String key = findVerifiedKey(args[1]);
                if (key == null) {
                    send(sender, "not-found");
                    return true;
                }
                data.set("verified." + key, null);
                session.remove(UUID.fromString(key));
                saveData();
                Player online = Bukkit.getPlayer(UUID.fromString(key));
                if (online != null && hasPassword() && !online.hasPermission("gawharalock.bypass")) {
                    lock(online);
                }
                send(sender, "revoked", "player", args[1]);
            }
            case "verify" -> {
                if (args.length < 2) {
                    send(sender, "admin-usage");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    send(sender, "not-found");
                    return true;
                }
                if (!hasPassword()) {
                    send(sender, "no-password");
                    return true;
                }
                markVerified(target);
                unlock(target);
                send(target, "success");
                send(sender, "verified", "player", target.getName());
            }
            case "reload" -> {
                reloadConfig();
                data = YamlConfiguration.loadConfiguration(dataFile);
                send(sender, "reloaded");
            }
            default -> send(sender, "admin-usage");
        }
        return true;
    }

    /** يدوّر على UUID اللاعب (بالاسم) في اللي معاهم الباسورد. */
    private String findVerifiedKey(String name) {
        ConfigurationSection v = data.getConfigurationSection("verified");
        if (v == null) {
            return null;
        }
        for (String key : v.getKeys(false)) {
            if (name.equalsIgnoreCase(v.getString(key + ".name", ""))) {
                return key;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("glockadmin") || !sender.hasPermission("gawharalock.admin")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("set", "off", "status", "list", "revoke", "verify", "reload"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("revoke") || args[0].equalsIgnoreCase("verify"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
        }
        String start = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(start));
        return out;
    }
}
