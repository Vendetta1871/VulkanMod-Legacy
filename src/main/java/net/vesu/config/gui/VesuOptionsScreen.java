package net.vesu.config.gui;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.init.SoundEvents;
import net.vesu.Initializer;
import net.vesu.config.gui.option.Option;
import net.vesu.config.gui.option.OptionPage;
import net.vesu.config.gui.option.VesuOptionPages;
import net.vesu.vulkan.FrameTargets;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Video settings laid out like Reese's Sodium Options: a search field on top, a rail of
 * pages on the left under the mod's header, flat option rows on the right with the control
 * at the row's end, the hovered row's description in a framed box under it, and
 * Undo / Apply / Done below. Replaces the vanilla video settings screen while the Vulkan
 * renderer runs (see {@code MinecraftM}). Edits stay pending until Apply, Done, Escape or
 * leaving the screen.
 */
public class VesuOptionsScreen extends GuiScreen {

    // Theme: one base color and a lighter and a darker variant of it.
    private static final int THEME = 0xFFE8642C;           // lava orange
    private static final int THEME_LIGHT = 0xFFFF996E;
    private static final int THEME_DARK = 0xFFB26443;

    private static final int BG_HOVER = 0xE0000000;
    private static final int BG = 0x90000000;
    private static final int BG_INACTIVE = 0x40000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFFAAAAAA;
    private static final int TEXT_RESTART = 0xFFFFD45A;
    private static final int TEXT_UNAVAILABLE = 0xFFFF6B5A;

    private static final int HEADER_HEIGHT = 22;
    private static final int TAB_HEIGHT = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int GROUP_GAP = 4;
    private static final int RAIL_GAP = 5;
    private static final int RAIL_MIN = 120, RAIL_MAX = 220;
    private static final int PAD = 6;
    private static final int CHECKBOX = 10;
    private static final int SLIDER_WIDTH = 90;
    private static final int SCROLLBAR = 10;
    private static final int BUTTON_WIDTH = 65, BUTTON_HEIGHT = 20, BUTTON_GAP = 4;
    private static final int SEARCH_HEIGHT = 20;
    private static final int TOOLTIP_LINE = 12;

    private static final int DONE = 0, APPLY = 1, UNDO = 2;
    private static final String[] BUTTONS = {"Done", "Apply", "Undo"};

    private final GuiScreen parent;
    private List<OptionPage> pages;
    private int page;
    private int scroll;
    private Option.Slider dragging;
    private boolean draggingScrollbar;
    private GuiTextField search;
    private String query = "";
    /** Autotest only: shows this row's description without a mouse. */
    private Option forcedHover;

    // Layout, in scaled pixels; set by initGui.
    private int frameX, frameY, frameWidth, frameHeight;
    private int railWidth;
    private int listX, listRight;

    private record Row(Option option, int y) {
    }

    public VesuOptionsScreen(GuiScreen parent) {
        this.parent = parent;
        this.pages = VesuOptionPages.create();
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        // Wider than 16:9 keeps a 16:9 frame in the middle.
        int width = this.width;
        if (width > this.height * 16 / 9) width = this.height * 16 / 9;
        int left = (this.width - width) / 2;
        this.frameX = left + width / 40;
        this.frameY = this.height / 8;
        this.frameWidth = width - width / 20;
        this.frameHeight = this.height / 4 * 3;

        int widest = this.fontRenderer.getStringWidth(this.brand()) + 22;
        for (OptionPage p : this.pages) widest = Math.max(widest, this.fontRenderer.getStringWidth(p.name()) + 24);
        this.railWidth = Math.min(Math.max(RAIL_MIN, widest), Math.min(RAIL_MAX, (int) (this.frameWidth * 0.35)));
        this.listX = this.frameX + this.railWidth + RAIL_GAP;

        String text = this.search != null ? this.search.getText() : "";
        boolean focused = this.search != null && this.search.isFocused();
        this.search = new GuiTextField(0, this.fontRenderer, this.frameX + 8, this.frameY - 26 + 6,
                this.frameWidth - 16, SEARCH_HEIGHT - 6);
        this.search.setEnableBackgroundDrawing(false);
        this.search.setMaxStringLength(64);
        this.search.setText(text);
        this.search.setFocused(focused);
        this.updateList();
    }

