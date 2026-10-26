package com.ic2newera.client.media;

import com.ic2newera.client.ui.UiButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Players review the input before replacing their own playlist. */
final class PlaylistImportScreen extends Screen {
    private final Screen parent;
    private final boolean audius;
    private final Consumer<String> importer;
    private EditBox input;
    private int left, panelWidth;

    PlaylistImportScreen(Screen parent, boolean audius, Consumer<String> importer) {
        super(Component.literal(audius ? "导入 Audius 歌单" : "导入网易云歌单"));
        this.parent=parent; this.audius=audius; this.importer=importer;
    }

    @Override protected void init() {
        String draft=input==null?"":input.getValue();
        panelWidth=Math.min(580,width-24); left=(width-panelWidth)/2;
        input=addRenderableWidget(new EditBox(font,left,76,panelWidth,20,Component.literal(audius ? "Audius 公开歌单链接或 ID" : "公开歌单链接或 ID")));
        input.setMaxLength(16384); input.setValue(draft); setInitialFocus(input);
        int half=(panelWidth-6)/2;
        addRenderableWidget(new UiButton(left,height-30,half,20,Component.literal("返回"),b->onClose()));
        addRenderableWidget(new UiButton(left+half+6,height-30,panelWidth-half-6,20,Component.literal("确认导入并替换歌单"),b->submit()));
    }

    private void submit() {
        if(input.getValue().isBlank()) return;
        importer.accept(input.getValue().trim()); minecraft.setScreen(parent);
    }

    @Override public void tick() { input.tick(); }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if((key==257||key==335)&&input.isFocused()) { submit(); return true; }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics pose,int x,int y,float partial) {
        renderBackground(pose);
        pose.fill(left-6,5,left+panelWidth+6,height-4,0xEF152631);
        pose.drawCenteredString(font,title,width/2,14,0xFFFFFF);
        pose.drawString(font,audius ? "输入 audius.co 公开歌单链接或编号；可用 Ctrl+V 粘贴。" : "输入网易云公开歌单链接或数字 ID；可用 Ctrl+V 粘贴。",left,42,0xDAE6EE);
        pose.drawString(font,audius ? "仅使用 Audius 公开曲目，不要求玩家登录。" : "也支持手工歌曲文本或 JSON，无需选择文件。",left,57,0xDAE6EE);
        int lineY=108;
        String note=audius ? "确认后替换自己的歌单，最多导入 100 首。" : "手工条目按“歌名 / 歌手”逐行填写，或提供歌曲对象 JSON；不要粘贴聊天说明或日志。确认后联网核实，约 150 秒预算，可停止/取消；全部成功才替换自己的歌单，最多 100 首。普通搜歌请返回手机使用网易云搜索。";
        for(var line:font.split(Component.literal(note),panelWidth)) {
            pose.drawString(font,line,left,lineY,0xBCEFE8); lineY+=11;
        }
        super.render(pose,x,y,partial);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
