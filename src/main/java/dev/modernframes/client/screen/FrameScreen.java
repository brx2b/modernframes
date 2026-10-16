package dev.modernframes.client.screen;

import com.mojang.math.Axis;
import dev.modernframes.ModernFramesConfig;
import dev.modernframes.client.texture.FrameTextureManager;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import dev.modernframes.common.data.FrameData;
import dev.modernframes.common.network.FramePolicy;
import dev.modernframes.common.network.FrameResultPayload;
import dev.modernframes.common.network.SetFramePayload;
import dev.modernframes.common.util.FrameResizer;
import dev.modernframes.common.util.FrameSources;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Configuration screen: remote URLs (any image API), local sandbox files, frame size
 * (multi-block) and appearance sliders, with an async live preview.
 */
public class FrameScreen extends Screen {

    private enum Tab {
        URL,
        FILE,
        SIZE,
        FRAME,
        ADJUST
    }

    private static final int PANEL_SIZE = 110;
    private static final int CONTROLS_WIDTH = 220;
    private static final int ROW = 24;
    private static final int TAB_WIDTH = 42;
    private static final int TAB_GAP = 2;
    private static final Component LABEL_FLIP_X = Component.translatable("modernframes.opt.flip_x");
    private static final Component LABEL_FLIP_Y = Component.translatable("modernframes.opt.flip_y");

    private final ImageFrameBlockEntity frame;
    private final boolean canEdit;

    // Staged configuration
    private String source;
    private FrameData.SourceKind kind;
    private float offsetU;
    private float offsetV;
    private float zoom;
    private float rotation;
    private int alpha;
    private int brightness;
    private int renderDistance;
    private boolean flipX;
    private boolean flipY;
    private boolean bothSides;
    private boolean lit;
    private int sizeW;
    private int sizeH;
    private boolean autoSize;
    private boolean backing;
    private boolean border;

    private Tab tab = Tab.URL;
    private FrameTextureManager.Entry previewEntry;
    private String clientError = "";

    private final List<String> localFiles = new ArrayList<>();
    private int fileIndex = -1;

    private EditBox urlBox;
    private EditBox pathBox;
    private ValueSlider sliderW;
    private ValueSlider sliderH;
    private ValueSlider sliderZoom;
    private ValueSlider sliderRotation;
    private ValueSlider sliderPanU;
    private ValueSlider sliderPanV;
    private ValueSlider sliderAlpha;
    private ValueSlider sliderBrightness;
    private Button flipXButton;
    private Button flipYButton;

    private final List<AbstractWidget> urlWidgets = new ArrayList<>();
    private final List<AbstractWidget> fileWidgets = new ArrayList<>();
    private final List<AbstractWidget> sizeWidgets = new ArrayList<>();
    private final List<AbstractWidget> frameWidgets = new ArrayList<>();
    private final List<AbstractWidget> adjustWidgets = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int controlsX;
    private int contentY;

