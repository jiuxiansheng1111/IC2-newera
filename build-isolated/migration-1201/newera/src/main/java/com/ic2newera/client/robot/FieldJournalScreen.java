package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.field.FieldJournal;
import com.ic2newera.field.FieldJournal.Tab;
import com.ic2newera.field.FieldRoutes;
import com.ic2newera.robot.RobotRegistry;
import io.github.mortuusars.exposure.gui.screen.PhotographScreen;
import io.github.mortuusars.exposure.item.PhotographItem;
import io.github.mortuusars.exposure.util.ItemAndStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FieldJournalScreen extends Screen {
    private final Screen parent;
    private final UUID robot;
    private Tab tab = Tab.MOON;
    private EditBox input;
    private String draft = "", status = "";
    private List<String> lines = List.of();
    private List<FieldJournal.Candidate> candidates = List.of(), archived = List.of();
    private int record, shot, scroll, top, bottom, x, w, timeout;
    private UUID ticket;
    private boolean loaded, confirmDisband, showRouteOnReply;
    private static final Map<UUID, CompletableFuture<String>> CHAT = new HashMap<>();
    private static java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientPacketListener> routeConnection = new java.lang.ref.WeakReference<>(null);
    private static FieldRoutes.Snapshot lastRoute = FieldRoutes.Snapshot.empty();
    FieldJournalScreen(Screen parent, UUID robot) {
        super(Component.literal("远征手记")); this.parent = parent; this.robot = robot;
    }
    @Override protected void init() {
        if (input != null) draft = input.getValue();
        input = null; w = Math.max(150, Math.min(500, width - 18)); x = (width - w) / 2;
        String[] labels = {"月球远征", "生态相册", "小队", "任务路线"};
        for (Tab t : Tab.values()) {
            var b = button(22, t.ordinal(), 5, labels[t.ordinal()], "切换到" + labels[t.ordinal()], () -> change(t));
            b.active = t != tab;
        }
        button(22, 4, 5, "导航地图", "查看实际路线和分步指引。", () -> minecraft.setScreen(new FieldRouteScreen(this)));
        top = 46;
        int y = height - 99;
        if (tab == Tab.MOON) {
            edit(y - 20, "物品编号 数量，或另一手拿材料后输入数量");
            button(y, 0, 3, "加备货项", "如 minecraft:cooked_beef 16，也可另一手拿物品，只填数量。", () -> request("add", input.getValue()));
            button(y, 1, 3, "减备货项", "填物品编号和数量；只填编号可移除整行。", () -> request("remove", input.getValue()));
            button(y, 2, 3, "登记备货箱", "手持手机，准星对准准备出发用的箱子。", () -> request("bind", ""));
            button(y + 20, 0, 4, "查看箱内", "在地球备货箱或月球补给箱八格内，查看箱内实物。", () -> request("stock", ""));
            button(y + 20, 1, 4, "核对清单", "检查备货清单与实际物资。", () -> request("check", ""));
            button(y + 20, 2, 4, "出发检查", "检查太空服、氧气和一级火箭。", () -> request("launch", ""));
            button(y + 20, 3, 4, "确认着陆", "实际登月着陆后确认队伍落点。", () -> request("landing", ""));
            button(y + 40, 0, 3, "记补给箱", "在月球上瞄准要记录的补给箱。", () -> request("cache", ""));
            button(y + 40, 1, 3, "去着陆点", "显示距离和高差；同行机器人可以带路。", () -> request("guide", "landing"));
            button(y + 40, 2, 3, "去补给箱", "按队伍已登记的月球补给箱位置导航。", () -> request("guide", "cache"));
            button(y - 41, 0, 2, "落点加入路线", "把本队月球着陆点加入自己的行程。", () -> request("pin","landing"));
            button(y - 41, 1, 2, "补给箱加入路线", "把本队月球补给箱加入自己的行程。", () -> request("pin","cache"));
            bottom = y - 44;
        } else if (tab == Tab.ALBUM) {
            button(y - 22, 0, 4, "上一页", "翻看公共相册的上一页。", () -> request("page", "back")).active = !archived.isEmpty();
            button(y - 22, 1, 4, "上一张", "选中本页上一条记录。", () -> selectRecord(-1)).active = archived.size() > 1;
            button(y - 22, 2, 4, "下一张", "选中本页下一条记录。", () -> selectRecord(1)).active = archived.size() > 1;
            button(y - 22, 3, 4, "下一页", "翻看公共相册的下一页。", () -> request("page", "next")).active = !archived.isEmpty();
            String selected = archived.isEmpty() ? "" : archived.get(record).id();
            button(y, 0, 3, "看原片", "打开所选记录对应的原片。", () -> request("photo", selected)).active = !selected.isEmpty();
            button(y, 1, 3, "去拍摄点", "导航到摄影者按下快门时的坐标。", () -> request("guide", "camera|" + selected)).active = !selected.isEmpty();
            button(y, 2, 3, "去动物位置", "导航到这张照片中动物当时所在的坐标。", () -> request("guide", selected)).active = !selected.isEmpty();
            button(y + 22, 0, 2, candidates.isEmpty() ? "暂无可提交的照片" : candidates.get(shot).label(),
                    "从背包中符合条件的胶片和照片中选择一帧。", () -> {
                        if (!candidates.isEmpty()) shot = (shot + 1) % candidates.size(); rebuildWidgets();
                    }).active = !candidates.isEmpty();
            button(y + 22, 1, 2, "收录所选照片", "将选中的野外照片和发现地点公开到本服务器相册。", () -> {
                if (!candidates.isEmpty()) request("archive", candidates.get(shot).id());
            }).active = !candidates.isEmpty();
            button(y + 43, 0, 2, "拍摄点加入路线", "保留摄影者当时的坐标。", () -> request("pin","camera|"+selected)).active=!selected.isEmpty();
            button(y + 43, 1, 2, "动物位置加入路线", "保留照片中动物的位置。", () -> request("pin",selected)).active=!selected.isEmpty();
            bottom = y - 39;
        } else if (tab==Tab.ITINERARY) {
            String stop=archived.isEmpty()?"":archived.get(record).id();
            String job=candidates.isEmpty()?"":candidates.get(shot).id();
            button(y-22,0,2,stop.isEmpty()?"暂无路标":"站 "+(record+1)+"　"+archived.get(record).label(),"点击轮选要调整或出发的站点。",()->selectRecord(1)).active=!stop.isEmpty();
            button(y-22,1,2,"记录当前位置","把脚下位置加到路线末尾。",()->request("pin_here",""));
            button(y,0,4,"出发","从所选站出发，到达后自动导航至下一站。",()->request("run",stop)).active=!stop.isEmpty();
            button(y,1,4,"前移","把所选站点提前。",()->request("up",stop)).active=record>0;
            button(y,2,4,"后移","把所选站点延后。",()->request("down",stop)).active=record+1<archived.size();
            button(y,3,4,"移除","从行程移除所选站点。",()->request("drop",stop)).active=!stop.isEmpty();
            button(y+22,0,1,job.isEmpty()?"暂无已接委托":"委托　"+candidates.get(shot).label(),"点击轮选已接的公告栏委托。",()->{shot=(shot+1)%candidates.size();rebuildWidgets();}).active=!job.isEmpty();
            button(y+43,0,2,"加任务地点","照片取已知发现地，物资取交付点。",()->request("job",job)).active=!job.isEmpty();
            button(y+43,1,2,"加交付点","把接取任务的公告栏加入行程。",()->request("return_job",job)).active=!job.isEmpty();
            bottom=y-25;
        } else {
            edit(y - 12, "朋友的完整游戏名");
            button(y + 10, 0, 2, "邀请队友", "邀请在线朋友一起备货和记录月球地点。", () -> request("invite", input.getValue()));
            button(y + 10, 1, 2, "接受邀请", "接受手机中显示的小队邀请。", () -> request("accept", ""));
            button(y + 32, 0, 2, "离开小队", "返回个人的远征清单。", () -> request("leave", ""));
            button(y + 32, 1, 2, confirmDisband ? "确认解散" : "解散小队", "队长再次点击以解散小队。", () -> {
                if (confirmDisband) { confirmDisband = false; request("disband", ""); }
                else { confirmDisband = true; rebuildWidgets(); }
            });
            bottom = y - 16;
        }
        int footerCols = tab == Tab.ALBUM ? 4 : 3;
        button(height - 23, 0, footerCols, "刷新", "重新读取服务器记录。", () -> request("view", ""));
        if (tab == Tab.ALBUM) {
            button(height - 23, 1, footerCols, "手机公开照片", "查看同服玩家已公开的手机原片与拍摄地点。", () ->
                    com.ic2newera.client.robot.camera.PhoneGalleryScreen.openPublic(this));
        }
        button(height - 23, footerCols - 2, footerCols, "停止导航", "关闭当前定位提示和选中机器人的带路指令。", () -> request("stop_nav", ""));
        button(height - 23, footerCols - 1, footerCols, "返回手机", "回到设备列表。", this::onClose);
        if (!loaded) { loaded = true; request("view", ""); }
    }
    private void selectRecord(int delta) {
        record = Math.floorMod(record + delta, archived.size()); rebuildWidgets();
    }
    private UiButton button(int y, int col, int cols, String name, String hint, Runnable action) {
        int cell = (w + 3) / cols;
        return addRenderableWidget(new UiButton(x + col * cell, y, cell - 3, 18,
                UiText.fit(font, Component.literal(name), cell - 10), b -> action.run(), Component.literal(hint)));
    }
    private void edit(int y, String hint) {
        input = new EditBox(font, x + 1, y, w - 2, 17, Component.literal(hint));
        input.setMaxLength(180); input.setHint(Component.literal(hint)); input.setValue(draft);
        addRenderableWidget(input);
    }
    private void change(Tab next) {
        if (ticket != null) return;
        tab = next; input = null; draft = ""; status = ""; lines = List.of(); candidates = archived = List.of();
        record = shot = scroll = 0; confirmDisband = false; loaded = false; rebuildWidgets();
    }
    private void request(String op, String arg) {
        if (ticket != null) return;
        ticket = UUID.randomUUID(); timeout = 0; showRouteOnReply = op.equals("guide") || op.equals("run"); status = "正在读取服务器记录……";
        FieldJournal.request(new FieldJournal.Request(ticket, tab, op, arg, robot == null ? FieldJournal.NO_ROBOT : robot, false));
    }
    public static void receive(FieldJournal.View v) {
        var mc = Minecraft.getInstance();
        var chat = CHAT.remove(v.ticket());
        if (chat != null) { chat.complete(v.notice()); return; }
        if (!(mc.screen instanceof FieldJournalScreen self) || !v.ticket().equals(self.ticket) || v.tab() != self.tab) return;
        self.ticket = null; self.status = v.notice();
        if (!v.lines().isEmpty() || !v.candidates().isEmpty() || !v.archived().isEmpty()) {
            String prior = self.candidates.isEmpty() ? "" : self.candidates.get(self.shot).id();
            String oldRecord = self.archived.isEmpty() ? "" : self.archived.get(self.record).id();
            self.lines = v.lines(); self.candidates = v.candidates(); self.shot = 0;
            for (int i = 0; i < self.candidates.size(); i++) if (self.candidates.get(i).id().equals(prior)) self.shot = i;
            self.archived = v.archived(); self.record = 0;
            for (int i = 0; i < self.archived.size(); i++) if (self.archived.get(i).id().equals(oldRecord)) self.record = i;
        }
        self.rebuildWidgets();
        if (v.photo().getItem() instanceof PhotographItem) {
            var copy = new ItemAndStack<PhotographItem>(v.photo().copy());
            mc.setScreen(new PhotographScreen(List.of(copy)) {
                @Override public void onClose() { mc.setScreen(self); }
            });
        } else if (self.showRouteOnReply && v.notice().startsWith("记录地点")) {
            mc.setScreen(new FieldRouteScreen(self));
        }
        self.showRouteOnReply = false;
    }
    public static void receiveRoute(FieldRoutes.Snapshot route) {
        routeConnection = new java.lang.ref.WeakReference<>(Minecraft.getInstance().getConnection()); lastRoute = route;
    }
    static FieldRoutes.Snapshot route() {
        var mc = Minecraft.getInstance();
        return mc.player != null && routeConnection.get() == mc.getConnection() ? lastRoute : FieldRoutes.Snapshot.empty();
    }
    record Query(Tab section, String op, String value) {}
    static Query query(String raw) {
        String s = raw.strip().replaceAll("[？?。！!\\s]+", "");
        String value = s.contains("补给箱") ? "cache" : s.contains("着陆点") ? "landing" : null;
        if (value != null && s.matches("带我去(上次的)?月球(补给箱|着陆点)"))
            return new Query(Tab.MOON, "guide", value);
        if (value != null && (s.equals("月球补给箱在哪") || s.equals("上次的月球补给箱在哪") || s.equals("月球着陆点在哪")
                || s.equals("上次的月球着陆点在哪") || s.equals("上回留在月球的补给箱在哪")))
            return new Query(Tab.MOON, "where", value);
        for (var species : com.ic2newera.field.EcologyAlbum.availableSpecies()) {
            String name = species.displayName();
            if (s.equals("带我去" + name + "的拍摄点") || s.equals("带我去" + name + "拍摄点"))
                return new Query(Tab.ALBUM, "guide", "camera|" + species.entityId());
            if (s.equals(name + "的拍摄点在哪")) return new Query(Tab.ALBUM, "where", "camera|" + species.entityId());
            if (s.equals("哪里见过" + name) || s.equals(name + "在哪里拍到的") || s.equals(name + "在哪"))
                return new Query(Tab.ALBUM, "where", species.entityId());
            if (s.equals("带我去" + name + "发现地") || s.equals("带我去看" + name))
                return new Query(Tab.ALBUM, "guide", species.entityId());
        }
        return null;
    }
    static CompletableFuture<String> chat(UUID robot, boolean remote, Query q) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return CompletableFuture.failedFuture(new IllegalStateException("已离线。"));
        UUID ticket = UUID.randomUUID();
        var answer = new CompletableFuture<String>(); CHAT.put(ticket, answer);
        answer.orTimeout(8, TimeUnit.SECONDS).whenComplete((s,e) -> mc.execute(() -> CHAT.remove(ticket)));
        FieldJournal.request(new FieldJournal.Request(ticket, q.section, q.op, q.value, robot, !remote));
        return answer;
    }
    @Override public void tick() {
        if (input != null) input.tick();
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) { minecraft.setScreen(null); return; }
        if (ticket != null && ++timeout > 160) { ticket = null; status = "暂未收到服务器的回应，请刷新后查看结果。"; }
    }
    private List<FormattedCharSequence> body() {
        List<FormattedCharSequence> text = new ArrayList<>();
        for (String line : lines) text.addAll(font.split(Component.literal(line), w - 10));
        return text;
    }
    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (my >= top && my <= bottom) { scroll = Math.max(0, scroll - (int)Math.signum(delta) * 3); return true; }
        return super.mouseScrolled(mx, my, delta);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g); g.fill(x - 5, 3, x + w + 5, height - 2, 0xF0172831);
        g.drawCenteredString(font, title, width / 2, 8, 0xF3F4E8);
        var text = body(); int visible = Math.max(1, (bottom - top) / 11);
        scroll = Math.min(scroll, Math.max(0, text.size() - visible));
        for (int i = 0; i < visible && i + scroll < text.size(); i++) g.drawString(font, text.get(i + scroll), x + 3, top + i * 11, 0xD6E5E6);
        if (tab == Tab.ALBUM) {
            String selected = archived.isEmpty() ? "尚未收录照片" : (record + 1) + " / " + archived.size() + "　" + archived.get(record).label();
            g.drawString(font, UiText.fit(font, Component.literal(selected), w), x + 1, height - 135, 0xF0D79C);
            if (my >= height - 137 && my < height - 123) g.renderTooltip(font, font.split(Component.literal(selected), w - 12), mx, my);
        }
        String note = status.replace('\n', ' ');
        g.drawString(font, UiText.fit(font, Component.literal(note), w), x, height - 35, 0xEADCA4);
        super.render(g, mx, my, partial);
        if (!status.isEmpty() && my >= height - 38 && my < height - 23)
            g.renderTooltip(font, font.split(Component.literal(status), Math.max(100, w - 16)), mx, my);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
