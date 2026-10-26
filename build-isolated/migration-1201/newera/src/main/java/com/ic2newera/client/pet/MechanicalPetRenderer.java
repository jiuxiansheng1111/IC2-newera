package com.ic2newera.client.pet;

import com.ic2newera.pet.MechanicalPetEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

public final class MechanicalPetRenderer extends MobRenderer<MechanicalPetEntity, AuthoredPetModel> {
    private final boolean cat;
    public MechanicalPetRenderer(EntityRendererProvider.Context context, boolean cat) {
        super(context, new AuthoredPetModel(), cat ? 0.23F : 0.3F);
        this.cat = cat;
        addLayer(new PetEquipmentLayer(this));
    }
    @Override public ResourceLocation getTextureLocation(MechanicalPetEntity entity) {
        AuthoredPetMesh mesh = AuthoredPetResources.INSTANCE.get(cat);
        return mesh == null ? MissingTextureAtlasSprite.getLocation() : mesh.texture;
    }
}
