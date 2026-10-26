package com.ic2newera.client.industrialhostile;

import com.google.gson.Gson;
import com.ic2newera.industrialhostile.EncounterHostile;
import com.ic2newera.industrialhostile.PatrolDrone;
import com.ic2newera.industrialhostile.IndustrialSentinel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The packaged mesh and editable Blockbench file share one generated geometry/UV source. */
final class IndustrialHostileModel<T extends EncounterHostile> extends EntityModel<T> {
    private final ModelPart root;
    private final ModelPart body;
    private final Map<String, ModelPart> parts = new LinkedHashMap<>();

    IndustrialHostileModel(ModelPart root) {
        this.root = root;
        body = root.getChild("body");
        for (String name : List.of("scanner", "rotor_left_front", "rotor_left_rear", "rotor_right_front", "rotor_right_rear",
                "head", "left_leg", "right_leg", "left_arm", "hammer_arm")) {
            if (body.hasChild(name)) parts.put(name, body.getChild(name));
        }
    }

    static LayerDefinition layer(String name) {
        String path = "/assets/ic2newera/models/entity/industrialhostile/" + name + ".json";
        try (var stream = Objects.requireNonNull(IndustrialHostileModel.class.getResourceAsStream(path), path);
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Mesh data = new Gson().fromJson(reader, Mesh.class);
            MeshDefinition mesh = new MeshDefinition();
            Map<String, PartDefinition> definitions = new LinkedHashMap<>();
            Map<String, Bone> bones = new LinkedHashMap<>();
            for (Bone bone : data.bones) {
                bones.put(bone.name, bone);
                CubeListBuilder boxes = CubeListBuilder.create();
                for (Box box : data.cubes) if (box.bone.equals(bone.name)) {
                    boxes.texOffs(box.uv[0], box.uv[1]).addBox(box.from[0] - bone.pivot[0], bone.pivot[1] - box.to[1],
                            box.from[2] - bone.pivot[2], box.to[0] - box.from[0], box.to[1] - box.from[1], box.to[2] - box.from[2]);
                }
                Bone parent = bones.get(bone.parent);
                PartDefinition parentDefinition = parent == null ? mesh.getRoot() : definitions.get(parent.name);
                PartPose pose = parent == null ? PartPose.offset(bone.pivot[0], 24 - bone.pivot[1], bone.pivot[2])
                        : PartPose.offset(bone.pivot[0] - parent.pivot[0], parent.pivot[1] - bone.pivot[1], bone.pivot[2] - parent.pivot[2]);
                definitions.put(bone.name, parentDefinition.addOrReplaceChild(bone.name, boxes, pose));
            }
            return LayerDefinition.create(mesh, data.texture_size[0], data.texture_size[1]);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取原创工业怪物模型 " + name, exception);
        }
    }

    @Override public void setupAnim(T entity, float swing, float amount, float age, float yaw, float pitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        if (entity instanceof PatrolDrone) {
            body.y -= Mth.sin(age * Mth.PI / 20) * 0.7F;
            for (var part : parts.entrySet()) if (part.getKey().startsWith("rotor_"))
                part.getValue().yRot = age * Mth.PI / 5;
            ModelPart eye = parts.get("scanner");
            eye.yRot = Mth.clamp(yaw, -30, 30) * Mth.DEG_TO_RAD;
            eye.xRot = Mth.clamp(pitch, -20, 20) * Mth.DEG_TO_RAD;
        } else {
            float walk = Mth.cos(swing * 0.65F) * 0.45F * amount;
            parts.get("left_leg").xRot = walk;
            parts.get("right_leg").xRot = -walk;
            parts.get("left_arm").xRot = -walk * 0.5F;
            parts.get("head").yRot = Mth.clamp(yaw, -35, 35) * Mth.DEG_TO_RAD;
            ModelPart hammer = parts.get("hammer_arm");
            if (entity.combatPhase() == EncounterHostile.WINDUP) {
                float progress = 1 - entity.phaseTicks() / (float) IndustrialSentinel.WINDUP_TICKS;
                // Blockbench uses Y-up; ModelPart uses Y-down, so X/Z rotation signs are reflected.
                hammer.xRot = 125 * Mth.DEG_TO_RAD * Mth.clamp(progress, 0, 1);
                body.xRot = -0.08F * progress;
            } else if (entity.combatPhase() == EncounterHostile.RECOVERY) {
                float remaining = entity.phaseTicks() / (float) IndustrialSentinel.RECOVERY_TICKS;
                hammer.xRot = -40 * Mth.DEG_TO_RAD * Mth.clamp(remaining, 0, 1);
                body.xRot = 0.12F * remaining;
            }
        }
    }

    @Override public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                                          float red, float green, float blue, float alpha) {
        root.render(pose, buffer, light, overlay, red, green, blue, alpha);
    }
    private static final class Mesh { int[] texture_size; List<Bone> bones; List<Box> cubes; }
    private static final class Bone { String name; float[] pivot; String parent; }
    private static final class Box { String bone; float[] from; float[] to; int[] uv; }
}
