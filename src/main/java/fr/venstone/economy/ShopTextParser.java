package fr.venstone.economy;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative parser for visible shop lore and explicit sale confirmations. */
public final class ShopTextParser {
    /** Highest accepted unit price; values above this are treated as unreadable noise. */
    public static final double MAX_UNIT_PRICE = 1_000_000.0;
    /** Highest accepted sale quantity in a single confirmation. */
    public static final int MAX_SALE_AMOUNT = 1_000_000;

    // Requires an explicit price label so unrelated text containing "vente" is not treated as a price.
    // A bare "vente" label is accepted only with an explicit ":" or "=" separator.
    private static final Pattern PRICE = Pattern.compile(
            "(?i)\\b(?:prix(?:\\s*(?:de\\s*)?(?:vente|sell))?|vente\\s*[:=]|sell\\s*price|sale\\s*price|price)\\s*[:=]?\\s*"
                    + "([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?)?");
    private static final Pattern LIMIT = Pattern.compile(
            "(?i)\\b(?:limites?|limits?|max(?:imum)?|restants?|remaining)\\s*[:=]?\\s*([0-9]+)"
                    + "(\\s*(?:stacks?|items?|unités?|units?))?");
    // Word boundary before the verb rejects "revendu"/"resold"; trailing punctuation is tolerated.
    private static final Pattern SOLD = Pattern.compile(
            "(?i)\\b(?:vous\\s+avez\\s+vendu|tu\\s+as\\s+vendu|vendu|sold)\\s*(?:[:x×-]?\\s*)?"
                    + "([0-9][0-9 .,_]*)\\s*(?:x|×)?\\s*(.*?)\\s*(?:pour|for|\\bà\\b)\\s*"
                    + "([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?)?\\s*[.!?\\s]*$");
    // A sale confirmation is ignored when it is explicitly negated.
    private static final Pattern NEGATED_SALE = Pattern.compile(
            "(?i)\\b(?:pas|jamais|aucun\\w*|no)\\b[^.!?]{0,24}\\b(?:vendu|sold)\\b");
    private ShopTextParser() {}

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9:_ ]", " ").replaceAll("\\s+", " ").trim();
    }
    public static Double readUnitPrice(String text) {
        Matcher m = PRICE.matcher(text == null ? "" : text);
        if (!m.find()) return null;
        try {
            double value = Double.parseDouble(m.group(1).replace(',', '.'));
            return value >= 0 && value <= MAX_UNIT_PRICE ? value : null;
        } catch (NumberFormatException e) { return null; }
    }
    public static Integer readLimitItems(String text) {
        Matcher m = LIMIT.matcher(text == null ? "" : text);
        if (!m.find()) return null;
        try {
            int n = Integer.parseInt(m.group(1));
            if (n <= 0) return null;
            String unit = m.group(2) == null ? "" : normalize(m.group(2));
            return unit.contains("stack") ? Math.multiplyExact(n, 64) : n;
        } catch (RuntimeException e) { return null; }
    }
    public record Sale(String itemName, int amount, double totalPrice) {}

    /** Stable key for a sale, so differently worded confirmations of the same sale collapse. */
    public static String saleSignature(Sale sale) {
        return normalize(sale.itemName()) + '|' + sale.amount() + '|' + Math.round(sale.totalPrice() * 100);
    }
    public static Sale readSale(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty() || NEGATED_SALE.matcher(raw).find()) return null;
        Matcher m = SOLD.matcher(raw);
        if (!m.find()) return null;
        try {
            int amount = Integer.parseInt(m.group(1).replaceAll("[ .,_]", ""));
            String name = m.group(2).trim().replaceAll("[.!?]+$", "");
            double total = Double.parseDouble(m.group(3).replace(',', '.'));
            if (amount <= 0 || amount > MAX_SALE_AMOUNT || name.isBlank() || !Double.isFinite(total) || total < 0) return null;
            return new Sale(name, amount, total);
        } catch (RuntimeException e) { return null; }
    }
}
