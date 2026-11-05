package com.ic2newera.client.media;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
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
    private UiButton submitButton;
    private int left, panelWidth;

    PlaylistImportScreen(Screen parent, boolean audius, Consumer<String> importer) {
        super(Component.literal(audius ? "导入 Audius 歌单" : "导入网易云歌单"));
        this.parent=parent; this.audius=audius; this.importer=importer;
    }

    @Override protected void init() {
        String draft=input==null?"":input.getValue();
        panelWidth=Math.min(580,width-24); left=(width-panelWidth)/2;
        input=addRenderableWidget(new EditBox(font,left,76,panelWidth,20,Component.literal(audius ? "Audius 公开歌单链接或 ID" : "网易云公开歌单链接或 ID")));
        input.setMaxLength(16384); input.setValue(draft); setInitialFocus(input);
        input.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(audius
                ? "粘贴 Audius 公开歌单链接或编号。" : "粘贴网易云公开歌单链接或数字 ID；也可按下方格式粘贴手工曲目。")));
        int half=(panelWidth-6)/2;
        addRenderableWidget(new UiButton(left,height-30,half,20,Component.literal("返回"),b->onClose()));
        submitButton = addRenderableWidget(new UiButton(left+half+6,height-30,panelWidth-half-6,20,Component.literal("确认导入并替换歌单"),b->submit()));
        submitButton.active = !draft.isBlank();
        submitButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(audius
                ? "联网读取并核对公开曲目；成功后用结果替换游戏内歌单。最多导入 100 首。取消只能中止处理中请求，不能撤销已完成的导入。"
                : "读取公开歌单或核实手工曲目；约 150 秒预算。全部成功后才替换游戏内歌单，最多 100 首。网易云红心收藏不会改变。取消只能中止处理中请求，不能撤销已完成的导入。")));
    }

    private void submit() {
        if(input.getValue().isBlank()) return;
        importer.accept(input.getValue().trim()); minecraft.setScreen(parent);
    }

    @Override public void tick() { input.tick(); submitButton.active = !input.getValue().isBlank(); }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if((key==257||key==335)&&input.isFocused()) { submit(); return true; }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics pose,int x,int y,float partial) {
        renderBackground(pose);
        pose.fill(left-6,5,left+panelWidth+6,height-4,0xEF152631);
        pose.drawCenteredString(font,title,width/2,14,0xFFFFFF);
        Component inputHelp=Component.literal(audius ? "输入 audius.co 公开歌单链接或编号；可用 Ctrl+V 粘贴。" : "输入网易云公开歌单链接或数字 ID；可用 Ctrl+V 粘贴。");
        Component formatHelp=Component.literal(audius ? "仅使用 Audius 公开曲目，不要求玩家登录。" : "也支持手工歌曲文本或 JSON，无需选择文件。");
        pose.drawString(font,UiText.fit(font,inputHelp,panelWidth),left,42,0xDAE6EE);
        pose.drawString(font,UiText.fit(font,formatHelp,panelWidth),left,57,0xDAE6EE);
        int lineY=108;
        String note=audius ? "确认后替换自己的歌单，最多导入 100 首。取消只能中止处理中请求，不能撤销已完成的导入。" : "手工条目按“歌名 / 歌手”逐行填写，或提供歌曲对象 JSON；不要粘贴聊天说明或日志。确认后联网核实，约 150 秒预算，可停止/取消；全部成功才替换自己的歌单，最多 100 首。取消不能撤销已完成的导入。普通搜歌请返回手机使用网易云搜索。";
        var noteLines=font.split(Component.literal(note),panelWidth);
        int availableLines=Math.max(0,(height-38-lineY)/11);
        for(int i=0;i<Math.min(availableLines,noteLines.size());i++) {
            if(i==availableLines-1 && noteLines.size()>availableLines)
                pose.drawString(font,"说明未显示完，请悬停查看…",left,lineY,0xBCEFE8);
            else pose.drawString(font,noteLines.get(i),left,lineY,0xBCEFE8);
            lineY+=11;
        }
        super.render(pose,x,y,partial);
        if(x>=left && x<left+panelWidth) {
            Component hover=y>=40 && y<52 ? inputHelp : y>=55 && y<67 ? formatHelp
                    : y>=106 && y<height-38 ? Component.literal(note) : null;
            if(hover!=null) pose.renderTooltip(font,font.split(hover,Math.min(300,panelWidth)),x,y);
        }
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