    public FrameScreen(ImageFrameBlockEntity frame) {
        super(Component.translatable("modernframes.screen.title"));
        this.frame = frame;
        FrameData data = frame.getData();
        this.source = data.source();
        this.kind = data.kind();
        this.offsetU = data.offsetU();
        this.offsetV = data.offsetV();
        this.zoom = data.zoom();
        this.rotation = data.rotation();
        this.alpha = data.alpha();
        this.brightness = data.brightness();
        this.renderDistance = data.renderDistance();
        this.flipX = data.flipX();
        this.flipY = data.flipY();
        this.bothSides = data.renderBothSides();
        this.lit = data.lit();
        this.sizeW = data.sizeW();
        this.sizeH = data.sizeH();
        this.autoSize = data.autoSize();
        this.backing = data.backing();
        this.border = data.border();
        this.tab = data.kind() == FrameData.SourceKind.FILE ? Tab.FILE : Tab.URL;
        Minecraft client = Minecraft.getInstance();
        this.canEdit = client.player != null && frame.canEdit(client.player);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        this.urlWidgets.clear();
        this.fileWidgets.clear();
        this.sizeWidgets.clear();
        this.frameWidgets.clear();
        this.adjustWidgets.clear();
        this.refreshFiles();

        int totalWidth = PANEL_SIZE + 12 + CONTROLS_WIDTH;
        int x0 = (this.width - totalWidth) / 2;
        int y0 = this.height / 2 - 80;
        this.panelX = x0;
        this.panelY = y0 + ROW + 4;
        this.controlsX = x0 + PANEL_SIZE + 12;
        this.contentY = y0 + ROW + 4;

        // Tabs
        addRenderableWidget(Button.builder(Component.translatable("modernframes.tab.url"), b -> setTab(Tab.URL))
                .bounds(this.controlsX, y0, TAB_WIDTH, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modernframes.tab.file"), b -> setTab(Tab.FILE))
                .bounds(this.controlsX + (TAB_WIDTH + TAB_GAP), y0, TAB_WIDTH, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modernframes.tab.size"), b -> setTab(Tab.SIZE))
                .bounds(this.controlsX + (TAB_WIDTH + TAB_GAP) * 2, y0, TAB_WIDTH, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modernframes.tab.frame"), b -> setTab(Tab.FRAME))
                .bounds(this.controlsX + (TAB_WIDTH + TAB_GAP) * 3, y0, TAB_WIDTH, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modernframes.tab.adjust"), b -> setTab(Tab.ADJUST))
                .bounds(this.controlsX + (TAB_WIDTH + TAB_GAP) * 4, y0, TAB_WIDTH, 20).build());

        buildUrlTab();
        buildFileTab();
        buildSizeTab();
        buildFrameTab();
        buildAdjustTab();

        // Save / cancel
        int buttonY = Math.max(this.contentY + 132, panelY + PANEL_SIZE + 10);
        Button save = Button.builder(Component.translatable("modernframes.screen.save"), b -> save())
                .bounds(this.controlsX + CONTROLS_WIDTH - 106, buttonY, 106, 20).build();
        save.active = this.canEdit;
        addRenderableWidget(save);
        addRenderableWidget(Button.builder(Component.translatable("modernframes.screen.cancel"), b -> onClose())
                .bounds(this.controlsX, buttonY, 106, 20).build());

        applyTab();

        // Reopening a configured frame: preload the saved picture into the preview.
        if (!this.source.isBlank() && this.previewEntry == null) {
            startPreview(false);
        }
    }

    private void buildUrlTab() {
        urlBox = new EditBox(this.font, this.controlsX, this.contentY, CONTROLS_WIDTH, 20,
                Component.translatable("modernframes.screen.url_box"));
        urlBox.setMaxLength(FrameData.MAX_SOURCE_LENGTH);
        urlBox.setHint(Component.translatable("modernframes.screen.url_hint"));
        if (this.kind == FrameData.SourceKind.URL) {
            urlBox.setValue(this.source);
        }
        addWidget(urlBox, urlWidgets);

        Button paste = Button.builder(Component.translatable("modernframes.screen.paste"), b ->
                        urlBox.setValue(Minecraft.getInstance().keyboardHandler.getClipboard()))
                .bounds(this.controlsX, this.contentY + ROW, 106, 20).build();
        addWidget(paste, urlWidgets);

        Button load = Button.builder(Component.translatable("modernframes.screen.load"), b -> loadFromUrl())
                .bounds(this.controlsX + CONTROLS_WIDTH - 106, this.contentY + ROW, 106, 20).build();
        addWidget(load, urlWidgets);
    }

    private void buildFileTab() {
        int y = this.contentY;
        Button previous = Button.builder(Component.literal("<"), b -> cycleFile(-1))
                .bounds(this.controlsX, y, 20, 20).build();
        addWidget(previous, fileWidgets);
        Button next = Button.builder(Component.literal(">"), b -> cycleFile(1))
                .bounds(this.controlsX + CONTROLS_WIDTH - 20, y, 20, 20).build();
        addWidget(next, fileWidgets);

        Button openFolder = Button.builder(Component.translatable("modernframes.screen.open_folder"), b -> openSandboxFolder())
                .bounds(this.controlsX, y + ROW, 106, 20).build();
        addWidget(openFolder, fileWidgets);
        Button refresh = Button.builder(Component.translatable("modernframes.screen.refresh"), b -> refreshFiles())
                .bounds(this.controlsX + CONTROLS_WIDTH - 106, y + ROW, 106, 20).build();
        addWidget(refresh, fileWidgets);

        if (ModernFramesConfig.Common.ALLOW_ABSOLUTE_PATHS.get()) {
            pathBox = new EditBox(this.font, this.controlsX, y + ROW * 3, CONTROLS_WIDTH, 20,
                    Component.translatable("modernframes.screen.path_box"));
            pathBox.setMaxLength(FrameData.MAX_SOURCE_LENGTH);
            pathBox.setHint(Component.translatable("modernframes.screen.path_hint"));
            if (this.kind == FrameData.SourceKind.FILE && FrameSources.resolveAbsolute(this.source).isPresent()) {
                pathBox.setValue(this.source);
            }
            addWidget(pathBox, fileWidgets);

            Button usePath = Button.builder(Component.translatable("modernframes.screen.use_path"), b -> useTypedPath())
                    .bounds(this.controlsX, y + ROW * 4, CONTROLS_WIDTH, 20).build();
            addWidget(usePath, fileWidgets);
        }
    }

    private void buildSizeTab() {
        int colA = this.controlsX;
        int colB = this.controlsX + 114;
        int y = this.contentY;
        int maxSize = Math.max(1, ModernFramesConfig.Common.RESIZE_MAX_SIZE.get());

        this.sliderW = new ValueSlider(colA, y, 106, 20, Component.translatable("modernframes.opt.size_w"),
                1.0D, maxSize, "%.0f", this.sizeW, value -> this.sizeW = (int) Math.round(value));
        addWidget(this.sliderW, sizeWidgets);
        this.sliderH = new ValueSlider(colB, y, 106, 20, Component.translatable("modernframes.opt.size_h"),
                1.0D, maxSize, "%.0f", this.sizeH, value -> this.sizeH = (int) Math.round(value));
        addWidget(this.sliderH, sizeWidgets);

        addWidget(makeToggle(this.controlsX, y + 22, CONTROLS_WIDTH, Component.translatable("modernframes.opt.auto_size"),
                () -> this.autoSize, value -> {
                    this.autoSize = value;
                    syncSizeWidgets();
                }), sizeWidgets);

        syncSizeWidgets();
    }

    private void buildFrameTab() {
        int colA = this.controlsX;
        int colB = this.controlsX + 114;
        int y = this.contentY;

        addWidget(makeToggle(colA, y, 106, Component.translatable("modernframes.opt.border"),
                () -> this.border, value -> this.border = value), frameWidgets);
        addWidget(makeToggle(colB, y, 106, Component.translatable("modernframes.opt.backing"),
                () -> this.backing, value -> this.backing = value), frameWidgets);
        y += 22;

        addWidget(makeToggle(colA, y, 106, Component.translatable("modernframes.opt.both_sides"),
                () -> this.bothSides, value -> this.bothSides = value), frameWidgets);
        addWidget(makeToggle(colB, y, 106, Component.translatable("modernframes.opt.lit"),
                () -> this.lit, value -> this.lit = value), frameWidgets);
        y += 22;

        addWidget(new ValueSlider(colA, y, 106, 20, Component.translatable("modernframes.opt.distance"),
                8.0D, 256.0D, "%.0f", this.renderDistance, value -> this.renderDistance = (int) Math.round(value)), frameWidgets);
    }

    private void buildAdjustTab() {
        int colA = this.controlsX;
        int colB = this.controlsX + 114;
        int y = this.contentY;

        this.sliderZoom = new ValueSlider(colA, y, 106, 20, Component.translatable("modernframes.opt.zoom"),
                0.25D, 4.0D, "%.2f", this.zoom, value -> this.zoom = (float) value);
        addWidget(this.sliderZoom, adjustWidgets);
        this.sliderRotation = new ValueSlider(colB, y, 106, 20, Component.translatable("modernframes.opt.rotation"),
                0.0D, 360.0D, "%.0f", this.rotation, value -> this.rotation = (float) value);
        addWidget(this.sliderRotation, adjustWidgets);
        y += 22;

        this.sliderPanU = new ValueSlider(colA, y, 106, 20, Component.translatable("modernframes.opt.offset_u"),
                -1.0D, 1.0D, "%.2f", this.offsetU, value -> this.offsetU = (float) value);
        addWidget(this.sliderPanU, adjustWidgets);
        this.sliderPanV = new ValueSlider(colB, y, 106, 20, Component.translatable("modernframes.opt.offset_v"),
                -1.0D, 1.0D, "%.2f", this.offsetV, value -> this.offsetV = (float) value);
        addWidget(this.sliderPanV, adjustWidgets);
        y += 22;

        this.sliderAlpha = new ValueSlider(colA, y, 106, 20, Component.translatable("modernframes.opt.alpha"),
                0.0D, 255.0D, "%.0f", this.alpha, value -> this.alpha = (int) Math.round(value));
        addWidget(this.sliderAlpha, adjustWidgets);
        this.sliderBrightness = new ValueSlider(colB, y, 106, 20, Component.translatable("modernframes.opt.brightness"),
                0.0D, 255.0D, "%.0f", this.brightness, value -> this.brightness = (int) Math.round(value));
        addWidget(this.sliderBrightness, adjustWidgets);
        y += 22;

        this.flipXButton = makeToggle(colA, y, 106, LABEL_FLIP_X, () -> this.flipX, value -> this.flipX = value);
        addWidget(this.flipXButton, adjustWidgets);
        this.flipYButton = makeToggle(colB, y, 106, LABEL_FLIP_Y, () -> this.flipY, value -> this.flipY = value);
        addWidget(this.flipYButton, adjustWidgets);
        y += 22;

        addWidget(Button.builder(Component.translatable("modernframes.screen.defaults"), b -> resetAdjust())
                .bounds(colA, y, CONTROLS_WIDTH, 20).build(), adjustWidgets);
    }

    private void addWidget(AbstractWidget widget, List<AbstractWidget> group) {
        addRenderableWidget(widget);
        group.add(widget);
    }

    private void setTab(Tab newTab) {
        this.tab = newTab;
        applyTab();
    }

    private void applyTab() {
        for (AbstractWidget widget : this.urlWidgets) {
            widget.visible = this.tab == Tab.URL;
        }
        for (AbstractWidget widget : this.fileWidgets) {
            widget.visible = this.tab == Tab.FILE;
        }
        for (AbstractWidget widget : this.sizeWidgets) {
            widget.visible = this.tab == Tab.SIZE;
        }
        for (AbstractWidget widget : this.frameWidgets) {
            widget.visible = this.tab == Tab.FRAME;
        }
        for (AbstractWidget widget : this.adjustWidgets) {
            widget.visible = this.tab == Tab.ADJUST;
        }
    }

    // ------------------------------------------------------------------
    // Size helpers
    // ------------------------------------------------------------------

    /** Best block size (in blocks) whose window aspect matches the image within the limits. */
    private int[] computeAutoSize(int imageWidth, int imageHeight) {
        int maxSize = Math.max(1, ModernFramesConfig.Common.RESIZE_MAX_SIZE.get());
        double aspect = imageWidth / (double) Math.max(1, imageHeight);
        int bestW = 1;
        int bestH = 1;
        double bestScore = Double.MAX_VALUE;
        for (int w = 1; w <= maxSize; w++) {
            int base = (int) Math.round(w / aspect);
            for (int delta = -1; delta <= 1; delta++) {
                int h = base + delta;
                if (h < 1 || h > maxSize) {
                    continue;
                }
                int[] fit = FrameResizer.fitToLimits(w, h);
                if (fit[0] != w || fit[1] != h) {
                    continue;
                }
                double score = Math.abs(Math.log((w / (double) h) / aspect));
                if (score < bestScore) {
                    bestScore = score;
                    bestW = w;
                    bestH = h;
                }
            }
        }
        return new int[]{bestW, bestH};
    }

    /** Keeps the size widgets in sync with the auto-fit mode. */
    private void syncSizeWidgets() {
        if (this.sliderW == null || this.sliderH == null) {
            return;
        }
        FrameTextureManager.Entry entry = this.previewEntry;
        boolean ready = entry != null && entry.status() == FrameTextureManager.Status.READY
                && entry.width() > 0 && entry.height() > 0;
        this.sliderW.active = !this.autoSize;
        this.sliderH.active = !this.autoSize;
        if (this.autoSize && ready) {
            int[] auto = computeAutoSize(entry.width(), entry.height());
            this.sizeW = auto[0];
            this.sizeH = auto[1];
            if (Math.round(this.sliderW.actualValue()) != auto[0]) {
                this.sliderW.setDoubleValue(auto[0]);
            }
            if (Math.round(this.sliderH.actualValue()) != auto[1]) {
                this.sliderH.setDoubleValue(auto[1]);
            }
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void loadFromUrl() {
        String url = this.urlBox.getValue().trim();
        if (url.isEmpty()) {
            return;
        }
        this.source = url;
        this.kind = FrameData.SourceKind.URL;
        startPreview(true);
    }

    private void useTypedPath() {
        if (this.pathBox == null) {
            return;
        }
        String path = this.pathBox.getValue().trim();
        if (path.isEmpty()) {
            return;
        }
        this.source = path;
        this.kind = FrameData.SourceKind.FILE;
        startPreview(true);
    }

    private void cycleFile(int direction) {
        if (this.localFiles.isEmpty()) {
            this.fileIndex = -1;
            return;
        }
        this.fileIndex = Math.floorMod(this.fileIndex + direction, this.localFiles.size());
        this.source = this.localFiles.get(this.fileIndex);
        this.kind = FrameData.SourceKind.FILE;
        startPreview(true);
    }

    private void refreshFiles() {
        this.localFiles.clear();
        Path dir = FrameSources.sandboxDir();
        FrameSources.ensureDir(dir);
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(FrameSources::isSupportedFileName)
                    .sorted()
                    .forEach(this.localFiles::add);
        } catch (Exception ignored) {
        }
        if (this.kind == FrameData.SourceKind.FILE) {
            this.fileIndex = this.localFiles.indexOf(this.source);
        }
    }

    private void openSandboxFolder() {
        Path dir = FrameSources.sandboxDir();
        FrameSources.ensureDir(dir);
        try {
            Util.getPlatform().openFile(dir.toFile());
        } catch (Throwable ignored) {
        }
    }

    private void startPreview(boolean retryFailed) {
        this.clientError = "";
        FrameData data = currentData();
        String error = FramePolicy.validate(data);
        if (error != null) {
            this.clientError = error;
            this.previewEntry = null;
            return;
        }
        this.previewEntry = FrameTextureManager.get(data, retryFailed);
    }

    private FrameData currentData() {
        return new FrameData(this.source == null ? "" : this.source.trim(), this.kind,
                this.offsetU, this.offsetV, this.zoom, this.rotation,
                this.alpha, this.brightness,
                this.flipX, this.flipY, this.bothSides, this.lit,
                this.renderDistance,
                this.sizeW, this.sizeH, this.autoSize,
                this.backing, this.border);
    }

    private void save() {
        FrameData data = currentData();
        if (this.autoSize && this.previewEntry != null
                && this.previewEntry.status() == FrameTextureManager.Status.READY
                && this.previewEntry.width() > 0 && this.previewEntry.height() > 0) {
            int[] auto = computeAutoSize(this.previewEntry.width(), this.previewEntry.height());
            data = data.withSize(auto[0], auto[1]);
        }
        String error = FramePolicy.validate(data);
        if (error != null) {
            this.clientError = error;
            return;
        }
        PacketDistributor.sendToServer(new SetFramePayload(this.frame.getBlockPos(), data));
        onClose();
    }

    /** Restores every slider and toggle of the Tweaks tab to its default value. */
    private void resetAdjust() {
        this.zoom = 1.0F;
        this.rotation = 0.0F;
        this.offsetU = 0.0F;
        this.offsetV = 0.0F;
        this.alpha = 255;
        this.brightness = 255;
        this.flipX = false;
        this.flipY = false;
        if (this.sliderZoom != null) {
            this.sliderZoom.setDoubleValue(this.zoom);
        }
        if (this.sliderRotation != null) {
            this.sliderRotation.setDoubleValue(this.rotation);
        }
        if (this.sliderPanU != null) {
            this.sliderPanU.setDoubleValue(this.offsetU);
        }
        if (this.sliderPanV != null) {
            this.sliderPanV.setDoubleValue(this.offsetV);
        }
        if (this.sliderAlpha != null) {
            this.sliderAlpha.setDoubleValue(this.alpha);
        }
        if (this.sliderBrightness != null) {
            this.sliderBrightness.setDoubleValue(this.brightness);
        }
        if (this.flipXButton != null) {
            this.flipXButton.setMessage(toggleLabel(LABEL_FLIP_X, this.flipX));
        }
        if (this.flipYButton != null) {
            this.flipYButton.setMessage(toggleLabel(LABEL_FLIP_Y, this.flipY));
        }
    }

    public void onServerResult(FrameResultPayload payload) {
        if (payload.success()) {
            onClose();
        } else {
            this.clientError = payload.message();
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.tab == Tab.SIZE) {
            syncSizeWidgets();
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        // Title
        graphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2, 16, 0xFFFFFF, true);

        // Preview panel
        graphics.fill(this.panelX - 2, this.panelY - 2, this.panelX + PANEL_SIZE + 2, this.panelY + PANEL_SIZE + 2, 0xFF3C3C3C);
        graphics.fill(this.panelX, this.panelY, this.panelX + PANEL_SIZE, this.panelY + PANEL_SIZE, 0xFF101318);
        drawPreview(graphics);

        // Preview status
        Component status = previewStatus();
        if (status != null) {
            graphics.drawString(this.font, status, this.panelX + (PANEL_SIZE - this.font.width(status)) / 2,
                    this.panelY + PANEL_SIZE + 8, 0xAAAAAA, false);
        }

        // Tab specific hints
        if (this.tab == Tab.URL) {
            graphics.drawString(this.font, Component.translatable("modernframes.screen.url_note"),
                    this.controlsX, this.contentY + ROW * 2 + 6, 0x888888, false);
        } else if (this.tab == Tab.FILE) {
            Component fileName = this.fileIndex >= 0 && this.fileIndex < this.localFiles.size()
                    ? Component.literal(this.localFiles.get(this.fileIndex))
                    : Component.translatable("modernframes.screen.no_files");
            graphics.drawString(this.font, fileName, this.controlsX + 24, this.contentY + 6, 0xFFFFFF, false);
            graphics.drawString(this.font, Component.translatable("modernframes.screen.sandbox_note"),
                    this.controlsX, this.contentY + ROW * 2 + 6, 0x888888, false);
        } else if (this.tab == Tab.SIZE) {
            FrameTextureManager.Entry entry = this.previewEntry;
            Component line;
            if (this.autoSize && entry != null && entry.status() == FrameTextureManager.Status.READY
                    && entry.width() > 0 && entry.height() > 0) {
                int[] auto = computeAutoSize(entry.width(), entry.height());
                line = Component.translatable("modernframes.screen.auto_size_line", auto[0], auto[1]);
            } else {
                line = Component.translatable("modernframes.screen.size_manual_line", this.sizeW, this.sizeH);
            }
            graphics.drawString(this.font, line, this.controlsX, this.contentY + 52, 0xCCCCCC, false);
            graphics.drawString(this.font, Component.translatable("modernframes.screen.size_hint"),
                    this.controlsX, this.contentY + 66, 0x888888, false);
            graphics.drawString(this.font, Component.translatable("modernframes.screen.size_hint2"),
                    this.controlsX, this.contentY + 78, 0x888888, false);
        } else if (this.tab == Tab.FRAME) {
            graphics.drawString(this.font, Component.translatable("modernframes.screen.backing_note"),
                    this.controlsX, this.contentY + 70, 0x888888, false);
        }

        // Error / feedback line
        if (!this.clientError.isEmpty()) {
            Component error = this.clientError.startsWith("modernframes.")
                    ? Component.translatable(this.clientError)
                    : Component.literal(this.clientError);
            graphics.drawString(this.font, error, this.controlsX, this.contentY + 132 + 8, 0xFF5555, false);
        }
        if (!this.canEdit) {
            graphics.drawString(this.font, Component.translatable("modernframes.screen.read_only"),
                    this.controlsX, this.contentY + 132 + 20, 0xFFAA55, false);
        }
    }

    private Component previewStatus() {
        if (!this.clientError.isEmpty()) {
            return null;
        }
        FrameTextureManager.Entry entry = this.previewEntry;
        if (entry == null) {
            return Component.translatable("modernframes.screen.no_preview");
        }
        return switch (entry.status()) {
            case LOADING -> Component.translatable("modernframes.screen.loading");
            case FAILED -> entry.error().startsWith("modernframes.")
                    ? Component.translatable(entry.error())
                    : Component.literal(entry.error());
            case READY -> Component.literal(entry.width() + " x " + entry.height());
        };
    }

    private void drawPreview(GuiGraphics graphics) {
        FrameTextureManager.Entry entry = this.previewEntry;
        if (entry == null || entry.status() != FrameTextureManager.Status.READY || entry.textureLocation() == null) {
            return;
        }
        int texWidth = entry.width();
        int texHeight = entry.height();
        if (texWidth <= 0 || texHeight <= 0) {
            return;
        }

        int effW = this.sizeW;
        int effH = this.sizeH;
        if (this.autoSize) {
            int[] auto = computeAutoSize(texWidth, texHeight);
            effW = auto[0];
            effH = auto[1];
        }

        float panelAspect = (effW - 0.125F) / (effH - 0.125F);
        float aspect = texWidth / (float) texHeight;
        float fitW = aspect >= panelAspect ? 1.0F : aspect / panelAspect;
        float fitH = aspect >= panelAspect ? panelAspect / aspect : 1.0F;
        float zoomClamped = Mth.clamp(this.zoom, 0.125F, 8.0F);
        float halfU = fitW / (2.0F * zoomClamped);
        float halfV = fitH / (2.0F * zoomClamped);
        float cu = 0.5F + Mth.clamp(this.offsetU, -1.0F, 1.0F) * (0.5F - halfU);
        float cv = 0.5F + Mth.clamp(this.offsetV, -1.0F, 1.0F) * (0.5F - halfV);

        float u0 = Mth.clamp((cu - halfU) * texWidth, 0.0F, texWidth);
        float v0 = Mth.clamp((cv - halfV) * texHeight, 0.0F, texHeight);
        float u1 = Mth.clamp((cu + halfU) * texWidth, 0.0F, texWidth);
        float v1 = Mth.clamp((cv + halfV) * texHeight, 0.0F, texHeight);

        ResourceLocation texture = entry.textureLocation();
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(this.panelX + PANEL_SIZE / 2.0F, this.panelY + PANEL_SIZE / 2.0F, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(this.rotation));
        pose.scale(this.flipX ? -1.0F : 1.0F, this.flipY ? -1.0F : 1.0F, 1.0F);
        graphics.blit(texture, -PANEL_SIZE / 2, -PANEL_SIZE / 2, PANEL_SIZE, PANEL_SIZE,
                u0, v0, Math.max(1, Math.round(u1 - u0)), Math.max(1, Math.round(v1 - v0)),
                texWidth, texHeight);
        pose.popPose();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.urlBox != null && this.urlBox.isFocused() && this.tab == Tab.URL) {
                loadFromUrl();
                return true;
            }
            if (this.pathBox != null && this.pathBox.isFocused() && this.tab == Tab.FILE) {
                useTypedPath();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    private Button makeToggle(int x, int y, int width, Component label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Button.builder(toggleLabel(label, getter.get()), button -> {
            setter.accept(!getter.get());
            button.setMessage(toggleLabel(label, getter.get()));
        }).bounds(x, y, width, 20).build();
    }

    private static Component toggleLabel(Component label, boolean on) {
        return Component.translatable("modernframes.screen.toggle", label,
                Component.translatable(on ? "options.on" : "options.off"));
    }

    private final class ValueSlider extends AbstractSliderButton {
        private final Component prefix;
        private final double min;
        private final double max;
        private final String format;
        private final DoubleConsumer setter;

        ValueSlider(int x, int y, int width, int height, Component prefix,
                    double min, double max, String format, double initial, DoubleConsumer setter) {
            super(x, y, width, height, Component.empty(),
                    max > min ? (initial - min) / (max - min) : 0.0D);
            this.prefix = prefix;
            this.min = min;
            this.max = max;
            this.format = format;
            this.setter = setter;
            this.updateMessage();
        }

        private double actualValue() {
            return this.min + (this.max - this.min) * this.value;
        }

        /** Moves the slider without notifying the setter (used by the auto-fit logic). */
        void setDoubleValue(double actual) {
            double range = this.max - this.min;
            this.value = range > 0.0D ? Mth.clamp((actual - this.min) / range, 0.0D, 1.0D) : 0.0D;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.prefix.getString() + ": "
                    + String.format(Locale.ROOT, this.format, this.actualValue())));
        }

        @Override
        protected void applyValue() {
            if (this.setter != null) {
                this.setter.accept(this.actualValue());
            }
        }
    }
}