    private String brand() {
        return "Vesu";
    }

    // ---- Layout ----

    private boolean searching() {
        return !this.query.isEmpty();
    }

    /** The groups shown on the right: the page's, or every match of the search by page. */
    private List<List<Option>> groups() {
        if (!this.searching()) return this.pages.get(this.page).groups();
        String q = this.query.toLowerCase(Locale.ROOT);
        List<List<Option>> found = new ArrayList<>();
        for (OptionPage p : this.pages) {
            List<Option> matches = new ArrayList<>();
            for (List<Option> group : p.groups()) {
                for (Option option : group) {
                    if (option.name.toLowerCase(Locale.ROOT).contains(q)
                            || (option.tooltip() != null && option.tooltip().toLowerCase(Locale.ROOT).contains(q))) {
                        matches.add(option);
                    }
                }
            }
            if (!matches.isEmpty()) found.add(matches);
        }
        return found;
    }

    private int contentHeight() {
        int h = 0;
        for (List<Option> group : this.groups()) h += group.size() * ROW_HEIGHT + GROUP_GAP;
        return Math.max(0, h - GROUP_GAP);
    }

    private boolean scrollable() {
        return this.contentHeight() > this.frameHeight;
    }

    /** Rows end before the scrollbar when there is one. */
    private void updateList() {
        this.listRight = this.frameX + this.frameWidth - (this.scrollable() ? SCROLLBAR + 1 : 0);
        int max = Math.max(0, this.contentHeight() - this.frameHeight);
        this.scroll = Math.max(0, Math.min(max, this.scroll));
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        int y = this.frameY - this.scroll;
        for (List<Option> group : this.groups()) {
            for (Option option : group) {
                rows.add(new Row(option, y));
                y += ROW_HEIGHT;
            }
            y += GROUP_GAP;
        }
        return rows;
    }

    private boolean visible(Row row) {
        return row.y + ROW_HEIGHT > this.frameY && row.y < this.frameY + this.frameHeight;
    }

    private int tabY(int i) {
        return this.frameY + HEADER_HEIGHT + i * TAB_HEIGHT;
    }

    private int sliderX() {
        return this.listRight - PAD - SLIDER_WIDTH;
    }

    private int buttonX(int slot) {
        return this.frameX + this.frameWidth - (slot + 1) * BUTTON_WIDTH - slot * BUTTON_GAP;
    }

    private int buttonY() {
        return this.frameY + this.frameHeight + 5;
    }

    private boolean buttonShown(int slot) {
        return slot != UNDO || this.anyModified();
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean overList(int mx, int my) {
        return inside(mx, my, this.listX, this.frameY, this.listRight - this.listX, this.frameHeight);
    }

    // ---- Drawing ----

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.forEachOption(Option::sync);
        this.drawDefaultBackground();
        this.drawSearch();
        this.drawRail(mouseX, mouseY);

        Option hovered = this.forcedHover;
        int hoveredY = this.frameY;
        for (Row row : this.rows()) {
            if (!this.visible(row)) continue;
            boolean hover = this.dragging == row.option || (this.dragging == null && !this.draggingScrollbar
                    && this.overList(mouseX, mouseY) && inside(mouseX, mouseY, this.listX, row.y, this.listRight - this.listX, ROW_HEIGHT));
            hover |= row.option == this.forcedHover;
            if (hover) {
                hovered = row.option;
                hoveredY = row.y;
            }
            this.drawRow(row, hover);
        }
        if (this.searching() && this.groups().isEmpty()) {
            this.fontRenderer.drawStringWithShadow("No options match.", this.listX + PAD, this.frameY + 5, TEXT_MUTED);
        }
        this.drawScrollbar();
        this.drawButtons(mouseX, mouseY);
        if (hovered != null) this.drawTooltip(hovered, hoveredY);
    }

