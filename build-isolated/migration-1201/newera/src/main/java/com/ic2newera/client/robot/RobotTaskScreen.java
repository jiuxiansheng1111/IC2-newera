package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiScissor;
import com.ic2newera.client.ui.UiText;
import com.mojang.blaze3d.systems.RenderSystem;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotCharacter;
import com.ic2newera.robot.RobotRegistry;
import com.ic2newera.robot.RobotTaskBook;
import com.ic2newera.robot.RobotTaskIntent;
import com.ic2newera.robot.RobotTaskPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Private server-backed list of recent tasks and up to eight named tasks. */
final class RobotTaskScreen extends Screen {
    private final Screen parent;
    private final UUID robot;
    private final String character;
    private final boolean remote;
    private final int remoteEnergy;
    private boolean requested, confirmForget;
    private boolean failed;
    private int left, panelWidth, listTop, listBottom, rowHeight = 30, offset;
    private String selectedKey = "", status = "", nameDraft = "";
    private List<RobotTaskBook.Row> rows = List.of();
    private EditBox name;
    private Button run, save, forget, reload, back;
    private CompletableFuture<RobotTaskPackets.Reply> pending;
    private int localEntityId = -1;

    RobotTaskScreen(Screen parent, UUID robot, String character, boolean remote, int remoteEnergy) {
        super(Component.literal("常用任务"));
        this.parent = parent; this.robot = robot; this.character = character;
        this.remote = remote; this.remoteEnergy = remoteEnergy;
    }

