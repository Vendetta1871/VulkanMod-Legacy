package net.vesu.config.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.MathHelper;
import net.minecraft.client.resources.I18n;
import net.vesu.pack.optifine.OptiFineFrontend;
import net.vesu.pack.optifine.ShaderScreens;
import net.vesu.shader.PackManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The options of an OptiFine pack, in the pages of its {@code shaders.properties}. A click moves
 * an option to its next value, a right click back. "Done" saves what differs from the defaults
 * and rebuilds the pack, so a page can be gone through without a rebuild per click.
 */
public class ShaderOptionsScreen extends GuiScreen {

    private static final int ID_DONE = 200, ID_BACK = 201, ID_PREV = 202, ID_NEXT = 203, ID_RESET = 204, ID_ENTRY = 1000;
    private static final int COLUMN_WIDTH = 155, ROW_HEIGHT = 20, ROW_GAP = 2;

    private final GuiScreen parent;
    private final OptiFineFrontend.Result pack;
    private final Map<String, String> edited;
    private final String pageName;
    private final List<String> entries = new ArrayList<>();
    private int page, rowsPerPage;

    public ShaderOptionsScreen(GuiScreen parent, OptiFineFrontend.Result pack) {
        this(parent, pack, new TreeMap<>(), "");
        pack.options().forEach((k, o) -> edited.put(k, o.value));
    }

    private ShaderOptionsScreen(GuiScreen parent, OptiFineFrontend.Result pack, Map<String, String> edited, String pageName) {
        this.parent = parent;
        this.pack = pack;
        this.edited = edited;
        this.pageName = pageName;
    }

    @Override
    public void initGui() {
        entries.clear();
        List<String> list = pack.screens().pages.get(pageName);
        if (list != null) {
            for (String e : list) {
                if (e.equals("*")) {
                    for (String name : pack.options().keySet()) if (!list.contains(name)) entries.add(name);
                } else {
                    entries.add(e);
                }
            }
        }
        layout();
    }

    private void layout() {
        this.buttonList.clear();
        int top = 34, bottom = this.height - 56;
        this.rowsPerPage = Math.max(1, (bottom - top) / (ROW_HEIGHT + ROW_GAP));
        int perPage = rowsPerPage * 2;
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int first = page * perPage;
        for (int i = first; i < Math.min(entries.size(), first + perPage); i++) {
            int slot = i - first;
            int x = this.width / 2 - COLUMN_WIDTH - 2 + (slot % 2) * (COLUMN_WIDTH + 4);
            int y = top + (slot / 2) * (ROW_HEIGHT + ROW_GAP);
            String entry = entries.get(i);
            if (entry.equals("<empty>")) continue;
            OptiFineFrontend.Option option = pack.options().get(entry);
            if (option != null && !option.bool && option.values.size() > 1 && pack.screens().sliders.contains(entry)) {
                this.buttonList.add(new Slider(ID_ENTRY + i, x, y, entry, option));
            } else {
                this.buttonList.add(new GuiButton(ID_ENTRY + i, x, y, COLUMN_WIDTH, ROW_HEIGHT, label(entry)));
            }
        }
        int by = this.height - 50;
        GuiButton prev = new GuiButton(ID_PREV, this.width / 2 - 155, by, 40, ROW_HEIGHT, "<");
        prev.enabled = page > 0;
        GuiButton next = new GuiButton(ID_NEXT, this.width / 2 + 115, by, 40, ROW_HEIGHT, ">");
        next.enabled = page < pages - 1;
        this.buttonList.add(prev);
        this.buttonList.add(next);
        this.buttonList.add(new GuiButton(ID_RESET, this.width / 2 - 75, by, 150, ROW_HEIGHT, "Defaults"));
        if (pageName.isEmpty()) {
            this.buttonList.add(new GuiButton(ID_DONE, this.width / 2 - 100, this.height - 27, I18n.format("gui.done")));
        } else {
            this.buttonList.add(new GuiButton(ID_BACK, this.width / 2 - 100, this.height - 27, I18n.format("gui.back")));
        }
    }

    private String label(String entry) {
        ShaderScreens s = pack.screens();
        if (entry.equals("<profile>")) return "Profile: " + currentProfile();
        if (entry.startsWith("[") && entry.endsWith("]")) {
            String name = entry.substring(1, entry.length() - 1);
            return s.lang.getOrDefault("screen." + name, name) + " >";
        }
        OptiFineFrontend.Option o = pack.options().get(entry);
        String name = s.lang.getOrDefault("option." + entry, entry);
        if (o == null) return name;
        String value = edited.get(entry);
        if (o.bool) return name + ": " + (value.equals("true") ? "ON" : "OFF");
        return name + ": " + s.lang.getOrDefault("value." + entry + "." + value, value);
    }

    private String currentProfile() {
        for (String name : pack.screens().profiles.keySet()) {
            if (matches(name)) return name;
        }
        return "Custom";
    }

