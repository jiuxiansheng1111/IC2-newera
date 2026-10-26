package com.ic2newera.client.pet;

import cn.laowu.mod.CatClothesData;
import cn.laowu.mod.CatOutfitType;
import cn.laowu.mod.accessory.CatAccessories;
import com.ic2newera.pet.MechanicalPet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

/** Integer-cuboid career gear and small equipment badges for native Meowchanics cats. */
final class MeowchanicsCatLayer<T extends TamableAnimal & MechanicalPet>
        extends RenderLayer<T, AuthoredPetModel<T>> {
    private static final ResourceLocation WHITE = new ResourceLocation(
            "ic2newera", "textures/entity/pets/equipment_white.png");

    MeowchanicsCatLayer(RenderLayerParent<T, AuthoredPetModel<T>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight, T pet,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (!pet.isCat() || !(pet.asEntity() instanceof Cat cat)) return;
        CatOutfitType outfit = CatClothesData.isEquipped(cat) ? CatClothesData.getOutfit(cat) : CatOutfitType.NONE;
        if (outfit == null) outfit = CatOutfitType.NONE;
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        AuthoredPetModel<T> model = getParentModel();
        renderOutfit(model, pet, outfit, pose, consumer, packedLight);
        renderAccessoryBadges(model, pet, cat, pose, consumer, packedLight);
    }

    private static <T extends TamableAnimal & MechanicalPet> void renderOutfit(
            AuthoredPetModel<T> model, T pet, CatOutfitType outfit, PoseStack pose,
            VertexConsumer consumer, int light) {
        // Career pieces are deliberately small and sparse: the authored face, wings, four paw gaps,
        // and tail flame remain visible. Every cuboid is integer-aligned and follows a rig bone.
        switch (outfit) {
            case TERMINATOR -> {
                box(model, pet, pose, consumer, light, "body", -1, 5, 1, 1, 7, 2, 0x424F58);
                box(model, pet, pose, consumer, light, "head", -2, 10, -6, 2, 11, -4, 0x4B5963);
                box(model, pet, pose, consumer, light, "head", -1, 9, -8, 1, 10, -7, 0x79D8EF);
                box(model, pet, pose, consumer, light, "body", -1, 7, 0, 1, 9, 3, 0x424F58);
            }
            case FISHING -> {
                box(model, pet, pose, consumer, light, "body", -3, 5, 1, -1, 7, 3, 0x6B513B);
                box(model, pet, pose, consumer, light, "body", -3, 7, 1, -2, 9, 3, 0x6B513B);
                box(model, pet, pose, consumer, light, "body", -4, 8, 1, -3, 9, 2, 0xC59A55);
                box(model, pet, pose, consumer, light, "body", -3, 9, 2, -2, 10, 3, 0x5B9FA5);
            }
            case FLIGHT -> {
                // Centerline pack leaves the existing left/right wings completely uncovered.
                box(model, pet, pose, consumer, light, "body", -1, 5, 1, 1, 7, 2, 0x657D96);
                box(model, pet, pose, consumer, light, "body", -1, 7, -1, 1, 10, 3, 0x657D96);
                box(model, pet, pose, consumer, light, "body", -2, 8, 0, -1, 9, 2, 0xA8C9DC);
                box(model, pet, pose, consumer, light, "body", 1, 8, 0, 2, 9, 2, 0xA8C9DC);
                box(model, pet, pose, consumer, light, "head", -1, 10, -6, 1, 11, -4, 0x8AB5CE);
            }
            case FIRE -> {
                box(model, pet, pose, consumer, light, "body", -1, 5, 2, 1, 7, 3, 0x743E36);
                box(model, pet, pose, consumer, light, "body", -1, 7, 1, 1, 9, 4, 0x743E36);
                box(model, pet, pose, consumer, light, "body", -1, 8, 0, 1, 9, 1, 0xD5753B);
                box(model, pet, pose, consumer, light, "body", -1, 6, -3, 1, 7, -2, 0xDB873B);
            }
            case HONEY -> {
                box(model, pet, pose, consumer, light, "body", -3, 5, 1, -1, 7, 3, 0x9B6927);
                box(model, pet, pose, consumer, light, "body", 1, 5, 1, 3, 7, 3, 0x9B6927);
                sideJar(model, pet, pose, consumer, light, -1, 0xD49B2F);
                sideJar(model, pet, pose, consumer, light, 1, 0xE0B547);
                box(model, pet, pose, consumer, light, "body", -2, 8, 0, 2, 9, 1, 0xF2D36D);
            }
            case TRANSPORT -> {
                // Small balanced panniers sit above the wing root, with a center yoke on the spine.
                box(model, pet, pose, consumer, light, "body", -3, 5, 1, -1, 7, 2, 0x815B3E);
                box(model, pet, pose, consumer, light, "body", 1, 5, 1, 3, 7, 2, 0x815B3E);
                box(model, pet, pose, consumer, light, "body", -3, 7, 0, -2, 9, 3, 0x815B3E);
                box(model, pet, pose, consumer, light, "body", 2, 7, 0, 3, 9, 3, 0x815B3E);
                box(model, pet, pose, consumer, light, "body", -1, 7, 1, 1, 8, 2, 0xB28A59);
                box(model, pet, pose, consumer, light, "body", -3, 8, -1, -2, 9, 0, 0xD6B56B);
                box(model, pet, pose, consumer, light, "body", 2, 8, -1, 3, 9, 0, 0xD6B56B);
            }
            case DYNAMITE -> {
                box(model, pet, pose, consumer, light, "body", -2, 5, 1, 2, 7, 2, 0xA84E36);
                box(model, pet, pose, consumer, light, "body", -2, 7, 0, 2, 8, 3, 0xA84E36);
                box(model, pet, pose, consumer, light, "body", -1, 8, 0, 1, 9, 3, 0xC85E3D);
                box(model, pet, pose, consumer, light, "body", -1, 9, 1, 1, 10, 2, 0xE5BD54);
            }
            case ENGINEERING -> {
                box(model, pet, pose, consumer, light, "body", -1, 5, 1, 1, 7, 2, 0x727C81);
                saddlePod(model, pet, pose, consumer, light, 0x727C81, 0xE0AE45);
                box(model, pet, pose, consumer, light, "body", -3, 7, 1, -2, 9, 3, 0x9A7048);
                box(model, pet, pose, consumer, light, "body", -3, 9, 1, -2, 10, 3, 0xD8AB54);
                box(model, pet, pose, consumer, light, "body", -2, 9, 1, -1, 10, 2, 0xE0AE45);
            }
            case MEDICAL -> {
                box(model, pet, pose, consumer, light, "body", -1, 5, 1, 1, 7, 3, 0xE7E9E2);
                box(model, pet, pose, consumer, light, "body", -1, 7, 1, 1, 9, 3, 0xE7E9E2);
                box(model, pet, pose, consumer, light, "body", -2, 8, 0, 2, 9, 1, 0xC84D4D);
                box(model, pet, pose, consumer, light, "body", -1, 7, 0, 1, 9, 1, 0xC84D4D);
                box(model, pet, pose, consumer, light, "body", 2, 7, 1, 3, 9, 3, 0xD8E0DC);
            }
            case MUSIC -> {
                box(model, pet, pose, consumer, light, "body", -3, 5, 0, -1, 7, 2, 0x8C563A);
                box(model, pet, pose, consumer, light, "body", -3, 7, 0, -2, 9, 2, 0x8C563A);
                box(model, pet, pose, consumer, light, "body", -4, 7, 0, -3, 9, 2, 0xE1B968);
                box(model, pet, pose, consumer, light, "body", -1, 7, 1, 1, 8, 2, 0xD9B251);
            }
            case AGENT -> {
                box(model, pet, pose, consumer, light, "head", -4, 7, -5, -3, 9, -4, 0x33434A);
                box(model, pet, pose, consumer, light, "head", -4, 8, -6, -3, 9, -5, 0xD75B4B);
                box(model, pet, pose, consumer, light, "body", -2, 5, 1, 2, 7, 2, 0xA9B6B2);
                box(model, pet, pose, consumer, light, "body", -2, 7, 1, 2, 8, 2, 0xA9B6B2);
            }
            case DIVING -> {
                box(model, pet, pose, consumer, light, "head", -2, 10, -6, 2, 11, -4, 0x557D83);
                box(model, pet, pose, consumer, light, "head", -2, 10, -8, -1, 11, -7, 0x8BD0D0);
                box(model, pet, pose, consumer, light, "head", 1, 10, -8, 2, 11, -7, 0x8BD0D0);
                box(model, pet, pose, consumer, light, "body", -2, 5, 1, 0, 7, 3, 0x4D777D);
                box(model, pet, pose, consumer, light, "body", 0, 5, 1, 2, 7, 3, 0x4D777D);
                box(model, pet, pose, consumer, light, "body", -2, 7, 1, -1, 9, 3, 0x4D777D);
                box(model, pet, pose, consumer, light, "body", 1, 7, 1, 2, 9, 3, 0x4D777D);
            }
            case COCKROACH -> {
                box(model, pet, pose, consumer, light, "body", -1, 5, 1, 1, 7, 2, 0x72513F);
                box(model, pet, pose, consumer, light, "body", -1, 7, -1, 1, 8, 4, 0x72513F);
                box(model, pet, pose, consumer, light, "body", -2, 7, 0, -1, 8, 3, 0x9A6448);
                box(model, pet, pose, consumer, light, "body", 1, 7, 0, 2, 8, 3, 0x9A6448);
                box(model, pet, pose, consumer, light, "body", -1, 8, 1, 1, 9, 3, 0xC18B58);
            }
            default -> { }
        }
    }

    private static <T extends TamableAnimal & MechanicalPet> void saddlePod(
            AuthoredPetModel<T> model, T pet, PoseStack pose, VertexConsumer consumer, int light,
            int shell, int highlight) {
        box(model, pet, pose, consumer, light, "body", -1, 7, 0, 1, 9, 3, shell);
        box(model, pet, pose, consumer, light, "body", -1, 8, -1, 1, 9, 0, highlight);
    }

    private static <T extends TamableAnimal & MechanicalPet> void sideJar(
            AuthoredPetModel<T> model, T pet, PoseStack pose, VertexConsumer consumer, int light,
            int side, int color) {
        float x0 = side < 0 ? -3 : 2, x1 = side < 0 ? -2 : 3;
        box(model, pet, pose, consumer, light, "body", x0, 7, 1, x1, 9, 3, color);
        box(model, pet, pose, consumer, light, "body", x0, 8, 0, x1, 9, 1, 0xF2D878);
    }

    private static <T extends TamableAnimal & MechanicalPet> void renderAccessoryBadges(
            AuthoredPetModel<T> model, T pet, Cat cat, PoseStack pose, VertexConsumer consumer, int light) {
        int index = 0;
        for (ItemStack stack : CatAccessories.equipment(cat)) {
            if (stack.isEmpty() || index >= 3) continue;
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            int color = badgeColor(path);
            float x = -1F + index;
            // A small dorsal plate is visible above the back and clear of wings, packs and tail flame.
            box(model, pet, pose, consumer, light, "body", x, 8, 3, x + 1F, 9, 4, color);
            box(model, pet, pose, consumer, light, "body", x, 9, 3, x + 1F, 10, 4, 0xE5D6A7);
            index++;
        }
    }

    private static int badgeColor(String path) {
        if (path.contains("fire") || path.contains("flame") || path.contains("blast")) return 0xC85D3D;
        if (path.contains("health") || path.contains("medical") || path.contains("bandage")) return 0xC54C50;
        if (path.contains("speed") || path.contains("feather") || path.contains("flight")) return 0x6DADCE;
        if (path.contains("attack") || path.contains("boxing") || path.contains("spiked")) return 0xB67843;
        if (path.contains("luck") || path.contains("magnet")) return 0x7C9C58;
        if (path.contains("intelligence") || path.contains("engineering") || path.contains("gear")) return 0xC29D4E;
        return 0xA7B0B1;
    }

    private static <T extends TamableAnimal & MechanicalPet> void box(
            AuthoredPetModel<T> model, T pet, PoseStack pose, VertexConsumer consumer, int light,
            String bone, float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        model.renderEquipmentCuboid(pet, pose, consumer, light, bone, x0, y0, z0, x1, y1, z1, color);
    }
}
