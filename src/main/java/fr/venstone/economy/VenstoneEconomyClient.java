package fr.venstone.economy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/** Client entrypoint: keybinds, chat listener, shop scan, HUD element and config screen. */
public final class VenstoneEconomyClient implements ClientModInitializer {
    public static final SalesTracker TRACKER = new SalesTracker();
    private static final Logger LOGGER = LoggerFactory.getLogger("venstone-economy");
    private static final Identifier HUD_ID = Identifier.of("venstone_economy_assistant", "economy_hud");
    private static final int SHOP_SCAN_INTERVAL_TICKS = 10;
    private static final int HUD_WIDTH = 264;
    private static final int HUD_ROW_HEIGHT = 49;

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;
    private static int scanTicker;
    private static int notificationCooldown;
    /** Cached clock reading, refreshed once per tick so the render path never re-reads the clock. */
    private static volatile long cachedNow;

    @Override public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("venstone", "general"));
        LOGGER.info("Venstone Economy Assistant initialised ({} tracked items, reset mode {})",
                TRACKER.items().size(), TRACKER.resetMode());
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.venstone_economy.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.venstone_economy.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) TRACKER.recordConfirmedSale(message.getString());
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            cachedNow = TRACKER.clock().now();
            TRACKER.tick();
            if (notificationCooldown > 0) notificationCooldown--;
            while (openKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new EconomyScreen(null));
            while (toggleKey.wasPressed()) {
                TRACKER.toggleHud();
                TRACKER.save();
                announce(client, Text.translatable(TRACKER.hudVisible()
                        ? "venstone_economy.notify.hud_on" : "venstone_economy.notify.hud_off"));
            }
            if (client.player != null && client.currentScreen instanceof HandledScreen<?>
                    && ++scanTicker >= SHOP_SCAN_INTERVAL_TICKS) {
                scanTicker = 0;
                scanVisibleShop(client);
            }
            notifyLimitReached(client);
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, HUD_ID, (drawContext, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (TRACKER.hudVisible() && mc.player != null && mc.world != null) renderHud(drawContext, mc);
        });
    }

    /** Shows a limit-reached message once per saturation, without spamming the chat. */
    private static void notifyLimitReached(MinecraftClient client) {
        // Bail out before marking anything: a suppressed notification must not consume the flag.
        if (client.player == null || notificationCooldown > 0) return;
        long now = TRACKER.clock().now();
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

    private static void scanVisibleShop(MinecraftClient client) {
        if (client.player == null || !(client.currentScreen instanceof HandledScreen<?> handled)) return;
        try {
            var playerInventory = client.player.getInventory();
            for (Slot slot : handled.getScreenHandler().slots) {
                // The player's own inventory is not shop stock; only the container matters.
                if (slot.inventory == playerInventory) continue;
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) continue;
                String itemName = stack.getName().getString();
                StringBuilder visible = new StringBuilder(itemName);
                LoreComponent lore = stack.get(DataComponentTypes.LORE);
                if (lore != null) for (Text line : lore.lines()) visible.append(' ').append(line.getString());
                TRACKER.updateShopDetails(visible.toString(), itemName);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Shop scan skipped", e);
        }
    }

    private static void renderHud(DrawContext ctx, MinecraftClient mc) {
        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();
        int width = Math.min(HUD_WIDTH, screenW - 18);
        if (width < 120) return;
        int visible = TRACKER.enabledCount();
        if (visible == 0) return;
        int maxRows = Math.max(0, (screenH - 31 - 9) / HUD_ROW_HEIGHT);
        int rows = Math.min(visible, maxRows);
        if (rows == 0) return;
        int height = 31 + rows * HUD_ROW_HEIGHT;
        SalesTracker.HudCorner corner = TRACKER.hudCorner();
        boolean right = corner == SalesTracker.HudCorner.TOP_RIGHT || corner == SalesTracker.HudCorner.BOTTOM_RIGHT;
        boolean bottom = corner == SalesTracker.HudCorner.BOTTOM_LEFT || corner == SalesTracker.HudCorner.BOTTOM_RIGHT;
        int x = right ? screenW - width - 9 : 9;
        int y = bottom ? Math.max(9, screenH - height - 9) : 9;

        ctx.fill(x, y, x + width, y + height, 0xE812101B);
        ctx.fill(x, y, x + width, y + 2, 0xFFB65BEE);
        ctx.fill(x, y + 2, x + 1, y + height, 0xFF6C3B91);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal("VENSTONE  /  ECONOMY"), x + 10, y + 8, 0xFFF5EAFE);
        String total = Text.translatable("venstone_economy.hud.tracked", visible).getString();
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(total), x + width - mc.textRenderer.getWidth(total) - 10, y + 8, 0xFFB9A9C8);

        int row = 0;
        long now = cachedNow != 0 ? cachedNow : TRACKER.clock().now();
        for (TrackedItem item : TRACKER.items()) {
            if (!item.enabled) continue;
            if (row >= rows) break;
            int ry = y + 27 + row * HUD_ROW_HEIGHT;
            int sold = item.soldItems(now);
            int remaining = item.remainingItems(now);
            int cap = Math.max(1, item.limitItems);
            String label = item.name == null || item.name.isBlank() ? item.id : item.name;
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(trim(label, 25)), x + 10, ry, 0xFFFFFFFF);
            String count = formatStacks(sold) + " / " + formatStacks(cap) + " " + Text.translatable("venstone_economy.unit.stacks").getString();
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(count), x + 10, ry + 13, 0xFFD8D0E0);
            long nextExpiry = item.nextExpiryMillis(now);
            String timer = nextExpiry > 0
                    ? Text.translatable("venstone_economy.hud.next_reset", Durations.format(nextExpiry)).getString()
                    : Text.translatable("venstone_economy.hud.no_reset").getString();
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(timer), x + width - mc.textRenderer.getWidth(timer) - 10, ry + 13,
                    remaining == 0 ? 0xFFFF8888 : 0xFFBDA6D7);
            int barX = x + 10, barY = ry + 29, barW = width - 20;
            ctx.fill(barX, barY, barX + barW, barY + 4, 0xFF393341);
            int fill = Math.round(barW * Math.min(1f, sold / (float) cap));
            if (fill > 0) ctx.fill(barX, barY, barX + fill, barY + 4, remaining == 0 ? 0xFFE15D70 : 0xFFAA65D6);
            String money = Text.translatable("venstone_economy.hud.max", String.format(Locale.FRANCE, "%.2f", item.maximumRevenue())).getString();
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(money), x + 10, ry + 36, 0xFFB8E8C4);
            String free = Text.translatable("venstone_economy.hud.free", formatStacks(remaining)).getString();
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(free), x + width - mc.textRenderer.getWidth(free) - 10, ry + 36, 0xFFB8E8C4);
            row++;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String formatStacks(int items) { return String.format(Locale.ROOT, "%.2f", items / (double) TrackedItem.ITEMS_PER_STACK); }

    /** Configuration screen: scrollable item list, editor fields and preferences. */
    public static final class EconomyScreen extends Screen {
        private final Screen parent;
        private ItemListWidget list;
        private TextFieldWidget idField, nameField, priceField, limitField;
        private ButtonWidget toggleButton, hudButton, cornerButton, resetButton;
        private String feedback = "";
        private int feedbackColor = 0xFFD8C4ED;

        protected EconomyScreen(Screen parent) {
            super(Text.translatable("venstone_economy.screen.title"));
            this.parent = parent;
        }

        @Override protected void init() {
            int listWidth = Math.min(190, width / 3);
            list = new ItemListWidget(client, TRACKER, listWidth, height - 60, 34);
            addDrawableChild(list);
            list.setSelectionListener(this::syncFields);

            int fx = 16 + listWidth + 8;
            int fieldWidth = width - 16 - fx;
            int half = (fieldWidth - 6) / 2;
            // Centre the control block vertically and keep it above the Done button.
            int blockHeight = 72 + 8 * 24;
            int y = Math.max(30, Math.min(34, height - 40 - blockHeight));

            idField = field(fx, y, fieldWidth, "venstone_economy.field.id");
            nameField = field(fx, y + 24, fieldWidth, "venstone_economy.field.name");
            priceField = field(fx, y + 48, half, "venstone_economy.field.price");
            limitField = field(fx + half + 6, y + 48, half, "venstone_economy.field.limit");
            int row = y + 72;

            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.apply"), b -> applySelected())
                    .dimensions(fx, row, half, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.add"), b -> addItem())
                    .dimensions(fx + half + 6, row, half, 20).build());
            row += 24;
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.prev"), b -> { TRACKER.selectPrevious(); reloadList(); })
                    .dimensions(fx, row, half, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.next"), b -> { TRACKER.selectNext(); reloadList(); })
                    .dimensions(fx + half + 6, row, half, 20).build());
            row += 24;
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.sold_stack"), b -> { TRACKER.addSelected(TrackedItem.ITEMS_PER_STACK); reloadList(); })
                    .dimensions(fx, row, half, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.sold_unit"), b -> { TRACKER.addSelected(1); reloadList(); })
                    .dimensions(fx + half + 6, row, half, 20).build());
            row += 24;
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.undo_stack"), b -> { TRACKER.adjustSelected(-TrackedItem.ITEMS_PER_STACK); reloadList(); })
                    .dimensions(fx, row, half, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.reset"), b -> { TRACKER.resetSelected(); reloadList(); })
                    .dimensions(fx + half + 6, row, half, 20).build());
            row += 24;
            toggleButton = ButtonWidget.builder(Text.translatable("venstone_economy.button.disable"), b -> {
                TrackedItem item = TRACKER.selected();
                if (item != null) { item.enabled = !item.enabled; TRACKER.save(); reloadList(); }
            }).dimensions(fx, row, half, 20).build();
            addDrawableChild(toggleButton);
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.remove"), b -> { TRACKER.removeSelected(); reloadList(); })
                    .dimensions(fx + half + 6, row, half, 20).build());
            row += 24;
            addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.suggest"), b -> suggest())
                    .dimensions(fx, row, half, 20).build());
            hudButton = ButtonWidget.builder(Text.translatable("venstone_economy.button.hide_hud"), b -> {
                TRACKER.toggleHud();
                TRACKER.save();
                reloadList();
            }).dimensions(fx + half + 6, row, half, 20).build();
            addDrawableChild(hudButton);
            row += 24;
            cornerButton = ButtonWidget.builder(cornerLabel(), b -> {
                TRACKER.setHudCorner(nextCorner(TRACKER.hudCorner()));
                TRACKER.save();
                reloadList();
            }).dimensions(fx, row, half, 20).build();
            addDrawableChild(cornerButton);
            resetButton = ButtonWidget.builder(resetLabel(), b -> {
                TRACKER.setResetMode(nextResetMode(TRACKER.resetMode()));
                reloadList();
            }).dimensions(fx + half + 6, row, half, 20).build();
            addDrawableChild(resetButton);
            row += 24;

            addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                    .dimensions(width / 2 - 55, height - 28, 110, 20).build());
            reloadList();
        }

        private TextFieldWidget field(int x, int y, int w, String key) {
            TextFieldWidget widget = new TextFieldWidget(textRenderer, x, y, w, 20, Text.translatable(key));
            widget.setPlaceholder(Text.translatable(key));
            widget.setMaxLength(100);
            addDrawableChild(widget);
            return widget;
        }

        private static SalesTracker.HudCorner nextCorner(SalesTracker.HudCorner corner) {
            SalesTracker.HudCorner[] values = SalesTracker.HudCorner.values();
            return values[(corner.ordinal() + 1) % values.length];
        }

        private static SalesTracker.ResetMode nextResetMode(SalesTracker.ResetMode mode) {
            SalesTracker.ResetMode[] values = SalesTracker.ResetMode.values();
            return values[(mode.ordinal() + 1) % values.length];
        }

        private static Text cornerLabel() {
            String corner = Text.translatable("venstone_economy.corner." + TRACKER.hudCorner().name().toLowerCase(Locale.ROOT)).getString();
            return Text.translatable("venstone_economy.button.hud_corner", corner);
        }

        private static Text resetLabel() {
            String mode = Text.translatable("venstone_economy.reset." + TRACKER.resetMode().name().toLowerCase(Locale.ROOT)).getString();
            return Text.translatable("venstone_economy.button.reset_mode", mode);
        }

        /** Rebuilds the list, then reloads the editor fields. Use when the item set changes. */
        private void reloadList() {
            if (list != null) list.refresh();
            syncFields();
        }

        /**
         * Reloads the editor fields and button labels from the tracker state. Deliberately does not
         * rebuild the list: this runs from the list's own click handler, where mutating entries
         * would corrupt its iteration.
         */
        private void syncFields() {
            TrackedItem item = TRACKER.selected();
            if (item != null) {
                idField.setText(item.id);
                nameField.setText(item.name == null ? "" : item.name);
                priceField.setText(String.format(Locale.ROOT, "%.4f", item.configuredPrice));
                limitField.setText(Integer.toString(Math.max(1, item.limitItems / TrackedItem.ITEMS_PER_STACK)));
            } else {
                idField.setText(""); nameField.setText(""); priceField.setText(""); limitField.setText("");
            }
            boolean enabled = item == null || item.enabled;
            toggleButton.setMessage(Text.translatable(enabled ? "venstone_economy.button.disable" : "venstone_economy.button.enable"));
            hudButton.setMessage(Text.translatable(TRACKER.hudVisible() ? "venstone_economy.button.hide_hud" : "venstone_economy.button.show_hud"));
            cornerButton.setMessage(cornerLabel());
            resetButton.setMessage(resetLabel());
        }

        /** Fills the id field with the best registry match for the current text or display name. */
        private void suggest() {
            List<String> byName = ItemResolver.suggestions(nameField.getText(), 1);
            List<String> byId = ItemResolver.suggestions(idField.getText(), 1);
            String pick = !byName.isEmpty() ? byName.get(0) : (!byId.isEmpty() ? byId.get(0) : null);
            if (pick == null) { setFeedback(Text.translatable("venstone_economy.feedback.no_match").getString(), 0xFFFF8888); return; }
            idField.setText(pick);
            setFeedback(Text.translatable("venstone_economy.feedback.suggested", pick).getString(), 0xFFB8E8C4);
        }

        private int readLimitStacks() {
            int stacks = Integer.parseInt(limitField.getText().trim());
            if (stacks < 1 || stacks > TrackedItem.MAX_LIMIT_ITEMS / TrackedItem.ITEMS_PER_STACK) throw new NumberFormatException();
            return stacks;
        }

        private void addItem() {
            try {
                double price = Double.parseDouble(priceField.getText().replace(',', '.'));
                int stacks = readLimitStacks();
                if (TRACKER.addItem(idField.getText(), nameField.getText(), price, stacks)) {
                    reloadList();
                    setFeedback(Text.translatable("venstone_economy.feedback.added").getString(), 0xFFB8E8C4);
                } else {
                    setFeedback(Text.translatable("venstone_economy.feedback.add_failed").getString(), 0xFFFF8888);
                }
            } catch (RuntimeException e) {
                setFeedback(Text.translatable("venstone_economy.feedback.invalid").getString(), 0xFFFF8888);
            }
        }

        private void applySelected() {
            TrackedItem item = TRACKER.selected();
            if (item == null) return;
            try {
                String newId = idField.getText();
                String resolved = ItemResolver.resolveId(newId);
                if (resolved != null) item.id = resolved;
                else if (ItemIds.isValid(newId)) item.id = ItemIds.canonical(newId);
                else if (!newId.isBlank()) { setFeedback(Text.translatable("venstone_economy.feedback.bad_id").getString(), 0xFFFF8888); return; }
                item.name = nameField.getText().isBlank() ? item.name : nameField.getText().trim();
                double price = Math.max(0, Double.parseDouble(priceField.getText().replace(',', '.')));
                item.configuredPrice = Math.min(price, ShopTextParser.MAX_UNIT_PRICE);
                item.limitItems = readLimitStacks() * TrackedItem.ITEMS_PER_STACK;
                item.normalize(TRACKER.clock().now());
                TRACKER.save();
                reloadList();
                setFeedback(Text.translatable("venstone_economy.feedback.applied").getString(), 0xFFB8E8C4);
            } catch (RuntimeException e) {
                setFeedback(Text.translatable("venstone_economy.feedback.invalid").getString(), 0xFFFF8888);
            }
        }

        private void setFeedback(String message, int color) { feedback = message; feedbackColor = color; }

        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFF5EAFE);
            TrackedItem item = TRACKER.selected();
            long now = TRACKER.clock().now();
            String status = item == null
                    ? Text.translatable("venstone_economy.status.none").getString()
                    : Text.translatable("venstone_economy.status.item", trim(item.name, 28), item.soldItems(now), item.limitItems,
                            String.format(Locale.FRANCE, "%.2f", item.maximumRevenue())).getString();
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, 24, 0xFFD8C4ED);
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("venstone_economy.hint.window"), width / 2, height - 46, 0xFFBBAFC6);
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("venstone_economy.hint.automatic"), width / 2, height - 58, 0xFFFFC78A);
            String error = TRACKER.lastError();
            if (error != null) {
                context.drawCenteredTextWithShadow(textRenderer, Text.translatable("venstone_economy.error.save", error), width / 2, height - 70, 0xFFFF8888);
            }
            if (!feedback.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, Text.literal(feedback), width / 2, height - 34, feedbackColor);
        }

        @Override public void close() { if (client != null) client.setScreen(parent); }

        @Override public boolean shouldPause() { return false; }
    }
}
