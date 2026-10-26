package com.ic2newera.client.pet;

import com.ic2newera.pet.MechanicalPet;
import com.ic2newera.pet.PetAction;
import com.ic2newera.pet.PetMenu;
import com.ic2newera.pet.PetInventory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Owner controls and a separate, fully slot-backed storage page. */
public final class PetScreen extends AbstractContainerScreen<PetMenu> {
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 230;
    private final Button[] commandButtons = new Button[14];
    private Button pageButton;
    private Button nativeProfileButton;
    private Button nativeLogisticsButton;
    private int page;

    public PetScreen(PetMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PANEL_WIDTH;
        imageHeight = PANEL_HEIGHT;
        inventoryLabelY = 2000;
    }

    @Override protected void init() {
        super.init();
        int x = leftPos + 8;
        int y = topPos + 123;
        String[] daily = {"screen.ic2newera.pet.follow.start", "screen.ic2newera.pet.pet",
                "screen.ic2newera.pet.jump", "screen.ic2newera.pet.nod",
                "screen.ic2newera.pet.shake", "screen.ic2newera.pet.peace.on",
                "screen.ic2newera.pet.bark", "screen.ic2newera.pet.dance"};
        boolean isDog = menu.pet() != null && !menu.pet().isCat();
        for (int i = 0; i < daily.length; i++) {
            if (i == 6 && !isDog) continue;
            int col = i % 2, row = i / 2;
            addCommand(i, daily[i], x + col * 142, y + row * 23, 138);
        }
        String[] cute = {"screen.ic2newera.pet.tilt", "screen.ic2newera.pet.paw",
                "screen.ic2newera.pet.stretch", "screen.ic2newera.pet.roll",
                "screen.ic2newera.pet.bow", "screen.ic2newera.pet.yawn"};
        for (int i = 0; i < cute.length; i++) {
            int id = i + 8, col = i % 2, row = i / 2;
            addCommand(id, cute[i], x + col * 142, y + row * 23, 138);
        }
        pageButton = addRenderableWidget(Button.builder(Component.empty(), button -> advancePage())
                .bounds(leftPos + 194, topPos + 7, 98, 20).build());
        if (!isDog) {
            nativeProfileButton = addNativeButton(15, "screen.ic2newera.pet.native_profile", x, y + 69);
            nativeLogisticsButton = addNativeButton(16, "screen.ic2newera.pet.native_logistics", x + 142, y + 69);
        }
        menu.setInventoryPage(false);
    }

    private Button addNativeButton(int id, String key, int x, int y) {
        return addRenderableWidget(Button.builder(Component.translatable(key), button -> {
            if (minecraft != null && minecraft.gameMode != null)
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }).bounds(x, y, 138, 20).build());
    }

