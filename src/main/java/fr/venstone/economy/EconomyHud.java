package fr.venstone.economy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Compact economy HUD.
 *
 * <p>Everything is laid out in fixed "design units" and the whole panel is then scaled by the
 * user's interface scale, so text, bars and spacing all grow together and nothing is distorted.
 * Only items that are enabled and have something to show are drawn, capped to the space available.
 */
final class EconomyHud {
    private static final int DESIGN_WIDTH = 196;
    private static final int HEADER_HEIGHT = 17;
    private static final int ROW_HEIGHT = 27;
    private static final int PADDING = 7;
    private static final int SCREEN_MARGIN = 8;

    private EconomyHud() { }

    static void render(DrawContext ctx, MinecraftClient mc, SalesTracker tracker, long now) {
        if (mc == null || mc.player == null || !tracker.hudVisible()) return;
        float scale = tracker.uiScale();
        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        int visible = tracker.enabledCount();
        if (visible == 0) return;

        // Space is measured in design units so the cap adapts to the scale factor.
        int availableHeight = Math.round((screenH - SCREEN_MARGIN * 2) / scale);
        int maxRows = Math.max(0, (availableHeight - HEADER_HEIGHT - PADDING) / ROW_HEIGHT);
        int rows = Math.min(visible, maxRows);
        if (rows <= 0) return;

        int panelHeight = HEADER_HEIGHT + rows * ROW_HEIGHT + PADDING;
        int panelWidth = DESIGN_WIDTH;

        SalesTracker.HudCorner corner = tracker.hudCorner();
        boolean right = corner == SalesTracker.HudCorner.TOP_RIGHT || corner == SalesTracker.HudCorner.BOTTOM_RIGHT;
        boolean bottom = corner == SalesTracker.HudCorner.BOTTOM_LEFT || corner == SalesTracker.HudCorner.BOTTOM_RIGHT;
        int originX = right
                ? Math.round(screenW - panelWidth * scale) - SCREEN_MARGIN
                : SCREEN_MARGIN;
        int originY = bottom
                ? Math.round(screenH - panelHeight * scale) - SCREEN_MARGIN
                : SCREEN_MARGIN;

        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(originX, originY);
        matrices.scale(scale, scale);
        try {
            drawPanel(ctx, mc.textRenderer, tracker, now, panelWidth, panelHeight, rows);
        } finally {
            matrices.popMatrix();
        }
    }

    private static void drawPanel(DrawContext ctx, TextRenderer font, SalesTracker tracker, long now,
                                  int width, int height, int rows) {
        // Body, then a 1px border and an accent rail so the panel reads as one block.
        ctx.fill(0, 0, width, height, Theme.PANEL);
        ctx.fill(0, 0, width, 1, Theme.BORDER);
        ctx.fill(0, height - 1, width, height, Theme.BORDER);
        ctx.fill(0, 0, 1, height, Theme.BORDER);
        ctx.fill(width - 1, 0, width, height, Theme.BORDER);
        ctx.fill(0, 1, 2, height - 1, Theme.ACCENT);

        String title = Text.translatable("venstone_economy.hud.title").getString();
        ctx.drawTextWithShadow(font, Text.literal(title), PADDING + 3, 5, Theme.TITLE);
        String tracked = Text.translatable("venstone_economy.hud.tracked", tracker.enabledCount()).getString();
        ctx.drawTextWithShadow(font, Text.literal(tracked), width - PADDING - font.getWidth(tracked), 5, Theme.MUTED);
        ctx.fill(PADDING, HEADER_HEIGHT - 1, width - PADDING, HEADER_HEIGHT, Theme.DIVIDER);

        int row = 0;
        for (TrackedItem item : tracker.items()) {
            if (!item.enabled) continue;
            if (row >= rows) break;
            drawRow(ctx, font, item, now, row * ROW_HEIGHT + HEADER_HEIGHT, width);
            row++;
        }
    }

    private static void drawRow(DrawContext ctx, TextRenderer font, TrackedItem item, long now,
                                int top, int width) {
        ItemEconomics eco = item.economics(now);
        int left = PADDING + 3;
        int right = width - PADDING;
        int innerWidth = right - left;

        // Line 1: name on the left, sold/limit stacks on the right.
        String label = item.name == null || item.name.isBlank() ? item.id : item.name;
        String counts = StackMath.stacks(eco.soldItems(), eco.stackSize())
                + " / " + StackMath.stacks(eco.limitItems(), eco.stackSize());
        int countsWidth = font.getWidth(counts);
        String name = font.trimToWidth(label, Math.max(10, innerWidth - countsWidth - 6));
        ctx.drawTextWithShadow(font, Text.literal(name), left, top + 1, Theme.TEXT);
        ctx.drawTextWithShadow(font, Text.literal(counts), right - countsWidth, top + 1,
                eco.remainingItems() == 0 ? Theme.DANGER : Theme.MUTED);

        // Line 2: reset timer on the left, maximum revenue on the right.
        String timer = timerLabel(item, now, eco);
        ctx.drawTextWithShadow(font, Text.literal(timer), left, top + 12, Theme.SUBTLE);
        String revenue = Text.translatable("venstone_economy.hud.max",
                formatMoney(eco.maxRevenue())).getString();
        ctx.drawTextWithShadow(font, Text.literal(revenue), right - font.getWidth(revenue), top + 12,
                eco.isSellable() ? Theme.MONEY : Theme.SUBTLE);

        // Line 3: a thin progress bar showing how much of the quota is used.
        int barTop = top + 22;
        ctx.fill(left, barTop, right, barTop + 2, Theme.TRACK);
        if (eco.limitItems() > 0) {
            int filled = Math.round(innerWidth * Math.min(1f, eco.soldItems() / (float) eco.limitItems()));
            if (filled > 0) ctx.fill(left, barTop, left + filled, barTop + 2,
                    eco.remainingItems() == 0 ? Theme.DANGER : Theme.ACCENT);
        }
    }

    private static String timerLabel(TrackedItem item, long now, ItemEconomics eco) {
        if (eco.limitItems() == 0) return Text.translatable("venstone_economy.hud.no_price").getString();
        long nextExpiry = item.nextExpiryMillis(now);
        if (nextExpiry > 0) {
            return Text.translatable("venstone_economy.hud.next_reset", Durations.format(nextExpiry)).getString();
        }
        return Text.translatable("venstone_economy.hud.free", StackMath.stacks(eco.remainingItems(), eco.stackSize())).getString();
    }

    /** Money formatting with two decimals and a locale-independent separator. */
    static String formatMoney(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Shared palette so the HUD and the configuration screen stay visually consistent. */
    static final class Theme {
        static final int PANEL = 0xE00E0C15;
        static final int BORDER = 0xFF2A2438;
        static final int DIVIDER = 0xFF221E30;
        static final int ACCENT = 0xFFA96BE8;
        static final int ACCENT_DIM = 0xFF6B4A96;
        static final int TITLE = 0xFFF2EAFB;
        static final int TEXT = 0xFFE8E2F0;
        static final int MUTED = 0xFFA79DB8;
        static final int SUBTLE = 0xFF7E768F;
        static final int TRACK = 0xFF262133;
        static final int MONEY = 0xFF9FE0B0;
        static final int DANGER = 0xFFF07E8C;

        private Theme() { }
    }
}
