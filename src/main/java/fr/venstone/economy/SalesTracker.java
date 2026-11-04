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
import java.util.ArrayList;
import java.util.List;

public final class SalesTracker {
    public static final long WINDOW_MILLIS = 60L * 60L * 1000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<TrackedItem>>() {}.getType();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("venstone-economy.json");
    private final List<TrackedItem> items = new ArrayList<>();
    private int selectedIndex;
    private long lastSave;

    public SalesTracker() { load(); }
    public List<TrackedItem> items() { return items; }
    public int selectedIndex() { return selectedIndex; }
    public TrackedItem selected() {
        if (items.isEmpty()) return null;
        selectedIndex = Math.floorMod(selectedIndex, items.size());
        return items.get(selectedIndex);
    }
    public void selectNext() { if (!items.isEmpty()) selectedIndex = (selectedIndex + 1) % items.size(); }
    public void selectPrevious() { if (!items.isEmpty()) selectedIndex = Math.floorMod(selectedIndex - 1, items.size()); }

    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (TrackedItem item : items) {
            int before = item.lots == null ? 0 : item.lots.size();
            item.normalize(now);
            changed |= before != item.lots.size();
        }
        if (changed || now - lastSave > 15_000L) save();
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
        if (items.stream().anyMatch(i -> i.id.equalsIgnoreCase(cleanId))) return false;
        items.add(new TrackedItem(cleanId, name == null || name.isBlank() ? cleanId : name.trim(), Math.max(0, price), limitStacks * 64));
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
        for (TrackedItem item : items) {
            String idPath = item.id == null ? "" : item.id.substring(item.id.indexOf(':') + 1).replace('_', ' ');
            String name = ShopTextParser.normalize(item.name);
            String id = ShopTextParser.normalize(idPath);
            if (normalized.equals(name) || normalized.equals(id) || normalized.contains(name) || normalized.contains(id) || name.contains(normalized)) return item;
        }
        return null;
    }
    public boolean recordConfirmedSale(String message) {
        ShopTextParser.Sale sale = ShopTextParser.readSale(message);
        if (sale == null) return false;
        TrackedItem item = findByNameOrId(sale.itemName());
        if (item == null || !item.enabled) return false;
        addSale(item, sale.amount());
        if (sale.totalPrice() >= 0 && sale.amount() > 0) item.unitPrice = sale.totalPrice() / sale.amount();
        save();
        return true;
    }
    public void updateShopDetails(String visibleText, String itemName) {
        TrackedItem item = findByNameOrId(itemName);
        if (item == null) return;
        Double price = ShopTextParser.readUnitPrice(visibleText);
        Integer limit = ShopTextParser.readLimitItems(visibleText);
        boolean changed = false;
        if (price != null && price >= 0 && price <= 1_000_000) { item.unitPrice = price; changed = true; }
        if (limit != null && limit > 0 && limit <= 64 * 100_000) { item.limitItems = limit; changed = true; }
        if (changed) save();
    }
    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) { GSON.toJson(items, LIST_TYPE, writer); }
            lastSave = System.currentTimeMillis();
        } catch (Exception e) { System.err.println("[Venstone Economy] Save failed: " + e.getMessage()); }
    }
    private void load() {
        try {
            if (Files.exists(FILE)) try (Reader reader = Files.newBufferedReader(FILE)) {
                List<TrackedItem> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) items.addAll(loaded);
            }
        } catch (Exception e) { System.err.println("[Venstone Economy] Config read failed: " + e.getMessage()); }
        items.removeIf(i -> i == null || i.id == null || i.name == null);
        if (items.isEmpty()) {
            items.add(new TrackedItem("minecraft:sugar_cane", "Canne à sucre", 0.12, 36 * 64));
            items.add(new TrackedItem("minecraft:cactus", "Cactus", 0.05, 36 * 64));
            items.add(new TrackedItem("minecraft:pumpkin", "Citrouille", 0.10, 36 * 64));
            items.add(new TrackedItem("minecraft:melon_slice", "Tranche de pastèque", 0.06, 36 * 64));
            save();
        }
        long now = System.currentTimeMillis();
        for (TrackedItem item : items) item.normalize(now);
    }
}
