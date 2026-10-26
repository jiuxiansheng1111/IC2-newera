package com.ic2newera.client.robot;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.minecraft.resources.ResourceLocation;

/** Immutable CPU-side mesh decoded only during resource reload, never per frame. */
final class RobotMesh {
    static final int STRIDE = 8;
    static final int PART_COUNT = 9;
    static final int NEUTRAL_MOUTH_PART = 7;
    static final int SMILING_MOUTH_PART = 8;
    static final int HEAD_PART = 1;
    static final int LEFT_ARM_PART = 2;
    static final int RIGHT_ARM_PART = 3;
    static final int LEFT_LEG_PART = 4;
    static final int RIGHT_LEG_PART = 5;
    private static final byte[] MAGIC = "NER20M01".getBytes(StandardCharsets.US_ASCII);
    final ResourceLocation texture;
    final Part[] parts;

    private RobotMesh(ResourceLocation texture, Part[] parts) {
        this.texture = texture;
        this.parts = parts;
    }

    static RobotMesh read(InputStream source, ResourceLocation texture) throws IOException {
        try (DataInputStream input = new DataInputStream(source)) {
            if (!Arrays.equals(input.readNBytes(MAGIC.length), MAGIC)) {
                throw new IOException("Invalid robot expression mesh header; expected NER20M01");
            }
            float[] bounds = new float[6];
            for (int i = 0; i < bounds.length; i++) bounds[i] = finite(input.readFloat());
            for (int i = 0; i < 3; i++) {
                if (bounds[i] >= bounds[i + 3] || Math.abs(bounds[i]) > 8 || Math.abs(bounds[i + 3]) > 8) {
                    throw new IOException("Invalid R16 model bounds");
                }
            }
            float sourceFloorY = bounds[4];
            float sourceHeight = bounds[4] - bounds[1];
            float sizeScale = RobotModelDimensions.modelScale(sourceHeight);
            Part[] parts = new Part[PART_COUNT];
            int total = 0;
            for (int part = 0; part < PART_COUNT; part++) {
                float x = finite(input.readFloat());
                float y = finite(input.readFloat());
                float z = finite(input.readFloat());
                int count = input.readInt();
                if (count < 0 || count % 4 != 0 || count > 100_000 || total + count > 100_000) {
                    throw new IOException("Invalid R16 quad vertex count: " + count);
                }
                total += count;
                float[] vertices = new float[count * STRIDE];
                for (int i = 0; i < vertices.length; i++) vertices[i] = finite(input.readFloat());
                for (int i = 0; i < vertices.length; i += STRIDE) {
                    for (int axis = 0; axis < 3; axis++) {
                        if (vertices[i + axis] < bounds[axis] - 0.001F || vertices[i + axis] > bounds[axis + 3] + 0.001F) {
                            throw new IOException("R16 vertex outside declared bounds");
                        }
                    }
                    vertices[i] = RobotModelDimensions.scaleHorizontal(vertices[i], sizeScale);
                    vertices[i + 1] = RobotModelDimensions.scaleVertical(vertices[i + 1], sourceFloorY, sizeScale);
                    vertices[i + 2] = RobotModelDimensions.scaleHorizontal(vertices[i + 2], sizeScale);
                    float nx = vertices[i + 5], ny = vertices[i + 6], nz = vertices[i + 7];
                    if (Math.abs(nx * nx + ny * ny + nz * nz - 1.0F) > 0.01F) {
                        throw new IOException("R16 vertex has invalid normal");
                    }
                }
                parts[part] = new Part(RobotModelDimensions.scaleHorizontal(x, sizeScale),
                        RobotModelDimensions.scaleVertical(y, sourceFloorY, sizeScale),
                        RobotModelDimensions.scaleHorizontal(z, sizeScale), vertices, sizeScale);
            }
            if (total == 0 || input.read() != -1) throw new IOException("Empty mesh or trailing R16 mesh data");
            return new RobotMesh(texture, parts);
        }
    }

    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Non-finite R16 mesh component");
        return value;
    }

    static RobotMesh missing(ResourceLocation texture) {
        // A conspicuous resource-error marker, never an old rejected character.
        float[][] corners = {{-.2F, -.24F, -.12F}, {.2F, -.24F, -.12F}, {.2F, 1.5F, -.12F}, {-.2F, 1.5F, -.12F},
                {-.2F, -.24F, .12F}, {.2F, -.24F, .12F}, {.2F, 1.5F, .12F}, {-.2F, 1.5F, .12F}};
        int[][] faces = {{0, 3, 2, 1}, {5, 6, 7, 4}, {4, 7, 3, 0}, {1, 2, 6, 5}, {3, 7, 6, 2}, {0, 1, 5, 4}};
        float[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        float[] data = new float[24 * STRIDE];
        int offset = 0;
        for (int face = 0; face < faces.length; face++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                for (float coordinate : corners[faces[face][vertex]]) data[offset++] = coordinate;
                for (float coordinate : uv[vertex]) data[offset++] = coordinate;
                for (float normal : normals[face]) data[offset++] = normal;
            }
        }
        Part[] parts = new Part[PART_COUNT];
        Arrays.fill(parts, new Part(0, 0, 0, new float[0]));
        parts[0] = new Part(0, 0, 0, data);
        return new RobotMesh(texture, parts);
    }

    record Part(float pivotX, float pivotY, float pivotZ, float[] vertices,
                float gripX, float gripY, float gripZ) {
        Part(float x, float y, float z, float[] vertices) {
            this(x, y, z, vertices, 1.0F);
        }

        Part(float x, float y, float z, float[] vertices, float sizeScale) {
            this(x, y, z, vertices, grip(vertices, 0, x, sizeScale), grip(vertices, 1, y, sizeScale),
                    grip(vertices, 2, z, sizeScale));
        }

        private static float grip(float[] vertices, int axis, float fallback, float sizeScale) {
            if (vertices.length == 0) return fallback;
            float bottom = -Float.MAX_VALUE;
            for (int i = 1; i < vertices.length; i += STRIDE) bottom = Math.max(bottom, vertices[i]);
            if (axis == 1) return bottom - 0.045F * sizeScale;
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (int i = 0; i < vertices.length; i += STRIDE) {
                if (vertices[i + 1] >= bottom - 0.09F) {
                    min = Math.min(min, vertices[i + axis]); max = Math.max(max, vertices[i + axis]);
                }
            }
            return (min + max) * 0.5F;
        }
    }
}