    @Override protected void init() {
        if (name != null) nameDraft = name.getValue();
        panelWidth = Math.max(112, Math.min(520, width - 20)); left = (width - panelWidth) / 2;
        back = addRenderableWidget(new UiButton(left + panelWidth - 48, 8, 48, 18, Component.literal("返回"), b -> onClose()));
        listTop = 52;
        reload = addRenderableWidget(new UiButton(left + panelWidth - 62, 30, 62, 18, Component.literal("重新读取"), b -> request(RobotTaskBook.Action.LIST, "", "", "")));
        reload.visible = false;
        int footerTop = Math.max(listTop + 42, height - 104);
        listBottom = footerTop - 15;
        name = addRenderableWidget(new EditBox(font, left, footerTop, Math.max(45, panelWidth - 4), 20, Component.literal("任务名称")));
        name.setMaxLength(32); name.setValue(nameDraft);
        int gap = 4, cell = Math.max(28, (panelWidth - gap * 2) / 3);
        int buttonY = footerTop + 25;
        run = addRenderableWidget(new UiButton(left, buttonY, cell, 20, Component.literal("执行"), b -> runSelected()));
        save = addRenderableWidget(new UiButton(left + cell + gap, buttonY, cell, 20, Component.literal("保存"), b -> saveSelected()));
        forget = addRenderableWidget(new UiButton(left + (cell + gap) * 2, buttonY,
                panelWidth - (cell + gap) * 2, 20, Component.literal("忘记"), b -> forgetSelected()));
        run.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(remote
                ? "接单消耗手机 100 EU、机器人 200 EU。" : "接单消耗机器人 200 EU。")));
        save.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(remote
                ? "保存选中的最近任务，成功后手机耗电 20 EU。" : "按填写的名字保存选中的最近任务。")));
        forget.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(remote
                ? "删除选中的命名任务，成功后手机耗电 20 EU。" : "删除选中的命名任务。")));
        updateControls();
        if (!requested) { requested = true; request(RobotTaskBook.Action.LIST, "", "", ""); }
    }

    private RobotTaskBook.Row selected() {
        return rows.stream().filter(row -> row.key().equals(selectedKey)).findFirst().orElse(null);
    }
    private boolean isRecent(RobotTaskBook.Row row) { return row != null && row.key().startsWith("@"); }
    private void updateControls() {
        if (run == null) return;
        RobotTaskBook.Row row = selected();
        boolean busy = pending != null;
        back.active = !busy;
        boolean readFee = !remote || minecraft.player != null && com.ic2newera.robot.IndustrialPhoneItem.hasHeldCharge(minecraft.player, com.ic2newera.robot.IndustrialPhoneItem.READ_COST);
        boolean commandFee = !remote || minecraft.player != null && com.ic2newera.robot.IndustrialPhoneItem.hasHeldCharge(minecraft.player, com.ic2newera.robot.IndustrialPhoneItem.COMMAND_COST);
        run.active = !busy && row != null && canRun() && commandFee;
        String normalizedName = name == null ? "" : RobotTaskIntent.normalizedName(name.getValue());
        save.active = !busy && isRecent(row) && !normalizedName.isEmpty() && !normalizedName.startsWith("@") && readFee;
        forget.active = !busy && row != null && !isRecent(row) && readFee;
        reload.visible = !busy && failed;
        forget.setMessage(Component.literal(confirmForget ? "确认" : "忘记"));
    }
    private boolean canRun() {
        if (remote) return remoteEnergy >= 200;
        var player = minecraft.player;
        if (player == null || minecraft.level == null) return false;
        if (localEntityId < 0) {
            var nearby = minecraft.level.getEntitiesOfClass(RobotEntity.class, player.getBoundingBox().inflate(9), e -> e.getUUID().equals(robot));
            if (!nearby.isEmpty()) localEntityId = nearby.get(0).getId();
        }
        return minecraft.level.getEntity(localEntityId) instanceof RobotEntity entity && entity.getUUID().equals(robot) && entity.energy() >= 200;
    }
    private void request(RobotTaskBook.Action action, String source, String taskName, String expected) {
        if (pending != null) return;
        if (remote) {
            if (minecraft.player == null || !minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                    && !minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get())) {
                status = "手持或副手持手机后才能使用远程任务簿。"; failed = true; updateControls(); return;
            }
            int cost = action == RobotTaskBook.Action.RUN ? com.ic2newera.robot.IndustrialPhoneItem.COMMAND_COST
                    : com.ic2newera.robot.IndustrialPhoneItem.READ_COST;
            if (!com.ic2newera.robot.IndustrialPhoneItem.hasHeldCharge(minecraft.player, cost)) {
                status = action == RobotTaskBook.Action.RUN ? "执行需要手机至少有 100 EU。" : "读取、保存或删除需要手机至少有 20 EU。";
                failed = true; updateControls(); return;
            }
        }
        status = action == RobotTaskBook.Action.LIST ? "正在读取常用任务……" : "正在提交操作……";
        failed = false;
        confirmForget = false;
        CompletableFuture<RobotTaskPackets.Reply> call = RobotTaskClient.request(robot, remote, action, source, taskName, expected);
        pending = call; updateControls();
        call.whenComplete((reply, problem) -> minecraft.execute(() -> {
            if (pending != call || minecraft.screen != this) return;
            pending = null;
            if (problem != null) {
                failed = true;
                Throwable cause = problem; while (cause.getCause() != null) cause = cause.getCause();
                status = cause instanceof IllegalStateException ? cause.getMessage() : "请求超时，请返回重开常用任务。";
            } else {
                failed = !reply.accepted();
                if (reply.accepted() || !reply.rows().isEmpty()) {
                    rows = reply.rows() == null ? List.of() : List.copyOf(reply.rows());
                    if (rows.stream().noneMatch(row -> row.key().equals(selectedKey))) selectedKey = "";
                }
                status = reply.message();
                if (!reply.accepted() && status.isBlank()) status = "操作未完成。";
            }
            offset = Math.min(offset, maxOffset()); updateControls();
        }));
    }
    private void runSelected() {
        RobotTaskBook.Row row = selected(); if (row == null || !canRun()) return;
        request(RobotTaskBook.Action.RUN, row.key(), "", RobotTaskBook.signature(row));
    }
    private void saveSelected() {
        RobotTaskBook.Row row = selected(); String value = RobotTaskIntent.normalizedName(name.getValue());
        if (!isRecent(row) || value.isEmpty()) return;
        request(RobotTaskBook.Action.SAVE, row.key(), value, RobotTaskBook.signature(row));
    }
    private void forgetSelected() {
        RobotTaskBook.Row row = selected(); if (row == null || isRecent(row)) return;
        if (!confirmForget) { confirmForget = true; status = "再次点击“确认”才会删除命名任务“" + row.key() + "”。"; updateControls(); return; }
        request(RobotTaskBook.Action.FORGET, row.key(), "", RobotTaskBook.signature(row));
    }
    private int visibleRows() { return Math.max(1, (listBottom - listTop) / rowHeight); }
    private int maxOffset() { return Math.max(0, rows.size() - visibleRows()); }
    private String itemName(ResourceLocation id) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        return item == null ? id.toString() : item.getDescription().getString();
    }
    private String rowTitle(RobotTaskBook.Row row) {
        if (isRecent(row)) return row.type().equals("cook") ? "最近完成的烹饪" : "最近完成的采集";
        return row.key() + (row.type().equals("cook") ? " · 烹饪" : " · 采集");
    }
    private String rowDetail(RobotTaskBook.Row row) {
        String detail = row.amount() + " × " + itemName(row.item());
        if (row.type().equals("collect")) detail += row.mode() == com.ic2newera.robot.RobotCollectionTask.AUTO ? " · 自动采集" : " · 仅拾取";
        return detail;
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g); g.fill(left - 5, 3, left + panelWidth + 5, height - 3, 0xEF152631);
        g.drawString(font, Component.literal("常用任务 · " + RobotCharacter.byId(character).chineseName), left, 12, 0xFFFFFF);
        g.drawString(font, UiText.fit(font, Component.literal(remote ? "查看、保存、删除各耗手机 20 EU。" : "选中任务即可复做，最近完成的任务还可以起名保存。"), panelWidth - (reload.visible ? 66 : 0)), left, 32, 0xBCEFE8);
        g.fill(left, listTop, left + panelWidth, listBottom, 0xFF18242D);
        UiScissor.enable(minecraft, left, listTop, left + panelWidth, listBottom);
        int count = Math.min(rows.size() - offset, visibleRows());
        for (int i = 0; i < count; i++) {
            int index = offset + i, y = listTop + i * rowHeight;
            RobotTaskBook.Row row = rows.get(index);
            if (row.key().equals(selectedKey)) g.fill(left + 2, y + 1, left + panelWidth - 2, y + rowHeight - 1, 0xFF31515C);
            g.drawString(font, UiText.fit(font, Component.literal(rowTitle(row)), panelWidth - 14), left + 6, y + 4, 0xF0F5F5);
            g.drawString(font, UiText.fit(font, Component.literal(rowDetail(row)), panelWidth - 14), left + 6, y + 17, 0xB7D6DC);
        }
        if (rows.isEmpty() && pending == null)
            g.drawString(font, UiText.fit(font, Component.literal("还没有可用任务。完成一单采集或烹饪后再来保存。"), panelWidth - 12), left + 6, listTop + 8, 0xCEDCE0);
        RenderSystem.disableScissor();
        g.drawString(font, Component.literal("任务名称"), left, listBottom + 3, 0xB7D6DC);
        if (maxOffset() > 0) {
            String page = (offset + 1) + "–" + (offset + count) + "/" + rows.size();
            g.drawString(font, page, left + panelWidth - font.width(page), listBottom + 3, 0xB7D6DC);
        }
        String shown = status.isBlank() ? remote ? "执行需要手机 " + com.ic2newera.robot.IndustrialPhoneItem.COMMAND_COST + " EU、机器人 200 EU。" : "执行需要机器人 200 EU。" : status;
        g.drawString(font, UiText.fit(font, Component.literal(shown), panelWidth), left, height - 10, 0xD2E2E7);
        super.render(g, mouseX, mouseY, partial);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && x >= left && x < left + panelWidth && y >= listTop && y < listBottom) {
            int index = offset + (int)(y - listTop) / rowHeight;
            if (index >= offset && index < Math.min(rows.size(), offset + visibleRows())) {
                selectedKey = rows.get(index).key(); confirmForget = false; updateControls(); return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (x >= left && x < left + panelWidth && y >= listTop && y < listBottom) {
            offset = Math.max(0, Math.min(maxOffset(), offset - (int)Math.signum(amount) * 2)); return true;
        }
        return super.mouseScrolled(x, y, amount);
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null) { onClose(); return; }
        if (remote) {
            if (!minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get()) && !minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get())) onClose();
        } else {
            if (localEntityId < 0) {
                var nearby = minecraft.level.getEntitiesOfClass(RobotEntity.class, minecraft.player.getBoundingBox().inflate(9), e -> e.getUUID().equals(robot));
                if (!nearby.isEmpty()) localEntityId = nearby.get(0).getId();
            }
            if (!(minecraft.level.getEntity(localEntityId) instanceof RobotEntity e) || !e.getUUID().equals(robot)
                    || !e.isAlive() || !e.isOwnedBy(minecraft.player) || e.distanceToSqr(minecraft.player) > 64) onClose();
        }
        if (name != null) name.tick(); updateControls();
    }
    // Task operations cannot be cancelled after the packet is sent. Keep the shared ticket alive
    // until its response/timeout so a quick reopen cannot dispatch a duplicate paid action.
    @Override public void removed() { super.removed(); }
    @Override public void onClose() {
        if (pending != null) { status = "常用任务正在处理，请等候服务器回应。"; return; }
        minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen() { return false; }
}
