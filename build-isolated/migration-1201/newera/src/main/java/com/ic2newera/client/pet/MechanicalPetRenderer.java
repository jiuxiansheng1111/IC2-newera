package com.ic2newera.client.pet;

import com.ic2newera.pet.MechanicalPet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.TamableAnimal;

public final class MechanicalPetRenderer<T extends TamableAnimal & MechanicalPet>
        extends MobRenderer<T, AuthoredPetModel<T>> {
    private final boolean cat;
    public MechanicalPetRenderer(EntityRendererProvider.Context context, boolean cat) {
        super(context, new AuthoredPetModel<>(), cat ? 0.23F : 0.3F);
        this.cat = cat;
        addLayer(new PetEquipmentLayer(this));
        addLayer(new MeowchanicsCatLayer<>(this));
    }
    @Override public ResourceLocation getTextureLocation(T entity) {
        AuthoredPetMesh mesh = AuthoredPetResources.INSTANCE.get(cat);
        return mesh == null ? MissingTextureAtlasSprite.getLocation() : mesh.texture;
    }
}
