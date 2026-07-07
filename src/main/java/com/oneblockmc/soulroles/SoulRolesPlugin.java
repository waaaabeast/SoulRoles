package com.oneblockmc.soulroles;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class SoulRolesPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PERMISSION = "soulroles.admin";

    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Random random = new Random();

    private File dataFile;
    private FileConfiguration dataConfig;
    private NamespacedKey itemKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "item_id");
        loadData();
        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("soulroles")).setExecutor(this);
        Objects.requireNonNull(getCommand("soulroles")).setTabCompleter(this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            ensureKnown(player);
            applyState(player);
        }
    }

    @Override
    public void onDisable() {
        saveData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ensureKnown(player);
        applyState(player);
        showRole(player);
    }


    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        ensureKnown(victim);
        Role victimRoleBeforeDeath = state(victim.getUniqueId()).role();

        if (victimRoleBeforeDeath == Role.A) {
            event.getDrops().add(createItem(CustomItem.SOUL, 1));
        }

        Player killer = victim.getKiller();
        if (killer == null || !killer.isOnline()) {
            return;
        }

        ensureKnown(killer);
        PlayerState killerState = state(killer.getUniqueId());
        if (killerState.role() != Role.A) {
            return;
        }

        setRole(victim.getUniqueId(), Role.A);
        setRole(killer.getUniqueId(), Role.NORMAL);
        reshuffleGroupB();
        saveData();

        Bukkit.getScheduler().runTask(this, () -> {
            applyState(killer);
            applyState(victim);
            showRole(killer);
            showRole(victim);
        });
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.hasItem()) {
            return;
        }

        ItemStack item = event.getItem();
        Optional<CustomItem> customItem = identify(item);
        if (customItem.isEmpty()) {
            return;
        }

        switch (customItem.get()) {
            case SOUL -> {
                return;
            }
            case HEART -> useHeart(event.getPlayer(), event, item);
            case RESETTER -> {
                rerollAll();
                consumeOne(item, event.getPlayer());
                announce(Component.text("Roles were shuffled."));
            }
            case SWAP -> {
                swapAAndB();
                consumeOne(item, event.getPlayer());
                announce(Component.text("Infected and Spark were swapped."));
            }
            case ADD_A -> {
                if (addRandomNormalToA()) {
                    consumeOne(item, event.getPlayer());
                    announce(Component.text("A normal player became Infected."));
                } else {
                    event.getPlayer().sendMessage(Component.text("No normal tracked players are available."));
                }
            }
        }

        event.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission to use this command."));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> handleStatus(sender, args);
            case "reset", "reroll" -> {
                rerollAll();
                sender.sendMessage(Component.text("Roles shuffled for tracked players."));
            }
            case "reload" -> {
                reloadConfig();
                loadData();
                registerRecipes();
                Bukkit.getOnlinePlayers().forEach(this::applyState);
                sender.sendMessage(Component.text("Roles reloaded."));
            }
            case "set" -> handleSet(sender, args);
            case "give" -> handleGive(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return startsWith(args[0], List.of("status", "reset", "reload", "set", "give"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return startsWith(args[1], List.of("soul", "heart", "resetter", "swap", "add_a"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return startsWith(args[2], List.of("A", "B", "NORMAL"));
        }
        return List.of();
    }

    private void handleStatus(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                Player online = Bukkit.getPlayerExact(args[1]);
                target = online;
            }
            if (target == null || target.getUniqueId() == null) {
                sender.sendMessage(Component.text("Unknown player. They must have joined once."));
                return;
            }
            PlayerState targetState = states.get(target.getUniqueId());
            if (targetState == null) {
                sender.sendMessage(Component.text(target.getName() + " is not tracked yet."));
                return;
            }
            sender.sendMessage(Component.text(target.getName() + ": " + targetState.role() + ", bonus hearts " + targetState.bonusHearts()));
            return;
        }

        long a = countRole(Role.A);
        long b = countRole(Role.B);
        long normal = countRole(Role.NORMAL);
        sender.sendMessage(Component.text("Tracked players: " + states.size() + " | A=" + a + " B=" + b + " normal=" + normal));
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /soulroles set <player> <A|B|NORMAL>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player must be online for this prototype command."));
            return;
        }

        Role role = Role.from(args[2]);
        if (role == null) {
            sender.sendMessage(Component.text("Role must be A, B, or NORMAL."));
            return;
        }

        ensureKnown(target);
        setRole(target.getUniqueId(), role);
        saveData();
        applyState(target);
        showRole(target);
        sender.sendMessage(Component.text("Set " + target.getName() + " to " + role + "."));
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can receive prototype items with this command."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /soulroles give <soul|heart|resetter|swap|add_a> [amount]"));
            return;
        }

        CustomItem customItem = CustomItem.from(args[1]);
        if (customItem == null) {
            sender.sendMessage(Component.text("Unknown item."));
            return;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                sender.sendMessage(Component.text("Amount must be a number."));
                return;
            }
        }

        player.getInventory().addItem(createItem(customItem, amount));
        sender.sendMessage(Component.text("Gave " + amount + " " + customItem.configKey() + "."));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/soulroles status [player]"));
        sender.sendMessage(Component.text("/soulroles reset"));
        sender.sendMessage(Component.text("/soulroles reload"));
        sender.sendMessage(Component.text("/soulroles set <player> <A|B|NORMAL>"));
        sender.sendMessage(Component.text("/soulroles give <soul|heart|resetter|swap|add_a> [amount]"));
    }

    private void useHeart(Player player, PlayerInteractEvent event, ItemStack item) {
        ensureKnown(player);
        int extraHearts = Math.max(1, getConfig().getInt("heart-item-extra-hearts", 1));
        PlayerState current = state(player.getUniqueId());
        states.put(player.getUniqueId(), current.withBonusHearts(current.bonusHearts() + extraHearts));
        saveData();
        applyState(player);
        showRole(player);
        consumeOne(item, player);
        player.sendMessage(Component.text("You gained " + extraHearts + " permanent heart(s)."));
        event.setCancelled(true);
    }

    private void rerollAll() {
        Set<UUID> pool = new HashSet<>(states.keySet());
        Bukkit.getOnlinePlayers().forEach(player -> {
            ensureKnown(player);
            pool.add(player.getUniqueId());
        });

        List<UUID> players = new ArrayList<>(pool);
        Collections.shuffle(players, random);

        int groupACount = Math.max(0, getConfig().getInt("group-a-count", 1));
        int groupBCount = Math.max(0, getConfig().getInt("group-b-count", 2));

        for (int i = 0; i < players.size(); i++) {
            UUID uuid = players.get(i);
            if (i < groupACount) {
                setRole(uuid, Role.A);
            } else if (i < groupACount + groupBCount) {
                setRole(uuid, Role.B);
            } else {
                setRole(uuid, Role.NORMAL);
            }
        }

        saveData();
        Bukkit.getOnlinePlayers().forEach(player -> {
            applyState(player);
            showRole(player);
        });
    }

    private void reshuffleGroupB() {
        List<UUID> pool = states.entrySet().stream()
            .filter(entry -> entry.getValue().role() != Role.A)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(pool, random);

        int groupBCount = Math.min(Math.max(0, getConfig().getInt("group-b-count", 2)), pool.size());
        Set<UUID> nextB = new HashSet<>(pool.subList(0, groupBCount));

        for (UUID uuid : pool) {
            setRole(uuid, nextB.contains(uuid) ? Role.B : Role.NORMAL);
        }
    }

    private void swapAAndB() {
        for (UUID uuid : new ArrayList<>(states.keySet())) {
            PlayerState playerState = state(uuid);
            if (playerState.role() == Role.A) {
                states.put(uuid, playerState.withRole(Role.B));
            } else if (playerState.role() == Role.B) {
                states.put(uuid, playerState.withRole(Role.A));
            }
        }
        saveData();
        Bukkit.getOnlinePlayers().forEach(player -> {
            applyState(player);
            showRole(player);
        });
    }

    private boolean addRandomNormalToA() {
        List<UUID> normalPlayers = states.entrySet().stream()
            .filter(entry -> entry.getValue().role() == Role.NORMAL)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(ArrayList::new));
        if (normalPlayers.isEmpty()) {
            return false;
        }

        UUID chosen = normalPlayers.get(random.nextInt(normalPlayers.size()));
        setRole(chosen, Role.A);
        saveData();
        Player online = Bukkit.getPlayer(chosen);
        if (online != null) {
            applyState(online);
            showRole(online);
        }
        return true;
    }

    private void ensureKnown(Player player) {
        states.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerState(Role.NORMAL, 0, player.getName()));
        PlayerState current = state(player.getUniqueId());
        states.put(player.getUniqueId(), current.withLastName(player.getName()));
    }

    private PlayerState state(UUID uuid) {
        return states.getOrDefault(uuid, new PlayerState(Role.NORMAL, 0, null));
    }

    private void setRole(UUID uuid, Role role) {
        PlayerState current = state(uuid);
        states.put(uuid, current.withRole(role));
    }

    private void applyState(Player player) {
        PlayerState playerState = state(player.getUniqueId());
        double maxHealth = heartsFor(playerState.role()) * 2.0D + playerState.bonusHearts() * 2.0D;
        maxHealth = Math.max(2.0D, maxHealth);

        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(maxHealth);
        }
        if (player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }

        double maxHeartsOnScreen = getConfig().getDouble("max-hearts-on-screen", 20.0D);
        if (maxHeartsOnScreen > 0.0D && maxHealth > maxHeartsOnScreen * 2.0D) {
            player.setHealthScale(maxHeartsOnScreen * 2.0D);
        } else {
            player.setHealthScaled(false);
        }
        player.sendHealthUpdate();
    }

    private int heartsFor(Role role) {
        return switch (role) {
            case A -> Math.max(1, getConfig().getInt("group-a-hearts", 5));
            case B -> Math.max(1, getConfig().getInt("group-b-hearts", 20));
            case NORMAL -> Math.max(1, getConfig().getInt("normal-hearts", 10));
        };
    }

    private void showRole(Player player) {
        Role role = state(player.getUniqueId()).role();
        String path = switch (role) {
            case A -> "messages.role-a";
            case B -> "messages.role-b";
            case NORMAL -> "messages.role-normal";
        };
        Component message = MINI.deserialize(getConfig().getString(path, role.name()));
        player.sendActionBar(message);
        player.sendMessage(message);
    }

    private void announce(Component message) {
        Bukkit.getOnlinePlayers().forEach(player -> {
            applyState(player);
            showRole(player);
        });
        Bukkit.broadcast(message);
    }

    private void registerRecipes() {
        removeRecipe("heart");
        removeRecipe("resetter");
        removeRecipe("swap");
        removeRecipe("add_a");

        if (getConfig().getBoolean("recipes.heart.enabled", true)) {
            ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "heart"), createItem(CustomItem.HEART, 1));
            recipe.shape("DND", "OSO", "DND");
            recipe.setIngredient('D', Material.DIAMOND_BLOCK);
            recipe.setIngredient('N', Material.NETHERITE_INGOT);
            recipe.setIngredient('O', Material.OBSIDIAN);
            recipe.setIngredient('S', new RecipeChoice.ExactChoice(createItem(CustomItem.SOUL, 1)));
            getServer().addRecipe(recipe);
        }

        if (getConfig().getBoolean("recipes.resetter.enabled", true)) {
            ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "resetter"), createItem(CustomItem.RESETTER, 1));
            recipe.shape("ESE", "DND", "ESE");
            recipe.setIngredient('E', Material.ENDER_EYE);
            recipe.setIngredient('S', new RecipeChoice.ExactChoice(createItem(CustomItem.SOUL, 1)));
            recipe.setIngredient('N', Material.DIAMOND_BLOCK);
            recipe.setIngredient('D', Material.DIAMOND);
            getServer().addRecipe(recipe);
        }

        if (getConfig().getBoolean("recipes.swap.enabled", true)) {
            ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "swap"), createItem(CustomItem.SWAP, 1));
            recipe.shape("APA", "SDS", "APA");
            recipe.setIngredient('A', Material.AMETHYST_SHARD);
            recipe.setIngredient('S', new RecipeChoice.ExactChoice(createItem(CustomItem.SOUL, 1)));
            recipe.setIngredient('D', Material.DIAMOND);
            recipe.setIngredient('P', Material.PRISMARINE_CRYSTALS);
            getServer().addRecipe(recipe);
        }

        if (getConfig().getBoolean("recipes.add-a.enabled", true)) {
            ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "add_a"), createItem(CustomItem.ADD_A, 1));
            recipe.shape("GSG", "RCR", "GSG");
            recipe.setIngredient('R', Material.REDSTONE);
            recipe.setIngredient('S', new RecipeChoice.ExactChoice(createItem(CustomItem.SOUL, 1)));
            recipe.setIngredient('C', Material.COMPASS);
            recipe.setIngredient('G', Material.GHAST_TEAR);
            getServer().addRecipe(recipe);
        }
    }

    private void removeRecipe(String key) {
        getServer().removeRecipe(new NamespacedKey(this, key));
    }

    private ItemStack createItem(CustomItem customItem, int amount) {
        Material material = materialFromConfig("items." + customItem.configKey() + ".material", customItem.defaultMaterial());
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MINI.deserialize(getConfig().getString("items." + customItem.configKey() + ".name", customItem.configKey())));
        int customModelData = getConfig().getInt("items." + customItem.configKey() + ".custom-model-data", customItem.defaultCustomModelData());
        if (customModelData > 0) {
            meta.setCustomModelData(customModelData);
        }
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, customItem.configKey());
        item.setItemMeta(meta);
        return item;
    }

    private Optional<CustomItem> identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return Optional.ofNullable(CustomItem.from(value));
    }

    private Material materialFromConfig(String path, Material fallback) {
        String configured = getConfig().getString(path);
        if (configured == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(configured);
        return material == null ? fallback : material;
    }

    private void consumeOne(ItemStack item, Player player) {
        if (player.getGameMode().name().equals("CREATIVE")) {
            return;
        }
        item.setAmount(item.getAmount() - 1);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create data.yml", exception);
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        states.clear();
        ConfigurationSection players = dataConfig.getConfigurationSection("players");
        if (players == null) {
            return;
        }

        for (String key : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String roleName = players.getString(key + ".role", "NORMAL");
                Role role = Role.from(roleName);
                int bonusHearts = Math.max(0, players.getInt(key + ".bonus-hearts", 0));
                String lastName = players.getString(key + ".last-name");
                states.put(uuid, new PlayerState(role == null ? Role.NORMAL : role, bonusHearts, lastName));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping invalid player UUID in data.yml: " + key);
            }
        }
    }

    private void saveData() {
        if (dataConfig == null) {
            return;
        }
        dataConfig.set("players", null);
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerState playerState = entry.getValue();
            dataConfig.set(path + ".role", playerState.role().name());
            dataConfig.set(path + ".bonus-hearts", playerState.bonusHearts());
            dataConfig.set(path + ".last-name", playerState.lastName());
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save data.yml: " + exception.getMessage());
        }
    }

    private long countRole(Role role) {
        return states.values().stream().filter(state -> state.role() == role).count();
    }

    private List<String> startsWith(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
            .toList();
    }

    private enum Role {
        A,
        B,
        NORMAL;

        private static Role from(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "A", "GROUP_A", "GROUP-A" -> A;
                case "B", "GROUP_B", "GROUP-B" -> B;
                case "NORMAL", "NONE", "UNGROUPED" -> NORMAL;
                default -> null;
            };
        }
    }

    private enum CustomItem {
        SOUL("soul", Material.ECHO_SHARD, 101001),
        HEART("heart", Material.HEART_OF_THE_SEA, 101002),
        RESETTER("resetter", Material.NETHER_STAR, 101003),
        SWAP("swap", Material.AMETHYST_SHARD, 101004),
        ADD_A("add-a", Material.RECOVERY_COMPASS, 101005);

        private final String configKey;
        private final Material defaultMaterial;
        private final int defaultCustomModelData;

        CustomItem(String configKey, Material defaultMaterial, int defaultCustomModelData) {
            this.configKey = configKey;
            this.defaultMaterial = defaultMaterial;
            this.defaultCustomModelData = defaultCustomModelData;
        }

        private String configKey() {
            return configKey;
        }

        private Material defaultMaterial() {
            return defaultMaterial;
        }

        private int defaultCustomModelData() {
            return defaultCustomModelData;
        }

        private static CustomItem from(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
            for (CustomItem item : values()) {
                if (item.configKey.equals(normalized)) {
                    return item;
                }
            }
            if (normalized.equals("add-a")) {
                return ADD_A;
            }
            return null;
        }
    }

    private record PlayerState(Role role, int bonusHearts, String lastName) {
        private PlayerState withRole(Role nextRole) {
            return new PlayerState(nextRole, bonusHearts, lastName);
        }

        private PlayerState withBonusHearts(int nextBonusHearts) {
            return new PlayerState(role, Math.max(0, nextBonusHearts), lastName);
        }

        private PlayerState withLastName(String nextLastName) {
            return new PlayerState(role, bonusHearts, nextLastName);
        }
    }
}
