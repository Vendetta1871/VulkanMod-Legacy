package net.vesu.config.gui.option;

import net.minecraft.client.gui.GuiScreen;

import java.util.List;
import java.util.function.Function;

/**
 * A tab of the options screen: groups of rows, or a link that opens another screen
 * (the shader pack list) when {@code opens} is set.
 */
public record OptionPage(String name, List<List<Option>> groups, Function<GuiScreen, GuiScreen> opens) {

    public static OptionPage of(String name, List<List<Option>> groups) {
        return new OptionPage(name, groups, null);
    }

    public static OptionPage link(String name, Function<GuiScreen, GuiScreen> opens) {
        return new OptionPage(name, List.of(), opens);
    }
}
