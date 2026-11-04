package fr.venstone.economy;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class VenstoneModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return VenstoneEconomyClient.EconomyScreen::new;
    }
}
