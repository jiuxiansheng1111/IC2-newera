package com.ic2newera.client.robot.camera;

import com.ic2newera.field.camera.PhoneCameraPackets;
import com.ic2newera.field.camera.PhoneCameraServerData;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Private local/server gallery plus explicitly published shared ecology photographs. */
public final class PhoneGalleryScreen extends Screen {
    private static PhoneGalleryScreen activeScreen;
    private final Screen parent;
    private List<Path> localPhotos = List.of();
    private List<PhoneCameraServerData.PhotoRow> serverRows = List.of();
    private int selected, mode, page, rowOffset, visibleRows;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private int imageWidth, imageHeight;
    private UUID selectedServer;
    private boolean listedLocalPhotos, requestedIdentity;
    private UUID pendingMapPhoto;
    private String localWorld, localFingerprint, localPlace = "";

    public PhoneGalleryScreen(Screen parent) { super(Component.literal("手机图库")); this.parent = parent; }

    @Override protected void init() {
        activeScreen = this;
        if (!requestedIdentity && Minecraft.getInstance().player != null && Minecraft.getInstance().getConnection() != null) {
            requestedIdentity = true;
            PhoneCameraPackets.requestWorldIdentity();
            localFingerprint = PhoneCameraController.currentClientFingerprint();
        }
        if (mode == 0 && !listedLocalPhotos) {
            localPhotos = PhoneCameraStorage.photos();
            listedLocalPhotos = true;
        }
        visibleRows = Math.max(1, Math.min(10, (height - 170) / 21));
        addRenderableWidget(Button.builder(Component.literal("本机"), b -> switchMode(0)).bounds(10, 34, 58, 20).build());
        addRenderableWidget(Button.builder(Component.literal("服务器私有"), b -> switchMode(1)).bounds(72, 34, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("公开相册"), b -> switchMode(2)).bounds(162, 34, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> changePage(-1)).bounds(10, height - 52, 70, 20).build())
                .active = page > 0 || mode != 0 && rowOffset > 0;
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> changePage(1)).bounds(84, height - 52, 70, 20).build())
                .active = mode == 0 ? (page + 1) * visibleRows < localPhotos.size() : rowOffset + visibleRows < serverRows.size() || serverRows.size() == 24;
        String[] actions = {"去拍摄点", "去被摄物", "拍摄点入线", "被摄点入线"};
        int cell = Math.min(84, (width - 18) / actions.length);
        for (int i = 0; i < actions.length; i++) {
            boolean subject = i % 2 == 1, add = i >= 2;
            addRenderableWidget(Button.builder(Component.literal(actions[i]), b -> navigate(subject, add))
                    .bounds(10 + i * cell, height - 78, cell - 3, 20).build());
        }
        if (mode == 0) addLocalRows();
        else addServerRows();
        addRenderableWidget(Button.builder(Component.literal("公开 / 撤下"), b -> {
            if (selectedServer != null && mode == 1 && selected < serverRows.size())
                PhoneCameraPackets.publish(selectedServer, !serverRows.get(selected).published());
        }).bounds(162, height - 52, 110, 20).build()).active = mode == 1;
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(width - 74, height - 28, 64, 20).build());
        if (mode == 0 && !localPhotos.isEmpty()) {
            int first = page * visibleRows;
            selectLocal(selected >= first && selected < first + visibleRows ? selected : first);
        } else if (mode != 0 && texture == null && selectedServer != null) PhoneCameraPackets.requestPhoto(selectedServer);
    }

    private void switchMode(int next) {
        mode = next; page = 0; rowOffset = 0; selected = 0; selectedServer = null; serverRows = List.of();
        listedLocalPhotos = false; pendingMapPhoto = null; disposeTexture(); rebuildWidgets(); if (mode != 0) requestRows();
    }
    private void changePage(int delta) {
        boolean request = false;
        if (mode == 0) page = Math.max(0, Math.min((Math.max(0, localPhotos.size() - 1)) / visibleRows, page + delta));
        else if (delta < 0) {
            if (rowOffset > 0) rowOffset = Math.max(0, rowOffset - visibleRows);
            else if (page > 0) { page--; rowOffset = 0; request = true; }
        } else if (rowOffset + visibleRows < serverRows.size()) rowOffset += visibleRows;
        else { page = Math.min(10000, page + 1); rowOffset = 0; request = true; }
        if (request) serverRows = List.of();
        selected = 0; selectedServer = null; disposeTexture(); rebuildWidgets();
        if (request) requestRows(); else if (mode != 0 && rowOffset < serverRows.size()) selectServer(rowOffset);
    }
    private void requestRows() { PhoneCameraPackets.requestGallery(mode == 2, page); }

    private void addLocalRows() {
        for (int i = 0; i < visibleRows && page * visibleRows + i < localPhotos.size(); i++) {
            final int index = i;
            Path path = localPhotos.get(page * visibleRows + i);
            if (path == null) continue;
            String caption = PhoneCameraStorage.listLabel(path);
            addRenderableWidget(Button.builder(Component.literal(caption), b -> selectLocal(index + page * visibleRows))
                    .bounds(10, 60 + i * 21, 232, 19).build());
        }
    }

    private void addServerRows() {
        for (int i = 0; i < visibleRows && rowOffset + i < serverRows.size(); i++) {
            int index = rowOffset + i;
            PhoneCameraServerData.PhotoRow row = serverRows.get(index);
            String name = speciesName(row.speciesId()) + " · " + row.camera().pos().toShortString() + (row.published() ? " · 公开" : "");
            addRenderableWidget(Button.builder(Component.literal(name), b -> selectServer(index))
                    .bounds(10, 60 + i * 21, 232, 19).build());
        }
    }

    private void selectLocal(int index) {
        if (index < 0 || index >= localPhotos.size()) return;
        selected = index; selectedServer = null; disposeTexture();
        updateLocalPlace();
        try { showImage(PhoneCameraStorage.read(localPhotos.get(index))); }
        catch (IOException | RuntimeException ignored) { texture = null; textureId = null; }
    }

    private void selectServer(int index) {
        if (index < 0 || index >= serverRows.size()) return;
        selectedServer = serverRows.get(index).id(); selected = index; disposeTexture();
        PhoneCameraPackets.requestPhoto(selectedServer);
    }

    private static String speciesName(String id) {
        return switch (id) {
            case "spawn:angler_fish" -> "鮟鱇鱼";
            case "spawn:clam" -> "蛤蜊";
            case "spawn:tuna" -> "金枪鱼";
            default -> id == null || id.isBlank() ? "风景 / 自拍" : id;
        };
    }

    private UUID selectedId() {
        if (selectedServer != null) return selectedServer;
        if (mode != 0 || selected < 0 || selected >= localPhotos.size()) return null;
        String name = localPhotos.get(selected).getFileName().toString();
        try { return UUID.fromString(name.substring("phone-".length(), name.length() - ".png".length())); }
        catch (RuntimeException invalid) { return null; }
    }

    private void navigate(boolean subject, boolean route) {
        UUID id = selectedId();
        if (id != null) {
            pendingMapPhoto = route ? null : id;
            PhoneCameraPackets.navigate(id, subject, route, mode == 0);
        }
    }

    public static void routeReady(UUID photo) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof PhoneGalleryScreen gallery && photo.equals(gallery.pendingMapPhoto) && photo.equals(gallery.selectedId())) {
            gallery.pendingMapPhoto = null;
            mc.setScreen(new com.ic2newera.client.robot.FieldRouteScreen(gallery));
        }
    }
    private void updateLocalPlace() {
        localWorld = PhoneCameraController.serverWorldIdentity();
        localPlace = PhoneCameraStorage.metadataSummary(selectedId(), localWorld, localFingerprint);
    }
    @Override public void tick() {
        if (mode == 0 && !Objects.equals(localWorld, PhoneCameraController.serverWorldIdentity())) updateLocalPlace();
    }

    private void showImage(NativeImage image) {
        imageWidth = image.getWidth(); imageHeight = image.getHeight(); texture = new DynamicTexture(image);
        textureId = new ResourceLocation("ic2newera", "phone_gallery/" + UUID.randomUUID().toString().replace("-", ""));
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
    }

    public static void receiveRows(boolean publicGallery, List<PhoneCameraServerData.PhotoRow> rows) {
        Minecraft.getInstance().execute(() -> {
            if (activeScreen == null || (activeScreen.mode == 2) != publicGallery || activeScreen.mode == 0) return;
            activeScreen.serverRows = List.copyOf(rows); activeScreen.rowOffset = 0; activeScreen.selected = 0; activeScreen.rebuildWidgets();
            if (!rows.isEmpty()) activeScreen.selectServer(0);
        });
    }

    /** Open the published ecology gallery from the journal with a normal return path. */
    public static void openPublic(Screen parent) {
        PhoneGalleryScreen screen = new PhoneGalleryScreen(parent); screen.mode = 2;
        Minecraft.getInstance().setScreen(screen); PhoneCameraPackets.requestGallery(true, 0);
    }

    public static void receiveImage(UUID id, byte[] png) {
        Minecraft.getInstance().execute(() -> {
            if (activeScreen == null || id == null || !id.equals(activeScreen.selectedServer) || png == null || png.length == 0
                    || png.length > PhoneCameraServerData.MAX_IMAGE_BYTES) return;
            try (ByteArrayInputStream input = new ByteArrayInputStream(png)) {
                NativeImage image = NativeImage.read(input);
                if (image.getWidth() > 4096 || image.getHeight() > 4096 || (long) image.getWidth() * image.getHeight() > 16_777_216L) { image.close(); return; }
                activeScreen.disposeTexture(); activeScreen.showImage(image);
            } catch (IOException ignored) { }
        });
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        if (mode == 0 && localPhotos.isEmpty()) graphics.drawCenteredString(font, "还没有本机照片；相机取景时按 P 拍摄。", width / 2, height / 2, 0xD8E8ED);
        else if (mode != 0 && serverRows.isEmpty()) graphics.drawCenteredString(font, mode == 2 ? "公开生态相册暂无照片。" : "服务器私有相册暂无照片。", width / 2, height / 2, 0xD8E8ED);
        if (textureId != null) {
            int maxW = Math.max(64, width - 278), maxH = Math.max(64, height - 92);
            float scale = Math.min((float) maxW / imageWidth, (float) maxH / imageHeight);
            int drawW = Math.max(1, (int) (imageWidth * scale)), drawH = Math.max(1, (int) (imageHeight * scale));
            int x = width - drawW - 12, y = (height - drawH) / 2;
            graphics.blit(textureId, x, y, drawW, drawH, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
            String name = mode == 0 && selected < localPhotos.size() ? localPhotos.get(selected).getFileName().toString()
                    : selectedServer == null ? "正在载入…" : selectedServer.toString();
            graphics.drawString(font, name + " · " + imageWidth + "×" + imageHeight, x, Math.max(18, y - 24), 0xFFFFFF);
            String place = mode == 0 && selected < localPhotos.size() ? localPlace
                    : selectedServer != null && selected < serverRows.size()
                    ? serverRows.get(selected).camera().dimension().location() + " " + serverRows.get(selected).camera().pos().toShortString()
                    : "";
            graphics.drawString(font, place, x, Math.max(30, y - 12), 0xCBE1E8);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void removed() { pendingMapPhoto = null; disposeTexture(); if (activeScreen == this) activeScreen = null; }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    private void disposeTexture() {
        if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
        else if (texture != null) texture.close();
        textureId = null; texture = null;
    }
}