    private void drawSearch() {
        int y = this.frameY - 26;
        drawRect(this.frameX, y, this.frameX + this.frameWidth, y + SEARCH_HEIGHT, BG);
        if (this.search.getText().isEmpty() && !this.search.isFocused()) {
            this.fontRenderer.drawString("Search options...", this.frameX + 8, y + 6, 0xFF6A6A6A);
        }
        this.search.drawTextBox();
    }

    private void drawRail(int mouseX, int mouseY) {
        int x = this.frameX, w = this.railWidth, y = this.frameY;
        // Header: the mark, the name and the version.
        drawRect(x, y, x + w, y + HEADER_HEIGHT, 0xBE000000);
        this.drawMark(x + 4, y + 3);
        this.fontRenderer.drawStringWithShadow(this.brand(), x + 22, y + 2, TEXT);
        this.fontRenderer.drawStringWithShadow(Initializer.VERSION, x + 22, y + 12, THEME_LIGHT);

        for (int i = 0; i < this.pages.size(); i++) {
            int ty = this.tabY(i);
            boolean selected = !this.searching() && i == this.page;
            boolean hover = inside(mouseX, mouseY, x, ty, w, TAB_HEIGHT);
            drawRect(x, ty, x + w, ty + TAB_HEIGHT, hover ? BG_HOVER : BG);
            if (selected) drawRect(x, ty, x + 2, ty + TAB_HEIGHT, THEME);
            int color = selected ? THEME_LIGHT : hover ? THEME : THEME_DARK;
            this.fontRenderer.drawStringWithShadow(this.pages.get(i).name(), x + 10, ty + 5, color);
        }
    }

    /** A 16 px volcano: a dark cone with a glowing crater and a plume. */
    private void drawMark(int x, int y) {
        for (int row = 0; row < 9; row++) {
            int half = 2 + row * 6 / 8;
            drawRect(x + 8 - half, y + 7 + row, x + 8 + half, y + 8 + row, row < 2 ? THEME : 0xFF5A4A44);
        }
        drawRect(x + 7, y + 7, x + 9, y + 10, THEME_LIGHT);
        drawRect(x + 6, y + 3, x + 8, y + 5, 0xFF8A8A8A);
        drawRect(x + 8, y + 1, x + 11, y + 3, 0xFFB0B0B0);
    }

    private void drawRow(Row row, boolean hover) {
        Option option = row.option;
        boolean available = option.available();
        int x = this.listX, y = row.y, right = this.listRight;
        drawRect(x, Math.max(y, this.frameY), right, Math.min(y + ROW_HEIGHT, this.frameY + this.frameHeight),
                hover ? BG_HOVER : BG_INACTIVE);
        if (y < this.frameY || y + ROW_HEIGHT > this.frameY + this.frameHeight) return;

        if (option instanceof Option.Action) {
            int w = this.fontRenderer.getStringWidth(option.name);
            this.fontRenderer.drawStringWithShadow(option.name, x + (right - x - w) / 2, y + 5,
                    !available ? TEXT_MUTED : hover ? THEME_LIGHT : TEXT);
            return;
        }

        int controlLeft;   // the name must end before this
        if (option instanceof Option.Radio radio) {
            int bx = right - PAD - CHECKBOX, by = y + (ROW_HEIGHT - CHECKBOX) / 2;
            int color = !available ? TEXT_MUTED : radio.selected() ? THEME : TEXT;
            this.drawBox(bx, by, color, radio.selected());
            String format = option.valueText();
            int fx = bx - 8 - this.fontRenderer.getStringWidth(format);
            this.fontRenderer.drawStringWithShadow(format, fx, y + 5, TEXT_MUTED);
            controlLeft = fx;
        } else if (option instanceof Option.Toggle toggle) {
            int bx = right - PAD - CHECKBOX, by = y + (ROW_HEIGHT - CHECKBOX) / 2;
            int color = !available ? TEXT_MUTED : toggle.pending() ? THEME : TEXT;
            this.drawBox(bx, by, color, toggle.pending());
            controlLeft = bx;
        } else if (option instanceof Option.Slider slider && hover && available) {
            int sx = this.sliderX(), sy = y + (ROW_HEIGHT - CHECKBOX) / 2;
            int thumb = sx + Math.round(slider.fraction() * (SLIDER_WIDTH - 4));
            drawRect(sx, sy + CHECKBOX / 2, sx + SLIDER_WIDTH, sy + CHECKBOX / 2 + 1, THEME);
            drawRect(thumb, sy, thumb + 4, sy + CHECKBOX, TEXT);
            String value = option.valueText();
            int vx = sx - 6 - this.fontRenderer.getStringWidth(value);
            this.fontRenderer.drawStringWithShadow(value, vx, y + 5, TEXT);
            controlLeft = vx;
        } else {
            String value = option.valueText();
            int vx = right - PAD - this.fontRenderer.getStringWidth(value);
            this.fontRenderer.drawStringWithShadow(value, vx, y + 5, available ? TEXT : TEXT_MUTED);
            controlLeft = vx;
        }

        String name = option.name;
        int room = controlLeft - 10 - (x + PAD);
        if (this.fontRenderer.getStringWidth(name) > room) {
            name = this.fontRenderer.trimStringToWidth(name, Math.max(0, room - this.fontRenderer.getStringWidth("..."))) + "...";
        }
        String style = !available ? "§7§m" : option.modified() ? "§o" : "";
        this.fontRenderer.drawStringWithShadow(style + name, x + PAD, y + 5, TEXT);
    }

