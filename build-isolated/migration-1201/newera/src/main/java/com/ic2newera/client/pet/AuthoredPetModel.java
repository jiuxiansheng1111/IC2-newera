package com.ic2newera.client.pet;

import com.ic2newera.pet.MechanicalPetEntity;
import com.ic2newera.pet.PetAction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.util.Mth;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Authored voxel pet rig with absolute-pivot hierarchical pose transforms. */
final class AuthoredPetModel extends EntityModel<MechanicalPetEntity> {
    private AuthoredPetMesh mesh;
    private AuthoredPetMesh cachedMesh;
    private final Map<String, float[]> posedVertices = new HashMap<>();
    private final Map<String, float[]> rigMatrices = new HashMap<>();
    private final Map<String, float[]> finalMatrices = new HashMap<>();
    private final Map<String, float[]> normalMatrices = new HashMap<>();
    private final Map<String, float[][]> restOrientations = new HashMap<>();
    private final Map<String, float[][]> parentRestOrientations = new HashMap<>();
    private final Map<MechanicalPetEntity, BlendState> blendStates = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private float groundOffset;
    private float animationTime;

    /** Small per-entity pose history; weak keys avoid retaining unloaded pets. */
    private static final class BlendState {
        PetAction action;
        int actionTicks;
        boolean sitting;
        float lastAge;
        float transitionAge;
        float[] currentAngles, currentScales, fromAngles, fromScales;
        boolean initialized;

        void ensureSize(int values) {
            if (currentAngles != null && currentAngles.length == values) return;
            currentAngles = new float[values]; fromAngles = new float[values];
            currentScales = new float[values]; fromScales = new float[values];
            for (int i = 0; i < values; i++) currentScales[i] = fromScales[i] = 1F;
            initialized = false;
        }
    }

