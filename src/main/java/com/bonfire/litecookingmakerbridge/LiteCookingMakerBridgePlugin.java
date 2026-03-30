package com.bonfire.litecookingmakerbridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class LiteCookingMakerBridgePlugin extends JavaPlugin implements Listener {

    private ReflectionBridge bridge;
    private Settings settings;
    private NamespacedKey makerUuidKey;
    private NamespacedKey makerNameKey;
    private NamespacedKey makerTimeKey;
    private final Map<String, SessionSnapshot> activeSnapshots = new HashMap<>();
    private final List<PendingClaim> pendingClaims = new ArrayList<>();
    private final Map<String, SuppressedGameClick> suppressedGameClicks = new HashMap<>();
    private long logicalTick = 0L;
    private int scanTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadBridgeSettings();

        makerUuidKey = new NamespacedKey(this, "lc_maker_uuid");
        makerNameKey = new NamespacedKey(this, "lc_maker_name");
        makerTimeKey = new NamespacedKey(this, "lc_maker_time");

        Plugin liteCooking = Bukkit.getPluginManager().getPlugin("LiteCooking");
        if (liteCooking == null || !liteCooking.isEnabled()) {
            getLogger().severe("LiteCooking not found or not enabled. Plugin is disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            bridge = new ReflectionBridge();
        } catch (ReflectiveOperationException ex) {
            getLogger().log(Level.SEVERE, "Failed to attach LiteCooking reflection bridge.", ex);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        long interval = Math.max(1, settings.scanIntervalTicks);
        scanTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::scanWorkstations, 1L, interval);
        getLogger().info("LiteCookingMakerBridge enabled. scanInterval=" + interval
                + " ticks, expBridge=" + settings.expEnabled);
    }

    @Override
    public void onDisable() {
        if (scanTaskId != -1) {
            Bukkit.getScheduler().cancelTask(scanTaskId);
        }
        if (bridge != null && !suppressedGameClicks.isEmpty()) {
            for (SuppressedGameClick blocked : suppressedGameClicks.values()) {
                try {
                    bridge.restoreSuppressedGame(blocked.workstation, blocked.game);
                } catch (ReflectiveOperationException ignored) {
                    // Plugin is disabling; best-effort restore only.
                }
            }
        }
        activeSnapshots.clear();
        pendingClaims.clear();
        suppressedGameClicks.clear();
        bridge = null;
    }

    private void reloadBridgeSettings() {
        reloadConfig();
        settings = Settings.from(getConfig());
    }

    private void scanWorkstations() {
        logicalTick += Math.max(1, settings.scanIntervalTicks);

        if (bridge == null) {
            return;
        }

        Map<String, Object> workstationMap;
        try {
            workstationMap = bridge.getWorkstations();
        } catch (ReflectiveOperationException ex) {
            debug("Failed to read LiteCooking workstations: " + ex.getMessage());
            return;
        }

        Set<String> seenKeys = new HashSet<>();
        for (Map.Entry<String, Object> entry : workstationMap.entrySet()) {
            String workstationKey = entry.getKey();
            Object workstation = entry.getValue();
            seenKeys.add(workstationKey);

            SessionSnapshot previous = activeSnapshots.get(workstationKey);
            Object session;
            try {
                session = bridge.getSession(workstation);
            } catch (ReflectiveOperationException ex) {
                debug("Failed to read session for workstation " + workstationKey + ": " + ex.getMessage());
                continue;
            }

            if (session == null) {
                if (previous != null) {
                    activeSnapshots.remove(workstationKey);
                    enqueueClaim(previous);
                }
                continue;
            }

            if (previous != null && previous.sessionRef == session) {
                try {
                    int stage = bridge.getStage(session);
                    previous.maxObservedStage = Math.max(previous.maxObservedStage, stage);
                    previous.lastSeenTick = logicalTick;
                } catch (ReflectiveOperationException ex) {
                    debug("Failed to update stage for workstation " + workstationKey + ": " + ex.getMessage());
                }
                continue;
            }

            if (previous != null) {
                enqueueClaim(previous);
            }

            try {
                SessionSnapshot current = buildSnapshot(workstationKey, workstation, session);
                if (current != null) {
                    activeSnapshots.put(workstationKey, current);
                }
            } catch (ReflectiveOperationException ex) {
                debug("Failed to build snapshot for workstation " + workstationKey + ": " + ex.getMessage());
            }
        }

        Iterator<Map.Entry<String, SessionSnapshot>> iterator = activeSnapshots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SessionSnapshot> entry = iterator.next();
            if (!seenKeys.contains(entry.getKey())) {
                enqueueClaim(entry.getValue());
                iterator.remove();
            }
        }

        pendingClaims.removeIf(claim -> claim.expireAtTick <= logicalTick || claim.isComplete());

        if (!suppressedGameClicks.isEmpty()) {
            Iterator<Map.Entry<String, SuppressedGameClick>> restoreIt = suppressedGameClicks.entrySet().iterator();
            while (restoreIt.hasNext()) {
                Map.Entry<String, SuppressedGameClick> entry = restoreIt.next();
                try {
                    bridge.restoreSuppressedGame(entry.getValue().workstation, entry.getValue().game);
                } catch (ReflectiveOperationException ex) {
                    debug("Failed restoring stale blocked mini-game click for ws="
                            + entry.getKey() + ": " + ex.getMessage());
                }
                restoreIt.remove();
            }
        }
    }

    private SessionSnapshot buildSnapshot(String workstationKey, Object workstation, Object session)
            throws ReflectiveOperationException {
        UUID ownerUuid = bridge.getOwnerUuid(session);
        if (ownerUuid == null) {
            return null;
        }

        List<ItemStack> rewardStacks = bridge.getRewardStacks(session);
        List<RewardMatcher> rewardMatchers = buildMatchers(rewardStacks);
        if (rewardMatchers.isEmpty()) {
            return null;
        }

        Location dropLocation = bridge.getDropLocation(workstation);
        if (dropLocation == null || dropLocation.getWorld() == null) {
            return null;
        }

        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.workstationKey = workstationKey;
        snapshot.sessionRef = session;
        snapshot.ownerUuid = ownerUuid;
        snapshot.ownerName = resolvePlayerName(ownerUuid);
        snapshot.recipeName = bridge.getRecipeName(session);
        snapshot.dropLocation = dropLocation;
        snapshot.stageCount = bridge.getStageCount(session);
        snapshot.maxObservedStage = bridge.getStage(session);
        snapshot.lastSeenTick = logicalTick;
        snapshot.rewardMatchers = rewardMatchers;
        snapshot.craftedAtEpochMillis = 0L;
        snapshot.expBaseGranted = false;
        return snapshot;
    }

    private List<RewardMatcher> buildMatchers(List<ItemStack> rewards) {
        List<RewardMatcher> result = new ArrayList<>();
        for (ItemStack reward : rewards) {
            if (reward == null || reward.getType() == Material.AIR || reward.getAmount() <= 0) {
                continue;
            }

            ItemStack sample = reward.clone();
            int amount = sample.getAmount();
            sample.setAmount(1);

            RewardMatcher existing = null;
            for (RewardMatcher matcher : result) {
                if (matcher.sample.isSimilar(sample)) {
                    existing = matcher;
                    break;
                }
            }

            if (existing == null) {
                result.add(new RewardMatcher(sample, amount));
            } else {
                existing.remaining += amount;
            }
        }
        return result;
    }

    private void enqueueClaim(SessionSnapshot snapshot) {
        if (snapshot.rewardMatchers.isEmpty() || !hasRemainingRewards(snapshot.rewardMatchers)) {
            return;
        }

        if (settings.requireFinalStage && snapshot.stageCount > 0) {
            int finalStageIndex = snapshot.stageCount - 1;
            if (snapshot.maxObservedStage < finalStageIndex) {
                debug("Skip claim (not at final stage): ws=" + snapshot.workstationKey + ", recipe=" + snapshot.recipeName);
                return;
            }
        }

        PendingClaim claim = new PendingClaim();
        claim.ownerUuid = snapshot.ownerUuid;
        claim.ownerName = snapshot.ownerName;
        claim.recipeName = snapshot.recipeName;
        claim.dropLocation = snapshot.dropLocation.clone();
        claim.craftedAtEpochMillis = snapshot.craftedAtEpochMillis > 0L
                ? snapshot.craftedAtEpochMillis
                : System.currentTimeMillis();
        claim.expBaseGranted = snapshot.expBaseGranted;
        claim.expireAtTick = logicalTick + Math.max(20, settings.claimTtlTicks);
        claim.rewards = new ArrayList<>();
        for (RewardMatcher matcher : snapshot.rewardMatchers) {
            if (matcher.remaining > 0) {
                claim.rewards.add(new RewardMatcher(matcher.sample.clone(), matcher.remaining));
            }
        }
        if (claim.rewards.isEmpty()) {
            return;
        }
        pendingClaims.add(claim);

        if (pendingClaims.size() > settings.maxPendingClaims) {
            pendingClaims.remove(0);
        }

        debug("Claim queued: ws=" + snapshot.workstationKey
                + ", recipe=" + snapshot.recipeName
                + ", owner=" + snapshot.ownerName
                + ", rewards=" + claim.rewards.size());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPreMiniGameClick(PlayerInteractEvent event) {
        if (bridge == null || settings == null || !settings.miniGameToolGateEnabled) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || block.getWorld() == null) {
            return;
        }

        try {
            String workstationKey = bridge.toWorkstationKey(block);
            Object workstation = bridge.getWorkstation(workstationKey);
            if (workstation == null) {
                return;
            }

            Object session = bridge.getSession(workstation);
            if (session == null) {
                return;
            }

            UUID ownerUuid = bridge.getOwnerUuid(session);
            if (ownerUuid == null || !ownerUuid.equals(event.getPlayer().getUniqueId())) {
                return;
            }

            Object stage = bridge.getCurrentStage(session);
            if (!bridge.isMiniGameStage(stage)) {
                return;
            }

            Object requiredItem = bridge.getStageGameItem(stage);
            if (requiredItem == null) {
                return;
            }

            if (settings.miniGameToolGateRequireLinkedItemOnly && !bridge.hasLiteItemLink(requiredItem)) {
                return;
            }

            ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
            if (bridge.matchesLiteItem(requiredItem, hand)) {
                return;
            }

            Object removedGame = bridge.suppressGameClick(workstation);
            if (removedGame == null) {
                return;
            }

            suppressedGameClicks.put(workstationKey, new SuppressedGameClick(workstation, removedGame));
            debug("Blocked mini-game click due to tool mismatch: ws=" + workstationKey
                    + ", recipe=" + bridge.getRecipeName(session)
                    + ", player=" + event.getPlayer().getName()
                    + ", required=" + bridge.describeLiteItem(requiredItem));
        } catch (ReflectiveOperationException ex) {
            debug("Mini-game tool gate pre-check failed: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPostMiniGameClick(PlayerInteractEvent event) {
        if (bridge == null || settings == null || !settings.miniGameToolGateEnabled) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || block.getWorld() == null) {
            return;
        }

        String workstationKey = bridge.toWorkstationKey(block);
        SuppressedGameClick suppressed = suppressedGameClicks.remove(workstationKey);
        if (suppressed == null) {
            return;
        }

        try {
            bridge.restoreSuppressedGame(suppressed.workstation, suppressed.game);
        } catch (ReflectiveOperationException ex) {
            debug("Mini-game tool gate restore failed for ws=" + workstationKey + ": " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (pendingClaims.isEmpty() && activeSnapshots.isEmpty()) {
            return;
        }

        Item entity = event.getEntity();
        ItemStack stack = entity.getItemStack();
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }

        ItemMeta meta = stack.getItemMeta();
        if (settings.skipIfAlreadyTagged && meta != null && hasMakerTag(meta.getPersistentDataContainer())) {
            return;
        }

        MatchResult match = findBestMatch(entity.getLocation(), stack);
        if (match == null) {
            return;
        }

        long craftedAtEpochMillis = match.getOrInitCraftedAtEpochMillis();
        tryGrantMainExp(match, stack);

        if (applyMakerTag(stack, match.getOwnerUuid(), match.getOwnerName(), match.getRecipeName(), craftedAtEpochMillis)) {
            entity.setItemStack(stack);
        }

        match.matcher.consume(stack.getAmount());
        if (match.claim != null && match.claim.isComplete()) {
            pendingClaims.remove(match.claim);
        }

        if (match.snapshot != null && match.snapshot.isComplete()) {
            debug("Live snapshot rewards consumed: ws=" + match.snapshot.workstationKey + ", recipe=" + match.snapshot.recipeName);
        }
    }

    private MatchResult findBestMatch(Location itemLocation, ItemStack stack) {
        double maxDistanceSquared = settings.matchRadius * settings.matchRadius;
        MatchResult best = null;
        double bestDistance = Double.MAX_VALUE;

        for (PendingClaim claim : pendingClaims) {
            if (claim.expireAtTick <= logicalTick) {
                continue;
            }
            if (claim.dropLocation.getWorld() == null || itemLocation.getWorld() == null) {
                continue;
            }
            if (!claim.dropLocation.getWorld().equals(itemLocation.getWorld())) {
                continue;
            }

            double distance = claim.dropLocation.distanceSquared(itemLocation);
            if (distance > maxDistanceSquared) {
                continue;
            }

            for (RewardMatcher matcher : claim.rewards) {
                if (matcher.matches(stack) && distance < bestDistance) {
                    bestDistance = distance;
                    best = MatchResult.forPending(claim, matcher);
                }
            }
        }

        for (SessionSnapshot snapshot : activeSnapshots.values()) {
            if (!snapshot.isEligibleForLiveMatch(settings.requireFinalStage)) {
                continue;
            }
            if (snapshot.dropLocation.getWorld() == null || itemLocation.getWorld() == null) {
                continue;
            }
            if (!snapshot.dropLocation.getWorld().equals(itemLocation.getWorld())) {
                continue;
            }

            double distance = snapshot.dropLocation.distanceSquared(itemLocation);
            if (distance > maxDistanceSquared) {
                continue;
            }

            for (RewardMatcher matcher : snapshot.rewardMatchers) {
                if (matcher.matches(stack) && distance < bestDistance) {
                    bestDistance = distance;
                    best = MatchResult.forSnapshot(snapshot, matcher);
                }
            }
        }

        return best;
    }

    private boolean hasRemainingRewards(List<RewardMatcher> rewards) {
        for (RewardMatcher reward : rewards) {
            if (reward.remaining > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean applyMakerTag(ItemStack stack, UUID ownerUuid, String ownerName, String recipeName, long craftedAtEpochMillis) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(makerUuidKey, PersistentDataType.STRING, ownerUuid.toString());
        pdc.set(makerNameKey, PersistentDataType.STRING, ownerName);
        if (settings.writeTimeTag) {
            pdc.set(makerTimeKey, PersistentDataType.LONG, craftedAtEpochMillis);
        }

        if (settings.writeLore) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            boolean loreChanged = false;

            String makerLoreLine = formatLoreLine(ownerUuid, ownerName, recipeName, craftedAtEpochMillis);
            if (!settings.avoidDuplicateLore || !containsLoreLine(lore, makerLoreLine)) {
                lore.add(makerLoreLine);
                loreChanged = true;
            }

            if (settings.writeTimeLore) {
                String timeLoreLine = formatTimeLoreLine(ownerUuid, ownerName, recipeName, craftedAtEpochMillis);
                if (!settings.avoidDuplicateLore || !containsLoreLine(lore, timeLoreLine)) {
                    lore.add(timeLoreLine);
                    loreChanged = true;
                }
            }

            if (loreChanged) {
                meta.setLore(lore);
            }
        }

        stack.setItemMeta(meta);
        return true;
    }

    private boolean hasMakerTag(PersistentDataContainer pdc) {
        return pdc.has(makerUuidKey, PersistentDataType.STRING)
                || pdc.has(makerNameKey, PersistentDataType.STRING)
                || pdc.has(makerTimeKey, PersistentDataType.LONG);
    }

    private String formatLoreLine(UUID ownerUuid, String ownerName, String recipeName, long craftedAtEpochMillis) {
        return formatTemplate(settings.loreTemplate, ownerUuid, ownerName, recipeName, craftedAtEpochMillis);
    }

    private String formatTimeLoreLine(UUID ownerUuid, String ownerName, String recipeName, long craftedAtEpochMillis) {
        return formatTemplate(settings.timeLoreTemplate, ownerUuid, ownerName, recipeName, craftedAtEpochMillis);
    }

    private String formatTemplate(String template, UUID ownerUuid, String ownerName, String recipeName, long craftedAtEpochMillis) {
        String safeOwnerName = (ownerName == null || ownerName.isBlank()) ? ownerUuid.toString() : ownerName;
        String safeRecipeName = (recipeName == null || recipeName.isBlank()) ? "unknown" : recipeName;
        String formattedTime = formatCraftTime(craftedAtEpochMillis);
        String line = template == null ? "" : template;
        line = line.replace("%player%", safeOwnerName);
        line = line.replace("%uuid%", ownerUuid.toString());
        line = line.replace("%recipe%", safeRecipeName);
        line = line.replace("%time%", formattedTime);
        line = line.replace("%epoch_ms%", Long.toString(craftedAtEpochMillis));
        line = line.replace("%epoch_seconds%", Long.toString(Math.floorDiv(craftedAtEpochMillis, 1000L)));
        return ChatColor.translateAlternateColorCodes('&', line);
    }

    private String formatCraftTime(long craftedAtEpochMillis) {
        return settings.timeFormatter.format(Instant.ofEpochMilli(craftedAtEpochMillis).atZone(settings.timeZoneId));
    }

    private boolean containsLoreLine(List<String> lore, String target) {
        String strippedTarget = ChatColor.stripColor(target);
        for (String line : lore) {
            if (line == null) {
                continue;
            }
            if (strippedTarget.equalsIgnoreCase(ChatColor.stripColor(line))) {
                return true;
            }
        }
        return false;
    }

    private String resolvePlayerName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return uuid.toString();
    }

    private void tryGrantMainExp(MatchResult match, ItemStack stack) {
        if (!settings.expEnabled || stack == null) {
            return;
        }
        if (settings.expCommandTemplates.isEmpty()) {
            debug("Skip exp grant: no exp.command-templates configured.");
            return;
        }

        Settings.ExpRule rule = settings.resolveExpRule(match.getRecipeName());
        boolean shouldGrantBase = !match.isExpBaseGranted() && rule.perCraft > 0d;
        int spawnedAmount = Math.max(1, stack.getAmount());
        double grant = (shouldGrantBase ? rule.perCraft : 0d) + (rule.perItem * spawnedAmount);
        grant = roundAmount(grant, settings.expAmountScale);
        if (grant > settings.expMaxAmount) {
            grant = settings.expMaxAmount;
        }

        if (shouldGrantBase) {
            match.markExpBaseGranted();
        }

        if (grant < settings.expMinAmount) {
            return;
        }

        UUID ownerUuid = match.getOwnerUuid();
        String ownerName = match.getOwnerName();
        Player online = Bukkit.getPlayer(ownerUuid);
        if (online == null && settings.expRequireOnlinePlayer) {
            debug("Skip exp grant: owner offline, recipe=" + match.getRecipeName() + ", uuid=" + ownerUuid);
            return;
        }

        String playerName = ownerName;
        if (online != null && online.getName() != null && !online.getName().isBlank()) {
            playerName = online.getName();
        } else if (playerName == null || playerName.isBlank()) {
            playerName = ownerUuid.toString();
        }

        String amount = formatAmount(grant);
        String amountInt = Long.toString(Math.round(grant));
        String recipe = match.getRecipeName() == null ? "unknown" : match.getRecipeName();

        boolean executed = false;
        for (String template : settings.expCommandTemplates) {
            String command = template;
            if (command == null || command.isBlank()) {
                continue;
            }
            if (command.charAt(0) == '/') {
                command = command.substring(1);
            }

            command = command
                    .replace("%player%", playerName)
                    .replace("%player_name%", playerName)
                    .replace("%uuid%", ownerUuid.toString())
                    .replace("%recipe%", recipe)
                    .replace("%amount%", amount)
                    .replace("%amount_int%", amountInt);

            boolean result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            executed = executed || result;
            debug("Exp command (" + (result ? "dispatched" : "missing") + "): " + command);

            if (result && !settings.expRunAllCommands) {
                break;
            }
        }

        if (!executed) {
            getLogger().warning("Failed to dispatch exp command for recipe="
                    + recipe + ", player=" + playerName + ". Check exp.command-templates.");
        }
    }

    private static double roundAmount(double value, int scale) {
        int boundedScale = Math.max(0, scale);
        if (boundedScale == 0) {
            return Math.floor(value);
        }
        double multiplier = Math.pow(10d, boundedScale);
        return Math.floor(value * multiplier) / multiplier;
    }

    private static String formatAmount(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9d) {
            return Long.toString(Math.round(value));
        }
        String text = String.format(Locale.US, "%.6f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private void debug(String message) {
        if (settings != null && settings.debug) {
            getLogger().info("[debug] " + message);
        }
    }

    private static final class Settings {
        final int scanIntervalTicks;
        final int claimTtlTicks;
        final double matchRadius;
        final boolean requireFinalStage;
        final int maxPendingClaims;
        final boolean skipIfAlreadyTagged;
        final boolean writeLore;
        final boolean avoidDuplicateLore;
        final String loreTemplate;
        final boolean writeTimeTag;
        final boolean writeTimeLore;
        final String timeLoreTemplate;
        final ZoneId timeZoneId;
        final DateTimeFormatter timeFormatter;
        final boolean miniGameToolGateEnabled;
        final boolean miniGameToolGateRequireLinkedItemOnly;
        final boolean expEnabled;
        final boolean expRequireOnlinePlayer;
        final boolean expRunAllCommands;
        final int expAmountScale;
        final double expMinAmount;
        final double expMaxAmount;
        final ExpRule expDefaultRule;
        final Map<String, ExpRule> expRecipeOverrides;
        final List<String> expCommandTemplates;
        final boolean debug;

        private Settings(
                int scanIntervalTicks,
                int claimTtlTicks,
                double matchRadius,
                boolean requireFinalStage,
                int maxPendingClaims,
                boolean skipIfAlreadyTagged,
                boolean writeLore,
                boolean avoidDuplicateLore,
                String loreTemplate,
                boolean writeTimeTag,
                boolean writeTimeLore,
                String timeLoreTemplate,
                ZoneId timeZoneId,
                DateTimeFormatter timeFormatter,
                boolean miniGameToolGateEnabled,
                boolean miniGameToolGateRequireLinkedItemOnly,
                boolean expEnabled,
                boolean expRequireOnlinePlayer,
                boolean expRunAllCommands,
                int expAmountScale,
                double expMinAmount,
                double expMaxAmount,
                ExpRule expDefaultRule,
                Map<String, ExpRule> expRecipeOverrides,
                List<String> expCommandTemplates,
                boolean debug
        ) {
            this.scanIntervalTicks = scanIntervalTicks;
            this.claimTtlTicks = claimTtlTicks;
            this.matchRadius = matchRadius;
            this.requireFinalStage = requireFinalStage;
            this.maxPendingClaims = maxPendingClaims;
            this.skipIfAlreadyTagged = skipIfAlreadyTagged;
            this.writeLore = writeLore;
            this.avoidDuplicateLore = avoidDuplicateLore;
            this.loreTemplate = loreTemplate;
            this.writeTimeTag = writeTimeTag;
            this.writeTimeLore = writeTimeLore;
            this.timeLoreTemplate = timeLoreTemplate;
            this.timeZoneId = timeZoneId;
            this.timeFormatter = timeFormatter;
            this.miniGameToolGateEnabled = miniGameToolGateEnabled;
            this.miniGameToolGateRequireLinkedItemOnly = miniGameToolGateRequireLinkedItemOnly;
            this.expEnabled = expEnabled;
            this.expRequireOnlinePlayer = expRequireOnlinePlayer;
            this.expRunAllCommands = expRunAllCommands;
            this.expAmountScale = expAmountScale;
            this.expMinAmount = expMinAmount;
            this.expMaxAmount = expMaxAmount;
            this.expDefaultRule = expDefaultRule;
            this.expRecipeOverrides = expRecipeOverrides;
            this.expCommandTemplates = expCommandTemplates;
            this.debug = debug;
        }

        static Settings from(FileConfiguration cfg) {
            ExpRule defaultRule = new ExpRule(
                    cfg.getDouble("exp.default.per-craft", 0d),
                    cfg.getDouble("exp.default.per-item", 0d)
            );
            double minGrant = Math.max(0d, cfg.getDouble("exp.min-amount", 0.01d));
            double maxGrant = Math.max(minGrant, cfg.getDouble("exp.max-amount", 9999d));
            ZoneId timeZoneId = parseTimeZoneId(cfg.getString("maker.time-zone", "Asia/Shanghai"));
            DateTimeFormatter timeFormatter = parseTimeFormatter(cfg.getString("maker.time-format", "yyyy-MM-dd HH:mm:ss"));

            return new Settings(
                    Math.max(1, cfg.getInt("scan-interval-ticks", 1)),
                    Math.max(20, cfg.getInt("claim-ttl-ticks", 200)),
                    Math.max(0.5d, cfg.getDouble("match-radius", 1.8d)),
                    cfg.getBoolean("require-final-stage", true),
                    Math.max(16, cfg.getInt("max-pending-claims", 256)),
                    cfg.getBoolean("maker.skip-if-already-tagged", true),
                    cfg.getBoolean("maker.write-lore", true),
                    cfg.getBoolean("maker.avoid-duplicate-lore", true),
                    cfg.getString("maker.lore-template", "&7Maker: &f%player%"),
                    cfg.getBoolean("maker.write-time-tag", true),
                    cfg.getBoolean("maker.write-time-lore", true),
                    cfg.getString("maker.time-lore-template", "&8Craft Time: &f%time%"),
                    timeZoneId,
                    timeFormatter,
                    cfg.getBoolean("mini-game-tool-gate.enabled", true),
                    cfg.getBoolean("mini-game-tool-gate.require-linked-item-only", true),
                    cfg.getBoolean("exp.enabled", false),
                    cfg.getBoolean("exp.require-online-player", true),
                    cfg.getBoolean("exp.run-all-commands", false),
                    Math.max(0, cfg.getInt("exp.amount-scale", 2)),
                    minGrant,
                    maxGrant,
                    defaultRule,
                    parseExpRecipeOverrides(cfg, defaultRule),
                    parseExpCommands(cfg),
                    cfg.getBoolean("debug", false)
            );
        }

        ExpRule resolveExpRule(String recipeName) {
            String normalized = normalizeRecipeKey(recipeName);
            ExpRule override = expRecipeOverrides.get(normalized);
            return override != null ? override : expDefaultRule;
        }

        private static List<String> parseExpCommands(FileConfiguration cfg) {
            List<String> commands = new ArrayList<>();
            String singleRaw = cfg.getString("exp.command-template", "");
            String single = singleRaw == null ? "" : singleRaw.trim();
            if (!single.isEmpty()) {
                commands.add(single);
            }

            List<String> configured = cfg.getStringList("exp.command-templates");
            for (String line : configured) {
                if (line != null && !line.isBlank()) {
                    commands.add(line.trim());
                }
            }

            if (commands.isEmpty()) {
                commands.add("mmocore admin experience give %player% %amount%");
            }
            return commands;
        }

        private static Map<String, ExpRule> parseExpRecipeOverrides(FileConfiguration cfg, ExpRule defaultRule) {
            Map<String, ExpRule> result = new LinkedHashMap<>();
            ConfigurationSection section = cfg.getConfigurationSection("exp.recipe-overrides");
            if (section == null) {
                return result;
            }

            for (String key : section.getKeys(false)) {
                if (key == null || key.isBlank()) {
                    continue;
                }

                ConfigurationSection recipeSection = section.getConfigurationSection(key);
                if (recipeSection != null) {
                    result.put(
                            normalizeRecipeKey(key),
                            new ExpRule(
                                    recipeSection.getDouble("per-craft", defaultRule.perCraft),
                                    recipeSection.getDouble("per-item", defaultRule.perItem)
                            )
                    );
                    continue;
                }

                Object raw = section.get(key);
                if (raw instanceof Number number) {
                    result.put(normalizeRecipeKey(key), new ExpRule(number.doubleValue(), defaultRule.perItem));
                }
            }

            return result;
        }

        private static String normalizeRecipeKey(String recipeName) {
            if (recipeName == null) {
                return "";
            }
            String stripped = ChatColor.stripColor(recipeName);
            if (stripped == null) {
                stripped = recipeName;
            }
            return stripped.trim().toLowerCase(Locale.ROOT);
        }

        private static ZoneId parseTimeZoneId(String raw) {
            if (raw == null || raw.isBlank()) {
                return ZoneId.of("Asia/Shanghai");
            }
            try {
                return ZoneId.of(raw.trim());
            } catch (DateTimeException ex) {
                return ZoneId.of("Asia/Shanghai");
            }
        }

        private static DateTimeFormatter parseTimeFormatter(String raw) {
            String pattern = (raw == null || raw.isBlank()) ? "yyyy-MM-dd HH:mm:ss" : raw.trim();
            try {
                return DateTimeFormatter.ofPattern(pattern, Locale.getDefault());
            } catch (IllegalArgumentException ex) {
                return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            }
        }

        private static final class ExpRule {
            final double perCraft;
            final double perItem;

            private ExpRule(double perCraft, double perItem) {
                this.perCraft = Math.max(0d, perCraft);
                this.perItem = Math.max(0d, perItem);
            }
        }
    }

    private static final class SuppressedGameClick {
        final Object workstation;
        final Object game;

        private SuppressedGameClick(Object workstation, Object game) {
            this.workstation = workstation;
            this.game = game;
        }
    }

    private static final class SessionSnapshot {
        String workstationKey;
        Object sessionRef;
        UUID ownerUuid;
        String ownerName;
        String recipeName;
        Location dropLocation;
        int stageCount;
        int maxObservedStage;
        long lastSeenTick;
        List<RewardMatcher> rewardMatchers;
        long craftedAtEpochMillis;
        boolean expBaseGranted;

        boolean isEligibleForLiveMatch(boolean requireFinalStage) {
            if (!requireFinalStage) {
                return true;
            }
            if (stageCount <= 0) {
                return true;
            }
            int finalStageIndex = stageCount - 1;
            return maxObservedStage >= finalStageIndex;
        }

        boolean isComplete() {
            for (RewardMatcher reward : rewardMatchers) {
                if (reward.remaining > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class PendingClaim {
        UUID ownerUuid;
        String ownerName;
        String recipeName;
        Location dropLocation;
        long expireAtTick;
        List<RewardMatcher> rewards;
        long craftedAtEpochMillis;
        boolean expBaseGranted;

        boolean isComplete() {
            for (RewardMatcher reward : rewards) {
                if (reward.remaining > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class RewardMatcher {
        final ItemStack sample;
        int remaining;

        RewardMatcher(ItemStack sample, int remaining) {
            this.sample = sample;
            this.remaining = Math.max(0, remaining);
        }

        boolean matches(ItemStack stack) {
            return remaining > 0 && sample.isSimilar(stack);
        }

        void consume(int amount) {
            remaining = Math.max(0, remaining - Math.max(1, amount));
        }
    }

    private static final class MatchResult {
        final SessionSnapshot snapshot;
        final PendingClaim claim;
        final RewardMatcher matcher;

        private MatchResult(SessionSnapshot snapshot, PendingClaim claim, RewardMatcher matcher) {
            this.snapshot = snapshot;
            this.claim = claim;
            this.matcher = matcher;
        }

        static MatchResult forPending(PendingClaim claim, RewardMatcher matcher) {
            return new MatchResult(null, claim, matcher);
        }

        static MatchResult forSnapshot(SessionSnapshot snapshot, RewardMatcher matcher) {
            return new MatchResult(snapshot, null, matcher);
        }

        UUID getOwnerUuid() {
            return claim != null ? claim.ownerUuid : snapshot.ownerUuid;
        }

        String getOwnerName() {
            return claim != null ? claim.ownerName : snapshot.ownerName;
        }

        String getRecipeName() {
            return claim != null ? claim.recipeName : snapshot.recipeName;
        }

        boolean isExpBaseGranted() {
            return claim != null ? claim.expBaseGranted : snapshot.expBaseGranted;
        }

        void markExpBaseGranted() {
            if (claim != null) {
                claim.expBaseGranted = true;
            } else {
                snapshot.expBaseGranted = true;
            }
        }

        long getOrInitCraftedAtEpochMillis() {
            long value = claim != null ? claim.craftedAtEpochMillis : snapshot.craftedAtEpochMillis;
            if (value > 0L) {
                return value;
            }

            long now = System.currentTimeMillis();
            if (claim != null) {
                claim.craftedAtEpochMillis = now;
            } else {
                snapshot.craftedAtEpochMillis = now;
            }
            return now;
        }
    }

    private static final class ReflectionBridge {
        private final Field liteCookingWorkstationsField;
        private final Field activeWorkstationsMapField;
        private final Field workstationGamesField;
        private final Field workstationSessionField;
        private final Field workstationLocationField;
        private final Field sessionOwnerField;
        private final Field sessionStageField;
        private final Field sessionRecipeNameField;
        private final Method sessionGetRecipeMethod;
        private final Method sessionGetStageMethod;
        private final Field stageTypeField;
        private final Field stageGameItemField;
        private final Field recipeRewardsField;
        private final Field recipeStagesField;
        private final Field liteItemLinkField;
        private final Method liteItemCompareMethod;
        private final Method liteItemToStackMethod;
        private final Constructor<?> liteLocationConstructor;
        private final Method liteLocationGetLocationMethod;

        ReflectionBridge() throws ReflectiveOperationException {
            Class<?> liteCookingClass = Class.forName("dev.nekomadev.liteCooking.LiteCooking");
            Class<?> activeWorkstationsClass = Class.forName("dev.nekomadev.liteCooking.data.ActiveWorkstations");
            Class<?> workstationClass = Class.forName("dev.nekomadev.liteCooking.data.Workstation");
            Class<?> sessionClass = Class.forName("dev.nekomadev.liteCooking.data.ActiveSession");
            Class<?> stageClass = Class.forName("dev.nekomadev.liteCooking.data.Stage");
            Class<?> recipeClass = Class.forName("dev.nekomadev.liteCooking.data.Recipe");
            Class<?> liteItemClass = Class.forName("dev.nekomadev.litecore.managers.LiteItem");
            Class<?> liteLocationClass = Class.forName("dev.nekomadev.litecore.managers.LiteLocation");

            liteCookingWorkstationsField = asAccessible(liteCookingClass.getDeclaredField("workstations"));
            activeWorkstationsMapField = asAccessible(activeWorkstationsClass.getDeclaredField("workstations"));
            workstationGamesField = asAccessible(workstationClass.getDeclaredField("games"));

            workstationSessionField = asAccessible(workstationClass.getDeclaredField("session"));
            workstationLocationField = asAccessible(workstationClass.getDeclaredField("location"));

            sessionOwnerField = asAccessible(sessionClass.getDeclaredField("owner"));
            sessionStageField = asAccessible(sessionClass.getDeclaredField("stage"));
            sessionRecipeNameField = asAccessible(sessionClass.getDeclaredField("recipe_name"));
            sessionGetRecipeMethod = asAccessible(sessionClass.getDeclaredMethod("get_recipe"));
            sessionGetStageMethod = asAccessible(sessionClass.getDeclaredMethod("get_stage"));

            stageTypeField = asAccessible(stageClass.getDeclaredField("type"));
            stageGameItemField = asAccessible(findDeclaredFieldCompat(stageClass, "game_item", "tool_item"));

            recipeRewardsField = asAccessible(recipeClass.getDeclaredField("rewards"));
            recipeStagesField = asAccessible(recipeClass.getDeclaredField("stages"));

            liteItemLinkField = asAccessible(liteItemClass.getDeclaredField("link"));
            liteItemCompareMethod = asAccessible(liteItemClass.getDeclaredMethod("compare", ItemStack.class));
            liteItemToStackMethod = asAccessible(liteItemClass.getDeclaredMethod("lite_to_stack", List.class));

            liteLocationConstructor = liteLocationClass.getDeclaredConstructor(String.class);
            liteLocationConstructor.setAccessible(true);
            liteLocationGetLocationMethod = asAccessible(liteLocationClass.getDeclaredMethod("getLocation"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> getWorkstations() throws ReflectiveOperationException {
            Object activeWorkstations = liteCookingWorkstationsField.get(null);
            if (activeWorkstations == null) {
                return Map.of();
            }

            Object rawMap = activeWorkstationsMapField.get(activeWorkstations);
            if (!(rawMap instanceof Map<?, ?>)) {
                return Map.of();
            }
            return (Map<String, Object>) rawMap;
        }

        Object getWorkstation(String workstationKey) throws ReflectiveOperationException {
            if (workstationKey == null || workstationKey.isBlank()) {
                return null;
            }
            return getWorkstations().get(workstationKey);
        }

        String toWorkstationKey(Block block) {
            return block.getX() + "," + block.getY() + "," + block.getZ() + "," + block.getWorld().getName();
        }

        Object getSession(Object workstation) throws ReflectiveOperationException {
            return workstationSessionField.get(workstation);
        }

        UUID getOwnerUuid(Object session) throws ReflectiveOperationException {
            return (UUID) sessionOwnerField.get(session);
        }

        Object getCurrentStage(Object session) throws ReflectiveOperationException {
            return sessionGetStageMethod.invoke(session);
        }

        boolean isMiniGameStage(Object stage) throws ReflectiveOperationException {
            if (stage == null) {
                return false;
            }
            Object type = stageTypeField.get(stage);
            return type != null && "MINI_GAME".equals(type.toString());
        }

        Object getStageGameItem(Object stage) throws ReflectiveOperationException {
            if (stage == null) {
                return null;
            }
            return stageGameItemField.get(stage);
        }

        boolean hasLiteItemLink(Object liteItem) throws ReflectiveOperationException {
            if (liteItem == null) {
                return false;
            }
            Object raw = liteItemLinkField.get(liteItem);
            if (raw == null) {
                return false;
            }
            String link = raw.toString().trim();
            return !link.isEmpty();
        }

        boolean matchesLiteItem(Object liteItem, ItemStack stack) throws ReflectiveOperationException {
            if (liteItem == null || stack == null || stack.getType() == Material.AIR) {
                return false;
            }
            Object result = liteItemCompareMethod.invoke(liteItem, stack);
            return result instanceof Boolean bool && bool;
        }

        String describeLiteItem(Object liteItem) throws ReflectiveOperationException {
            if (liteItem == null) {
                return "null";
            }
            Object rawLink = liteItemLinkField.get(liteItem);
            String link = rawLink == null ? "" : rawLink.toString();
            return link.isBlank() ? "liteItem(no-link)" : link;
        }

        @SuppressWarnings("unchecked")
        Object suppressGameClick(Object workstation) throws ReflectiveOperationException {
            Object raw = workstationGamesField.get(null);
            if (!(raw instanceof Map<?, ?> map)) {
                return null;
            }
            return ((Map<Object, Object>) map).remove(workstation);
        }

        @SuppressWarnings("unchecked")
        void restoreSuppressedGame(Object workstation, Object game) throws ReflectiveOperationException {
            if (workstation == null || game == null) {
                return;
            }
            Object raw = workstationGamesField.get(null);
            if (!(raw instanceof Map<?, ?> map)) {
                return;
            }
            ((Map<Object, Object>) map).putIfAbsent(workstation, game);
        }

        int getStage(Object session) throws ReflectiveOperationException {
            return (int) sessionStageField.get(session);
        }

        String getRecipeName(Object session) throws ReflectiveOperationException {
            Object value = sessionRecipeNameField.get(session);
            return value == null ? null : value.toString();
        }

        int getStageCount(Object session) throws ReflectiveOperationException {
            Object recipe = sessionGetRecipeMethod.invoke(session);
            if (recipe == null) {
                return 0;
            }
            Object stages = recipeStagesField.get(recipe);
            if (stages instanceof List<?> list) {
                return list.size();
            }
            return 0;
        }

        @SuppressWarnings("unchecked")
        List<ItemStack> getRewardStacks(Object session) throws ReflectiveOperationException {
            Object recipe = sessionGetRecipeMethod.invoke(session);
            if (recipe == null) {
                return List.of();
            }

            Object rewards = recipeRewardsField.get(recipe);
            if (!(rewards instanceof List<?> rewardList) || rewardList.isEmpty()) {
                return List.of();
            }

            Object converted = liteItemToStackMethod.invoke(null, rewardList);
            if (!(converted instanceof List<?> stacks) || stacks.isEmpty()) {
                return List.of();
            }

            List<ItemStack> result = new ArrayList<>();
            for (Object stack : stacks) {
                if (stack instanceof ItemStack itemStack) {
                    result.add(itemStack.clone());
                }
            }
            return result;
        }

        Location getDropLocation(Object workstation) throws ReflectiveOperationException {
            Object locationRaw = workstationLocationField.get(workstation);
            if (!(locationRaw instanceof String locationString) || locationString.isBlank()) {
                return null;
            }

            Object liteLocation = liteLocationConstructor.newInstance(locationString);
            Object bukkitLocation = liteLocationGetLocationMethod.invoke(liteLocation);
            if (!(bukkitLocation instanceof Location location)) {
                return null;
            }
            return location.clone().add(0.5d, 1.0d, 0.5d);
        }

        private static Field asAccessible(Field field) {
            field.setAccessible(true);
            return field;
        }

        private static Method asAccessible(Method method) {
            method.setAccessible(true);
            return method;
        }

        private static Field findDeclaredFieldCompat(Class<?> target, String... candidates)
                throws NoSuchFieldException {
            NoSuchFieldException last = null;
            for (String name : candidates) {
                try {
                    return target.getDeclaredField(name);
                } catch (NoSuchFieldException ex) {
                    last = ex;
                }
            }
            throw last != null ? last : new NoSuchFieldException(target.getName());
        }
    }
}