    /** A check box: a frame, filled when checked. */
    private void drawBox(int x, int y, int color, boolean checked) {
        drawRect(x, y, x + CHECKBOX, y + 1, color);
        drawRect(x, y + CHECKBOX - 1, x + CHECKBOX, y + CHECKBOX, color);
        drawRect(x, y + 1, x + 1, y + CHECKBOX - 1, color);
        drawRect(x + CHECKBOX - 1, y + 1, x + CHECKBOX, y + CHECKBOX - 1, color);
        if (checked) drawRect(x + 2, y + 2, x + CHECKBOX - 2, y + CHECKBOX - 2, color);
    }

    private void drawScrollbar() {
        if (!this.scrollable()) return;
        int x = this.frameX + this.frameWidth - SCROLLBAR, top = this.frameY, bottom = top + this.frameHeight;
        drawRect(x, top, x + SCROLLBAR, top + 1, TEXT_MUTED);
        drawRect(x, bottom - 1, x + SCROLLBAR, bottom, TEXT_MUTED);
        drawRect(x, top + 1, x + 1, bottom - 1, TEXT_MUTED);
        drawRect(x + SCROLLBAR - 1, top + 1, x + SCROLLBAR, bottom - 1, TEXT_MUTED);
        int[] thumb = this.scrollThumb();
        drawRect(x + 2, thumb[0], x + SCROLLBAR - 2, thumb[1], TEXT_MUTED);
    }

    /** Top and bottom of the scrollbar thumb. */
    private int[] scrollThumb() {
        int track = this.frameHeight - 4, content = this.contentHeight();
        int size = Math.max(8, track * this.frameHeight / content);
        int top = this.frameY + 2 + (track - size) * this.scroll / Math.max(1, content - this.frameHeight);
        return new int[]{top, top + size};
    }

    private void drawButtons(int mouseX, int mouseY) {
        boolean pending = this.anyModified();
        for (int i = 0; i < BUTTONS.length; i++) {
            if (!this.buttonShown(i)) continue;
            int x = this.buttonX(i), y = this.buttonY();
            boolean enabled = i == DONE || pending;
            boolean hover = enabled && inside(mouseX, mouseY, x, y, BUTTON_WIDTH, BUTTON_HEIGHT);
            drawRect(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, !enabled ? BG_INACTIVE : hover ? BG_HOVER : BG);
            this.drawCenteredString(this.fontRenderer, BUTTONS[i], x + BUTTON_WIDTH / 2, y + 6,
                    enabled ? TEXT : TEXT_MUTED);
        }
    }

