package fr.venstone.economy;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative parser for visible shop lore and explicit sale confirmations. */
public final class ShopTextParser {
    private static final Pattern PRICE = Pattern.compile("(?i)(?:prix\\s*(?:de\\s*)?(?:vente|sell)|vente|sell\\s*price|price)\\s*[:=]?\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?)?");
    private static final Pattern LIMIT = Pattern.compile("(?i)(?:limite|limit|max(?:imum)?|restant|remaining)\\s*[:=]?\\s*([0-9]+)(\\s*(?:stacks?|items?|unités?|units?))?");
    private static final Pattern SOLD = Pattern.compile("(?i)(?:vous\\s+avez\\s+vendu|tu\\s+as\\s+vendu|vendu|sold)\\s*(?:[:x×-]?\\s*)?([0-9][0-9 .,_]*)\\s*(?:x|×)?\\s*(.*?)\\s*(?:pour|for|\\bà\\b)\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?)?\\s*$");
    private ShopTextParser() {}

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9:_ ]", " ").replaceAll("\\s+", " ").trim();
    }
    public static Double readUnitPrice(String text) {
        Matcher m = PRICE.matcher(text == null ? "" : text);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1).replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }
    public static Integer readLimitItems(String text) {
        Matcher m = LIMIT.matcher(text == null ? "" : text);
        if (!m.find()) return null;
        try {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2) == null ? "" : normalize(m.group(2));
            return unit.contains("stack") ? Math.multiplyExact(n, 64) : n;
        } catch (RuntimeException e) { return null; }
    }
    public record Sale(String itemName, int amount, double totalPrice) {}
    public static Sale readSale(String text) {
        Matcher m = SOLD.matcher(text == null ? "" : text.trim());
        if (!m.find()) return null;
        try {
            int amount = Integer.parseInt(m.group(1).replaceAll("[ .,_]", ""));
            String name = m.group(2).trim().replaceAll("[.!]+$", "");
            double total = Double.parseDouble(m.group(3).replace(',', '.'));
            if (amount <= 0 || amount > 1_000_000 || name.isBlank()) return null;
            return new Sale(name, amount, total);
        } catch (RuntimeException e) { return null; }
    }
}
