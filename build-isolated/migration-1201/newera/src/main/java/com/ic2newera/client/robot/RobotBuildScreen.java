package com.ic2newera.client.robot;

import com.ic2newera.client.robot.blueprint.BlueprintClientBridge;
import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiScissor;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.robot.building.RobotBuildPackets;
import com.ic2newera.robot.building.RobotBuildPlan;
import com.ic2newera.robot.building.RobotBuildService;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Text construction and imported blueprint preview, shared by local and phone control. */
public final class RobotBuildScreen extends Screen {
    private static final long MAX_IMPORT_BYTES = 8L * 1024L * 1024L;
    private static final int FILE_ROWS = 8;
    private static final List<String> BLUEPRINT_EXTENSIONS = List.of("nbt", "schem", "litematic", "bbmodel", "json", "png", "jpg", "jpeg");

    private final Screen parent;
    private final UUID robot;
    private final boolean remote;
    private final String character;
    private UUID proposal = RobotBuildService.NONE, request;
    private String draft = "建一间9x7的木屋，有玻璃窗和门";
    private String report = "输入房屋或生产线描述，例如“搭建锡生产线，用低压太阳能和玻璃纤维电缆供电”。已有场站可描述换线或改产；先生成方案，核对保留/改动的设备、材料和供电后再开始。";
    private String status = "";
    private EditBox description;
    private Button generate, aiCreate, start, pause, resume, cancel, refresh;
    private Button textTab, blueprintTab, openInbox, refreshInbox, importSelected, workerMinus, workerPlus;
    private final List<Button> fileButtons = new ArrayList<>();
    private final List<Path> inboxFiles = new ArrayList<>();
    private RobotBuildPlan previewPlan;
    private RobotBuildPlan cachedPreviewPlan;
    private List<ProjectedCube> projectedCubes = List.of();
    private int cachedPreviewWidth = -1, cachedPreviewHeight = -1;
    private float cachedPreviewRotation = Float.NaN, cachedPreviewZoom = Float.NaN;
    private Path selectedFile;
    private int left, panelWidth, bottom, scroll, ticks, workers = 1, fileScroll, fileRowTop, fileRowsVisible;
    private int previewLeft, previewTop, previewRight, previewBottom;
    private float previewZoom = 3.5F, previewRotation = 28F;
    private boolean hasTask, running, cancelArmed, blueprintTabSelected, draggingPreview, planning, compact;
    private long requestedAt;

    public RobotBuildScreen(Screen parent, UUID robot, String character, boolean remote) {
        super(Component.literal(character + " · 建造与生产线"));
        this.parent = parent;
        this.robot = robot;
        this.character = character;
        this.remote = remote;
    }

    @Override protected void init() {
        int cursor = description == null ? draft.length() : description.getCursorPosition();
        if (description != null) draft = description.getValue();
        compact = compactLayout();
        panelWidth = Math.min(900, Math.max(100, width - 24));
        left = (width - panelWidth) / 2;
        bottom = Math.max(compact ? 98 : 88, height - (compact ? 110 : 126));

        int tabGap = 6, tabWidth = (panelWidth - tabGap) / 2;
        int tabY = compact ? 24 : 38;
        textTab = addRenderableWidget(new UiButton(left, tabY, tabWidth, 20, Component.literal("文字建造"), b -> switchTab(false)));
        blueprintTab = addRenderableWidget(new UiButton(left + tabWidth + tabGap, tabY, tabWidth, 20,
                Component.literal("文件图纸 / 图片"), b -> switchTab(true)));
        int workerY = compact ? 46 : 61;
        workerMinus = addRenderableWidget(new UiButton(left, workerY, 26, 18, Component.literal("−"), b -> changeWorkers(-1)));
        workerPlus = addRenderableWidget(new UiButton(left + 26 + 4, workerY, 26, 18, Component.literal("+"), b -> changeWorkers(1)));

        description = addRenderableWidget(new EditBox(font, left, height - 82, panelWidth, 20, Component.literal("房屋或生产线描述")));
        description.setMaxLength(1200);
        description.setValue(draft);
        description.moveCursorTo(cursor);

        int cell5 = (panelWidth - 16) / 5;
        generate = rowButton(0, 0, cell5, "生成方案", b -> send(RobotBuildService.PREVIEW));
        aiCreate = rowButton(1, 0, cell5, "AI创作", b -> planTextLocally());
        start = rowButton(2, 0, cell5, "开始施工", b -> send(RobotBuildService.START));
        pause = rowButton(3, 0, cell5, "暂停作业", b -> send(RobotBuildService.PAUSE));
        resume = rowButton(4, 0, cell5, "继续作业", b -> send(RobotBuildService.RESUME));
        int cell4 = (panelWidth - 12) / 4;
        cancel = rowButton(0, 1, cell4, "取消任务", b -> {
            if (cancelArmed) { send(RobotBuildService.CANCEL); cancelArmed = false; }
            else { cancelArmed = true; status = "再点一次取消：停止任务，已放建筑和机器保留。"; }
            updateButtons();
        });
        refresh = rowButton(1, 1, cell4, "检查与进度", b -> send(RobotBuildService.STATUS));
        rowButton(2, 1, cell4, "复制清单", b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(report);
            status = "已复制当前显示的施工清单。";
        });
        rowButton(3, 1, cell4, "返回", b -> onClose());

