package fr.venstone.economy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: keybinds, chat listener, shop scan, HUD element and configuration screen.
 *
 * <p>The mod is strictly read-only: it inspects the container the client already renders and the
 * chat lines it already receives. Nothing is sent to the server.
 */
public final class VenstoneEconomyClient implements ClientModInitializer {
    public static final SalesTracker TRACKER = new SalesTracker();
    public static final ShopCatalog CATALOG = new ShopCatalog();
    private static final ShopScanner SCANNER = new ShopScanner(CATALOG);
    private static final Logger LOGGER = LoggerFactory.getLogger("venstone-economy");
    private static final Identifier HUD_ID = Identifier.of("venstone_economy_assistant", "economy_hud");
    private static final int SHOP_SCAN_INTERVAL_TICKS = 10;

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;
    private static int scanTicker;
    private static int notificationCooldown;
    private static Screen lastScreen;
    /** Cached clock reading, refreshed once per tick so the render path never re-reads the clock. */
    private static volatile long cachedNow;

    @Override public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("venstone", "general"));
        LOGGER.info("Venstone Economy Assistant initialised ({} tracked items, reset mode {}, {} catalogued prices)",
                TRACKER.items().size(), TRACKER.resetMode(), CATALOG.size());
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.venstone_economy.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.venstone_economy.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            try {
                if (TRACKER.recordConfirmedSale(message.getString())) syncCatalogFromSale(message.getString());
            } catch (RuntimeException e) {
                LOGGER.debug("Ignoring malformed chat message", e);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            cachedNow = TRACKER.clock().now();
            TRACKER.tick();
            if (notificationCooldown > 0) notificationCooldown--;
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new EconomyScreen(null, CATALOG, SCANNER));
            }
            while (toggleKey.wasPressed()) {
                TRACKER.toggleHud();
                TRACKER.save();
                announce(client, Text.translatable(TRACKER.hudVisible()
                        ? "venstone_economy.notify.hud_on" : "venstone_economy.notify.hud_off"));
            }
            handleScreenChange(client);
            if (client.player != null && client.currentScreen instanceof HandledScreen<?>
                    && ++scanTicker >= SHOP_SCAN_INTERVAL_TICKS) {
                scanTicker = 0;
                scanShop(client);
            }
            notifyLimitReached(client);
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, HUD_ID, (drawContext, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.world == null) return;
            EconomyHud.render(drawContext, mc, TRACKER, cachedNow != 0 ? cachedNow : TRACKER.clock().now());
        });
    }

    /** Resets the scanner when the open container changes, so a new page starts from a clean slate. */
    private static void handleScreenChange(MinecraftClient client) {
        Screen current = client.currentScreen;
        if (current != lastScreen) {
            lastScreen = current;
            scanTicker = 0;
            if (!(current instanceof HandledScreen<?>)) SCANNER.reset();
        }
    }

    /**
     * Reads the open container and stores what it shows. Prices are pushed onto the matching tracked
     * items, and genuinely new sellable items are auto-tracked so the HUD and the economics update
     * without any manual entry.
     */
    private static void scanShop(MinecraftClient client) {
        if (!SCANNER.scan(client)) return;
        boolean changed = false;
        for (ShopCatalog.PriceEntry entry : CATALOG.sellableEntries()) {
            if (TRACKER.findByNameOrId(entry.id) != null) {
                TRACKER.applyShopPrices(entry.id, entry.sellPrice, entry.hasBuyPrice() ? entry.buyPrice : null);
                changed = true;
            } else if (TRACKER.autoTrack() && TRACKER.addItem(entry.id, entry.name, entry.sellPrice, entry.stackSize)) {
                changed = true;
            }
        }
        if (changed) {
            TRACKER.save();
            LOGGER.debug("Shop prices refreshed ({} tracked items)", TRACKER.items().size());
        }
    }

    /** Keeps the catalogue in sync with the unit price a confirmed sale reveals. */
    private static void syncCatalogFromSale(String message) {
        ShopTextParser.Sale sale = ShopTextParser.readSale(message);
        if (sale == null || sale.amount() <= 0) return;
        double unit = sale.totalPrice() / sale.amount();
        TrackedItem item = TRACKER.findByNameOrId(sale.itemName());
        if (item == null) return;
        CATALOG.record(item.id, item.name, item.stackSize(), unit, null, null, null, true);
        CATALOG.save();
    }

    /** Shows a limit-reached message once per saturation, without spamming the chat. */
    private static void notifyLimitReached(MinecraftClient client) {
        // Bail out before marking anything: a suppressed notification must not consume the flag.
        if (client.player == null || notificationCooldown > 0) return;
        long now = cachedNow != 0 ? cachedNow : TRACKER.clock().now();
        for (TrackedItem item : TRACKER.items()) {
            if (!item.enabled || item.limitNotified) continue;
            if (item.remainingItems(now) > 0) continue;
            item.limitNotified = true;
            TRACKER.save();
            announce(client, Text.translatable("venstone_economy.notify.limit", item.name));
            return;
        }
    }

    private static void announce(MinecraftClient client, Text text) {
        if (client.player == null || notificationCooldown > 0) return;
        client.player.sendMessage(text, true);
        notificationCooldown = 40;
    }
}
