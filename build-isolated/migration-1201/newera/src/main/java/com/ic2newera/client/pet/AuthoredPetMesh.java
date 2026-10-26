package com.ic2newera.client.pet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable authored voxel quads decoded only during resource reload. */
final class AuthoredPetMesh {
    static final int STRIDE = 8;
    final String species;
    final ResourceLocation texture;
    final List<Bone> bones;
    final Map<String, Bone> byName;
    final float minX, minY, minZ, maxX, maxY, maxZ;

    private AuthoredPetMesh(String species, ResourceLocation texture, List<Bone> bones, float[] bounds) {
        this.species = species;
        this.texture = texture;
        this.bones = List.copyOf(bones);
        Map<String, Bone> names = new HashMap<>();
        for (Bone bone : bones) names.put(bone.name(), bone);
        this.byName = Map.copyOf(names);
        minX = bounds[0]; minY = bounds[1]; minZ = bounds[2];
        maxX = bounds[3]; maxY = bounds[4]; maxZ = bounds[5];
    }

    static AuthoredPetMesh read(InputStream stream, ResourceLocation texture, String expectedSpecies) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("format") || !root.get("format").getAsString().equals("ic2newera:voxel_pet_mesh_v1"))
                throw new IOException("Unknown voxel pet mesh format");
            String species = root.get("species").getAsString();
            if (!expectedSpecies.equals(species)) throw new IOException("Pet mesh species mismatch: " + species);
            float[] bounds = vector(root.getAsJsonArray("bounds").get(0).getAsJsonArray(), 3);
            float[] upper = vector(root.getAsJsonArray("bounds").get(1).getAsJsonArray(), 3);
            float[] packedBounds = {bounds[0], bounds[1], bounds[2], upper[0], upper[1], upper[2]};
            JsonArray sourceBones = root.getAsJsonArray("bones");
            if (sourceBones == null || sourceBones.size() == 0) throw new IOException("Empty pet rig");
            List<Bone> bones = new ArrayList<>(sourceBones.size());
            Map<String, Bone> seen = new HashMap<>();
            int totalVertices = 0;
            for (JsonElement source : sourceBones) {
                JsonObject data = source.getAsJsonObject();
                String name = data.get("name").getAsString();
                String parent = data.has("parent") && !data.get("parent").isJsonNull() ? data.get("parent").getAsString() : null;
                if (seen.containsKey(name) || parent != null && !seen.containsKey(parent))
                    throw new IOException("Duplicate or out-of-order pet bone " + name);
                float[] pivot = vector(data.getAsJsonArray("pivot"), 3);
                float[] defaultRotation = vector(data.getAsJsonArray("rotation"), 3);
                JsonArray rawVertices = data.getAsJsonArray("vertices");
                if (rawVertices == null || rawVertices.size() % STRIDE != 0 || rawVertices.size() > 600_000
                        || totalVertices + rawVertices.size() > 1_000_000)
                    throw new IOException("Invalid vertex count for pet bone " + name);
                float[] vertices = new float[rawVertices.size()];
                for (int i = 0; i < vertices.length; i++) {
                    vertices[i] = finite(rawVertices.get(i).getAsFloat());
                    if (i % STRIDE == 3 && (vertices[i] < 0 || vertices[i] > 1)
                            || i % STRIDE == 4 && (vertices[i] < 0 || vertices[i] > 1))
                        throw new IOException("Pet UV outside normalized atlas");
                }
                for (int i = 0; i < vertices.length; i += STRIDE) {
                    float nx = vertices[i + 5], ny = vertices[i + 6], nz = vertices[i + 7];
                    float normalLength = nx * nx + ny * ny + nz * nz;
                    if (Math.abs(normalLength - 1F) > 0.02F) throw new IOException("Invalid mesh normal");
                }
                if (vertices.length % (4 * STRIDE) != 0) throw new IOException("Pet vertices must form quads");
                Bone bone = new Bone(name, parent, pivot, defaultRotation, vertices);
                seen.put(name, bone); bones.add(bone); totalVertices += vertices.length;
            }
            if (totalVertices == 0 || !seen.containsKey("body") || !seen.containsKey("head"))
                throw new IOException("Pet mesh lacks visible body/head geometry");
            return new AuthoredPetMesh(species, texture, bones, packedBounds);
        } catch (RuntimeException error) {
            throw new IOException("Malformed voxel pet mesh", error);
        }
    }

    private static float[] vector(JsonArray values, int size) throws IOException {
        if (values == null || values.size() != size) throw new IOException("Invalid pet mesh vector");
        float[] result = new float[size];
        for (int i = 0; i < size; i++) result[i] = finite(values.get(i).getAsFloat());
        return result;
    }

    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Non-finite pet mesh component");
        return value;
    }

    record Bone(String name, String parent, float[] pivot, float[] defaultRotation, float[] vertices) { }
}
