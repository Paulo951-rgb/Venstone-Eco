package fr.venstone.economy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.text.Text;

/** Compact scrollable list of tracked items shown on the left of the configuration screen. */
final class ItemListWidget extends EntryListWidget<ItemListWidget.ItemEntry> {
    private static final int ROW_HEIGHT = 20;

    private final SalesTracker tracker;
    private Runnable selectionListener = () -> { };

    ItemListWidget(MinecraftClient client, SalesTracker tracker, int width, int height, int y) {
        super(client, width, height, y, ROW_HEIGHT);
        this.tracker = tracker;
        refresh();
    }

    /** Called after the user picks a row, so the editor fields can follow the new selection. */
    void setSelectionListener(Runnable listener) { this.selectionListener = listener == null ? () -> { } : listener; }

    /** Rebuilds the rows and re-applies the tracker's selection. */
    void refresh() {
        clearEntries();
        for (int i = 0; i < tracker.items().size(); i++) addEntry(new ItemEntry(i));
        int index = tracker.selectedIndex();
        if (index >= 0 && index < children().size()) setSelected(children().get(index));
    }

    @Override public int getRowWidth() { return getWidth() - 8; }

    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, Text.translatable("venstone_economy.screen.title"));
    }

    /** A single tracked item: name on the left, quota on the right. */
    final class ItemEntry extends EntryListWidget.Entry<ItemEntry> {
        private final int index;

        ItemEntry(int index) { this.index = index; }

        @Override public void render(DrawContext ctx, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            if (index >= tracker.items().size()) return;
            TrackedItem item = tracker.items().get(index);
            long now = tracker.clock().now();
            int x = getContentX();
            int width = getContentWidth();
            int y = getContentY();
            boolean selected = tracker.selectedIndex() == index;
            if (selected) ctx.fill(x - 3, y, x + width + 3, y + getContentHeight() - 2, EconomyHud.Theme.ACCENT_DIM & 0x60FFFFFF);
            if (hovered && !selected) ctx.fill(x - 3, y, x + width + 3, y + getContentHeight() - 2, 0x22FFFFFF);

            String label = item.name == null || item.name.isBlank() ? item.id : item.name;
            ItemEconomics eco = item.economics(now);
            String quota = StackMath.stacks(eco.soldItems(), eco.stackSize()) + "/" + StackMath.stacks(eco.limitItems(), eco.stackSize());
            int quotaWidth = client.textRenderer.getWidth(quota);
            int nameColor = item.enabled ? EconomyHud.Theme.TEXT : EconomyHud.Theme.SUBTLE;
            String name = client.textRenderer.trimToWidth(label, Math.max(10, width - quotaWidth - 10));
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(name), x, y + 1, nameColor);
            int quotaColor = eco.remainingItems() == 0 ? EconomyHud.Theme.DANGER
                    : (item.enabled ? EconomyHud.Theme.MUTED : EconomyHud.Theme.SUBTLE);
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(quota), x + width - quotaWidth, y + 1, quotaColor);
            String detail = Text.translatable("venstone_economy.list.max", EconomyHud.formatMoney(eco.maxRevenue())).getString();
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(detail), x, y + 10, EconomyHud.Theme.SUBTLE);
            if (!item.enabled) {
                String off = Text.translatable("venstone_economy.status.disabled").getString();
                ctx.drawTextWithShadow(client.textRenderer, Text.literal(off),
                        x + width - client.textRenderer.getWidth(off), y + 10, EconomyHud.Theme.SUBTLE);
            }
        }

        @Override public boolean mouseClicked(Click click, boolean doubled) {
            tracker.select(index);
            selectionListener.run();
            return true;
        }
    }
}
