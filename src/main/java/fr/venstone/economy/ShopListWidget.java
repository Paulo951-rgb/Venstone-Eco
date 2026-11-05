package fr.venstone.economy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.text.Text;

/** Scrollable list of the prices discovered in the shop. */
final class ShopListWidget extends EntryListWidget<ShopListWidget.ShopEntry> {
    private static final int ROW_HEIGHT = 20;

    private final ShopCatalog catalog;
    private final long now;

    ShopListWidget(MinecraftClient client, ShopCatalog catalog, int width, int height, int y) {
        super(client, width, height, y, ROW_HEIGHT);
        this.catalog = catalog;
        this.now = System.currentTimeMillis();
        refresh();
    }

    /** Rebuilds the rows from the catalogue, richest sell price first. */
    void refresh() {
        clearEntries();
        for (ShopCatalog.PriceEntry entry : catalog.sellableEntries()) addEntry(new ShopEntry(entry));
        if (!children().isEmpty()) setSelected(children().get(0));
    }

    @Override public int getRowWidth() { return getWidth() - 8; }

    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, Text.translatable("venstone_economy.shop.title"));
    }

    /** One catalogue row: item name, sell price and freshness. */
    final class ShopEntry extends EntryListWidget.Entry<ShopEntry> {
        private final ShopCatalog.PriceEntry entry;

        ShopEntry(ShopCatalog.PriceEntry entry) { this.entry = entry; }

        @Override public void render(DrawContext ctx, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            int x = getContentX();
            int width = getContentWidth();
            int y = getContentY();
            boolean stale = catalog.isStale(entry, now);
            String label = entry.name == null || entry.name.isBlank() ? entry.id : entry.name;
            String price = entry.hasSellPrice() ? EconomyHud.formatMoney(entry.sellPrice) : "-";
            int priceWidth = client.textRenderer.getWidth(price);
            String name = client.textRenderer.trimToWidth(label, Math.max(10, width - priceWidth - 12));
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(name), x, y + 1,
                    stale ? EconomyHud.Theme.SUBTLE : EconomyHud.Theme.TEXT);
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(price), x + width - priceWidth, y + 1,
                    stale ? EconomyHud.Theme.DANGER : EconomyHud.Theme.MONEY);
            String detail = Text.translatable("venstone_economy.shop.limit",
                    StackMath.wholeStacks(entry.limitItems(), entry.stackSize)).getString();
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(detail), x, y + 10, EconomyHud.Theme.SUBTLE);
        }

        @Override public boolean mouseClicked(Click click, boolean doubled) { return true; }
    }
}
