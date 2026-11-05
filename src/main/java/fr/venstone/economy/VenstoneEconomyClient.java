package fr.venstone.economy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
import org.lwjgl.glfw.GLFW;
import net.minecraft.util.Identifier;
import java.util.Locale;

public final class VenstoneEconomyClient implements ClientModInitializer {
    public static final SalesTracker TRACKER = new SalesTracker();
    public static boolean hudVisible = true;
    private static final Identifier HUD_ID = Identifier.of("venstone_economy_assistant", "economy_hud");
    private static final int SHOP_SCAN_INTERVAL_TICKS = 10;
    private static KeyBinding openKey;
    private static int scanTicker;

    @Override public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("venstone", "general"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.venstone_economy.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                category
        ));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) TRACKER.recordConfirmedSale(message.getString());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            TRACKER.tick();
            while (openKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new EconomyScreen(null));
            if (client.player != null && client.currentScreen instanceof HandledScreen<?>
                    && ++scanTicker >= SHOP_SCAN_INTERVAL_TICKS) {
                scanTicker = 0;
                scanVisibleShop(client);
            }
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, HUD_ID, (drawContext, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (hudVisible && mc.player != null && mc.world != null) renderHud(drawContext, mc);
        });
    }

    private static void scanVisibleShop(MinecraftClient client) {
        if (client.player == null) return;
        for (Slot slot : client.player.currentScreenHandler.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            String itemName = stack.getName().getString();
            StringBuilder visible = new StringBuilder(itemName);
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null) for (Text line : lore.lines()) visible.append(' ').append(line.getString());
            TRACKER.updateShopDetails(visible.toString(), itemName);
        }
    }

    private static void renderHud(DrawContext ctx, MinecraftClient mc) {
        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();
        int width = Math.min(264, screenW - 18);
        int x = screenW - width - 9, y = 9;
        int visible = (int)TRACKER.items().stream().filter(i -> i.enabled).count();
        if (visible == 0 || width < 120) return;
        int maxRows = Math.max(0, (screenH - 31 - y) / 49);
        int rows = Math.min(visible, maxRows);
        if (rows == 0) return;
        int height = 31 + rows * 49;
        ctx.fill(x, y, x + width, y + height, 0xE812101B);
        ctx.fill(x, y, x + width, y + 2, 0xFFB65BEE);
        ctx.fill(x, y + 2, x + 1, y + height, 0xFF6C3B91);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal("VENSTONE  /  ECONOMY"), x + 10, y + 8, 0xFFF5EAFE);
        String total = visible + " suivi(s)";
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(total), x + width - mc.textRenderer.getWidth(total) - 10, y + 8, 0xFFB9A9C8);
        int row = 0;
        long now = System.currentTimeMillis();
        for (TrackedItem item : TRACKER.items()) {
            if (!item.enabled) continue;
            if (row >= rows) break;
            int ry = y + 27 + row * 49;
            int sold = item.soldItems(now), remaining = item.remainingItems(now);
            int cap = Math.max(1, item.limitItems);
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(trim(item.name, 25)), x + 10, ry, 0xFFFFFFFF);
            String count = formatStacks(sold) + " / " + formatStacks(cap) + " stacks";
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(count), x + 10, ry + 13, 0xFFD8D0E0);
            long nextExpiry = item.nextExpiryMillis(now);
            String timer = nextExpiry > 0 ? "prochain + " + formatTime(nextExpiry) : "aucun reset actif";
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(timer), x + width - mc.textRenderer.getWidth(timer) - 10, ry + 13,
                    remaining == 0 ? 0xFFFF8888 : 0xFFBDA6D7);
            int barX = x + 10, barY = ry + 29, barW = width - 20;
            ctx.fill(barX, barY, barX + barW, barY + 4, 0xFF393341);
            int fill = Math.round(barW * Math.min(1f, sold / (float)cap));
            if (fill > 0) ctx.fill(barX, barY, barX + fill, barY + 4, remaining == 0 ? 0xFFE15D70 : 0xFFAA65D6);
            String money = String.format(Locale.FRANCE, "Max : %.2f €", item.maximumRevenue());
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(money), x + 10, ry + 36, 0xFFB8E8C4);
            String free = formatStacks(remaining) + " stacks dispo.";
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(free), x + width - mc.textRenderer.getWidth(free) - 10, ry + 36, 0xFFB8E8C4);
            row++;
        }
    }

    private static String trim(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
    private static String formatStacks(int items) { return String.format(Locale.ROOT, "%.2f", items / (double)TrackedItem.ITEMS_PER_STACK); }
    static String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600, minutes = (seconds % 3600) / 60, secs = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%dh %02dm", hours, minutes) : String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
    }

    public static final class EconomyScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget idField, nameField, priceField, limitField;
        private ButtonWidget toggleButton, hudButton;
        private String feedback = "";
        private int feedbackColor = 0xFFD8C4ED;
        protected EconomyScreen(Screen parent) { super(Text.literal("Venstone Economy Assistant")); this.parent = parent; }

        @Override protected void init() {
            int cx = width / 2, left = cx - 154;
            int shift = Math.max(0, Math.min(240, 324 - height));
            int y1 = 42 - shift, y2 = 75 - shift, y3 = 102 - shift, y4 = 129 - shift;
            addDrawableChild(ButtonWidget.builder(Text.literal("◀ Précédent"), b -> { TRACKER.selectPrevious(); refreshFields(); })
                    .dimensions(left, y1, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Suivant ▶"), b -> { TRACKER.selectNext(); refreshFields(); })
                    .dimensions(cx + 9, y1, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("+ 1 stack vendue"), b -> TRACKER.addSelected(TrackedItem.ITEMS_PER_STACK))
                    .dimensions(left, y2, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("+ 1 unité vendue"), b -> TRACKER.addSelected(1))
                    .dimensions(cx + 9, y2, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("− 1 stack (annuler)"), b -> TRACKER.adjustSelected(-TrackedItem.ITEMS_PER_STACK))
                    .dimensions(left, y3, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Vider l'historique"), b -> { TRACKER.resetSelected(); refreshFields(); })
                    .dimensions(cx + 9, y3, 145, 22).build());
            hudButton = ButtonWidget.builder(Text.literal("Masquer HUD"), b -> {
                hudVisible = !hudVisible; refreshFields();
            }).dimensions(left, y4, 145, 22).build();
            addDrawableChild(hudButton);
            toggleButton = ButtonWidget.builder(Text.literal("Désactiver cet objet"), b -> {
                TrackedItem item = TRACKER.selected();
                if (item != null) { item.enabled = !item.enabled; TRACKER.save(); refreshFields(); }
            }).dimensions(cx + 9, y4, 145, 22).build();
            addDrawableChild(toggleButton);

            int fy = 183 - shift;
            idField = field(left, fy, "ID objet (ex. minecraft:cactus)");
            nameField = field(cx + 9, fy, "Nom affiché");
            priceField = field(left, fy + 27, "Prix par unité (€)");
            limitField = field(cx + 9, fy + 27, "Limite (stacks)");
            addDrawableChild(ButtonWidget.builder(Text.literal("Ajouter cet objet"), b -> {
                try {
                    double price = Double.parseDouble(priceField.getText().replace(',', '.'));
                    int limit = Integer.parseInt(limitField.getText());
                    if (TRACKER.addItem(idField.getText(), nameField.getText(), price, limit)) {
                        refreshFields();
                        setFeedback("Objet ajouté.", 0xFFB8E8C4);
                    } else {
                        setFeedback("Ajout impossible : ID vide ou déjà présent.", 0xFFFF8888);
                    }
                } catch (RuntimeException e) {
                    setFeedback("Ajout impossible : prix ou limite invalide.", 0xFFFF8888);
                }
            }).dimensions(left, fy + 57, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Appliquer les réglages"), b -> applySelected())
                    .dimensions(cx + 9, fy + 57, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Supprimer l'objet"), b -> { TRACKER.removeSelected(); refreshFields(); })
                    .dimensions(left, fy + 85, 145, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Retour"), b -> close())
                    .dimensions(cx - 55, height - 30, 110, 20).build());
            refreshFields();
        }
        private TextFieldWidget field(int x, int y, String hint) {
            TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, 145, 20, Text.literal(hint));
            f.setPlaceholder(Text.literal(hint)); f.setMaxLength(100); addDrawableChild(f); return f;
        }
        private void setFeedback(String message, int color) { feedback = message; feedbackColor = color; }
        private void refreshFields() {
            TrackedItem item = TRACKER.selected();
            if (item != null && priceField != null) {
                idField.setText(item.id); nameField.setText(item.name);
                priceField.setText(String.format(Locale.ROOT, "%.4f", item.unitPrice));
                limitField.setText(Integer.toString(Math.max(1, item.limitItems / TrackedItem.ITEMS_PER_STACK)));
            }
            if (toggleButton != null) {
                boolean enabled = item == null || item.enabled;
                toggleButton.setMessage(Text.literal(enabled ? "Désactiver cet objet" : "Activer cet objet"));
            }
            if (hudButton != null) hudButton.setMessage(Text.literal(hudVisible ? "Masquer HUD" : "Afficher HUD"));
        }
        private void applySelected() {
            TrackedItem item = TRACKER.selected(); if (item == null) return;
            try {
                String newId = idField.getText().trim().toLowerCase();
                item.name = nameField.getText().isBlank() ? item.name : nameField.getText().trim();
                item.unitPrice = Math.max(0, Double.parseDouble(priceField.getText().replace(',', '.')));
                item.limitItems = Math.max(1, Integer.parseInt(limitField.getText())) * TrackedItem.ITEMS_PER_STACK;
                if (!newId.isBlank()) item.id = newId;
                item.normalize(System.currentTimeMillis());
                TRACKER.save();
                refreshFields();
                setFeedback("Réglages appliqués.", 0xFFB8E8C4);
            } catch (RuntimeException e) {
                setFeedback("Réglages invalides : prix ou limite non numérique.", 0xFFFF8888);
            }
        }
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            renderBackground(context, mouseX, mouseY, delta);
            int left = width / 2 - 165, right = width / 2 + 165;
            context.fill(left, 10, right, height - 10, 0xD91A1522);
            context.fill(left, 10, right, 12, 0xFFB65BEE);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 19, 0xFFF5EAFE);
            TrackedItem item = TRACKER.selected();
            long now = System.currentTimeMillis();
            String status = item == null ? "Aucun objet" : trim(item.name, 28) + "  •  " + item.soldItems(now) + "/" + item.limitItems + " unités actives  •  Max " + String.format(Locale.FRANCE, "%.2f €", item.maximumRevenue());
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, 30, 0xFFD8C4ED);
            super.render(context, mouseX, mouseY, delta);
            context.drawTextWithShadow(textRenderer, Text.literal("Chaque vente crée son propre délai de 60 min. Les ventes expirent une par une."), left + 11, 162, 0xFFBBAFC6);
            context.drawTextWithShadow(textRenderer, Text.literal("Lecture automatique des ventes confirmées et des informations visibles du shop."), left + 11, 174, 0xFFFFC78A);
            if (!feedback.isEmpty()) context.drawTextWithShadow(textRenderer, Text.literal(feedback), left + 11, height - 44, feedbackColor);
        }
        @Override public void close() { if (client != null) client.setScreen(parent); }
        @Override public boolean shouldPause() { return false; }
    }
}
