package com.ic2newera.client.media;

import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.media.MediaPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

/** Displays only the owner-authorized, server-issued official NetEase QR session. */
public final class NetEaseLoginScreen extends Screen {
    private final Screen parent;
    private String qrUrl = "", status = "仅服主可登录；二维码仅在本机生成。";
    private boolean remember = true, pending, polling, loggedIn, checkedOnce, changing;
    private long nextPoll;
    private long requestGeneration;
    private BitMatrix matrix;
    public NetEaseLoginScreen(Screen parent) { super(Component.literal("网易云登录")); this.parent = parent; }

    @Override protected void init() {
        int panelWidth = Math.min(340, width - 20), left = (width - panelWidth) / 2, y = height - 29;
        int half = (panelWidth - 4) / 2;
        UiButton rememberButton = addRenderableWidget(new UiButton(left, y, half, 20,
                Component.literal("记住登录：" + (remember ? "开" : "关")), b -> toggleRemember()));
        rememberButton.active = !changing && !pending && (!loggedIn || remember);
        UiButton loginButton = addRenderableWidget(new UiButton(left + half + 4, y, panelWidth - half - 4,
                20, Component.literal(pending || loggedIn ? "退出登录" : "开始扫码"), b -> { if (pending || loggedIn) logout(); else begin(); }));
        loginButton.active = !changing;
        if (!pending && !loggedIn && !checkedOnce) { checkedOnce = true; checkLoginState(); }
    }
    private void checkLoginState() {
        long generation = requestGeneration;
        MediaClient.request(MediaPackets.NETEASE_LOGIN_POLL, "").whenComplete((result, failure) -> runOnClient(() -> {
            if (generation != requestGeneration) return;
            if (failure != null) { status = message(failure); rebuildWidgets(); return; }
            JsonObject data = result.json();
            String state = data.has("state") ? data.get("state").getAsString() : "idle";
            if (state.equals("expired") || state.equals("idle")) MusicController.clearLiked();
            loggedIn = state.equals("logged_in");
            if (data.has("remember")) remember = data.get("remember").getAsBoolean();
            pending = state.equals("waiting") || state.equals("scanned");
            qrUrl = data.has("qr_url") ? data.get("qr_url").getAsString() : "";
            matrix = makeQr(qrUrl);
            if (data.has("message")) status = data.get("message").getAsString();
            rebuildWidgets();
        }));
    }
    private void toggleRemember() {
        if (changing || pending) return;
        if (remember) {
            long generation = ++requestGeneration;
            changing = true; rebuildWidgets();
            MediaClient.request(MediaPackets.NETEASE_LOGIN_FORGET, "").whenComplete((result, failure) -> runOnClient(() -> {
                if (generation != requestGeneration) return;
                changing = false;
                if (failure != null || result.json().get("state").getAsString().equals("failed")) { status = failure == null ? result.json().get("message").getAsString() : message(failure); }
                else { remember = false; status = result.json().get("message").getAsString(); }
                rebuildWidgets();
            }));
        } else { remember = true; rebuildWidgets(); }
    }
    private void begin() {
        if (pending || changing) return;
        MusicController.clearLiked();
        long generation = ++requestGeneration;
        pending = true; changing = true; nextPoll = Long.MAX_VALUE;
        status = "正在向网易云申请二维码…"; rebuildWidgets();
        MediaClient.request(MediaPackets.NETEASE_LOGIN_BEGIN, remember ? "remember" : "session").whenComplete((result, failure) -> runOnClient(() -> {
            if (generation != requestGeneration) return;
            changing = false;
            if (failure != null) { pending = false; status = message(failure); rebuildWidgets(); return; }
            JsonObject data = result.json(); qrUrl = data.has("qr_url") ? data.get("qr_url").getAsString() : "";
            String state = data.has("state") ? data.get("state").getAsString() : "failed";
            if (data.has("remember")) remember = data.get("remember").getAsBoolean();
            pending = state.equals("waiting") || state.equals("scanned");
            status = data.has("message") ? data.get("message").getAsString()
                    : qrUrl.isBlank() ? "服务器未返回二维码。" : "请用网易云音乐扫码，并在手机上确认。";
            if (!qrUrl.isBlank() && !data.has("message")) status = "请用网易云音乐扫码，并在手机上确认。";
            matrix = makeQr(qrUrl); nextPoll = System.currentTimeMillis() + 2000; rebuildWidgets();
        }));
    }
    private void poll() {
        if (!pending || polling || System.currentTimeMillis() < nextPoll) return;
        long generation = requestGeneration;
        polling = true; nextPoll = System.currentTimeMillis() + 2500;
        MediaClient.request(MediaPackets.NETEASE_LOGIN_POLL, "").whenComplete((result, failure) -> runOnClient(() -> {
            if (generation != requestGeneration) return;
            polling = false;
            if (failure != null) { status = message(failure); return; }
            JsonObject data = result.json();
            String state = data.has("state") ? data.get("state").getAsString() : "failed";
            status = data.has("message") ? data.get("message").getAsString() : state;
            if (data.has("remember")) remember = data.get("remember").getAsBoolean();
            if (state.equals("logged_in") || state.equals("expired") || state.equals("failed") || state.equals("idle")) {
                if (!state.equals("logged_in")) MusicController.clearLiked();
                loggedIn = state.equals("logged_in");
                pending = false; qrUrl = ""; matrix = null; rebuildWidgets();
            }
        }));
    }
    private void logout() {
        if (changing) return;
        MusicController.clearLiked();
        long generation = ++requestGeneration;
        changing = true; pending = false; polling = false; qrUrl = ""; matrix = null;
        status = "正在清除服主本机登录状态…"; rebuildWidgets();
        MediaClient.request(MediaPackets.NETEASE_LOGIN_LOGOUT, "").whenComplete((result, failure) -> runOnClient(() -> {
            if (generation != requestGeneration) return;
            changing = false;
            pending = false; loggedIn = false; qrUrl = ""; matrix = null;
            status = failure == null ? result.json().get("message").getAsString() : message(failure);
            rebuildWidgets();
        }));
    }
    private void runOnClient(Runnable action) { if (minecraft != null) minecraft.execute(action); }
    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? "网易云登录请求失败。" : cause.getMessage();
    }
    private static BitMatrix makeQr(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M); hints.put(EncodeHintType.MARGIN, 2);
            return new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 136, 136, hints);
        } catch (WriterException invalid) { return null; }
    }
    @Override public void tick() { poll(); }
    @Override public void render(GuiGraphics pose, int mouseX, int mouseY, float partial) {
        renderBackground(pose); pose.fill( 8, 4, width - 8, height - 2, 0xEF152631);
        pose.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        if (matrix != null) {
            int size = Math.min(matrix.getWidth(), Math.max(96, Math.min(150, height - 78)));
            int left = (width - size) / 2, top = 31, pixel = Math.max(1, size / matrix.getWidth());
            int actual = pixel * matrix.getWidth(), x0 = (width - actual) / 2;
            pose.fill( x0 - 3, top - 3, x0 + actual + 3, top + actual + 3, 0xFFFFFFFF);
            for (int x = 0; x < matrix.getWidth(); x++) for (int y = 0; y < matrix.getHeight(); y++)
                if (matrix.get(x, y)) pose.fill( x0 + x * pixel, top + y * pixel, x0 + (x + 1) * pixel, top + (y + 1) * pixel, 0xFF101010);
        }
        pose.drawString(font, UiText.fit(font, Component.literal(status), width - 28), 14, height - 52, 0xDAE6EE);
        String storage = remember ? "登录状态只保存在本机，退出登录可清除。" : "登录状态只保存在本次运行内存。";
        pose.drawString(font, UiText.fit(font, Component.literal(storage), width - 28), 14, height - 40, 0xBCEFE8);
        super.render(pose, mouseX, mouseY, partial);
        if (mouseY >= height - 54 && mouseY < height - 42) pose.renderTooltip(font, Component.literal(status), mouseX, mouseY);
    }
    @Override public void onClose() { requestGeneration++; if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
