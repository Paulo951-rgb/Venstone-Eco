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

    // EconomyShopGUI's sell confirmation is "Vous avez vendu avec succès x656 Canne à sucre pour
    // 78.72€." — the quantity carries a leading "x", the verb may be followed by "avec succès"
    // (FR) / "successfully" (EN), and older/simple wordings put the "x" after the number instead.
    // The pattern is anchored to the end so trailing prose ("... et merci") is rejected.
    private static final Pattern SOLD = Pattern.compile(
            "(?i)\\b(?:vous\\s+avez\\s+vendu|tu\\s+as\\s+vendu|you\\s+have\\s+sold|vendu|sold)\\b"
                    + "(?:\\s+avec\\s+succ[eè]s|\\s+successfully)?"
                    + "\\s*[:=]?\\s*(?:x|×)?\\s*([0-9][0-9 .,_]*)\\s*(?:x|×)?\\s*(.*?)\\s*"
                    + "(?:pour|for|\\bà\\b)\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?)?\\s*[.!?]*\\s*$");
    // A sale confirmation is ignored when it is explicitly negated.
    private static final Pattern NEGATED_SALE = Pattern.compile(
            "(?i)\\b(?:pas|jamais|aucun\\w*|no)\\b[^.!?]{0,24}\\b(?:vendu|sold)\\b");

    // Sell price first, so the buy price on the same tooltip is never mistaken for it. The label is
    // the verb "Vendre" (FR), "Sell/Sale price" (EN) or "prix de vente"; a short run of non-digits
    // tolerates decoration between the label and the number ("Sell Price ■ 0.05").
    private static final Pattern SELL_PRICE = Pattern.compile(
            "(?i)\\b(?:vendre|sell\\s*price|sale\\s*price|prix\\s*(?:de\\s*)?vente)\\b"
                    + "[^0-9]{0,12}?([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?)?");
    // Generic price fallback. "vente" only counts when it is not part of "limite de vente".
    private static final Pattern PRICE = Pattern.compile(
            "(?i)\\b(?:prix(?:\\s*(?:de\\s*)?(?:vente|sell))?|(?<!de )vente\\s*[:=]|sell\\s*price|sale\\s*price|price)\\s*[:=]?\\s*"
                    + "([0-9]+(?:[.,][0-9]+)?)\\s*(?:€|euros?)?");

    // Sell limit first: the tooltip shows both "Limite d'achat" and "Limite de vente"; only the
    // latter governs selling. Words are tolerated between "limite" and "vente".
    private static final Pattern SELL_LIMIT = Pattern.compile(
            "(?i)\\blimites?\\b[^0-9]{0,24}?\\bvente\\b[^0-9]{0,12}?[:=]?\\s*"
                    + "([0-9]+)(\\s*(?:stacks?|items?|unités?|units?))?");
    private static final Pattern LIMIT = Pattern.compile(
            "(?i)\\b(?:limites?|limits?|max(?:imum)?|restants?|remaining)\\s*[:=]?\\s*([0-9]+)"
                    + "(\\s*(?:stacks?|items?|unités?|units?))?");
    private ShopTextParser() {}

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9:_ ]", " ").replaceAll("\\s+", " ").trim();
    }
    /** Unit price read from a tooltip, preferring the sell price over the buy price. */
    public static Double readUnitPrice(String text) {
        String raw = text == null ? "" : text;
        Double sell = firstPrice(SELL_PRICE, raw);
        return sell != null ? sell : firstPrice(PRICE, raw);
    }

    private static Double firstPrice(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        try {
            double value = Double.parseDouble(m.group(1).replace(',', '.'));
            return value >= 0 && value <= MAX_UNIT_PRICE ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Item limit read from a tooltip, preferring the sell limit over the buy limit. */
    public static Integer readLimitItems(String text) {
        String raw = text == null ? "" : text;
        Integer sell = firstLimit(SELL_LIMIT, raw);
        return sell != null ? sell : firstLimit(LIMIT, raw);
    }

    private static Integer firstLimit(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        try {
            int n = Integer.parseInt(m.group(1));
            if (n <= 0) return null;
            String unit = m.group(2) == null ? "" : normalize(m.group(2));
            return unit.contains("stack") ? Math.multiplyExact(n, 64) : n;
        } catch (RuntimeException e) {
            return null;
        }
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