    @Override
    public void setupAnim(MechanicalPetEntity pet, float limbSwing, float limbAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        animationTime = ageInTicks;
        mesh = AuthoredPetResources.INSTANCE.get(pet.isCat());
        if (mesh == null) { cachedMesh = null; posedVertices.clear(); groundOffset = 0; return; }
        if (mesh != cachedMesh) {
            cachedMesh = mesh;
            posedVertices.clear(); rigMatrices.clear(); finalMatrices.clear(); normalMatrices.clear();
            restOrientations.clear(); parentRestOrientations.clear();
            for (AuthoredPetMesh.Bone bone : mesh.bones) {
                posedVertices.put(bone.name(), new float[bone.vertices().length]);
                rigMatrices.put(bone.name(), new float[16]);
                finalMatrices.put(bone.name(), new float[16]);
                normalMatrices.put(bone.name(), new float[9]);
                float[][] parentRest = bone.parent() == null ? identity3() : restOrientations.get(bone.parent());
                if (parentRest == null) parentRest = identity3();
                parentRestOrientations.put(bone.name(), parentRest);
                restOrientations.put(bone.name(), multiply(parentRest, rotation(radians(bone.defaultRotation()))));
            }
        }
        Map<String, float[]> angles = new HashMap<>();
        Map<String, float[]> scales = new HashMap<>();
        for (AuthoredPetMesh.Bone bone : mesh.bones) {
            float[] rotation = bone.defaultRotation().clone();
            for (int i = 0; i < 3; i++) rotation[i] *= Mth.DEG_TO_RAD;
            angles.put(bone.name(), rotation);
            scales.put(bone.name(), new float[]{1, 1, 1});
        }
        float walk = Mth.cos(limbSwing * 0.72F) * 0.52F * limbAmount;
        rotate(angles, "leg_fl", walk, 0, 0); rotate(angles, "leg_br", walk, 0, 0);
        rotate(angles, "leg_fr", -walk, 0, 0); rotate(angles, "leg_bl", -walk, 0, 0);
        // Applied to torso vertices only below so the child leg bones remain planted.
        float idleBodySway = Mth.sin(ageInTicks * 0.075F) * 0.009F;
        rotate(angles, "head", 0, 0, Mth.sin(ageInTicks * 0.062F + 0.7F) * 0.010F);
        rotate(angles, "tail", 0, Mth.sin(ageInTicks * 0.075F) * 0.016F, 0);
        if (pet.isCat()) {
            // Symmetric, slow wing movement (about four degrees at the tips) and a tiny flame flick.
            float wingBeat = Mth.sin(ageInTicks * 0.055F) * 0.07F;
            rotate(angles, "wing_l", 0, 0, wingBeat);
            rotate(angles, "wing_r", 0, 0, -wingBeat);
            rotate(angles, "tail_flame", Mth.sin(ageInTicks * 0.09F) * 0.055F,
                    0, Mth.sin(ageInTicks * 0.065F + 0.8F) * 0.035F);
        }
        rotate(angles, "head", Mth.clamp(headPitch, -24F, 24F) * Mth.DEG_TO_RAD,
                Mth.clamp(netHeadYaw, -38F, 38F) * Mth.DEG_TO_RAD, 0);

        PetAction action = pet.getPetAction();
        float partial = Mth.clamp(ageInTicks - pet.tickCount, 0F, 1F);
        float progress = pet.getActionProgress(partial);
        float pulse = Mth.sin(progress * Mth.PI);
        float species = pet.isCat() ? 1F : -1F;
        if (pet.isOrderedToSit() && action == PetAction.IDLE) {
            rotate(angles, "body", 0.08F, 0, 0);
            rotate(angles, "leg_fl", 0.25F, 0, 0); rotate(angles, "leg_fr", 0.25F, 0, 0);
            rotate(angles, "leg_bl", -0.82F, 0, 0); rotate(angles, "leg_br", -0.82F, 0, 0);
            rotate(angles, "tail", -0.3F, 0, 0);
        }
        switch (action) {
            case PET -> {
                rotate(angles, "head", -0.08F * Mth.sin(progress * Mth.PI * 4), 0,
                        0.3F * Mth.sin(progress * Mth.PI * 2));
                rotate(angles, "tail", 0, 0.62F * Mth.sin(progress * Mth.PI * 3), 0);
                rotate(angles, "ear_l", 0, 0, 0.16F * Mth.sin(progress * Mth.PI * 6));
                rotate(angles, "ear_r", 0, 0, -0.16F * Mth.sin(progress * Mth.PI * 6));
            }
            case EAT -> {
                float chew = Math.max(0, Mth.sin(progress * Mth.PI * 8));
                // In this Y-up, -Z-forward rig, negative X lowers the muzzle and opens the chin.
                rotate(angles, "head", -0.2F - 0.08F * chew, 0, 0);
                rotate(angles, "jaw", -(pet.isCat() ? 0.38F : 0.48F) * chew, 0, 0);
                scale(scales, "eye_l", 1, 1 - 0.3F * Math.max(0, Mth.sin(progress * Mth.PI * 4)), 1);
                scale(scales, "eye_r", 1, 1 - 0.3F * Math.max(0, Mth.sin(progress * Mth.PI * 4)), 1);
                rotate(angles, "tail", 0, 0.28F * Mth.sin(progress * Mth.PI * 4), 0);
            }
            case JUMP -> {
                // Positive X lifts the downward-pointing paws forward toward the belly.
                rotate(angles, "body", 0.14F * pulse, 0, 0);
                for (String leg : LEGS) rotate(angles, leg, 0.82F * pulse, 0, 0);
            }
            case NOD -> rotate(angles, "head", -0.42F * Mth.sin(progress * Mth.PI * 2), 0, 0);
            case SHAKE -> rotate(angles, "head", 0, 0.5F * Mth.sin(progress * Mth.PI * 4), 0);
            case STANDOFF -> { rotate(angles, "body", -0.13F, 0, 0); rotate(angles, "tail", -0.48F, 0, 0); }
            case POUNCE -> {
                rotate(angles, "body", -0.28F * pulse, 0, 0);
                rotate(angles, "leg_fl", 0.7F * pulse, 0, 0); rotate(angles, "leg_fr", 0.7F * pulse, 0, 0);
                rotate(angles, "leg_bl", -0.7F * pulse, 0, 0); rotate(angles, "leg_br", -0.7F * pulse, 0, 0);
                if (!pet.isCat()) {
                    // Dogs dip the muzzle onto the target and open the lower jaw for the bite.
                    rotate(angles, "head", -0.12F * pulse, 0, 0);
                    rotate(angles, "jaw", -0.78F * pulse, 0, 0);
                }
            }
            case SWIPE -> {
                rotate(angles, "leg_fl", 1.05F * pulse, 0, 0);
                rotate(angles, "leg_fr", 0.18F * pulse, 0, 0);
                rotate(angles, "body", -0.07F, 0, 0);
            }
            case RETREAT -> { rotate(angles, "body", 0.12F * pulse, 0, 0); rotate(angles, "tail", -0.6F, 0, 0); }
            case BARK -> {
                float beat = Mth.sin(ageInTicks * 0.68F), step = Mth.sin(ageInTicks * 0.42F);
                // Raise the -Z muzzle while rotating the lower jaw down to call.
                rotate(angles, "head", 0.38F + 0.11F * beat, 0, 0);
                rotate(angles, "jaw", -0.35F * Math.max(0, beat), 0, 0);
                rotate(angles, "ear_l", 0.2F * beat, 0, 0); rotate(angles, "ear_r", -0.2F * beat, 0, 0);
                rotate(angles, "body", 0, 0, 0.12F * Mth.sin(ageInTicks * 0.31F));
                rotate(angles, "leg_fl", 0.55F * Math.max(0, step), 0, 0);
                rotate(angles, "leg_fr", 0.55F * Math.max(0, -step), 0, 0);
                rotate(angles, "leg_bl", 0.32F * Math.max(0, -step), 0, 0);
                rotate(angles, "leg_br", 0.32F * Math.max(0, step), 0, 0);
                rotate(angles, "tail", 0, 0.55F * Mth.sin(ageInTicks * 0.54F), 0);
            }
            case DANCE -> {
                float beat = Mth.sin(progress * Mth.PI * 10);
                rotate(angles, "body", 0, 0, 0.2F * Mth.sin(progress * Mth.PI * 5));
                rotate(angles, "leg_fl", 0.82F * Math.max(0, beat), 0, 0);
                rotate(angles, "leg_fr", 0.82F * Math.max(0, -beat), 0, 0);
                rotate(angles, "leg_bl", 0.5F * Math.max(0, -beat), 0, 0);
                rotate(angles, "leg_br", 0.5F * Math.max(0, beat), 0, 0);
                rotate(angles, "tail", 0, 0.48F * Mth.sin(progress * Mth.PI * 10), 0);
                rotate(angles, "head", 0, 0, 0.12F * Mth.sin(progress * Mth.PI * 10));
            }
            case TILT -> {
                rotate(angles, "head", -0.1F * pulse, 0, species * 0.48F * pulse);
                rotate(angles, "ear_l", 0.12F * Mth.sin(progress * Mth.PI * 2), 0, 0);
                rotate(angles, "ear_r", -0.12F * Mth.sin(progress * Mth.PI * 2), 0, 0);
                rotate(angles, "tail", 0, (pet.isCat() ? 0.48F : 0.3F) * Mth.sin(progress * Mth.PI * 2), 0);
            }
            case PAW -> {
                // The foreleg paw lies below and slightly forward of its pivot; +X raises it forward.
                rotate(angles, "leg_fl", 1.05F * pulse, 0, 0);
                rotate(angles, "leg_fr", 0.15F * pulse, 0, 0);
                rotate(angles, "body", 0, 0, 0.08F * species * pulse);
                rotate(angles, "tail", 0, 0.35F * Mth.sin(progress * Mth.PI * 2), 0);
            }
            case STRETCH -> {
                // Lower the chest/muzzle, reach the forepaws forward, and fold the hind paws back.
                rotate(angles, "body", -0.28F * pulse, 0, 0);
                rotate(angles, "leg_fl", 0.82F * pulse, 0, 0); rotate(angles, "leg_fr", 0.82F * pulse, 0, 0);
                rotate(angles, "leg_bl", -0.72F * pulse, 0, 0); rotate(angles, "leg_br", -0.72F * pulse, 0, 0);
                rotate(angles, "head", -0.22F * pulse, 0, 0);
                rotate(angles, "tail", -0.48F * pulse, 0, 0);
            }
            case ROLL -> {
                rotate(angles, "body", 0, 0, Mth.PI * 2 * progress);
                for (String leg : LEGS) rotate(angles, leg, 0.72F * pulse, 0, 0.3F * pulse);
                rotate(angles, "head", 0, 0, species * 0.18F * Mth.sin(progress * Mth.PI * 2));
                rotate(angles, "tail", 0, 0.65F * Mth.sin(progress * Mth.PI * 2), 0);
            }
            case BOW -> {
                // The torso and muzzle extend toward -Z, so negative X dips the front of the pet.
                rotate(angles, "body", -(pet.isCat() ? 0.32F : 0.28F) * pulse, 0, 0);
                rotate(angles, "head", -0.3F * pulse, 0, 0);
                rotate(angles, "leg_fl", 0.12F * pulse, 0, 0); rotate(angles, "leg_fr", 0.12F * pulse, 0, 0);
                rotate(angles, "tail", -0.3F * pulse, 0, 0);
            }
            case YAWN -> {
                // Same lower-jaw hinge direction as EAT; squinting stays synchronized with the open mouth.
                rotate(angles, "head", -0.14F * pulse, 0, 0);
                rotate(angles, "body", -0.16F * pulse, 0, 0);
                rotate(angles, "jaw", -(pet.isCat() ? 0.5F : 0.62F) * pulse, 0, 0);
                scale(scales, "eye_l", 1, 1 - 0.84F * pulse, 1);
                scale(scales, "eye_r", 1, 1 - 0.84F * pulse, 1);
                rotate(angles, "lid_l", 0.4F * pulse, 0, 0); rotate(angles, "lid_r", 0.4F * pulse, 0, 0);
                rotate(angles, "leg_fl", 0.25F * pulse, 0, 0); rotate(angles, "leg_fr", 0.25F * pulse, 0, 0);
                rotate(angles, "tail", (pet.isCat() ? -0.45F : -0.25F) * pulse, 0, 0);
            }
            case HISS -> {
                float hiss = 0.45F + 0.55F * pulse;
                float quiver = Mth.sin(ageInTicks * 1.1F);
                // The body remains a cuboid: a restrained lift and pitch suggest an arched back.
                rotate(angles, "body", -0.16F * hiss, 0, 0);
                scale(scales, "body", 1F, 1F + 0.035F * hiss, 1F);
                rotate(angles, "head", 0.12F * hiss, 0, 0);
                rotate(angles, "jaw", -(pet.isCat() ? 0.46F : 0.5F) * hiss, 0, 0);
                rotate(angles, "ear_l", -0.3F * hiss, 0, -0.28F * hiss);
                rotate(angles, "ear_r", -0.3F * hiss, 0, 0.28F * hiss);
                rotate(angles, "tail", -0.42F * hiss, 0.12F * quiver * hiss, 0);
                // Cancel locomotion swing while hissing, keeping all four cuboid legs apart.
                rotate(angles, "leg_fl", -walk, 0, 0); rotate(angles, "leg_br", -walk, 0, 0);
                rotate(angles, "leg_fr", walk, 0, 0); rotate(angles, "leg_bl", walk, 0, 0);
            }
            default -> { }
        }

        smoothPose(pet, action, pet.tickCount + partial, angles, scales);

        float flattenLinear = Mth.clamp(pet.getFlattenProgress(partial), 0F, 1F);
        float flatten = smootherstep(flattenLinear);
        float verticalScale = 1F - 0.78F * flatten;
        float horizontalScale = 1F + 0.06F * flatten;
        float centerX = (mesh.minX + mesh.maxX) * 0.5F, centerZ = (mesh.minZ + mesh.maxZ) * 0.5F;
        float[] flattenMatrix = scalingAround(new float[]{centerX, mesh.minY, centerZ},
                horizontalScale, verticalScale, horizontalScale);
        float[] identity = identity4();
        for (AuthoredPetMesh.Bone bone : mesh.bones) {
            float[] parentMatrix = bone.parent() == null ? identity : rigMatrices.getOrDefault(bone.parent(), identity);
            float[] base = radians(bone.defaultRotation());
            float[][] localDelta = deltaRotation(base, angles.get(bone.name()));
            float[][] parentBasis = parentRestOrientations.get(bone.name());
            float[][] delta = multiply(multiply(parentBasis, localDelta), transpose(parentBasis));
            float[] rotationMatrix = around(bone.pivot(), delta);
            float[] scale = scales.get(bone.name());
            float[][] ownBasis = restOrientations.get(bone.name());
            float[][] localScale = {{scale[0],0,0},{0,scale[1],0},{0,0,scale[2]}};
            float[][] scaleAxes = multiply(multiply(ownBasis, localScale), transpose(ownBasis));
            float[] scaleMatrix = around(bone.pivot(), scaleAxes);
            float[] rigMatrix = multiply4(parentMatrix, multiply4(scaleMatrix, rotationMatrix));
            float[] posedRig = rigMatrix;
            if ("body".equals(bone.name()) && action == PetAction.IDLE && Math.abs(idleBodySway) > 1.0E-7F) {
                float[] sway = around(bone.pivot(), rotation(new float[]{0F, idleBodySway, 0F}));
                posedRig = multiply4(sway, rigMatrix);
            }
            float[] fullMatrix = multiply4(flattenMatrix, posedRig);
            System.arraycopy(rigMatrix, 0, rigMatrices.get(bone.name()), 0, 16);
            System.arraycopy(fullMatrix, 0, finalMatrices.get(bone.name()), 0, 16);
            inverseTranspose3(fullMatrix, normalMatrices.get(bone.name()));
        }
        float posedMinY = Float.MAX_VALUE;
        for (AuthoredPetMesh.Bone bone : mesh.bones) {
            float[] source = bone.vertices();
            float[] transformed = posedVertices.get(bone.name());
            float[] matrix = finalMatrices.get(bone.name());
            float[] normalMatrix = normalMatrices.get(bone.name());
            for (int i = 0; i < source.length; i += AuthoredPetMesh.STRIDE) {
                float x = source[i], y = source[i + 1], z = source[i + 2];
                float px = matrix[0] * x + matrix[1] * y + matrix[2] * z + matrix[3];
                float py = matrix[4] * x + matrix[5] * y + matrix[6] * z + matrix[7];
                float pz = matrix[8] * x + matrix[9] * y + matrix[10] * z + matrix[11];
                float nx = normalMatrix[0] * source[i + 5] + normalMatrix[1] * source[i + 6] + normalMatrix[2] * source[i + 7];
                float ny = normalMatrix[3] * source[i + 5] + normalMatrix[4] * source[i + 6] + normalMatrix[5] * source[i + 7];
                float nz = normalMatrix[6] * source[i + 5] + normalMatrix[7] * source[i + 6] + normalMatrix[8] * source[i + 7];
                float normalLength = Mth.sqrt(nx * nx + ny * ny + nz * nz);
                if (normalLength > 1.0E-7F) { nx /= normalLength; ny /= normalLength; nz /= normalLength; }
                posedMinY = Math.min(posedMinY, py);
                transformed[i] = px; transformed[i + 1] = py; transformed[i + 2] = pz;
                transformed[i + 3] = source[i + 3]; transformed[i + 4] = source[i + 4];
                transformed[i + 5] = nx; transformed[i + 6] = ny; transformed[i + 7] = nz;
            }
        }
        boolean airborne = action == PetAction.JUMP || action == PetAction.POUNCE;
        groundOffset = pet.onGround() && !airborne && Float.isFinite(posedMinY) ? -posedMinY : 0;
    }

