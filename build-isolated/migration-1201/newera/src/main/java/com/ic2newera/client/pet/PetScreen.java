package com.ic2newera.client.pet;

import com.ic2newera.pet.MechanicalPetEntity;
import com.ic2newera.pet.PetAction;
import com.ic2newera.pet.PetMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Two-page, slotless pet panel with a live preview of the actual bound entity. */
public final class PetScreen extends AbstractContainerScreen<PetMenu> {
    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 215;
    private final Button[] commandButtons = new Button[14];
    private Button pageButton;
    private boolean cutePage;

    public PetScreen(PetMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PANEL_WIDTH;
        imageHeight = PANEL_HEIGHT;
        inventoryLabelY = 2000;
    }

    @Override protected void init() {
        super.init();
        int x = leftPos + 8;
        int y = topPos + 115;
        String[] daily = {"screen.ic2newera.pet.follow.start", "screen.ic2newera.pet.pet",
                "screen.ic2newera.pet.jump", "screen.ic2newera.pet.nod",
                "screen.ic2newera.pet.shake", "screen.ic2newera.pet.peace.on"};
        for (int id = 0; id < daily.length; id++)
            addCommand(id, daily[id], x + (id % 2) * 124, y + (id / 2) * 23, 120);

        MechanicalPetEntity pet = menu.pet();
        boolean dog = pet != null && !pet.isCat();
        if (dog) addCommand(6, "screen.ic2newera.pet.bark", x, topPos + 184, 120);
        addCommand(7, "screen.ic2newera.pet.dance", dog ? x + 124 : x, topPos + 184, dog ? 120 : 244);

        String[] cute = {"screen.ic2newera.pet.tilt", "screen.ic2newera.pet.paw",
                "screen.ic2newera.pet.stretch", "screen.ic2newera.pet.roll",
                "screen.ic2newera.pet.bow", "screen.ic2newera.pet.yawn"};
        for (int i = 0; i < cute.length; i++) {
            int id = 8 + i;
            int row = i / 2;
            int col = i % 2;
            addCommand(id, cute[i], x + col * 124, y + row * 23, 120);
        }
        pageButton = addRenderableWidget(Button.builder(Component.empty(), button -> cutePage = !cutePage)
                .bounds(leftPos + 158, topPos + 8, 94, 20).build());
    }

    private void addCommand(int id, String key, int x, int y, int width) {
        int buttonId = id;
        commandButtons[id] = addRenderableWidget(Button.builder(Component.translatable(key), button -> {
            if (minecraft != null && minecraft.gameMode != null)
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }).bounds(x, y, width, 20).build());
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE61A2022);
        graphics.fill(leftPos + 5, topPos + 28, leftPos + imageWidth - 5, topPos + 110, 0xFF293337);
        graphics.fill(leftPos + 8, topPos + 31, leftPos + 105, topPos + 107, 0xFF13191B);
        MechanicalPetEntity pet = menu.pet();
        if (pet == null || !pet.isAlive()) {
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.unavailable"), leftPos + 115, topPos + 40, 0xE8796B);
            return;
        }
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, leftPos + 55, topPos + 104, 33,
                (float) (leftPos + 55) - mouseX, (float) (topPos + 64) - mouseY, pet);
        graphics.drawString(font, pet.isCat() ? Component.translatable("screen.ic2newera.pet.kind.cat")
                : Component.translatable("screen.ic2newera.pet.kind.dog"), leftPos + 115, topPos + 38, 0xE9C46A);
        String statusKey = pet.isPancake() ? "screen.ic2newera.pet.status.pancake"
                : pet.isFollowing() ? "screen.ic2newera.pet.status.following" : "screen.ic2newera.pet.status.waiting";
        graphics.drawString(font, Component.translatable(statusKey), leftPos + 115, topPos + 54, 0xD7E0DE);
        graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.peace",
                Component.translatable(pet.isPeaceful() ? "options.on" : "options.off")), leftPos + 115, topPos + 68, 0xB7C4C2);
        int hunger = Math.max(0, Math.min(MechanicalPetEntity.MAX_HUNGER, pet.getHunger()));
        int hungerColor = hunger == 0 ? 0xFFFF5B55 : hunger <= 4 ? 0xFFE9B354 : 0xFFB5D18A;
        graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.hunger", hunger),
                leftPos + 115, topPos + 82, hungerColor);
        graphics.fill(leftPos + 115, topPos + 94, leftPos + 239, topPos + 100, 0xFF111718);
        int filled = Math.round(124.0F * hunger / MechanicalPetEntity.MAX_HUNGER);
        graphics.fill(leftPos + 115, topPos + 94, leftPos + 115 + filled, topPos + 100, hungerColor);
        graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.feed"),
                leftPos + 115, topPos + 101, 0xD7E0DE);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MechanicalPetEntity pet = menu.pet();
        boolean accessible = pet != null && pet.isAlive() && minecraft != null && minecraft.player != null
                && pet.isOwnedBy(minecraft.player) && pet.distanceToSqr(minecraft.player) <= 64;
        boolean transition = pet == null || pet.getPetAction() == PetAction.FLATTEN || pet.getPetAction() == PetAction.RESTORE;
        boolean barking = pet != null && pet.getPetAction() == PetAction.BARK;
        boolean interactionBusy = pet != null && pet.getPetAction().isInteraction();
        for (int i = 0; i < commandButtons.length; i++) {
            Button button = commandButtons[i];
            if (button == null) continue;
            boolean inPage = cutePage ? i >= 8 : i < 8;
            button.visible = inPage;
            boolean enabledForPancake = pet != null && (!pet.isPancake() || i == 0 || i == 5);
            boolean enabledDuringInteraction = !interactionBusy || i == 0 || i == 5 || (barking && i == 6);
            button.active = accessible && !transition && enabledForPancake && enabledDuringInteraction;
        }
        pageButton.setMessage(Component.translatable(cutePage
                ? "screen.ic2newera.pet.page.daily" : "screen.ic2newera.pet.page.cute"));
        pageButton.active = accessible;
        if (pet != null && commandButtons[0] != null) {
            commandButtons[0].setMessage(Component.translatable(pet.isFollowing()
                    ? "screen.ic2newera.pet.follow.stop" : "screen.ic2newera.pet.follow.start"));
            commandButtons[5].setMessage(Component.translatable(pet.isPeaceful()
                    ? "screen.ic2newera.pet.peace.off" : "screen.ic2newera.pet.peace.on"));
            if (commandButtons[6] != null && barking)
                commandButtons[6].setMessage(Component.translatable("screen.ic2newera.pet.bark.stop"));
            else if (commandButtons[6] != null)
                commandButtons[6].setMessage(Component.translatable("screen.ic2newera.pet.bark"));
        }
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 5, 0xFFFFFF);
        graphics.drawString(font, Component.translatable(cutePage
                ? "screen.ic2newera.pet.page.cute.title" : "screen.ic2newera.pet.page.daily.title"), 8, 18, 0xB7C4C2);
    }

    @Override public boolean isPauseScreen() { return false; }
}