        int folderY = compact ? 67 : 87;
        openInbox = addRenderableWidget(new UiButton(left + 6, folderY, 104, 19, Component.literal("打开图纸目录"), b -> openInbox(),
                Component.literal("当前游戏目录下的 IC2-NewEra-blueprints/inbox")));
        refreshInbox = addRenderableWidget(new UiButton(left + 114, folderY, 58, 19, Component.literal("刷新"), b -> refreshInbox()));
        int listWidth = Math.min(245, Math.max(150, panelWidth / 3));
        fileRowTop = compact ? 91 : 128;
        fileRowsVisible = Math.max(1, Math.min(FILE_ROWS, Math.max(0, bottom - fileRowTop) / 20));
        fileButtons.clear();
        for (int i = 0; i < FILE_ROWS; i++) {
            final int row = i;
            Button button = addRenderableWidget(new UiButton(left + 6, fileRowTop + i * 20, listWidth, 18,
                    Component.literal(""), b -> selectFileRow(row)));
            button.visible = !compact || i < fileRowsVisible;
            fileButtons.add(button);
        }
        importSelected = addRenderableWidget(new UiButton(left, height - 56, cell5, 20,
                Component.literal("解析并预览"), b -> processSelectedFile()));

        if (blueprintTabSelected) refreshInbox();
        updateButtons();
        if (request == null && ticks == 0 && !blueprintTabSelected) send(RobotBuildService.STATUS);
    }

    private Button rowButton(int col, int row, int cell, String label, Button.OnPress press) {
        return addRenderableWidget(new UiButton(left + col * (cell + 4), height - 56 + row * 24,
                col == 4 ? panelWidth - col * (cell + 4) : cell, 20, Component.literal(label), press));
    }

    private void switchTab(boolean blueprint) {
        if (description != null) draft = description.getValue();
        blueprintTabSelected = blueprint;
        rebuildWidgets();
    }

    private void changeWorkers(int delta) {
        workers = Math.max(1, Math.min(8, workers + delta));
        updateButtons();
    }

    private void send(int action) {
        if (request != null) return;
        cancelArmed = false;
        if (action == RobotBuildService.PREVIEW) { proposal = RobotBuildService.NONE; previewPlan = null; scroll = 0; }
        request = UUID.randomUUID();
        requestedAt = System.currentTimeMillis();
        status = "正在由服务器校验……";
        RobotBuildPackets.CHANNEL.sendToServer(new RobotBuildPackets.Request(
                robot, request, remote, action, proposal, description.getValue(), workers));
        updateButtons();
    }

    public static void receive(RobotBuildPackets.Response response) {
        if (!(Minecraft.getInstance().screen instanceof RobotBuildScreen screen)
                || !screen.robot.equals(response.robot()) || !response.ticket().equals(screen.request)) return;
        screen.request = null;
        screen.cancelArmed = false;
        screen.proposal = response.proposal();
        screen.report = response.report();
        screen.previewPlan = response.plan();
        screen.hasTask = response.hasTask();
        screen.running = response.running();
        screen.status = response.accepted()
                ? (response.total() > 0 ? "作业进度 " + response.done() + "/" + response.total() + "；" : "") + "结果已更新。"
                : "未执行；请查看说明。";
        screen.scroll = 0;
        screen.updateButtons();
    }

    private void planTextLocally() {
        if (!ensureBridge()) return;
        proposal = RobotBuildService.NONE;
        previewPlan = null;
        report = "正在调用本机已配置的AI规划并请求服务器核对……";
        status = "AI文字创作只在点击此按钮时运行；最终方块计划仍由服务器校验。";
        planning = true;
        try {
            BlueprintClientBridge.getProvider().planTextLocally(robot, remote, workers, description.getValue(),
                    this::postStatus, this::postResult);
        } catch (RuntimeException error) {
            planning = false; status = "本机AI创作启动失败：" + error.getMessage();
        }
        updateButtons();
    }

    private void processSelectedFile() {
        if (selectedFile == null) { status = "请先从图纸目录选择文件。"; return; }
        try {
            selectedFile = validateSelectedFile(selectedFile);
        } catch (IOException | SecurityException error) {
            selectedFile = null;
            status = "文件已变化或不安全，已拒绝读取：" + error.getMessage();
            refreshInbox();
            return;
        }
        if (!ensureBridge()) return;
        String ext = extension(selectedFile);
        proposal = RobotBuildService.NONE;
        previewPlan = null;
        planning = true;
        if (isImage(ext)) {
            report = "图片只会提交给本机已配置的视觉模型规划；图片本身不会上传游戏服务器。";
            status = "正在调用你配置的视觉AI……";
            try {
                BlueprintClientBridge.getProvider().planImageLocally(selectedFile, robot, remote, workers,
                        description.getValue(), this::postStatus, this::postResult);
            } catch (RuntimeException error) {
                planning = false; status = "视觉AI规划启动失败：" + error.getMessage();
            }
        } else {
            report = "正在解析图纸并将施工计划提交服务器核对……";
            status = "正在上传并解析图纸……";
            try {
                BlueprintClientBridge.getProvider().importFile(selectedFile, robot, remote, workers,
                        description.getValue(), this::postStatus, this::postResult);
            } catch (RuntimeException error) {
                planning = false; status = "图纸解析启动失败：" + error.getMessage();
            }
        }
        updateButtons();
    }

    private boolean ensureBridge() {
        if (BlueprintClientBridge.getProvider() != null) return true;
        status = "客户端图纸服务尚未就绪，请重开界面后再试。";
        return false;
    }

    private void postStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != this) return;
            status = text == null ? "" : text;
        });
    }

    private void postResult(BlueprintClientBridge.Result result) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != this) return;
            planning = false;
            if (result == null || !result.success()) {
                status = result == null ? "规划没有返回结果。" : result.report();
                report = status;
                proposal = RobotBuildService.NONE;
                previewPlan = null;
            } else {
                proposal = result.proposal();
                previewPlan = result.plan();
                report = result.report();
                status = "服务器已返回施工预览；核对材料和构件后再开始。";
                scroll = 0;
            }
            updateButtons();
        });
    }

    private void updateButtons() {
        if (generate == null) return;
        boolean ready = request == null;
        textTab.active = blueprintTabSelected;
        blueprintTab.active = !blueprintTabSelected;
        boolean hasDraft = description != null && !description.getValue().isBlank();
        generate.visible = !blueprintTabSelected;
        aiCreate.visible = !blueprintTabSelected;
        start.visible = true;
        pause.visible = true;
        resume.visible = true;
        cancel.visible = true;
        refresh.visible = true;
        openInbox.visible = blueprintTabSelected;
        refreshInbox.visible = blueprintTabSelected;
        importSelected.visible = blueprintTabSelected;
        workerMinus.visible = workerPlus.visible = true;
        for (Button fileButton : fileButtons) fileButton.visible = blueprintTabSelected;
        generate.active = ready && !planning && hasDraft;
        aiCreate.active = ready && !planning && hasDraft && BlueprintClientBridge.getProvider() != null;
        start.active = ready && !planning && !proposal.equals(RobotBuildService.NONE);
        pause.active = ready && !planning && hasTask && running;
        resume.active = ready && !planning && hasTask && !running;
        cancel.active = ready && !planning && (hasTask || !proposal.equals(RobotBuildService.NONE));
        cancel.setMessage(Component.literal(cancelArmed ? "确定取消" : "取消任务"));
        refresh.active = ready;
        boolean workerLocked = planning || !proposal.equals(RobotBuildService.NONE);
        workerMinus.active = workers > 1 && !workerLocked;
        workerPlus.active = workers < 8 && !workerLocked;
        importSelected.active = ready && !planning && selectedFile != null && BlueprintClientBridge.getProvider() != null;
        refreshFileButtons();
    }

    private void refreshFileButtons() {
        for (int row = 0; row < fileButtons.size(); row++) {
            int index = fileScroll + row;
            Button button = fileButtons.get(row);
            button.visible = blueprintTabSelected && row < fileRowsVisible;
            if (index >= inboxFiles.size()) {
                button.active = false;
                button.setMessage(Component.literal(""));
                continue;
            }
            Path path = inboxFiles.get(index);
            button.active = true;
            String name = path.getFileName().toString();
            if (name.length() > 30) name = name.substring(0, 27) + "…";
            button.setMessage(Component.literal((path.equals(selectedFile) ? "› " : "") + name));
        }
    }

    private boolean compactLayout() {
        return width < 600 || height < 400;
    }

    private void selectFileRow(int row) {
        int index = fileScroll + row;
        if (index < 0 || index >= inboxFiles.size()) return;
        selectedFile = inboxFiles.get(index);
        String ext = extension(selectedFile);
        status = isImage(ext)
                ? "点击“解析并预览”后图片发送至你配置的视觉AI；不会发给游戏服务器。"
                : "已选择图纸。点击“解析并预览”后上传并由服务器校验施工计划。";
        refreshFileButtons();
        updateButtons();
    }

    @Override public void tick() {
        if (description != null) description.tick();
        ticks++;
        if (request != null && System.currentTimeMillis() - requestedAt > 15000) {
            request = null;
            status = "暂未收到回复；刷新状态后再操作，避免重复施工。";
        }
        updateButtons();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        poseTitle(graphics);
        if (compact) {
            graphics.drawString(font, UiText.fit(font, Component.literal("施工人数 " + workers + " / 8"), 100), left + 60, 50, 0xB9D5D9);
        } else {
            graphics.drawString(font, UiText.fit(font, Component.literal("施工人数：" + workers + "（1人单独作业，2–8人协作）"), panelWidth - 70), left + 60, 65, 0xB9D5D9);
            graphics.drawString(font, UiText.fit(font, Component.literal(remote ? "手机远程建造" : "现场建造"), 130),
                    left + panelWidth - 132, 65, 0xAAC8CE);
        }
        if (blueprintTabSelected) renderBlueprintPage(graphics, mouseX, mouseY);
        else renderTextPage(graphics, mouseX, mouseY);
        graphics.drawString(font, UiText.fit(font, Component.literal(status), panelWidth), left, height - 106, 0xBDE1DA);
        graphics.drawString(font, Component.literal("描述："), left, height - 94, 0xCAD7DB);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= height - 109 && mouseY < height - 96)
            graphics.renderTooltip(font, font.split(Component.literal(status), Math.min(300, panelWidth)), mouseX, mouseY);
    }

    private void poseTitle(GuiGraphics graphics) {
        graphics.drawString(font, UiText.fit(font, title, panelWidth), left, compact ? 7 : 12, 0xF2F5E9);
        if (!compact) graphics.drawString(font, UiText.fit(font, Component.literal("先看真实构件、材料与供电，再确认施工。"), panelWidth), left, 27, 0xAFCACF);
    }

    private void renderTextPage(GuiGraphics graphics, int mouseX, int mouseY) {
        int top = compact ? 68 : 85;
        int reportRight = previewPlan == null ? left + panelWidth : left + panelWidth - 250;
        int previewX = left + panelWidth - 244;
        graphics.fill(left - 4, top - 3, reportRight - 3, bottom + 3, 0xBB152332);
        drawReport(graphics, left, top, reportRight - left - 8, bottom - top, report);
        if (previewPlan != null) {
            graphics.fill(previewX, top - 3, left + panelWidth + 4, bottom + 3, 0xBB152332);
            graphics.drawString(font, UiText.fit(font, Component.literal(previewPlan.title() + " · " + previewPlan.parts().size() + " 个实际构件"), 228),
                    previewX + 8, top + 4, 0xDCE8E5);
            drawBlockPreview(graphics, previewPlan, previewX + 5, top + 17,
                    left + panelWidth - 5, bottom - 5);
        } else {
            graphics.fill(previewX, top - 3, left + panelWidth + 4, bottom + 3, 0xBB152332);
            graphics.drawString(font, "方块布局预览", previewX + 8, top + 8, 0xDCE8E5);
            graphics.drawWordWrap(font, Component.literal("方案返回实际RobotBuildPlan后在此显示。鼠标拖动旋转，滚轮缩放。"),
                    previewX + 8, top + 28, 220, 0xAFCACF);
        }
    }

    private void renderBlueprintPage(GuiGraphics graphics, int mouseX, int mouseY) {
        int listWidth = Math.min(245, Math.max(150, panelWidth / 3));
        int paneLeft = left + listWidth + 18;
        previewLeft = paneLeft + 6;
        int paneTop = compact ? 66 : 105;
        previewTop = compact ? 89 : 110;
        previewRight = left + panelWidth - 6;
        previewBottom = compact ? bottom : bottom - 82;
        graphics.fill(left - 4, compact ? fileRowTop - 2 : 83, left + listWidth + 12, bottom + 3, 0xBB152332);
        if (!compact) {
            graphics.drawString(font, UiText.fit(font, Component.literal("目录：IC2-NewEra-blueprints/inbox"), listWidth), left + 6, 109, 0xAFCACF);
            graphics.drawString(font, "支持 NBT / SCHEM / LITEMATIC / BBMODEL / PNG / JPG；单个文件最大8 MB。", paneLeft + 4, 88, 0xCAD7DB);
        }
        if (inboxFiles.isEmpty() && !compact) {
            graphics.drawWordWrap(font, Component.literal("目录为空。打开目录复制文件，或直接拖拽到窗口。"),
                    left + 7, 322, listWidth, 0xAFCACF);
        }
        graphics.fill(paneLeft, paneTop, previewRight + 5, bottom + 3, 0xBB152332);
        if (selectedFile != null) {
            graphics.drawString(font, UiText.fit(font, Component.literal("已选：" + selectedFile.getFileName()), previewRight - paneLeft), paneLeft + 8, compact ? 68 : 108, 0xE8E8CF);
            String ext = extension(selectedFile);
            if (!compact) {
                if (isImage(ext)) graphics.drawString(font, "图片发送至已配置的视觉AI；游戏服务器只收方块计划。", paneLeft + 8, 123, 0xD5C99B);
                else graphics.drawString(font, "预览显示施工方块位置及近似颜色，细节以游戏实物为准。", paneLeft + 8, 123, 0xB9D5D9);
            }
        } else {
            graphics.drawString(font, "选中图纸或参考图片", paneLeft + 8, compact ? 68 : 110, 0xE8E8CF);
        }

        if (previewPlan != null) {
            int previewContentTop = compact ? previewTop + 2 : previewTop + 34;
            drawBlockPreview(graphics, previewPlan, previewLeft, previewContentTop, previewRight, previewBottom);
            if (!compact) {
                graphics.drawString(font, UiText.fit(font, Component.literal(previewPlan.title() + " · " + previewPlan.parts().size() + " 个实际构件"), previewRight - previewLeft),
                        previewLeft + 4, previewTop + 3, 0xDCE8E5);
                int reportY = previewBottom + 6;
                drawReport(graphics, previewLeft + 4, reportY, previewRight - previewLeft - 8,
                        Math.max(32, bottom - reportY - 28), report);
            }
        } else {
            if (compact) graphics.drawString(font, "实际方块预览显示在此", previewLeft + 8, previewTop + 8, 0xB9C9CD);
            else graphics.drawWordWrap(font, Component.literal("导入后将按服务器返回的实际RobotBuildPlan绘制方块示意。鼠标拖动可旋转，滚轮可缩放。不会用图片马赛克伪装立体施工方案。"),
                    previewLeft + 10, previewTop + 44, previewRight - previewLeft - 20, 0xB9C9CD);
        }
    }

    private void drawReport(GuiGraphics graphics, int x, int y, int width, int height, String text) {
        var lines = font.split(Component.literal(text == null ? "" : text), Math.max(40, width));
        int visible = Math.max(0, height) / (font.lineHeight + 3);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        if (height <= 0) return;
        UiScissor.enable(minecraft, x, y, x + width, y + height);
        for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++)
            graphics.drawString(font, lines.get(i), x + 4, y + (i - scroll) * (font.lineHeight + 3), 0xE5EBEF);
        RenderSystem.disableScissor();
    }

    private void drawBlockPreview(GuiGraphics graphics, RobotBuildPlan plan, int x0, int y0, int x1, int y1) {
        int w = x1 - x0, h = y1 - y0;
        UiScissor.enable(minecraft, x0, y0, x1, y1);
        graphics.fill(x0, y0, x1, y1, 0xC816202A);
        int floorY = y1 - 16;
        graphics.fill(x0 + 4, floorY, x1 - 4, floorY + 1, 0x665A7679);
        ensureProjection(plan, w, h);
        drawProjectedCubes(graphics, (x0 + x1) * 0.5F, y1 - 18F);
        graphics.drawString(font, "拖动旋转 · 滚轮缩放", x0 + 6, y0 + 5, 0xAFCACF);
        RenderSystem.disableScissor();
    }

    private record ProjectedCube(float centerX, float topY, float size, int color) { }

    private void ensureProjection(RobotBuildPlan plan, int width, int height) {
        if (plan == cachedPreviewPlan && width == cachedPreviewWidth && height == cachedPreviewHeight
                && Float.compare(previewRotation, cachedPreviewRotation) == 0
                && Float.compare(previewZoom, cachedPreviewZoom) == 0) return;
        List<PreviewCube> cubes = new ArrayList<>(plan.parts().size());
        float radians = (float) Math.toRadians(previewRotation);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float maxX = 0, maxZ = 0, maxY = 0;
        for (RobotBuildPlan.Part part : plan.parts()) {
            float rx = part.offset().getX() * cos - part.offset().getZ() * sin;
            float rz = part.offset().getX() * sin + part.offset().getZ() * cos;
            maxX = Math.max(maxX, Math.abs(rx)); maxZ = Math.max(maxZ, Math.abs(rz)); maxY = Math.max(maxY, part.offset().getY());
            cubes.add(new PreviewCube(part, rx, rz, baseColor(part)));
        }
        float fitX = (width - 28F) / Math.max(1F, (maxX + maxZ + 2F) * 2F);
        float fitY = (height - 36F) / Math.max(1F, maxY + (maxX + maxZ + 2F) * 0.55F);
        float scale = Math.max(1.2F, Math.min(previewZoom, Math.min(fitX, fitY)));
        cubes.sort(Comparator.comparingDouble((PreviewCube cube) -> cube.rx + cube.rz)
                .thenComparingInt(cube -> cube.part.offset().getY()));
        List<ProjectedCube> projected = new ArrayList<>(cubes.size());
        for (PreviewCube cube : cubes) {
            float centerX = (cube.rx - cube.rz) * scale;
            float topY = -cube.part.offset().getY() * scale * 1.45F - (cube.rx + cube.rz) * scale * 0.5F;
            projected.add(new ProjectedCube(centerX, topY, scale, cube.color));
        }
        projectedCubes = List.copyOf(projected);
        cachedPreviewPlan = plan;
        cachedPreviewWidth = width;
        cachedPreviewHeight = height;
        cachedPreviewRotation = previewRotation;
        cachedPreviewZoom = previewZoom;
    }

    private record PreviewCube(RobotBuildPlan.Part part, float rx, float rz, int color) { }

    /** Projects every actual plan part once per rotation/zoom/size change, then emits a single GUI batch. */
    private void drawProjectedCubes(GuiGraphics graphics, float centerX, float baseY) {
        if (projectedCubes.isEmpty()) return;
        graphics.flush();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = graphics.pose().last().pose();
        for (ProjectedCube cube : projectedCubes) {
            float cx = centerX + cube.centerX;
            float topY = baseY + cube.topY;
            float size = cube.size, halfH = size * 0.5F, depth = size * 1.45F;
            emitQuad(builder, matrix, shade(cube.color, 0.68F),
                    cx, topY + halfH, cx - size, topY,
                    cx - size, topY + depth, cx, topY + halfH + depth);
            emitQuad(builder, matrix, shade(cube.color, 0.50F),
                    cx + size, topY, cx, topY + halfH,
                    cx, topY + halfH + depth, cx + size, topY + depth);
            emitQuad(builder, matrix, shade(cube.color, 1.12F),
                    cx, topY - halfH, cx + size, topY,
                    cx, topY + halfH, cx - size, topY);
        }
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
    }

    private static void emitQuad(BufferBuilder builder, Matrix4f matrix, int color,
                                 float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        emitTriangle(builder, matrix, ax, ay, bx, by, cx, cy, color);
        emitTriangle(builder, matrix, ax, ay, cx, cy, dx, dy, color);
    }

    private static void emitTriangle(BufferBuilder builder, Matrix4f matrix,
                                    float ax, float ay, float bx, float by, float cx, float cy, int color) {
        float red = ((color >>> 16) & 255) / 255F;
        float green = ((color >>> 8) & 255) / 255F;
        float blue = (color & 255) / 255F;
        builder.vertex(matrix, ax, ay, 0).color(red, green, blue, 1F).endVertex();
        builder.vertex(matrix, bx, by, 0).color(red, green, blue, 1F).endVertex();
        builder.vertex(matrix, cx, cy, 0).color(red, green, blue, 1F).endVertex();
    }

    private int baseColor(RobotBuildPlan.Part part) {
        try {
            BlockState state = part.state();
            MapColor mapColor = state.getMapColor(minecraft.level, BlockPos.ZERO);
            return mapColor == null || mapColor.col == 0 ? colorFromId(part.blockId()) : mapColor.col;
        } catch (RuntimeException ignored) {
            return colorFromId(part.blockId());
        }
    }

    private static int colorFromId(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) return 0x9AA4A8;
        int hash = id.getPath().hashCode() ^ id.getNamespace().hashCode();
        int r = 80 + (hash & 0x7F), g = 80 + ((hash >>> 8) & 0x7F), b = 80 + ((hash >>> 16) & 0x7F);
        return (r << 16) | (g << 8) | b;
    }

    private static int shade(int color, float factor) {
        int red = Math.min(255, Math.max(0, (int) (((color >>> 16) & 255) * factor)));
        int green = Math.min(255, Math.max(0, (int) (((color >>> 8) & 255) * factor)));
        int blue = Math.min(255, Math.max(0, (int) ((color & 255) * factor)));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (blueprintTabSelected && mouseX >= previewLeft && mouseX <= previewRight && mouseY >= previewTop && mouseY <= previewBottom
                || !blueprintTabSelected && previewPlan != null && mouseX >= left + panelWidth - 250 && mouseX <= left + panelWidth
                && mouseY >= (compact ? 68 : 85) && mouseY <= bottom) {
            previewZoom = Math.max(1.5F, Math.min(12F, previewZoom + (float) Math.signum(delta) * 0.35F));
            return true;
        }
        if (blueprintTabSelected && mouseX >= left && mouseX < left + Math.min(245, Math.max(150, panelWidth / 3)) + 14
                && mouseY >= fileRowTop && mouseY < fileRowTop + fileRowsVisible * 20) {
            fileScroll = Math.max(0, Math.min(Math.max(0, inboxFiles.size() - fileRowsVisible), fileScroll - (int) Math.signum(delta)));
            refreshFileButtons();
            return true;
        }
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= (compact ? 68 : 85) && mouseY < bottom) {
            scroll -= (int) Math.signum(delta) * 3;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inPreview(mouseX, mouseY)) {
            draggingPreview = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragDeltaX, double dragDeltaY) {
        if (draggingPreview && button == 0) {
            previewRotation = (previewRotation + (float) dragDeltaX * 1.1F) % 360F;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragDeltaX, dragDeltaY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingPreview) { draggingPreview = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean inPreview(double x, double y) {
        if (blueprintTabSelected) return x >= previewLeft && x <= previewRight && y >= previewTop && y <= previewBottom;
        return previewPlan != null && x >= left + panelWidth - 250 && x <= left + panelWidth
                && y >= (compact ? 68 : 85) && y <= bottom;
    }

    @Override public void onFilesDrop(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        int before = inboxFiles.size();
        for (Path path : paths) copyDroppedFile(path);
        refreshInbox();
        if (selectedFile != null) fileScroll = Math.max(0, inboxFiles.indexOf(selectedFile) - fileRowsVisible + 1);
        int added = Math.max(0, inboxFiles.size() - before);
        status = "拖拽处理完成：新增 " + added + " 个文件。" + (added < paths.size() ? "超限、格式不支持、符号链接或同名文件已拒绝。" : "");
        updateButtons();
    }

    private void copyDroppedFile(Path source) {
        try {
            if (source == null || Files.isSymbolicLink(source)) throw new IOException("拒绝符号链接文件");
            BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("只接受普通文件");
            if (attributes.size() <= 0 || attributes.size() > MAX_IMPORT_BYTES) throw new IOException("文件必须大于0且不超过8 MB");
            if (!isSupported(extension(source))) throw new IOException("不支持此文件格式");
            Path inbox = inboxDirectory(true);
            Path name = source.getFileName();
            if (name == null || name.toString().isBlank()) throw new IOException("文件名无效");
            Path target = inbox.resolve(name.toString()).normalize();
            if (!target.getParent().equals(inbox)) throw new IOException("文件名包含非法路径");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            selectedFile = target;
            status = "已导入并选中 " + name + "。";
        } catch (FileAlreadyExistsException error) {
            status = "同名文件已存在，未覆盖：" + (source == null ? "" : source.getFileName());
        } catch (IOException | SecurityException error) {
            status = "未导入文件：" + error.getMessage();
        }
    }

    private void openInbox() {
        try {
            Path inbox = inboxDirectory(true);
            Util.getPlatform().openFile(inbox.toFile());
            status = "已打开 IC2-NewEra-blueprints/inbox。";
        } catch (IOException | SecurityException error) {
            status = "无法打开图纸目录：" + error.getMessage();
        }
    }

    private void refreshInbox() {
        inboxFiles.clear();
        try {
            Path inbox = inboxDirectory(true);
            try (var stream = Files.list(inbox)) {
                stream.filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(this::isSupportedAndSmall)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .limit(256).forEach(inboxFiles::add);
            }
            if (selectedFile != null && !inboxFiles.contains(selectedFile)) selectedFile = null;
            fileScroll = Math.min(fileScroll, Math.max(0, inboxFiles.size() - fileRowsVisible));
            status = "图纸目录已刷新，共 " + inboxFiles.size() + " 个可用文件。";
        } catch (IOException | SecurityException error) {
            status = "无法读取图纸目录：" + error.getMessage();
        }
        refreshFileButtons();
    }

    private boolean isSupportedAndSmall(Path path) {
        try {
            if (Files.isSymbolicLink(path)) return false;
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() && attrs.size() > 0 && attrs.size() <= MAX_IMPORT_BYTES
                    && isSupported(extension(path));
        } catch (IOException | SecurityException ignored) { return false; }
    }

    private Path inboxDirectory(boolean create) throws IOException {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        Path root = gameDir.resolve("IC2-NewEra-blueprints");
        Path inbox = root.resolve("inbox");
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) throw new IOException("图纸目录不能是符号链接");
        if (Files.exists(inbox, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(inbox)) throw new IOException("inbox不能是符号链接");
        if (create) Files.createDirectories(inbox);
        if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)) throw new IOException("inbox目录不存在");
        Path realGame = gameDir.toRealPath();
        Path realInbox = inbox.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realInbox.startsWith(realGame.resolve("IC2-NewEra-blueprints"))) throw new IOException("图纸目录越出当前游戏目录");
        return realInbox;
    }

    /** Rechecks canonical containment, symlink status, type, format, and byte limit immediately before reading. */
    private Path validateSelectedFile(Path file) throws IOException {
        Path inbox = inboxDirectory(false);
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.getParent().equals(inbox) || Files.isSymbolicLink(candidate))
            throw new IOException("文件必须是inbox中的普通文件，不能是符号链接");
        BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() <= 0 || attributes.size() > MAX_IMPORT_BYTES)
            throw new IOException("文件类型或大小已变化");
        if (!isSupported(extension(candidate))) throw new IOException("文件格式不支持");
        Path canonicalFile = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!canonicalFile.getParent().equals(inbox)) throw new IOException("文件路径越出inbox");
        return canonicalFile;
    }

    private static String extension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    private static boolean isImage(String ext) { return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg"); }
    private static boolean isSupported(String ext) { return BLUEPRINT_EXTENSIONS.contains(ext); }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 257 || key == 335) && description != null && description.isFocused()) {
            if (blueprintTabSelected) processSelectedFile(); else send(RobotBuildService.PREVIEW);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