    private boolean matches(String profile) {
        Map<String, String> wanted = new TreeMap<>();
        pack.screens().apply(profile, wanted);
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            if (pack.options().containsKey(e.getKey()) && !e.getValue().equals(edited.get(e.getKey()))) return false;
        }
        return true;
    }

    private void cycleProfile(int step) {
        List<String> names = new ArrayList<>(pack.screens().profiles.keySet());
        if (names.isEmpty()) return;
        int next = Math.floorMod(names.indexOf(currentProfile()) + step, names.size());
        Map<String, String> wanted = new TreeMap<>();
        pack.screens().apply(names.get(next), wanted);
        wanted.forEach((k, v) -> {
            if (pack.options().containsKey(k)) edited.put(k, v);
        });
    }

    private void cycle(String entry, int step) {
        OptiFineFrontend.Option o = pack.options().get(entry);
        if (o == null) return;
        if (o.bool) {
            edited.put(entry, edited.get(entry).equals("true") ? "false" : "true");
        } else if (!o.values.isEmpty()) {
            int at = o.values.indexOf(edited.get(entry));
            edited.put(entry, o.values.get(Math.floorMod(at + step, o.values.size())));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        String title = pageName.isEmpty() ? "Shader options" : pack.screens().lang.getOrDefault("screen." + pageName, pageName);
        this.drawCenteredString(this.fontRenderer, title, this.width / 2, 15, 0xFFFFFF);
        for (GuiButton b : this.buttonList) {
            if (b.id >= ID_ENTRY && b.isMouseOver()) {
                String comment = pack.screens().lang.get("option." + entries.get(b.id - ID_ENTRY) + ".comment");
                if (comment != null) {
                    this.drawCenteredString(this.fontRenderer, this.fontRenderer.trimStringToWidth(comment, this.width - 20),
                            this.width / 2, this.height - 62, 0xA0A0A0);
                }
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 1) {
            for (GuiButton b : this.buttonList) {
                if (b.id >= ID_ENTRY && b.mousePressed(this.mc, mouseX, mouseY)) {
                    String entry = entries.get(b.id - ID_ENTRY);
                    if (entry.equals("<profile>")) cycleProfile(-1);
                    else cycle(entry, -1);
                    layout();
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || button instanceof Slider) return;
        switch (button.id) {
            case ID_DONE -> {
                Map<String, String> changed = new TreeMap<>();
                pack.options().forEach((k, o) -> {
                    if (!o.defaultValue.equals(edited.get(k))) changed.put(k, edited.get(k));
                });
                PackManager.applyOptions(changed);
                this.mc.displayGuiScreen(this.parent);
                return;
            }
            case ID_BACK -> {
                this.mc.displayGuiScreen(this.parent);
                return;
            }
            case ID_PREV -> page--;
            case ID_NEXT -> page++;
            case ID_RESET -> pack.options().forEach((k, o) -> edited.put(k, o.defaultValue));
            default -> {
                String entry = entries.get(button.id - ID_ENTRY);
                if (entry.equals("<profile>")) {
                    cycleProfile(1);
                } else if (entry.startsWith("[") && entry.endsWith("]")) {
                    this.mc.displayGuiScreen(new ShaderOptionsScreen(this, pack, edited, entry.substring(1, entry.length() - 1)));
                    return;
                } else {
                    cycle(entry, 1);
                }
            }
        }
        layout();
    }

    /** An option of {@code sliders=}: dragging walks its values, right click still steps back. */
    private final class Slider extends GuiButton {
        private final String entry;
        private final OptiFineFrontend.Option option;
        private boolean dragging;

        Slider(int id, int x, int y, String entry, OptiFineFrontend.Option option) {
            super(id, x, y, COLUMN_WIDTH, ROW_HEIGHT, label(entry));
            this.entry = entry;
            this.option = option;
        }

        private void follow(int mouseX) {
            float at = (mouseX - (this.x + 4)) / (float) (this.width - 8);
            int index = Math.round(MathHelper.clamp(at, 0.0f, 1.0f) * (option.values.size() - 1));
            edited.put(entry, option.values.get(index));
            this.displayString = label(entry);
        }

        @Override
        protected int getHoverState(boolean mouseOver) {
            return 0;
        }

        @Override
        protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) return;
            if (dragging) follow(mouseX);
            float at = Math.max(0, option.values.indexOf(edited.get(entry))) / (float) (option.values.size() - 1);
            int knob = this.x + (int) (at * (this.width - 8));
            mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            this.drawTexturedModalRect(knob, this.y, 0, 66, 4, 20);
            this.drawTexturedModalRect(knob + 4, this.y, 196, 66, 4, 20);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!super.mousePressed(mc, mouseX, mouseY)) return false;
            dragging = true;
            follow(mouseX);
            return true;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }
    }
}
