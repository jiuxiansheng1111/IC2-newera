package com.ic2newera.client.pet;

import com.ic2newera.pet.MechanicalPet;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.TamableAnimal;

/** Cuboid chest packs and horse-armor overlays that follow the authored pet rig. */
final class PetEquipmentLayer<T extends TamableAnimal & MechanicalPet> extends RenderLayer<T, AuthoredPetModel<T>> {
    private static final ResourceLocation WHITE = new ResourceLocation(
            "ic2newera", "textures/entity/pets/equipment_white.png");

    PetEquipmentLayer(RenderLayerParent<T, AuthoredPetModel<T>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight, T pet,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        ItemStack chest = pet.getPetChestStack();
        ItemStack armor = pet.getPetArmorStack();
        if (chest.isEmpty() && armor.isEmpty()) return;

        AuthoredPetModel model = getParentModel();
        var consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        if (!armor.isEmpty() && armor.getItem() instanceof HorseArmorItem) {
            renderArmor(model, pet, pose, consumer, packedLight, armorColor(armor));
        }
        if (!chest.isEmpty()) renderChestPacks(model, pet, pose, consumer, packedLight);
    }

    private static int armorColor(ItemStack stack) {
        if (stack.getItem() instanceof DyeableLeatherItem leather) return leather.getColor(stack);
        if (stack.is(Items.GOLDEN_HORSE_ARMOR)) return 0xE3B638;
        if (stack.is(Items.DIAMOND_HORSE_ARMOR)) return 0x53C4C4;
        if (stack.is(Items.IRON_HORSE_ARMOR)) return 0xB7C0C8;
        return 0xA9AFB2;
    }

    private static <T extends TamableAnimal & MechanicalPet> void renderArmor(AuthoredPetModel<T> model, T pet, PoseStack pose,
                                   com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int color) {
        boolean cat = pet.isCat();
        // A narrow back plate follows the torso while leaving cat wings and the rear flame clear.
        if (cat) {
            box(model, pet, pose, consumer, light, "body", -2F, 6F, -3F, 2F, 7F, 5F, color);
            box(model, pet, pose, consumer, light, "body", -2F, 4F, -4F, 2F, 5F, -3F, shade(color, 0.84F));
            // Collar is set below the muzzle and does not cross the mouth or eyes.
            box(model, pet, pose, consumer, light, "head", -2F, 5F, -4F, 2F, 6F, -3F, shade(color, 0.9F));
        } else {
            box(model, pet, pose, consumer, light, "body", -3F, 10F, -4F, 3F, 11F, 6F, color);
            box(model, pet, pose, consumer, light, "body", -2F, 6F, -5F, 2F, 8F, -4F, shade(color, 0.84F));
            // The head-following neck guard ends behind the muzzle and cannot cover the face.
            box(model, pet, pose, consumer, light, "head", -2F, 7F, -3F, 2F, 8F, -2F, shade(color, 0.9F));
        }

        for (String leg : new String[]{"leg_fl", "leg_fr", "leg_bl", "leg_br"}) {
            boolean front = leg.contains("f");
            float x0, x1, z0, z1, legBottom, legTop;
            if (cat) {
                boolean right = leg.endsWith("r");
                x0 = right ? 1F : -2F; x1 = right ? 2F : -1F;
                z0 = front ? -3F : 2F; z1 = front ? -1F : 4F;
                legBottom = 2F; legTop = 3F;
                // Stand the guard one full voxel in front of each paw, outside the leg volume.
                float guardZ0 = front ? -4F : 4F;
                float guardZ1 = front ? -3F : 5F;
                box(model, pet, pose, consumer, light, leg, x0, legBottom, guardZ0, x1, legTop, guardZ1,
                        shade(color, 0.93F));
            } else {
                boolean left = leg.endsWith("l");
                x0 = left ? 1F : -3F; x1 = left ? 3F : -1F;
                z0 = front ? -5F : 3F; z1 = front ? -2F : 7F;
                legBottom = 3F; legTop = 5F;
                float guardZ0 = front ? -6F : 7F;
                float guardZ1 = front ? -5F : 8F;
                float sideX0 = left ? 3F : -4F;
                float sideX1 = left ? 4F : -3F;
                float sideZ0 = front ? -5F : 6F;
                float sideZ1 = front ? -4F : 7F;
                box(model, pet, pose, consumer, light, leg, x0, legBottom, guardZ0, x1, legTop, guardZ1,
                        shade(color, 0.93F));
                box(model, pet, pose, consumer, light, leg, sideX0, legBottom, sideZ0, sideX1, 4F, sideZ1,
                        shade(color, 0.84F));
            }
        }
    }

    private static <T extends TamableAnimal & MechanicalPet> void renderChestPacks(AuthoredPetModel<T> model, T pet, PoseStack pose,
                                        com.mojang.blaze3d.vertex.VertexConsumer consumer, int light) {
        boolean cat = pet.isCat();
        float y0 = cat ? 7F : 7F;
        float y1 = cat ? 9F : 9F;
        float x0 = cat ? 2F : 3F;
        float x1 = cat ? 3F : 4F;
        float z0 = 0F;
        float z1 = 3F;
        for (float sign : new float[]{-1F, 1F}) {
            float minX = sign < 0 ? -x1 : x0;
            float maxX = sign < 0 ? -x0 : x1;
            // Packs sit above the cat's wing tips, away from its tail flame, and alongside the dog's ribs.
            box(model, pet, pose, consumer, light, "body", minX, y0, z0, maxX, y1 - 1F, z1, 0x95683F);
            box(model, pet, pose, consumer, light, "body", minX, y1 - 1F, z0, maxX, y1, z1, 0xB58A55);
            box(model, pet, pose, consumer, light, "body", minX, y0, -1F, maxX, y1, 0F, 0xD3AE5C);
        }
        if (cat) {
            // A central spine and cross-yoke sit directly on the backplate and meet both bags.
            box(model, pet, pose, consumer, light, "body", -1F, 7F, 1F, 1F, 8F, 2F, 0x95683F);
            box(model, pet, pose, consumer, light, "body", -2F, 8F, 1F, 2F, 9F, 2F, 0xB58A55);
        }
    }

    private static <T extends TamableAnimal & MechanicalPet> void box(AuthoredPetModel<T> model, T pet, PoseStack pose,
                            com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, String bone,
                            float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        model.renderEquipmentCuboid(pet, pose, consumer, light, bone, x0, y0, z0, x1, y1, z1, color);
    }

    private static int shade(int color, float multiplier) {
        int r = (int)(((color >> 16) & 0xFF) * multiplier);
        int g = (int)(((color >> 8) & 0xFF) * multiplier);
        int b = (int)((color & 0xFF) * multiplier);
        return (r << 16) | (g << 8) | b;
    }
}
