package fr.venstone.economy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the shop container that is currently open and feeds the price catalogue.
 *
 * <p>Nothing is sent to the server: the scan only inspects the slots the client already displays.
 * The container's own slots are read; the player's inventory is skipped because it is not shop
 * stock. A container is only treated as a shop when at least one item carries a recognizable price,
 * which keeps ordinary chests and inventories out of the catalogue.
 *
 * <p>A container is only committed once the same contents have been seen twice in a row, so a menu
 * that is still populating is never recorded half-read.
 */
public final class ShopScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("venstone-economy");

    /**
     * Words that mark a slot as a navigation or decoration button rather than shop stock. Matched as
     * whole words so an item whose name merely contains one (for example "Backpack") is kept.
     */
    private static final java.util.regex.Pattern NAVIGATION_WORDS = java.util.regex.Pattern.compile(
            "\\b(?:page suivante|page precedente|next page|previous page|suivant|suivants"
                    + "|precedent|precedents|retour|retours|back|fermer|ferme|close|closes"
                    + "|categorie|categories|category|accueil|home|quitter|quit)\\b");

    /** Matches pagination labels such as "Page 2 / 5" in normalized form. */
    private static final java.util.regex.Pattern PAGE_LABEL =
            java.util.regex.Pattern.compile("^page\\s*(\\d|suivante|precedente|next|previous|$)");

    /** One item read from the container. */
    public record ScannedItem(String registryId, String name, int stackSize, Double sellPrice,
                              Double buyPrice, Integer sellLimit, Integer buyLimit) { }

    /** Result of scanning one container. */
    public record ScanResult(String category, List<ScannedItem> items, boolean shopLike) { }

    private final ShopCatalog catalog;
    private final List<ScannedItem> lastScan = new ArrayList<>();
    private String lastCategory = "";
    private long lastScanAt;

    public ShopScanner(ShopCatalog catalog) { this.catalog = catalog; }

    public long lastScanAt() { return lastScanAt; }

    /**
     * Scans the open container, if any.
     *
     * @return {@code true} when the catalogue gained or refreshed at least one entry
     */
    public boolean scan(MinecraftClient client) {
        if (client == null || client.player == null) return false;
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) return false;
        ScanResult result;
        try {
            result = read(handled, client.player.getInventory());
        } catch (RuntimeException e) {
            LOGGER.debug("Shop scan skipped", e);
            return false;
        }
        if (!result.shopLike() || result.items().isEmpty()) return false;

        if (!result.category().equals(lastCategory) || !sameItems(result.items())) {
            // Contents changed: hold off until the next scan confirms they have settled.
            lastScan.clear();
            lastScan.addAll(result.items());
            lastCategory = result.category();
            return false;
        }

        lastScanAt = System.currentTimeMillis();
        boolean changed = false;
        for (ScannedItem scanned : result.items()) {
            if (catalog.record(scanned.registryId(), scanned.name(), scanned.stackSize(), scanned.sellPrice(),
                    scanned.buyPrice(), scanned.sellLimit(), scanned.buyLimit())) {
                changed = true;
            }
        }
        if (changed) catalog.save();
        return changed;
    }

    /** Reads every container slot of an open handled screen. Pure inspection, no side effects. */
    public static ScanResult read(HandledScreen<?> handled, PlayerInventory playerInventory) {
        ScreenHandler handler = handled.getScreenHandler();
        String category = handled.getTitle() == null ? "" : handled.getTitle().getString();
        List<ScannedItem> items = new ArrayList<>();
        boolean shopLike = false;
        for (Slot slot : handler.slots) {
            if (slot.inventory == playerInventory) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            ScannedItem item = describe(stack);
            if (item == null) continue;
            if (item.sellPrice() != null || item.buyPrice() != null) shopLike = true;
            items.add(item);
        }
        return new ScanResult(category, items, shopLike);
    }

    /** Builds a {@link ScannedItem} from a stack, or {@code null} for a button or decoration. */
    static ScannedItem describe(ItemStack stack) {
        Item item = stack.getItem();
        String registryId = Registries.ITEM.getId(item).toString();
        String name = stack.getName().getString();
        StringBuilder visible = new StringBuilder(name);
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) for (Text line : lore.lines()) visible.append(' ').append(line.getString());
        return build(registryId, name, stack.getMaxCount(), visible.toString());
    }

    /**
     * Pure form of {@link #describe}: decides whether a slot is a shop entry and what it holds.
     * Kept free of client types so the reliability rules are unit-testable.
     */
    public static ScannedItem build(String registryId, String displayName, int maxCount, String visibleText) {
        String id = ItemIds.canonical(registryId);
        if (id == null) return null;
        // A custom UI icon is not a real item; only genuine registry items enter the catalogue.
        if (!ItemResolver.isRegistered(id)) return null;
        // A blank display name is not a navigation button: fall back to the id path instead.
        if (displayName != null && !displayName.isBlank() && isNavigation(displayName)) return null;
        String text = visibleText == null ? "" : visibleText;
        Double sell = ShopTextParser.readUnitPrice(text);
        Double buy = ShopTextParser.readBuyPrice(text);
        Integer sellLimit = ShopTextParser.readLimitItems(text);
        Integer buyLimit = ShopTextParser.readBuyLimitItems(text);
        // An entry with no price at all is a category icon or filler; it must not enter the database.
        if (sell == null && buy == null) return null;
        String name = displayName == null || displayName.isBlank() ? ItemIds.path(id) : displayName.trim();
        return new ScannedItem(id, name, StackMath.clampStackSize(maxCount), sell, buy, sellLimit, buyLimit);
    }

    /** True for navigation buttons and pure decoration, which are never shop stock. */
    static boolean isNavigation(String name) {
        if (name == null || name.isBlank()) return true;
        String lower = ShopTextParser.normalize(name);
        if (lower.isEmpty()) return true;
        if (PAGE_LABEL.matcher(lower).find()) return true;
        if (NAVIGATION_WORDS.matcher(lower).find()) return true;
        // No letters at all: an arrow, a divider, a blank filler.
        return lower.replaceAll("[^a-z]", "").isEmpty();
    }

    private boolean sameItems(List<ScannedItem> items) {
        if (items.size() != lastScan.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            ScannedItem a = items.get(i);
            ScannedItem b = lastScan.get(i);
            if (!a.registryId().equals(b.registryId()) || a.stackSize() != b.stackSize()
                    || !Objects.equals(a.sellPrice(), b.sellPrice())
                    || !Objects.equals(a.buyPrice(), b.buyPrice())
                    || !a.name().equals(b.name())) return false;
        }
        return true;
    }

    /** Resets the settle counter; call when the open container changes. */
    public void reset() {
        lastScan.clear();
        lastCategory = "";
    }
}
