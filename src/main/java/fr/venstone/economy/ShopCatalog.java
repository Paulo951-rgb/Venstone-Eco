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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Local price database built from what the shop shows.
 *
 * <p>Every entry keeps the sell price, the buy price, the sell limit, the buy limit and the last
 * time each of them was seen. A value is only overwritten by a reading that is at least as
 * trustworthy, so a partial or misread tooltip can never erase a good price: a missing field leaves
 * the stored value untouched and only marks the entry as stale.
 */
public final class ShopCatalog {
    /** Stored readings older than this are reported as stale. */
    public static final long STALE_MILLIS = 24L * 60L * 60L * 1000L;
    public static final int SCHEMA_VERSION = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger("venstone-economy");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double MAX_PRICE = ShopTextParser.MAX_UNIT_PRICE;

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, PriceEntry> entries = new LinkedHashMap<>();
    private boolean dirty;

    public ShopCatalog() { this(defaultFile(), System::currentTimeMillis); }

    public ShopCatalog(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    private static Path defaultFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("venstone-shop-prices.json");
    }

    /** One item's known shop data plus the timestamps of the last trusted reading. */
    public static final class PriceEntry {
        public String id;
        public String name;
        public double sellPrice;
        public double buyPrice;
        public int sellLimit;
        public int buyLimit;
        public int stackSize = StackMath.DEFAULT_STACK_SIZE;
        /** When any usable value was last read; drives the staleness marker. */
        public long lastSeenAt;

        public boolean hasSellPrice() { return sellPrice > 0; }
        public boolean hasBuyPrice() { return buyPrice > 0; }
        public boolean hasSellLimit() { return sellLimit > 0; }

        /** Items sellable per cycle for this item, honouring its real stack size. */
        public int limitItems() {
            return hasSellLimit() ? sellLimit : StackMath.limitItems(stackSize);
        }
    }

    public int size() { return entries.size(); }

    public PriceEntry get(String id) {
        return id == null ? null : entries.get(ItemIds.canonical(id));
    }

    /** Entries with a known sell price, richest first: the most useful overview. */
    public List<PriceEntry> sellableEntries() {
        List<PriceEntry> out = new ArrayList<>();
        for (PriceEntry entry : entries.values()) if (entry.hasSellPrice()) out.add(entry);
        out.sort(Comparator.comparingDouble((PriceEntry e) -> e.sellPrice).reversed());
        return out;
    }

    public boolean isStale(PriceEntry entry, long now) {
        return entry != null && (entry.lastSeenAt <= 0 || now - entry.lastSeenAt > STALE_MILLIS);
    }

    /**
     * Records what a tooltip showed. Only non-null readings are applied, so a tooltip that failed to
     * expose one line keeps the previously stored value. Returns {@code true} when anything changed.
     */
    public boolean record(String id, String name, int stackSize, Double sellPrice, Double buyPrice,
                          Integer sellLimit, Integer buyLimit) {
        String cleanId = ItemIds.canonical(id);
        if (cleanId == null) return false;
        long now = clock.getAsLong();
        PriceEntry entry = entries.get(cleanId);
        boolean created = entry == null;
        if (created) {
            entry = new PriceEntry();
            entry.id = cleanId;
            // Register before applying readings so later lookups find it; a reading that turns out to
            // be empty still leaves a placeholder that the next scan can fill in.
            entries.put(cleanId, entry);
        }
        if (name != null && !name.isBlank()) entry.name = name.trim();
        else if (entry.name == null) entry.name = ItemIds.path(cleanId);

        boolean changed = created;

        if (validPrice(sellPrice)) {
            // A new or different value replaces the old one; an identical reading only refreshes the
            // timestamp. Either way the value was seen just now, so the entry is not stale.
            changed |= entry.sellPrice != sellPrice;
            entry.sellPrice = sellPrice;
            entry.lastSeenAt = now;
        }
        if (validPrice(buyPrice)) {
            changed |= entry.buyPrice != buyPrice;
            entry.buyPrice = buyPrice;
            entry.lastSeenAt = now;
        }
        if (validLimit(sellLimit)) {
            changed |= entry.sellLimit != sellLimit;
            entry.sellLimit = sellLimit;
            entry.lastSeenAt = now;
        }
        if (validLimit(buyLimit)) {
            changed |= entry.buyLimit != buyLimit;
            entry.buyLimit = buyLimit;
        }
        int cleanStack = StackMath.clampStackSize(stackSize);
        if (entry.stackSize != cleanStack) {
            entry.stackSize = cleanStack;
            changed = true;
        }
        if (changed) dirty = true;
        return changed;
    }

    private static boolean validPrice(Double price) {
        return price != null && Double.isFinite(price) && price > 0 && price <= MAX_PRICE;
    }

    private static boolean validLimit(Integer limit) { return limit != null && limit > 0; }

    /** Empties the catalogue. Used by the "Clear" action; the caller is responsible for saving. */
    public void clear() {
        if (entries.isEmpty()) return;
        entries.clear();
        dirty = true;
    }

    /** Writes the catalogue atomically. Returns {@code false} and logs on failure. */
    public boolean save() {
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
            return true;
        } catch (Exception e) {
            dirty = true;
            LOGGER.warn("Could not save shop catalogue to {}", file, e);
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
            return false;
        }
    }

    private JsonObject serialize() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray array = new JsonArray();
        for (PriceEntry entry : entries.values()) array.add(GSON.toJsonTree(entry));
        root.add("items", array);
        return root;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) return;
            JsonObject root = element.getAsJsonObject();
            if (!root.has("items") || !root.get("items").isJsonArray()) return;
            for (JsonElement raw : root.getAsJsonArray("items")) {
                if (raw == null || !raw.isJsonObject()) continue;
                try {
                    PriceEntry entry = GSON.fromJson(raw, PriceEntry.class);
                    String cleanId = entry == null ? null : ItemIds.canonical(entry.id);
                    if (cleanId == null) continue;
                    entry.id = cleanId;
                    if (entry.stackSize <= 0) entry.stackSize = StackMath.DEFAULT_STACK_SIZE;
                    if (!Double.isFinite(entry.sellPrice) || entry.sellPrice < 0) entry.sellPrice = 0;
                    if (!Double.isFinite(entry.buyPrice) || entry.buyPrice < 0) entry.buyPrice = 0;
                    if (entry.name == null || entry.name.isBlank()) entry.name = ItemIds.path(cleanId);
                    entries.put(cleanId, entry);
                } catch (RuntimeException malformed) {
                    LOGGER.warn("Skipping malformed catalogue entry: {}", raw, malformed);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read shop catalogue from {}", file, e);
        }
    }
}
