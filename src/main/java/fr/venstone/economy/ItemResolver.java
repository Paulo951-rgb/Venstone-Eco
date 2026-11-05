package fr.venstone.economy;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry-backed item resolution. The registry only exists once the client has bootstrapped,
 * so every lookup is guarded: outside a running client the helpers fall back to the pure
 * {@link ItemIds} rules instead of throwing. That also keeps this class unit-testable.
 */
public final class ItemResolver {
    private ItemResolver() { }

    /** Returns a canonical id present in the registry, or {@code null} when unknown/unavailable. */
    public static String resolveId(String input) {
        String canonical = ItemIds.canonical(input);
        if (canonical == null) return null;
        Identifier parsed = Identifier.tryParse(canonical);
        if (parsed == null) return null;
        try {
            return Registries.ITEM.containsId(parsed) ? parsed.toString() : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Localized display name, or the readable path when the id cannot be resolved. */
    public static String displayName(String id) {
        String canonical = ItemIds.canonical(id);
        if (canonical != null) {
            Identifier parsed = Identifier.tryParse(canonical);
            if (parsed != null) {
                try {
                    if (Registries.ITEM.containsId(parsed)) return Registries.ITEM.get(parsed).getName().getString();
                } catch (RuntimeException | LinkageError unavailable) {
                    // Fall through to the readable path.
                }
            }
        }
        return ItemIds.path(id);
    }

    /** Candidate ids whose id or localized name matches the query; empty when no registry is loaded. */
    public static List<String> suggestions(String query, int limit) {
        List<String> out = new ArrayList<>();
        if (limit <= 0) return out;
        String normalizedQuery = ShopTextParser.normalize(query);
        try {
            for (Identifier id : Registries.ITEM.getIds()) {
                if (!id.getNamespace().equals("minecraft")) continue;
                Item item = Registries.ITEM.get(id);
                boolean matchesId = normalizedQuery.isEmpty() || ShopTextParser.normalize(id.getPath()).contains(normalizedQuery);
                boolean matchesName = !normalizedQuery.isEmpty()
                        && ShopTextParser.normalize(item.getName().getString()).contains(normalizedQuery);
                if (!matchesId && !matchesName) continue;
                out.add(id.toString());
                if (out.size() >= limit) break;
            }
        } catch (RuntimeException | LinkageError unavailable) {
            out.clear();
        }
        return out;
    }
}
