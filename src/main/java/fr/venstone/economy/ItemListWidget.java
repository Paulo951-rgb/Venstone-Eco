package fr.venstone.economy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.text.Text;

import java.util.Locale;

/** Scrollable list of tracked items shown on the left of the configuration screen. */
final class ItemListWidget extends EntryListWidget<ItemListWidget.ItemEntry> {
    private static final int ROW_HEIGHT = 24;

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

    @Override public int getRowWidth() { return getWidth() - 12; }

    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, Text.translatable("venstone_economy.screen.title"));
    }

    /** A single tracked item, drawn as name plus a rolling-window summary. */
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
            if (tracker.selectedIndex() == index) ctx.fill(x - 2, y - 1, x + width + 2, y + getContentHeight() - 1, 0x66483468);
            int nameColor = item.enabled ? 0xFFFFFFFF : 0xFF8C8398;
            String label = item.name == null || item.name.isBlank() ? item.id : item.name;
            String name = client.textRenderer.trimToWidth(label, width - 6);
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(name), x, y + 2, nameColor);
            String stacks = String.format(Locale.ROOT, "%.1f / %.1f", item.soldItems(now) / (double) TrackedItem.ITEMS_PER_STACK,
                    item.limitItems / (double) TrackedItem.ITEMS_PER_STACK);
            int countColor = item.remainingItems(now) == 0 ? 0xFFFF8888 : 0xFFBFA9D9;
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(stacks + " stacks"), x, y + 12, countColor);
            if (!item.enabled) {
                String off = Text.translatable("venstone_economy.status.disabled").getString();
                ctx.drawTextWithShadow(client.textRenderer, Text.literal(off), x + width - client.textRenderer.getWidth(off), y + 12, 0xFF8C8398);
            }
        }

        @Override public boolean mouseClicked(Click click, boolean doubled) {
            tracker.select(index);
            selectionListener.run();
            return true;
        }
    }
}