    private static final String[] LEGS = {"leg_fl", "leg_fr", "leg_bl", "leg_br"};

    private void smoothPose(MechanicalPetEntity pet, PetAction action, float ageInTicks,
                            Map<String, float[]> angles, Map<String, float[]> scales) {
        int size = cachedMesh.bones.size() * 3;
        BlendState state = blendStates.computeIfAbsent(pet, ignored -> new BlendState());
        state.ensureSize(size);
        boolean sitting = pet.isOrderedToSit();
        boolean actionChanged = state.initialized && (state.action != action || state.sitting != sitting
                || action != PetAction.IDLE && pet.getActionTicks() > state.actionTicks);
        if (!state.initialized) {
            int index = 0;
            for (AuthoredPetMesh.Bone bone : cachedMesh.bones) {
                float[] target = angles.get(bone.name()), targetScale = scales.get(bone.name());
                for (int axis = 0; axis < 3; axis++, index++) {
                    state.currentAngles[index] = target[axis];
                    state.currentScales[index] = targetScale[axis];
                }
            }
            state.initialized = true;
            state.transitionAge = 6F;
            state.lastAge = ageInTicks;
        } else if (actionChanged) {
            System.arraycopy(state.currentAngles, 0, state.fromAngles, 0, size);
            System.arraycopy(state.currentScales, 0, state.fromScales, 0, size);
            state.transitionAge = 0F;
        }
        // Multiple render contexts may draw the same entity with different ageInTicks.
        // Only advance on a monotonic entity-time sample; stale GUI samples cannot rewind
        // the clock and make the next world render skip the transition.
        if (ageInTicks >= state.lastAge) {
            float elapsed = ageInTicks - state.lastAge;
            state.transitionAge = Math.min(6F, state.transitionAge + Math.min(elapsed, 6F));
            state.lastAge = ageInTicks;
        }
        float blend = smootherstep(state.transitionAge / 6F);
        int index = 0;
        for (AuthoredPetMesh.Bone bone : cachedMesh.bones) {
            float[] target = angles.get(bone.name()), targetScale = scales.get(bone.name());
            for (int axis = 0; axis < 3; axis++, index++) {
                float angle = Mth.lerp(blend, state.fromAngles[index], target[axis]);
                float scale = Mth.lerp(blend, state.fromScales[index], targetScale[axis]);
                target[axis] = angle;
                targetScale[axis] = scale;
                state.currentAngles[index] = angle;
                state.currentScales[index] = scale;
            }
        }
        state.action = action;
        state.sitting = sitting;
        state.actionTicks = pet.getActionTicks();
    }