    /** The hovered row's description in a framed box under the row, or over it near the bottom. */
    private void drawTooltip(Option option, int rowY) {
        int x = this.listX, right = this.listRight, textWidth = right - x - 6;
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (option.tooltip() != null) {
            for (String line : this.fontRenderer.listFormattedStringToWidth(option.tooltip(), textWidth)) {
                lines.add(line);
                colors.add(TEXT);
            }
        }
        this.addNote(lines, colors, option.impact() != null ? "Performance Impact: " + option.impact().label.toUpperCase(Locale.ROOT) : null, TEXT_MUTED, textWidth);
        this.addNote(lines, colors, option.needsRestart() ? "Takes effect after a restart." : null, TEXT_RESTART, textWidth);
        this.addNote(lines, colors, !option.available() ? option.unavailableReason() : null, TEXT_UNAVAILABLE, textWidth);
        if (lines.isEmpty()) return;

        int height = lines.size() * TOOLTIP_LINE + 4;
        int y = rowY + ROW_HEIGHT;
        if (y + height > this.height - 2) y = rowY - height;
        drawRect(x, y, right, y + height, BG_HOVER);
        drawRect(x, y, right, y + 1, THEME);
        drawRect(x, y + height - 1, right, y + height, THEME);
        drawRect(x, y + 1, x + 1, y + height - 1, THEME);
        drawRect(right - 1, y + 1, right, y + height - 1, THEME);
        for (int i = 0; i < lines.size(); i++) {
            this.fontRenderer.drawStringWithShadow(lines.get(i), x + 3, y + 3 + i * TOOLTIP_LINE, colors.get(i));
        }
    }

    private void addNote(List<String> lines, List<Integer> colors, String note, int color, int width) {
        if (note == null) return;
        for (String line : this.fontRenderer.listFormattedStringToWidth(note, width)) {
            lines.add(line);
            colors.add(color);
        }
    }

    // ---- Input ----

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        this.search.mouseClicked(mouseX, mouseY, button);
        if (inside(mouseX, mouseY, this.frameX, this.frameY - 26, this.frameWidth, SEARCH_HEIGHT)) {
            this.search.setFocused(true);
            if (button == 1) this.setQuery("");
            return;
        }

        for (int i = 0; i < this.pages.size(); i++) {
            if (!inside(mouseX, mouseY, this.frameX, this.tabY(i), this.railWidth, TAB_HEIGHT)) continue;
            this.click();
            this.search.setText("");
            this.query = "";
            this.selectPage(i);
            return;
        }

        for (int i = 0; i < BUTTONS.length; i++) {
            if (!this.buttonShown(i) || !inside(mouseX, mouseY, this.buttonX(i), this.buttonY(), BUTTON_WIDTH, BUTTON_HEIGHT)) continue;
            if (i == DONE) {
                this.click();
                this.mc.displayGuiScreen(this.parent);
            } else if (this.anyModified()) {
                this.click();
                if (i == APPLY) this.applyChanges();
                else this.forEachOption(Option::revert);
                this.updateList();
            }
            return;
        }

        if (this.scrollable() && inside(mouseX, mouseY, this.frameX + this.frameWidth - SCROLLBAR, this.frameY, SCROLLBAR, this.frameHeight)) {
            this.draggingScrollbar = true;
            this.dragScrollbar(mouseY);
            return;
        }

        if (!this.overList(mouseX, mouseY)) return;
        for (Row row : this.rows()) {
            if (!inside(mouseX, mouseY, this.listX, row.y, this.listRight - this.listX, ROW_HEIGHT)) continue;
            Option option = row.option;
            if (!option.available()) return;
            if (option instanceof Option.Radio radio) {
                this.click();
                radio.select();
            } else if (option instanceof Option.Action action) {
                this.click();
                action.run(this);
            } else if (option instanceof Option.Cycle<?> cycle) {
                this.click();
                cycle.cycle(button == 1 || isShiftKeyDown() ? -1 : 1);
            } else if (option instanceof Option.Slider slider && button == 0 && mouseX >= this.sliderX() - 4) {
                this.dragging = slider;
                this.drag(mouseX);
            }
            return;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (this.dragging != null) this.drag(mouseX);
        if (this.draggingScrollbar) this.dragScrollbar(mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.draggingScrollbar = false;
        if (this.dragging != null) {
            this.dragging = null;
            this.click();
        }
    }

    private void drag(int mouseX) {
        this.dragging.setFraction((mouseX - this.sliderX() - 2) / (float) (SLIDER_WIDTH - 4));
    }

    private void dragScrollbar(int mouseY) {
        int[] thumb = this.scrollThumb();
        int size = thumb[1] - thumb[0], track = this.frameHeight - 4 - size;
        float f = (mouseY - this.frameY - 2 - size / 2f) / Math.max(1, track);
        this.scroll = Math.round(f * (this.contentHeight() - this.frameHeight));
        this.updateList();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            this.scroll -= Integer.signum(wheel) * ROW_HEIGHT * 2;
            this.updateList();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(this.parent);
        } else if (keyCode == Keyboard.KEY_F && isCtrlKeyDown()) {
            this.search.setFocused(true);
        } else if (this.search.isFocused() && this.search.textboxKeyTyped(typedChar, keyCode)) {
            this.setQuery(this.search.getText());
        }
    }

