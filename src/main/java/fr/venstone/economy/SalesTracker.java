package fr.venstone.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the tracked-item list, the selection, the HUD preferences and the on-disk config.
 *
 * <p>The config is a versioned JSON object ({@link #SCHEMA_VERSION}) so future fields can be
 * migrated deterministically. Older files that held a bare JSON array, or items with the
 * pre-v2 {@code unitPrice} field, are upgraded transparently on load.
 */
public final class SalesTracker {
    public static final long WINDOW_MILLIS = 60L * 60L * 1000L;
    public static final int SCHEMA_VERSION = 2;
    /** Identical confirmations arriving within this delay are treated as one sale. */
    private static final long DEDUPE_WINDOW_MILLIS = 2_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("venstone-economy");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Clock clock;
    private final List<TrackedItem> items = new ArrayList<>();
    private final List<String> recentSignatures = new ArrayList<>();
    private final List<Long> recentSignatureTimes = new ArrayList<>();

    private int selectedIndex;
    private boolean dirty;
    private boolean firstRun;
    private boolean hudVisible = true;
    private HudCorner hudCorner = HudCorner.TOP_RIGHT;
    private ResetMode resetMode = ResetMode.WINDOW;
    private String lastMessage;
    private long lastMessageAt;
    private String lastError;
    private long lastErrorAt;

    public SalesTracker() { this(defaultFile(), SystemClock.get()); }

    SalesTracker(Path file) { this(file, SystemClock.get()); }

    SalesTracker(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    private static Path defaultFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("venstone-economy.json");
    }

    public List<TrackedItem> items() { return items; }
    public int selectedIndex() { return selectedIndex; }
    public boolean hudVisible() { return hudVisible; }
    public HudCorner hudCorner() { return hudCorner; }
    public ResetMode resetMode() { return resetMode; }
    public Clock clock() { return clock; }

    /**
     * Sets the reset model globally and applies it to every tracked item. Persisted with the config.
     */
    public void setResetMode(ResetMode mode) {
        if (mode == null || mode == resetMode) return;
        resetMode = mode;
        for (TrackedItem item : items) { item.resetMode = mode; item.limitReachedAt = 0; item.windowStart = 0; }
        dirty = true;
        save();
    }

    public TrackedItem selected() {
        if (items.isEmpty()) return null;
        selectedIndex = Math.floorMod(selectedIndex, items.size());
        return items.get(selectedIndex);
    }

    public void select(int index) {
        if (items.isEmpty()) return;
        selectedIndex = Math.floorMod(index, items.size());
        dirty = true;
    }

    public void selectNext() { if (!items.isEmpty()) select((selectedIndex + 1) % items.size()); }
    public void selectPrevious() { if (!items.isEmpty()) select(Math.floorMod(selectedIndex - 1, items.size())); }

    public void setHudVisible(boolean visible) { if (hudVisible != visible) { hudVisible = visible; dirty = true; } }
    public void toggleHud() { setHudVisible(!hudVisible); }
    public void setHudCorner(HudCorner corner) { if (corner != null && corner != hudCorner) { hudCorner = corner; dirty = true; } }

    /** Number of items currently shown in the HUD; cached for the render path. */
    public int enabledCount() {
        int count = 0;
        for (TrackedItem item : items) if (item.enabled) count++;
        return count;
    }

    /** Expires finished lots. Writes to disk only when something actually changed. */
    public void tick() {
        long now = clock.now();
        boolean changed = false;
        for (TrackedItem item : items) {
            int before = item.lots == null ? 0 : item.lots.size();
            item.normalize(now);
            changed |= before != item.lots.size();
        }
        if (changed) dirty = true;
        if (dirty) save();
    }

    public void addSale(TrackedItem item, int amount) {
        if (item == null || amount <= 0) return;
        item.addSale(amount, clock.now());
        dirty = true;
        save();
    }

    public void addSelected(int amount) { addSale(selected(), amount); }

    public void adjustSelected(int delta) {
        TrackedItem item = selected();
        if (item != null) { item.adjustLatestSale(delta, clock.now()); dirty = true; save(); }
    }

    public void resetSelected() {
        TrackedItem item = selected();
        if (item != null) { item.reset(); dirty = true; save(); }
    }

    /** Adds a new tracked item; the id is resolved against the item registry when available. */
    public boolean addItem(String id, String name, double price, int limitStacks) {
        if (id == null || id.isBlank() || limitStacks < 1) return false;
        String resolved = ItemResolver.resolveId(id);
        String cleanId = resolved != null ? resolved : ItemIds.canonical(id);
        if (cleanId == null) return false;
        if (items.stream().anyMatch(i -> i.id != null && i.id.equalsIgnoreCase(cleanId))) return false;
        double cleanPrice = Double.isFinite(price) && price > 0 ? Math.min(price, ShopTextParser.MAX_UNIT_PRICE) : 0;
        long limitItems = Math.min((long) limitStacks * TrackedItem.ITEMS_PER_STACK, TrackedItem.MAX_LIMIT_ITEMS);
        String cleanName = name == null || name.isBlank() ? ItemResolver.displayName(cleanId) : name.trim();
        items.add(new TrackedItem(cleanId, cleanName, cleanPrice, (int) limitItems));
        selectedIndex = items.size() - 1;
        dirty = true;
        save();
        return true;
    }

    public void removeSelected() {
        if (items.isEmpty()) return;
        items.remove(selectedIndex);
        selectedIndex = Math.max(0, Math.min(selectedIndex, items.size() - 1));
        dirty = true;
        save();
    }

    /** Finds the item whose id, id path or display name best matches the given text. */
    public TrackedItem findByNameOrId(String text) {
        String normalized = ShopTextParser.normalize(text);
        if (normalized.isBlank()) return null;
        // Exact identifiers win over substring matches so overlapping names stay unambiguous.
        for (TrackedItem item : items) {
            if (normalized.equals(ShopTextParser.normalize(item.name))
                    || normalized.equals(ShopTextParser.normalize(ItemIds.path(item.id)))
                    || normalized.equals(ShopTextParser.normalize(item.id))) return item;
        }
        // Prefer the longest matching id path so "melon" does not win over "melon slice".
        // Very short needles are ignored: a 1-2 character query matches far too much.
        if (normalized.length() < 3) return null;
        TrackedItem best = null;
        int bestLength = -1;
        for (TrackedItem item : items) {
            String candidate = ShopTextParser.normalize(ItemIds.path(item.id));
            String name = ShopTextParser.normalize(item.name);
            if (candidate.isEmpty() && name.isEmpty()) continue;
            boolean matches = (candidate.length() >= 3 && (normalized.contains(candidate) || candidate.contains(normalized)))
                    || (name.length() >= 3 && (normalized.contains(name) || name.contains(normalized)));
            if (matches && candidate.length() > bestLength) {
                best = item;
                bestLength = candidate.length();
            }
        }
        return best;
    }

    public boolean recordConfirmedSale(String message) {
        ShopTextParser.Sale sale = ShopTextParser.readSale(message);
        if (sale == null) return false;
        long now = clock.now();
        String signature = ShopTextParser.saleSignature(sale);
        if (signature.equals(lastMessage) && now - lastMessageAt < DEDUPE_WINDOW_MILLIS) return false;
        for (int i = 0; i < recentSignatures.size(); i++) {
            if (recentSignatures.get(i).equals(signature) && now - recentSignatureTimes.get(i) < DEDUPE_WINDOW_MILLIS) {
                return false;
            }
        }
        TrackedItem item = findByNameOrId(sale.itemName());
        if (item == null || !item.enabled) return false;
        remember(signature, now);
        item.addSale(sale.amount(), now);
        if (sale.amount() > 0) item.observedPrice = sale.totalPrice() / sale.amount();
        dirty = true;
        save();
        return true;
    }

    public void updateShopDetails(String visibleText, String itemName) {
        TrackedItem item = findByNameOrId(itemName);
        if (item == null) return;
        Double price = ShopTextParser.readUnitPrice(visibleText);
        Integer limit = ShopTextParser.readLimitItems(visibleText);
        boolean changed = false;
        if (price != null && price > 0 && price <= ShopTextParser.MAX_UNIT_PRICE && price != item.observedPrice) {
            item.observedPrice = price;
            changed = true;
        }
        if (limit != null && limit > 0 && limit <= TrackedItem.MAX_LIMIT_ITEMS && limit != item.limitItems) {
            item.limitItems = limit;
            changed = true;
        }
        if (changed) { dirty = true; save(); }
    }

    private void remember(String signature, long now) {
        lastMessage = signature;
        lastMessageAt = now;
        recentSignatures.add(signature);
        recentSignatureTimes.add(now);
        while (!recentSignatures.isEmpty() && now - recentSignatureTimes.get(0) > DEDUPE_WINDOW_MILLIS) {
            recentSignatures.remove(0);
            recentSignatureTimes.remove(0);
        }
    }

    /** Last save error, or {@code null} when the most recent save succeeded. */
    public String lastError() { return lastError; }

    public boolean hasRecentError() { return lastError != null && clock.now() - lastErrorAt < 30_000L; }

    public void save() {
        dirty = false;
        Path parent = file.getParent();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(tmp)) { GSON.toJson(serialize(), writer); }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lastError = null;
        } catch (Exception e) {
            dirty = true;
            lastError = e.getMessage();
            lastErrorAt = clock.now();
            LOGGER.warn("Could not save config to {}", file, e);
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
        }
    }

    private JsonObject serialize() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("selectedIndex", selectedIndex);
        root.addProperty("hudVisible", hudVisible);
        root.addProperty("hudCorner", hudCorner.name());
        root.addProperty("resetMode", resetMode.name());
        JsonArray array = new JsonArray();
        for (TrackedItem item : items) {
            JsonObject json = GSON.toJsonTree(item).getAsJsonObject();
            json.remove("unitPrice");
            json.remove("legacyUnitPrice");
            array.add(json);
        }
        root.add("items", array);
        return root;
    }

    private void load() {
        firstRun = !Files.exists(file);
        if (!firstRun) {
            try (Reader reader = Files.newBufferedReader(file)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element != null && element.isJsonObject()) readObject(element.getAsJsonObject());
                else if (element != null && element.isJsonArray()) {
                    // Legacy format: a bare array of items.
                    readItems(element.getAsJsonArray());
                }
            } catch (Exception e) {
                LOGGER.warn("Could not read config from {}", file, e);
            }
        }
        items.removeIf(i -> i == null || i.id == null || i.id.isBlank());
        long now = clock.now();
        for (TrackedItem item : items) {
            if (item.name == null || item.name.isBlank()) item.name = ItemIds.path(item.id);
            if (item.lots == null) item.lots = new ArrayList<>();
            item.resetMode = resetMode;
            item.normalize(now);
        }
        selectedIndex = items.isEmpty() ? 0 : Math.floorMod(selectedIndex, items.size());
        if (items.isEmpty() && firstRun) {
            int limit = 36 * TrackedItem.ITEMS_PER_STACK;
            items.add(new TrackedItem("minecraft:sugar_cane", "Canne à sucre", 0.12, limit));
            items.add(new TrackedItem("minecraft:cactus", "Cactus", 0.05, limit));
            items.add(new TrackedItem("minecraft:pumpkin", "Citrouille", 0.10, limit));
            items.add(new TrackedItem("minecraft:melon_slice", "Tranche de pastèque", 0.06, limit));
            for (TrackedItem item : items) item.resetMode = resetMode;
            save();
        }
    }

    private void readObject(JsonObject root) {
        int version = intOr(root, "schemaVersion", 1);
        if (version > SCHEMA_VERSION) {
            LOGGER.warn("Config schema v{} is newer than supported v{}; unknown fields are ignored", version, SCHEMA_VERSION);
        }
        hudVisible = boolOr(root, "hudVisible", hudVisible);
        selectedIndex = intOr(root, "selectedIndex", selectedIndex);
        if (root.has("hudCorner") && root.get("hudCorner").isJsonPrimitive()) {
            try { hudCorner = HudCorner.valueOf(root.get("hudCorner").getAsString()); }
            catch (IllegalArgumentException ignored) { }
        }
        if (root.has("resetMode") && root.get("resetMode").isJsonPrimitive()) {
            try { resetMode = ResetMode.valueOf(root.get("resetMode").getAsString()); }
            catch (IllegalArgumentException ignored) { }
        }
        if (root.has("items") && root.get("items").isJsonArray()) readItems(root.getAsJsonArray("items"));
    }

    /** Reads an int field, falling back to the default when it is missing or the wrong type. */
    private static int intOr(JsonObject root, String key, int fallback) {
        try { return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsInt() : fallback; }
        catch (RuntimeException wrongType) { return fallback; }
    }

    private static boolean boolOr(JsonObject root, String key, boolean fallback) {
        try { return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException wrongType) { return fallback; }
    }

    private void readItems(JsonArray array) {
        for (JsonElement entry : array) {
            if (entry == null || !entry.isJsonObject()) continue;
            // One malformed entry must not discard the rest of the file.
            try {
                TrackedItem item = GSON.fromJson(entry, TrackedItem.class);
                if (item != null) items.add(item);
            } catch (RuntimeException malformedEntry) {
                LOGGER.warn("Skipping malformed item entry: {}", entry, malformedEntry);
            }
        }
    }

    /** HUD anchoring corner, persisted with the rest of the preferences. */
    public enum HudCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * How a reached sell limit recovers.
     *
     * <p>Venstone (EconomyShopGUI) uses {@link #WINDOW}: a fixed 60-minute window anchored on the
     * first sale of the period, so the whole quota returns 60 minutes after that first sale.
     * {@link #ROLLING} keeps the original per-lot model, {@link #TIMER} counts down once the limit
     * is fully reached, and {@link #CLOCK} resets at the next hour boundary.
     */
    public enum ResetMode { WINDOW, ROLLING, TIMER, CLOCK }
}