    private static float smootherstep(float value) {
        float t = Mth.clamp(value, 0F, 1F);
        return t * t * t * (t * (t * 6F - 15F) + 10F);
    }

    private static void rotate(Map<String, float[]> angles, String bone, float x, float y, float z) {
        float[] value = angles.get(bone);
        if (value != null) { value[0] += x; value[1] += y; value[2] += z; }
    }
    private static void scale(Map<String, float[]> scales, String bone, float x, float y, float z) {
        float[] value = scales.get(bone);
        if (value != null) { value[0] *= x; value[1] *= y; value[2] *= z; }
    }
    private static float[] radians(float[] degrees) {
        return new float[]{degrees[0] * Mth.DEG_TO_RAD, degrees[1] * Mth.DEG_TO_RAD, degrees[2] * Mth.DEG_TO_RAD};
    }
    private static float[][] rotation(float[] a) {
        float sx = Mth.sin(a[0]), cx = Mth.cos(a[0]), sy = Mth.sin(a[1]), cy = Mth.cos(a[1]), sz = Mth.sin(a[2]), cz = Mth.cos(a[2]);
        return new float[][]{{cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx},
                {sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx}, {-sy, cy * sx, cy * cx}};
    }
    private static float[][] identity3() {
        return new float[][]{{1,0,0},{0,1,0},{0,0,1}};
    }
    private static float[][] deltaRotation(float[] base, float[] target) {
        float[][] from = rotation(base), to = rotation(target), inverse = transpose(from);
        return multiply(to, inverse);
    }
    private static float[][] transpose(float[][] m) {
        return new float[][]{{m[0][0], m[1][0], m[2][0]}, {m[0][1], m[1][1], m[2][1]}, {m[0][2], m[1][2], m[2][2]}};
    }
    private static float[][] multiply(float[][] a, float[][] b) {
        float[][] out = new float[3][3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
            out[r][c] = a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c];
        return out;
    }
    private static float[] identity4() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }
    private static float[] multiply4(float[] a, float[] b) {
        float[] out = new float[16];
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) {
            out[row * 4 + col] = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col]
                    + a[row * 4 + 2] * b[8 + col] + a[row * 4 + 3] * b[12 + col];
        }
        return out;
    }
    private static float[] around(float[] pivot, float[][] linear) {
        float[] matrix = identity4();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) matrix[row * 4 + col] = linear[row][col];
            matrix[row * 4 + 3] = pivot[row] - linear[row][0] * pivot[0] - linear[row][1] * pivot[1] - linear[row][2] * pivot[2];
        }
        return matrix;
    }
    private static float[] scalingAround(float[] pivot, float sx, float sy, float sz) {
        return new float[]{sx,0,0,pivot[0] * (1-sx), 0,sy,0,pivot[1] * (1-sy), 0,0,sz,pivot[2] * (1-sz), 0,0,0,1};
    }
    private static void inverseTranspose3(float[] matrix, float[] out) {
        float a = matrix[0], b = matrix[1], c = matrix[2];
        float d = matrix[4], e = matrix[5], f = matrix[6];
        float g = matrix[8], h = matrix[9], i = matrix[10];
        out[0] = e * i - f * h; out[1] = f * g - d * i; out[2] = d * h - e * g;
        out[3] = c * h - b * i; out[4] = a * i - c * g; out[5] = b * g - a * h;
        out[6] = b * f - c * e; out[7] = c * d - a * f; out[8] = a * e - b * d;
        float determinant = a * out[0] + b * out[1] + c * out[2];
        if (Math.abs(determinant) < 1.0E-8F) { out[0] = out[4] = out[8] = 1; out[1] = out[2] = out[3] = out[5] = out[6] = out[7] = 0; return; }
        float inverse = 1F / determinant;
        for (int index = 0; index < 9; index++) out[index] *= inverse;
    }

    /** Renders cuboid equipment through the same animated bone matrix as its attachment. */
    void renderEquipmentCuboid(MechanicalPetEntity pet, PoseStack pose, VertexConsumer consumer, int light,
                               String bone, float x0, float y0, float z0, float x1, float y1, float z1,
                               int color) {
        if (mesh == null || !mesh.byName.containsKey(bone)) return;
        float[] matrix = finalMatrices.get(bone), normalMatrix = normalMatrices.get(bone);
        if (matrix == null || normalMatrix == null) return;
        pose.pushPose();
        // Match renderToBuffer's correction from vanilla's Y-down model coordinates to our Y-up rig.
        pose.translate(0, 1.501F, 0);
        pose.scale(-1F, -1F, 1F);
        pose.translate(0, groundOffset / 16F, 0);
        pose.scale(1F / 16F, 1F / 16F, 1F / 16F);
        PoseStack.Pose transform = pose.last();

        float[][] points = {
                {x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},
                {x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}
        };
        // Each face is a four-corner cuboid side plus its outward unit normal.
        int[][] faces = {{0,3,2,1},{4,5,6,7},{0,4,7,3},{1,2,6,5},{0,1,5,4},{3,7,6,2}};
        float[][] normals = {{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,-1,0},{0,1,0}};
        float red = ((color >> 16) & 0xFF) / 255F;
        float green = ((color >> 8) & 0xFF) / 255F;
        float blue = (color & 0xFF) / 255F;
        for (int face = 0; face < faces.length; face++) {
            float shade = face == 2 || face == 4 ? 0.78F : face == 3 || face == 5 ? 0.92F : 1F;
            float r = red * shade, g = green * shade, b = blue * shade;
            for (int corner = 0; corner < 4; corner++) {
                float[] p = points[faces[face][corner]];
                float px = matrix[0] * p[0] + matrix[1] * p[1] + matrix[2] * p[2] + matrix[3];
                float py = matrix[4] * p[0] + matrix[5] * p[1] + matrix[6] * p[2] + matrix[7];
                float pz = matrix[8] * p[0] + matrix[9] * p[1] + matrix[10] * p[2] + matrix[11];
                float[] n = normals[face];
                Vector3f normal = new Vector3f(
                        normalMatrix[0] * n[0] + normalMatrix[1] * n[1] + normalMatrix[2] * n[2],
                        normalMatrix[3] * n[0] + normalMatrix[4] * n[1] + normalMatrix[5] * n[2],
                        normalMatrix[6] * n[0] + normalMatrix[7] * n[1] + normalMatrix[8] * n[2]);
                transform.normal().transform(normal).normalize();
                consumer.vertex(transform.pose(), px, py, pz).color(r, g, b, 1F)
                        .uv(corner == 1 || corner == 2 ? 1F : 0F, corner >= 2 ? 1F : 0F)
                        .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                        .normal(normal.x, normal.y, normal.z).endVertex();
            }
        }
        pose.popPose();
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer consumer, int light, int overlay,
                               float red, float green, float blue, float alpha) {
        if (mesh == null || posedVertices.isEmpty()) return;
        pose.pushPose();
        // LivingEntityRenderer supplies scale(-1,-1,1) then translate(0,-1.501,0)
        // for vanilla Y-down ModelPart coordinates. Cancel that exact pair so
        // Blockbench's authored Y-up points rest on the entity's feet.
        pose.translate(0, 1.501F, 0);
        pose.scale(-1F, -1F, 1F);
        pose.translate(0, groundOffset / 16F, 0);
        pose.scale(1F / 16F, 1F / 16F, 1F / 16F);
        PoseStack.Pose transform = pose.last();
        Vector3f normal = new Vector3f();
        for (AuthoredPetMesh.Bone bone : mesh.bones) {
            float[] vertices = posedVertices.get(bone.name());
            if (vertices == null || vertices.length == 0) continue;
            int boneLight = light;
            if ("cat".equals(mesh.species) && "tail_flame".equals(bone.name())) {
                int currentBlockLight = (light >>> 4) & 0xF;
                int flameFloor = 10 + Mth.floor((Mth.sin(animationTime * 0.08F) + 1F) * 1F);
                boneLight = (light & ~0xF0) | (Math.max(currentBlockLight, flameFloor) << 4);
            }
            for (int i = 0; i < vertices.length; i += AuthoredPetMesh.STRIDE) {
                normal.set(vertices[i + 5], vertices[i + 6], vertices[i + 7]);
                transform.normal().transform(normal).normalize();
                consumer.vertex(transform.pose(), vertices[i], vertices[i + 1], vertices[i + 2])
                        .color(red, green, blue, alpha).uv(vertices[i + 3], vertices[i + 4])
                        .overlayCoords(overlay).uv2(boneLight).normal(normal.x, normal.y, normal.z).endVertex();
            }
        }
        pose.popPose();
    }
}
