package net.vulkanmod.config.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.vulkanmod.shader.PackManager;

import java.io.IOException;
import java.util.List;

/**
 * Shader pack list (docs/shaderpacks, M0): one row per pack in {@code shaderpacks/}, with
 * its format and state. Clicking a usable pack selects it, "Off" clears the
 * selection. The selection applies immediately and is saved.
 */
public class ShaderPackScreen extends GuiScreen {

    private static final String TITLE = "Shader Packs (VulkanMod)";
    private static final int ROW_WIDTH = 300;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;

    private static final int ID_OFF = 1;
    private static final int ID_RESCAN = 2;
    private static final int ID_PREV = 3;
    private static final int ID_NEXT = 4;
    private static final int ID_OPTIONS = 5;
    private static final int ID_DONE = 200;
    private static final int ID_ROW = 1000;   // + index into the entry list

    private final GuiScreen parent;
    private int page;
    private int rowsPerPage;

    public ShaderPackScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        PackManager.rescan();
        layout();
    }

    private void layout() {
        this.buttonList.clear();
        List<PackManager.Entry> entries = PackManager.entries();
        int top = 44;
        int bottom = this.height - 60;
        // The first row is always "Off".
        this.rowsPerPage = Math.max(1, (bottom - top) / (ROW_HEIGHT + ROW_GAP) - 1);
        int pages = Math.max(1, (entries.size() + this.rowsPerPage - 1) / this.rowsPerPage);
        this.page = Math.min(this.page, pages - 1);

        int x = this.width / 2 - ROW_WIDTH / 2;
        int y = top;
        String selected = PackManager.selectedId();
        GuiButton off = new GuiButton(ID_OFF, x, y, ROW_WIDTH, ROW_HEIGHT, (selected == null ? "> Off <" : "Off"));
        this.buttonList.add(off);
        y += ROW_HEIGHT + ROW_GAP;

        int first = this.page * this.rowsPerPage;
        int last = Math.min(entries.size(), first + this.rowsPerPage);
        for (int i = first; i < last; i++) {
            PackManager.Entry e = entries.get(i);
            String label = e.name() + "  [" + e.kind().label + "]";
            if (e.id().equals(selected)) label = "> " + label + " <";
            GuiButton row = new GuiButton(ID_ROW + i, x, y, ROW_WIDTH, ROW_HEIGHT, label);
            row.enabled = e.usable();
            this.buttonList.add(row);
            y += ROW_HEIGHT + ROW_GAP;
        }

        int by = this.height - 52;
        GuiButton prev = new GuiButton(ID_PREV, x, by, 48, ROW_HEIGHT, "<");
        prev.enabled = this.page > 0;
        GuiButton next = new GuiButton(ID_NEXT, x + ROW_WIDTH - 48, by, 48, ROW_HEIGHT, ">");
        next.enabled = this.page < pages - 1;
        this.buttonList.add(prev);
        this.buttonList.add(next);
        this.buttonList.add(new GuiButton(ID_RESCAN, this.width / 2 - 100, by, 98, ROW_HEIGHT, "Rescan folder"));
        GuiButton options = new GuiButton(ID_OPTIONS, this.width / 2 + 2, by, 98, ROW_HEIGHT, "Pack options...");
        options.enabled = PackManager.activeOptions() != null;
        this.buttonList.add(options);
        this.buttonList.add(new GuiButton(ID_DONE, this.width / 2 - 100, this.height - 27, I18n.format("gui.done")));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawCenteredString(this.fontRenderer, TITLE, this.width / 2, 15, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer,
                "Folder: " + PackManager.DIR.toAbsolutePath(),
                this.width / 2, 28, 0xA0A0A0);

        // The hovered row's state, else the selected pack's error.
        String info = null;
        int color = 0xA0A0A0;
        for (GuiButton b : this.buttonList) {
            if (b.id >= ID_ROW && b.isMouseOver()) {
                PackManager.Entry e = PackManager.entries().get(b.id - ID_ROW);
                info = e.id() + (e.message() == null ? "" : ": " + e.message());
                color = e.usable() ? 0xA0A0A0 : 0xFF8080;
            }
        }
        if (info == null && PackManager.activeError() != null) {
            info = PackManager.selectedId() + ": " + PackManager.activeError();
            color = 0xFF8080;
        }
        if (info == null && net.vulkanmod.vulkan.PackRuntime.compiling() != null) {
            info = "Compiling " + net.vulkanmod.vulkan.PackRuntime.compiling() + ": " + net.vulkanmod.vulkan.PackRuntime.compilePercent() + "%, the picture changes when it is done.";
            color = 0xFFD080;
        }
        if (info == null && PackManager.pausedByRt(PackManager.active())) {
            info = "Paused: ray tracing is on. Turn it off to use this pack.";
            color = 0xFFD080;
        }
        if (info == null && PackManager.entries().isEmpty()) {
            info = "No packs found. Put pack folders or zips into the folder above.";
        }
        if (info != null) {
            this.drawCenteredString(this.fontRenderer, this.fontRenderer.trimStringToWidth(info, this.width - 20),
                    this.width / 2, this.height - 64, color);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) return;
        if (button.id == ID_DONE) {
            this.mc.displayGuiScreen(this.parent);
            return;
        }
        if (button.id == ID_OPTIONS) {
            this.mc.displayGuiScreen(new ShaderOptionsScreen(this, PackManager.activeOptions()));
            return;
        }
        if (button.id == ID_OFF) {
            PackManager.select(null);
        } else if (button.id == ID_RESCAN) {
            PackManager.rescan();
        } else if (button.id == ID_PREV) {
            this.page--;
        } else if (button.id == ID_NEXT) {
            this.page++;
        } else if (button.id >= ID_ROW) {
            PackManager.select(PackManager.entries().get(button.id - ID_ROW).id());
        }
        layout();
    }
}
