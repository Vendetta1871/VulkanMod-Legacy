package net.vesu.config.gui.option;

import java.util.List;

/**
 * A page of the options screen: groups of rows, plus options that have no row of their own
 * but are applied with the page (the shader pack choice behind its list of rows).
 */
public record OptionPage(String name, List<List<Option>> groups, List<Option> hidden) {

    public static OptionPage of(String name, List<List<Option>> groups) {
        return new OptionPage(name, groups, List.of());
    }
}
