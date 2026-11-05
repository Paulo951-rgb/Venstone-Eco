package fr.venstone.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class SalesTracker {
    public static final long WINDOW_MILLIS = 60L * 60L * 1000L;
    /** Identical confirmations arriving within this delay are treated as one sale. */
    private static final long DEDUPE_WINDOW_MILLIS = 2_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<TrackedItem>>() {}.getType();

    private final Path file;
    private final List<TrackedItem> items = new ArrayList<>();
    private int selectedIndex;
    private boolean dirty;
    private boolean firstRun;
    private String lastMessage;
    private long lastMessageAt;

    public SalesTracker() { this(defaultFile()); }
    SalesTracker(Path file) { this.file = file; load(); }

    private static Path defaultFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("venstone-economy.json");
    }

    public List<TrackedItem> items() { return items; }
    public int selectedIndex() { return selectedIndex; }
    public TrackedItem selected() {
        if (items.isEmpty()) return null;
        selectedIndex = Math.floorMod(selectedIndex, items.size());
        return items.get(selectedIndex);
    }
    public void selectNext() { if (!items.isEmpty()) selectedIndex = (selectedIndex + 1) % items.size(); }
    public void selectPrevious() { if (!items.isEmpty()) selectedIndex = Math.floorMod(selectedIndex - 1, items.size()); }

    /** Expires finished lots. Writes to disk only when a lot actually expired. */
    public void tick() {
        long now = System.currentTimeMillis();
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
        item.addSale(amount, System.currentTimeMillis());
        save();
    }
    public void addSelected(int amount) { addSale(selected(), amount); }
    public void adjustSelected(int delta) {
        TrackedItem item = selected();
        if (item != null) { item.adjustLatestSale(delta, System.currentTimeMillis()); save(); }
    }
    public void resetSelected() { TrackedItem item = selected(); if (item != null) { item.reset(); save(); } }

    public boolean addItem(String id, String name, double price, int limitStacks) {
        if (id == null || id.isBlank() || limitStacks < 1) return false;
        String cleanId = id.trim().toLowerCase();
        if (items.stream().anyMatch(i -> i.id != null && i.id.equalsIgnoreCase(cleanId))) return false;
        double cleanPrice = Double.isFinite(price) && price > 0 ? Math.min(price, ShopTextParser.MAX_UNIT_PRICE) : 0;
        long limitItems = Math.min((long)limitStacks * TrackedItem.ITEMS_PER_STACK, TrackedItem.MAX_LIMIT_ITEMS);
        String cleanName = name == null || name.isBlank() ? cleanId : name.trim();
        items.add(new TrackedItem(cleanId, cleanName, cleanPrice, (int)limitItems));
        selectedIndex = items.size() - 1; save(); return true;
    }
    public void removeSelected() {
        if (items.isEmpty()) return;
        items.remove(selectedIndex);
        selectedIndex = Math.max(0, Math.min(selectedIndex, items.size() - 1)); save();
    }
    public TrackedItem findByNameOrId(String text) {
        String normalized = ShopTextParser.normalize(text);
        if (normalized.isBlank()) return null;
        // Exact identifiers win over substring matches so overlapping names stay unambiguous.
        for (TrackedItem item : items) {
            if (normalized.equals(ShopTextParser.normalize(item.name))
                    || normalized.equals(ShopTextParser.normalize(idPath(item.id)))
                    || normalized.equals(ShopTextParser.normalize(item.id))) return item;
        }
        for (TrackedItem item : items) {
            if (matches(normalized, ShopTextParser.normalize(item.name))
                    || matches(normalized, ShopTextParser.normalize(idPath(item.id)))) return item;
        }
        return null;
    }
    private static boolean matches(String needle, String candidate) {
        if (candidate.isEmpty()) return false;
        if (needle.contains(candidate)) return true;
        // Only treat a short needle as a partial name when it is long enough to be meaningful.
        return needle.length() >= 3 && candidate.contains(needle);
    }
    private static String idPath(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
    }
    public boolean recordConfirmedSale(String message) {
        ShopTextParser.Sale sale = ShopTextParser.readSale(message);
        if (sale == null) return false;
        long now = System.currentTimeMillis();
        if (message.equals(lastMessage) && now - lastMessageAt < DEDUPE_WINDOW_MILLIS) return false;
        TrackedItem item = findByNameOrId(sale.itemName());
        if (item == null || !item.enabled) return false;
        lastMessage = message; lastMessageAt = now;
        item.addSale(sale.amount(), now);
        if (sale.amount() > 0) item.unitPrice = sale.totalPrice() / sale.amount();
        save();
        return true;
    }
    public void updateShopDetails(String visibleText, String itemName) {
        TrackedItem item = findByNameOrId(itemName);
        if (item == null) return;
        Double price = ShopTextParser.readUnitPrice(visibleText);
        Integer limit = ShopTextParser.readLimitItems(visibleText);
        boolean changed = false;
        if (price != null && price >= 0 && price <= ShopTextParser.MAX_UNIT_PRICE && price != item.unitPrice) {
            item.unitPrice = price; changed = true;
        }
        if (limit != null && limit > 0 && limit <= TrackedItem.MAX_LIMIT_ITEMS && limit != item.limitItems) {
            item.limitItems = limit; changed = true;
        }
        if (changed) save();
    }
    public void save() {
        dirty = false;
        Path parent = file.getParent();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(tmp)) { GSON.toJson(items, LIST_TYPE, writer); }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            dirty = true;
            System.err.println("[Venstone Economy] Save failed: " + e.getMessage());
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
        }
    }
    private void load() {
        firstRun = !Files.exists(file);
        try {
            if (!firstRun) try (Reader reader = Files.newBufferedReader(file)) {
                List<TrackedItem> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) items.addAll(loaded);
            }
        } catch (Exception e) { System.err.println("[Venstone Economy] Config read failed: " + e.getMessage()); }
        items.removeIf(i -> i == null || i.id == null || i.id.isBlank());
        long now = System.currentTimeMillis();
        for (TrackedItem item : items) {
            if (item.name == null || item.name.isBlank()) item.name = idPath(item.id);
            if (item.lots == null) item.lots = new ArrayList<>();
            item.normalize(now);
        }
        if (items.isEmpty() && firstRun) {
            items.add(new TrackedItem("minecraft:sugar_cane", "Canne à sucre", 0.12, 36 * 64));
            items.add(new TrackedItem("minecraft:cactus", "Cactus", 0.05, 36 * 64));
            items.add(new TrackedItem("minecraft:pumpkin", "Citrouille", 0.10, 36 * 64));
            items.add(new TrackedItem("minecraft:melon_slice", "Tranche de pastèque", 0.06, 36 * 64));
            save();
        }
    }
}