    private void advancePage() {
        boolean wasInventory = page == 2;
        page = (page + 1) % 3;
        boolean inventory = page == 2;
        menu.setInventoryPage(inventory);
        if (wasInventory != inventory && minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 14);
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
        MechanicalPet pet = menu.pet();
        if (pet == null || !pet.asEntity().isAlive()) {
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.unavailable"), leftPos + 8, topPos + 35, 0xE8796B);
            return;
        }
        if (page == 2) {
            graphics.fill(leftPos + 5, topPos + 27, leftPos + imageWidth - 5, topPos + 69, 0xFF293337);
            graphics.drawString(font, pet.isCat() ? Component.translatable("screen.ic2newera.pet.kind.cat")
                    : Component.translatable("screen.ic2newera.pet.kind.dog"), leftPos + 8, topPos + 31, 0xE9C46A);
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.health",
                    Math.round(pet.asEntity().getHealth()), Math.round(pet.asEntity().getMaxHealth())), leftPos + 112, topPos + 31, 0xF0C7B0);
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.hunger",
                    pet.getHunger(), MechanicalPet.MAX_HUNGER), leftPos + 8, topPos + 47,
                    pet.getHunger() == 0 ? 0xFFFF5B55 : 0xB5D18A);
            int charge = pet.getBatteryCharge();
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.battery",
                    charge, pet.isPowered() ? Component.translatable("options.on") : Component.translatable("options.off")),
                    leftPos + 112, topPos + 47, pet.isPowered() ? 0x76D9A5 : 0xE8796B);
            graphics.drawString(font, Component.translatable(pet.isFollowing()
                    ? "screen.ic2newera.pet.status.following" : "screen.ic2newera.pet.status.waiting"), leftPos + 8, topPos + 59, 0xD7E0DE);
            graphics.fill(leftPos + 5, topPos + 72, leftPos + imageWidth - 5, topPos + 84, 0xFF293337);
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.inventory.battery"), leftPos + 8, topPos + 73, 0xD7E0DE);
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.inventory.armor"), leftPos + 31, topPos + 73, 0xD7E0DE);
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.inventory.chest"), leftPos + 54, topPos + 73, 0xD7E0DE);
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.inventory.cargo"), leftPos + 92, topPos + 73, 0xD7E0DE);
            drawSlotBackgrounds(graphics);
            graphics.drawString(font, Component.translatable("screen.ic2newera.pet.inventory", PetInventory.CARGO_SIZE),
                    leftPos + 176, topPos + 74, 0xB7C4C2);
            return;
        }
        graphics.fill(leftPos + 5, topPos + 28, leftPos + imageWidth - 5, topPos + 110, 0xFF293337);
        graphics.fill(leftPos + 8, topPos + 31, leftPos + 105, topPos + 107, 0xFF13191B);
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, leftPos + 55, topPos + 104, 33,
                (float) (leftPos + 55) - mouseX, (float) (topPos + 64) - mouseY, pet.asEntity());
        graphics.drawString(font, pet.isCat() ? Component.translatable("screen.ic2newera.pet.kind.cat")
                : Component.translatable("screen.ic2newera.pet.kind.dog"), leftPos + 115, topPos + 36, 0xE9C46A);
        String statusKey = pet.isPancake() ? "screen.ic2newera.pet.status.pancake"
                : pet.isFollowing() ? "screen.ic2newera.pet.status.following" : "screen.ic2newera.pet.status.waiting";
        graphics.drawString(font, Component.translatable(statusKey), leftPos + 115, topPos + 50, 0xD7E0DE);
        graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.health",
                Math.round(pet.asEntity().getHealth()), Math.round(pet.asEntity().getMaxHealth())), leftPos + 115, topPos + 64, 0xF0C7B0);
        graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.hunger", pet.getHunger(), MechanicalPet.MAX_HUNGER),
                leftPos + 115, topPos + 78, pet.getHunger() == 0 ? 0xFFFF5B55 : 0xB5D18A);
        int charge = pet.getBatteryCharge();
        graphics.drawString(font, Component.translatable("screen.ic2newera.pet.status.battery",
                charge, pet.isPowered() ? Component.translatable("options.on") : Component.translatable("options.off")),
                leftPos + 115, topPos + 92, pet.isPowered() ? 0x76D9A5 : 0xE8796B);
        graphics.drawString(font, Component.translatable(page == 1
                ? "screen.ic2newera.pet.page.cute.title" : "screen.ic2newera.pet.page.daily.title"), leftPos + 8, topPos + 113, 0xB7C4C2);
    }

    private void drawSlotBackgrounds(GuiGraphics graphics) {
        for (int x : new int[]{8, 31, 54}) drawSlot(graphics, leftPos + x, topPos + 85, true);
        boolean hasChest = menu.pet() != null && menu.pet().hasPetChest();
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            drawSlot(graphics, leftPos + 92 + col * 18, topPos + 103 + row * 18, hasChest);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            drawSlot(graphics, leftPos + 8 + col * 18, topPos + 158 + row * 18, true);
        for (int col = 0; col < 9; col++) drawSlot(graphics, leftPos + 8 + col * 18, topPos + 212, true);
    }

    private static void drawSlot(GuiGraphics graphics, int x, int y, boolean active) {
        int fill = active ? 0xFF4A5557 : 0xFF282F30;
        graphics.fill(x, y, x + 16, y + 16, fill);
        graphics.fill(x, y, x + 16, y + 1, 0xFF111718);
        graphics.fill(x, y, x + 1, y + 16, 0xFF111718);
        graphics.fill(x + 15, y, x + 16, y + 16, 0xFF899596);
        graphics.fill(x, y + 15, x + 16, y + 16, 0xFF899596);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MechanicalPet pet = menu.pet();
        boolean accessible = pet != null && pet.asEntity().isAlive() && minecraft != null && minecraft.player != null
                && pet.asEntity().isOwnedBy(minecraft.player) && pet.asEntity().distanceToSqr(minecraft.player) <= 64;
        boolean transition = pet == null || pet.getPetAction() == PetAction.FLATTEN || pet.getPetAction() == PetAction.RESTORE;
        boolean barking = pet != null && pet.getPetAction() == PetAction.BARK;
        boolean interactionBusy = pet != null && pet.getPetAction().isInteraction();
        for (int i = 0; i < commandButtons.length; i++) {
            Button button = commandButtons[i];
            if (button == null) continue;
            button.visible = page == 0 ? i < 8 : page == 1 && i >= 8;
            boolean enabledForPancake = pet != null && (!pet.isPancake() || i == 0 || i == 5);
            boolean enabledDuringInteraction = !interactionBusy || i == 0 || i == 5 || (barking && i == 6);
            boolean powered = pet != null && (pet.isPowered() || i == 0 || i == 5);
            button.active = page != 2 && accessible && !transition && enabledForPancake && enabledDuringInteraction && powered;
        }
        if (nativeProfileButton != null) {
            nativeProfileButton.visible = page != 2;
            nativeProfileButton.active = accessible && !transition;
            nativeLogisticsButton.visible = page == 1;
            nativeLogisticsButton.active = accessible && pet != null && pet.hasPetChest() && !transition;
        }
        pageButton.setMessage(Component.translatable(page == 0 ? "screen.ic2newera.pet.page.cute"
                : page == 1 ? "screen.ic2newera.pet.page.inventory" : "screen.ic2newera.pet.page.actions"));
        pageButton.active = accessible;
        if (pet != null) {
            if (commandButtons[0] != null) commandButtons[0].setMessage(Component.translatable(pet.isFollowing()
                    ? "screen.ic2newera.pet.follow.stop" : "screen.ic2newera.pet.follow.start"));
            if (commandButtons[5] != null) commandButtons[5].setMessage(Component.translatable(pet.isPeaceful()
                    ? "screen.ic2newera.pet.peace.off" : "screen.ic2newera.pet.peace.on"));
            if (commandButtons[6] != null) commandButtons[6].setMessage(Component.translatable(barking
                    ? "screen.ic2newera.pet.bark.stop" : "screen.ic2newera.pet.bark"));
        }
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (page == 2) {
            Component tip = inventoryTooltip(mouseX, mouseY);
            if (tip != null) graphics.renderTooltip(font, tip, mouseX, mouseY);
        }
    }

    private Component inventoryTooltip(int mouseX, int mouseY) {
        int x = mouseX - leftPos, y = mouseY - topPos;
        if (inside(x, y, 8, 85)) return Component.translatable("screen.ic2newera.pet.tip.battery");
        if (inside(x, y, 31, 85)) return Component.translatable("screen.ic2newera.pet.tip.armor");
        if (inside(x, y, 54, 85)) return Component.translatable("screen.ic2newera.pet.tip.chest");
        if (x >= 92 && x < 92 + 9 * 18 && y >= 103 && y < 157) {
            if (menu.pet() == null || !menu.pet().hasPetChest()) return Component.translatable("screen.ic2newera.pet.tip.cargo_locked");
            return Component.translatable("screen.ic2newera.pet.tip.cargo");
        }
        return null;
    }

    private static boolean inside(int x, int y, int slotX, int slotY) {
        return x >= slotX && x < slotX + 16 && y >= slotY && y < slotY + 16;
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 5, 0xFFFFFF);
    }
    @Override public boolean isPauseScreen() { return false; }
}