    private void setQuery(String query) {
        this.search.setText(query);
        this.query = query.trim();
        this.scroll = 0;
        this.forcedHover = null;
        this.updateList();
    }

    @Override
    public void updateScreen() {
        this.search.updateCursorCounter();
    }

    private void click() {
        this.mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ---- Settings ----

    private void forEachOption(java.util.function.Consumer<Option> action) {
        for (OptionPage p : this.pages) {
            for (List<Option> group : p.groups()) {
                group.forEach(action);
            }
            p.hidden().forEach(action);
        }
    }

    private boolean anyModified() {
        boolean[] modified = {false};
        this.forEachOption(option -> modified[0] |= option.available() && option.modified());
        return modified[0];
    }

    /** Applies what is pending now (a row that opens another screen needs the applied state). */
    public void applyPending() {
        if (this.anyModified()) this.applyChanges();
    }

    /** Rebuilds one page from the current state (the shader pack list after a rescan). */
    public void reloadPage(String name) {
        for (int i = 0; i < this.pages.size(); i++) {
            if (this.pages.get(i).name().equals(name) && name.equals(VesuOptionPages.SHADER_PACKS)) {
                this.pages.set(i, VesuOptionPages.shaderPacks());
            }
        }
        this.forcedHover = null;
        this.updateList();
    }

    /** Pending values to the game, then saves whichever settings file changed. */
    private void applyChanges() {
        GameSettings settings = this.mc.gameSettings;
        int guiScale = settings.guiScale;
        boolean[] changed = new boolean[Option.Storage.values().length];
        this.forEachOption(option -> {
            if (option.available() && option.modified()) {
                option.apply();
                changed[option.storage.ordinal()] = true;
            }
        });
        if (changed[Option.Storage.VESU.ordinal()]) {
            FrameTargets.applySettings();
            Initializer.saveConfig();
        }
        if (changed[Option.Storage.VANILLA.ordinal()]) {
            settings.saveOptions();
        }
        // Vanilla may clamp or round what it was given: show what it kept.
        this.forEachOption(Option::revert);
        if (settings.guiScale != guiScale) {
            ScaledResolution resolution = new ScaledResolution(this.mc);
            this.setWorldAndResolution(this.mc, resolution.getScaledWidth(), resolution.getScaledHeight());
        }
    }

    /** Leaving the screen any way (Done, Escape, a pack's options) applies what is pending. */
    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        this.dragging = null;
        if (this.anyModified()) this.applyChanges();
        // Vanilla's video settings do this too: a new mipmap level reloads the block models.
        this.mc.gameSettings.onGuiClosed();
    }

    // ---- Autotest ----

    public int pageCount() {
        return this.pages.size();
    }

    public String pageName(int i) {
        return this.pages.get(i).name();
    }

    /** Selects a page. */
    public void selectPage(int i) {
        this.page = i;
        this.scroll = 0;
        this.dragging = null;
        this.forcedHover = null;
        this.updateList();
    }

    /** Types {@code query} into the search field. */
    public void search(String query) {
        this.setQuery(query);
    }

    /** Shows the description of the page's {@code index}-th row, or none for -1. */
    public void hoverRow(int index) {
        List<Row> rows = this.rows();
        this.forcedHover = index >= 0 && index < rows.size() ? rows.get(index).option : null;
    }
}
