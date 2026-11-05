package fr.venstone.economy;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Configuration screen: a tabbed, compact layout that keeps the editor, the discovered shop prices
 * and the preferences apart instead of stacking every control on one page.
 */
public final class EconomyScreen extends Screen {
    private enum Tab { TRACKED, PRICES, SETTINGS }

    private static final int HEADER = 30;
    private static final int FOOTER = 30;
    private static final int GAP = 6;

    private final Screen parent;
    private final ShopCatalog catalog;
    private final ShopScanner scanner;
    private Tab tab = Tab.TRACKED;

    private ItemListWidget list;
    private ShopListWidget shopList;
    private TextFieldWidget idField, nameField, priceField;
    private ButtonWidget toggleButton, hudButton, cornerButton, resetButton, autoTrackButton;
    private String feedback = "";
    private int feedbackColor = EconomyHud.Theme.MUTED;

    public EconomyScreen(Screen parent, ShopCatalog catalog, ShopScanner scanner) {
        super(Text.translatable("venstone_economy.screen.title"));
        this.parent = parent;
        this.catalog = catalog;
        this.scanner = scanner;
    }

    @Override protected void init() {
        buildTabs();
        switch (tab) {
            case TRACKED -> buildTrackedTab();
            case PRICES -> buildPricesTab();
            case SETTINGS -> buildSettingsTab();
        }
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                .dimensions(width / 2 - 50, height - 26, 100, 20).build());
    }

    private void buildTabs() {
        Tab[] tabs = Tab.values();
        String[] keys = { "venstone_economy.tab.tracked", "venstone_economy.tab.prices", "venstone_economy.tab.settings" };
        int tabWidth = Math.min(120, (width - 24 - GAP * (tabs.length - 1)) / tabs.length);
        int totalWidth = tabWidth * tabs.length + GAP * (tabs.length - 1);
        int startX = (width - totalWidth) / 2;
        for (int i = 0; i < tabs.length; i++) {
            Tab candidate = tabs[i];
            ButtonWidget button = ButtonWidget.builder(Text.translatable(keys[i]), b -> {
                tab = candidate;
                clearAndInit();
            }).dimensions(startX + i * (tabWidth + GAP), 8, tabWidth, 18).build();
            button.active = candidate != tab;
            addDrawableChild(button);
        }
    }

    private int contentTop() { return HEADER; }

    private int contentHeight() { return height - HEADER - FOOTER; }

    // --- Tracked items ---------------------------------------------------------------------------

    private void buildTrackedTab() {
        int listWidth = Math.min(200, Math.max(140, width / 3));
        list = new ItemListWidget(client, VenstoneEconomyClient.TRACKER, listWidth, contentHeight(), contentTop());
        list.setX(12);
        addDrawableChild(list);
        list.setSelectionListener(this::syncFields);

        int fx = 12 + listWidth + 10;
        int fieldWidth = width - 12 - fx;
        int half = Math.max(60, (fieldWidth - GAP) / 2);
        int y = contentTop() + 2;

        idField = field(fx, y, fieldWidth, "venstone_economy.field.id");
        nameField = field(fx, y + 22, fieldWidth, "venstone_economy.field.name");
        priceField = field(fx, y + 44, half, "venstone_economy.field.price");
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.suggest"), b -> suggest())
                .dimensions(fx + half + GAP, y + 44, half, 20).build());

        int row = y + 68;
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.apply"), b -> applySelected())
                .dimensions(fx, row, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.add"), b -> addItem())
                .dimensions(fx + half + GAP, row, half, 20).build());
        row += 24;
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.prev"), b -> { VenstoneEconomyClient.TRACKER.selectPrevious(); reloadList(); })
                .dimensions(fx, row, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.next"), b -> { VenstoneEconomyClient.TRACKER.selectNext(); reloadList(); })
                .dimensions(fx + half + GAP, row, half, 20).build());
        row += 24;
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.sold_stack"), b -> { VenstoneEconomyClient.TRACKER.addSelected(StackMath.DEFAULT_STACK_SIZE); reloadList(); })
                .dimensions(fx, row, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.sold_unit"), b -> { VenstoneEconomyClient.TRACKER.addSelected(1); reloadList(); })
                .dimensions(fx + half + GAP, row, half, 20).build());
        row += 24;
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.undo_stack"), b -> { VenstoneEconomyClient.TRACKER.adjustSelected(-StackMath.DEFAULT_STACK_SIZE); reloadList(); })
                .dimensions(fx, row, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.reset"), b -> { VenstoneEconomyClient.TRACKER.resetSelected(); reloadList(); })
                .dimensions(fx + half + GAP, row, half, 20).build());
        row += 24;
        toggleButton = ButtonWidget.builder(Text.translatable("venstone_economy.button.disable"), b -> {
            TrackedItem item = VenstoneEconomyClient.TRACKER.selected();
            if (item != null) { item.enabled = !item.enabled; VenstoneEconomyClient.TRACKER.save(); reloadList(); }
        }).dimensions(fx, row, half, 20).build();
        addDrawableChild(toggleButton);
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.button.remove"), b -> { VenstoneEconomyClient.TRACKER.removeSelected(); reloadList(); })
                .dimensions(fx + half + GAP, row, half, 20).build());
        reloadList();
    }

    private TextFieldWidget field(int x, int y, int w, String key) {
        TextFieldWidget widget = new TextFieldWidget(textRenderer, x, y, w, 20, Text.translatable(key));
        widget.setPlaceholder(Text.translatable(key));
        widget.setMaxLength(100);
        addDrawableChild(widget);
        return widget;
    }

    // --- Shop prices -----------------------------------------------------------------------------

    private void buildPricesTab() {
        shopList = new ShopListWidget(client, catalog, Math.min(320, width - 24), contentHeight() - 26, contentTop() + 2);
        shopList.setX((width - Math.min(320, width - 24)) / 2);
        addDrawableChild(shopList);

        int row = height - FOOTER - 22;
        int buttonWidth = Math.min(150, (width - 24 - GAP * 2) / 3);
        int startX = (width - (buttonWidth * 3 + GAP * 2)) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.shop.import"), b -> importCatalog())
                .dimensions(startX, row, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.shop.save"), b -> {
            catalog.save();
            setFeedback(Text.translatable("venstone_economy.feedback.saved").getString(), EconomyHud.Theme.MONEY);
        }).dimensions(startX + buttonWidth + GAP, row, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("venstone_economy.shop.clear"), b -> {
            catalog.clear();
            catalog.save();
            shopList.refresh();
            setFeedback(Text.translatable("venstone_economy.feedback.cleared").getString(), EconomyHud.Theme.MUTED);
        }).dimensions(startX + (buttonWidth + GAP) * 2, row, buttonWidth, 20).build());
    }

    /** Turns every sellable catalogue entry into a tracked item, skipping the ones already present. */
    private void importCatalog() {
        int added = 0;
        for (ShopCatalog.PriceEntry entry : catalog.sellableEntries()) {
            if (VenstoneEconomyClient.TRACKER.findByNameOrId(entry.id) == null) {
                if (VenstoneEconomyClient.TRACKER.addItem(entry.id, entry.name, entry.sellPrice, entry.stackSize)) added++;
            }
        }
        setFeedback(Text.translatable("venstone_economy.feedback.imported", added).getString(),
                added > 0 ? EconomyHud.Theme.MONEY : EconomyHud.Theme.MUTED);
    }

    // --- Settings --------------------------------------------------------------------------------

    private void buildSettingsTab() {
        SalesTracker tracker = VenstoneEconomyClient.TRACKER;
        int center = width / 2;
        int left = center - 130;
        int wide = 260;
        int half = 127;
        int y = contentTop() + 14;

        hudButton = ButtonWidget.builder(hudLabel(), b -> {
            tracker.toggleHud();
            tracker.save();
            syncSettings();
        }).dimensions(left, y, wide, 20).build();
        addDrawableChild(hudButton);
        y += 24;

        cornerButton = ButtonWidget.builder(cornerLabel(), b -> {
            tracker.setHudCorner(nextCorner(tracker.hudCorner()));
            tracker.save();
            syncSettings();
        }).dimensions(left, y, wide, 20).build();
        addDrawableChild(cornerButton);
        y += 24;

        resetButton = ButtonWidget.builder(resetLabel(), b -> {
            tracker.setResetMode(nextResetMode(tracker.resetMode()));
            syncSettings();
        }).dimensions(left, y, wide, 20).build();
        addDrawableChild(resetButton);
        y += 24;

        autoTrackButton = ButtonWidget.builder(autoTrackLabel(), b -> {
            tracker.toggleAutoTrack();
            syncSettings();
        }).dimensions(left, y, wide, 20).build();
        addDrawableChild(autoTrackButton);
        y += 30;

        // Interface scale: shrink, readout, grow, and a one-click return to 100%.
        int small = 40;
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
            tracker.setUiScaleIndex(UiScale.previous(tracker.uiScaleIndex()));
            syncSettings();
        }).dimensions(left, y, small, 20).build());
        addDrawableChild(ButtonWidget.builder(scaleLabel(), b -> {
            tracker.resetUiScale();
            syncSettings();
        }).dimensions(left + small + GAP, y, wide - small * 2 - GAP * 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
            tracker.setUiScaleIndex(UiScale.next(tracker.uiScaleIndex()));
            syncSettings();
        }).dimensions(left + wide - small, y, small, 20).build());
    }

    private Text scaleLabel() {
        return Text.translatable("venstone_economy.button.ui_scale",
                UiScale.label(VenstoneEconomyClient.TRACKER.uiScaleIndex()));
    }

    private Text autoTrackLabel() {
        return Text.translatable(VenstoneEconomyClient.TRACKER.autoTrack()
                ? "venstone_economy.button.auto_track_on" : "venstone_economy.button.auto_track_off");
    }

    private static SalesTracker.HudCorner nextCorner(SalesTracker.HudCorner corner) {
        SalesTracker.HudCorner[] values = SalesTracker.HudCorner.values();
        return values[(corner.ordinal() + 1) % values.length];
    }

    private static SalesTracker.ResetMode nextResetMode(SalesTracker.ResetMode mode) {
        SalesTracker.ResetMode[] values = SalesTracker.ResetMode.values();
        return values[(mode.ordinal() + 1) % values.length];
    }

    private Text hudLabel() {
        return Text.translatable(VenstoneEconomyClient.TRACKER.hudVisible()
                ? "venstone_economy.button.hide_hud" : "venstone_economy.button.show_hud");
    }

    private Text cornerLabel() {
        String corner = Text.translatable("venstone_economy.corner."
                + VenstoneEconomyClient.TRACKER.hudCorner().name().toLowerCase(Locale.ROOT)).getString();
        return Text.translatable("venstone_economy.button.hud_corner", corner);
    }

    private Text resetLabel() {
        String mode = Text.translatable("venstone_economy.reset."
                + VenstoneEconomyClient.TRACKER.resetMode().name().toLowerCase(Locale.ROOT)).getString();
        return Text.translatable("venstone_economy.button.reset_mode", mode);
    }

    private void syncSettings() {
        if (hudButton != null) hudButton.setMessage(hudLabel());
        if (cornerButton != null) cornerButton.setMessage(cornerLabel());
        if (resetButton != null) resetButton.setMessage(resetLabel());
        if (autoTrackButton != null) autoTrackButton.setMessage(autoTrackLabel());
    }

    // --- Editor helpers --------------------------------------------------------------------------

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
        if (idField == null) return;
        TrackedItem item = VenstoneEconomyClient.TRACKER.selected();
        if (item != null) {
            idField.setText(item.id);
            nameField.setText(item.name == null ? "" : item.name);
            priceField.setText(String.format(Locale.ROOT, "%.4f", item.effectiveSellPrice()));
        } else {
            idField.setText(""); nameField.setText(""); priceField.setText("");
        }
        if (toggleButton != null) {
            boolean enabled = item == null || item.enabled;
            toggleButton.setMessage(Text.translatable(enabled ? "venstone_economy.button.disable" : "venstone_economy.button.enable"));
        }
    }

    /** Fills the id field with the best registry match for the current text or display name. */
    private void suggest() {
        List<String> byName = ItemResolver.suggestions(nameField.getText(), 1);
        List<String> byId = ItemResolver.suggestions(idField.getText(), 1);
        String pick = !byName.isEmpty() ? byName.get(0) : (!byId.isEmpty() ? byId.get(0) : null);
        if (pick == null) { setFeedback(Text.translatable("venstone_economy.feedback.no_match").getString(), EconomyHud.Theme.DANGER); return; }
        idField.setText(pick);
        setFeedback(Text.translatable("venstone_economy.feedback.suggested", pick).getString(), EconomyHud.Theme.MONEY);
    }

    private void addItem() {
        try {
            double price = Double.parseDouble(priceField.getText().replace(',', '.'));
            if (VenstoneEconomyClient.TRACKER.addItem(idField.getText(), nameField.getText(), price)) {
                reloadList();
                setFeedback(Text.translatable("venstone_economy.feedback.added").getString(), EconomyHud.Theme.MONEY);
            } else {
                setFeedback(Text.translatable("venstone_economy.feedback.add_failed").getString(), EconomyHud.Theme.DANGER);
            }
        } catch (RuntimeException e) {
            setFeedback(Text.translatable("venstone_economy.feedback.invalid").getString(), EconomyHud.Theme.DANGER);
        }
    }

    private void applySelected() {
        TrackedItem item = VenstoneEconomyClient.TRACKER.selected();
        if (item == null) return;
        try {
            String newId = idField.getText();
            String resolved = ItemResolver.resolveId(newId);
            if (resolved != null) item.id = resolved;
            else if (ItemIds.isValid(newId)) item.id = ItemIds.canonical(newId);
            else if (!newId.isBlank()) { setFeedback(Text.translatable("venstone_economy.feedback.bad_id").getString(), EconomyHud.Theme.DANGER); return; }
            item.name = nameField.getText().isBlank() ? item.name : nameField.getText().trim();
            double price = Math.max(0, Double.parseDouble(priceField.getText().replace(',', '.')));
            item.configuredPrice = Math.min(price, ShopTextParser.MAX_UNIT_PRICE);
            // The sell limit is universal: always 36 stacks of this item's real stack size.
            item.stackSize = StackMath.clampStackSize(ItemResolver.stackSize(item.id));
            item.limitItems = StackMath.limitItems(item.stackSize());
            item.normalize(VenstoneEconomyClient.TRACKER.clock().now());
            VenstoneEconomyClient.TRACKER.save();
            reloadList();
            setFeedback(Text.translatable("venstone_economy.feedback.applied").getString(), EconomyHud.Theme.MONEY);
        } catch (RuntimeException e) {
            setFeedback(Text.translatable("venstone_economy.feedback.invalid").getString(), EconomyHud.Theme.DANGER);
        }
    }

    private void setFeedback(String message, int color) { feedback = message; feedbackColor = color; }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        SalesTracker tracker = VenstoneEconomyClient.TRACKER;
        switch (tab) {
            case TRACKED -> renderTrackedTab(context, tracker);
            case PRICES -> renderPricesTab(context);
            case SETTINGS -> renderSettingsTab(context, tracker);
        }
        if (!feedback.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(feedback), width / 2, height - 40, feedbackColor);
        }
        String error = tracker.lastError();
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("venstone_economy.error.save", error),
                    width / 2, height - 52, EconomyHud.Theme.DANGER);
        }
    }

    private void renderTrackedTab(DrawContext context, SalesTracker tracker) {
        TrackedItem item = tracker.selected();
        long now = tracker.clock().now();
        int fx = 12 + (list == null ? 0 : list.getWidth()) + 10;
        if (item == null) {
            context.drawTextWithShadow(textRenderer, Text.translatable("venstone_economy.status.none"), fx, contentTop() + 90, EconomyHud.Theme.MUTED);
            return;
        }
        ItemEconomics eco = item.economics(now);
        int y = contentTop() + 90;
        String limitInfo = Text.translatable("venstone_economy.status.limit",
                StackMath.LIMIT_STACKS, eco.stackSize(), eco.limitItems()).getString();
        context.drawTextWithShadow(textRenderer, Text.literal(limitInfo), fx, y, EconomyHud.Theme.MUTED);
        y += 12;
        String status = Text.translatable("venstone_economy.status.item",
                StackMath.stacks(eco.soldItems(), eco.stackSize()),
                StackMath.stacks(eco.limitItems(), eco.stackSize()),
                EconomyHud.formatMoney(eco.maxRevenue())).getString();
        context.drawTextWithShadow(textRenderer, Text.literal(status), fx, y, EconomyHud.Theme.MUTED);
        y += 12;
        String perStack = Text.translatable("venstone_economy.status.per_stack",
                EconomyHud.formatMoney(eco.pricePerStack())).getString();
        context.drawTextWithShadow(textRenderer, Text.literal(perStack), fx, y, EconomyHud.Theme.MUTED);
        y += 12;
        if (eco.buyPrice() > 0) {
            String margin = Text.translatable("venstone_economy.status.margin",
                    EconomyHud.formatMoney(eco.unitMargin()), EconomyHud.formatMoney(eco.maxMargin())).getString();
            context.drawTextWithShadow(textRenderer, Text.literal(margin), fx, y, EconomyHud.Theme.MONEY);
        } else {
            context.drawTextWithShadow(textRenderer, Text.translatable("venstone_economy.status.no_buy"),
                    fx, y, EconomyHud.Theme.SUBTLE);
        }
    }

    private void renderPricesTab(DrawContext context) {
        long last = scanner == null ? 0 : scanner.lastScanAt();
        String status = last <= 0
                ? Text.translatable("venstone_economy.shop.never").getString()
                : Text.translatable("venstone_economy.shop.last_scan", Durations.format(System.currentTimeMillis() - last)).getString();
        String info = Text.translatable("venstone_economy.shop.summary", catalog.size(), status).getString();
        context.drawTextWithShadow(textRenderer, Text.literal(info), 14, contentTop() + 4, EconomyHud.Theme.MUTED);
    }

    private void renderSettingsTab(DrawContext context, SalesTracker tracker) {
        int y = contentTop() + 14 + 24 * 3 + 30 + 24;
        String scale = Text.translatable("venstone_economy.status.scale", UiScale.label(tracker.uiScaleIndex())).getString();
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(scale), width / 2, y, EconomyHud.Theme.MUTED);
        y += 14;
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("venstone_economy.hint.window"),
                width / 2, y, EconomyHud.Theme.SUBTLE);
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    @Override public boolean shouldPause() { return false; }
}
