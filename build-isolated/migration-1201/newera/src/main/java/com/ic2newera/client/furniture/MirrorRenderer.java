package com.ic2newera.client.furniture;

import com.ic2newera.IC2NewEra;
import com.ic2newera.furniture.MirrorBlock;
import com.ic2newera.furniture.MirrorBlockEntity;
import com.ic2newera.furniture.MirrorContent;
import com.ic2newera.pet.MechanicalPet;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.Comparator;
import java.util.List;

/**
 * A planar, perspective-correct portrait reflection. Only nearby players and pets
 * are rendered, not a recursive second world. An off-axis frustum clips both the
 * mirror aperture and everything behind its glass; no cloned entities are spawned.
 */
public final class MirrorRenderer implements BlockEntityRenderer<MirrorBlockEntity> {
    private static BufferBuilder reflectionBuffer = new BufferBuilder(262144);
    private static final BufferBuilder GLASS_BUFFER = new BufferBuilder(256);
    private static MultiBufferSource.BufferSource reflections = MultiBufferSource.immediate(reflectionBuffer);
    private static TextureTarget target;
    private static boolean reflecting;
    private static boolean disabled;
    private static int renderedThisFrame;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public MirrorRenderer(BlockEntityRendererProvider.Context context) { }

    @Override public void render(MirrorBlockEntity mirror, float partial, PoseStack poses,
                                 MultiBufferSource ignored, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        if (reflecting || disabled || mc.level == null || mc.player == null || renderedThisFrame >= 4) return;
        Direction facing = mirror.getBlockState().getValue(MirrorBlock.FACING);
        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());
        // Canonical +X is the off-screen camera's right when it looks north.
        Vec3 right = new Vec3(-normal.z, 0, normal.x);
        Vec3 origin = Vec3.atBottomCenterOf(mirror.getBlockPos()).add(normal.scale(1.0 / 16.0));
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double distance = eye.subtract(origin).dot(normal);
        if (distance <= .08 || eye.distanceToSqr(origin) > 144) return;
        boolean floor = mirror.isFloorMirror();
        double minX = floor ? -5.0/16 : -4.0/16;
        double maxX = -minX, minY = floor ? 4.0/16 : 9.0/16, maxY = floor ? 30.0/16 : 15.0/16;
        double eyeX = eye.subtract(origin).dot(right), eyeY = eye.y - origin.y;
        Vec3 virtualEye = eye.subtract(normal.scale(2 * distance));
        // The near plane is the mirror itself, not the camera. A small overlap
        // avoids cutting a nose exactly on the glass without showing its rear side.
        Matrix4f projection = new Matrix4f().setFrustum((float)(minX-eyeX), (float)(maxX-eyeX),
                (float)(minY-eyeY), (float)(maxY-eyeY), (float)distance, (float)(distance+9));
        List<LivingEntity> entities = mc.level.getEntitiesOfClass(LivingEntity.class,
                new AABB(origin, origin).inflate(8), e -> reflectedKind(e) && e.isAlive()
                        && !e.isInvisibleTo(mc.player) && e.position().subtract(origin).dot(normal) > -.1
                        && e.distanceToSqr(origin) < 64 && visibleFromGlass(e, origin, normal));
        entities.sort(Comparator.comparingDouble(e -> e.distanceToSqr(origin)));
        if (entities.size() > 12) entities = entities.subList(0, 12);
        renderedThisFrame++;
        try {
            renderPortraits(mc, projection, virtualEye, normal, entities, partial);
            drawGlass(poses, facing, minX, maxX, minY, maxY);
        } catch (RuntimeException failure) {
            // Driver/resource failures leave the authored glass visible and do not
            // crash the world or retry a broken framebuffer every render frame.
            disabled = true;
            reflecting = false;
            reflectionBuffer.discard();
            reflectionBuffer = new BufferBuilder(262144);
            reflections = MultiBufferSource.immediate(reflectionBuffer);
            GLASS_BUFFER.discard();
            LOGGER.error("镜面动态倒影暂时停用，静态镜面保留；重新进入世界后重试", failure);
        }
    }

    private static boolean reflectedKind(LivingEntity entity) {
        return entity instanceof Player || entity instanceof MechanicalPet || entity instanceof Cat || entity instanceof Wolf;
    }
    private static boolean visibleFromGlass(LivingEntity entity, Vec3 origin, Vec3 normal) {
        Vec3 start = origin.add(0, .7, 0).add(normal.scale(.12));
        return entity.level().clip(new ClipContext(start, entity.getEyePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
    }

    private static void renderPortraits(Minecraft mc, Matrix4f projection, Vec3 eye, Vec3 normal,
                                       List<LivingEntity> entities, float partial) {
        try (GlState old = new GlState()) {
            if (target == null) {
                target = new TextureTarget(512, 512, true, Minecraft.ON_OSX);
                target.setClearColor(.37F, .45F, .49F, 1);
            }
            reflecting = true;
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            modelView.setIdentity();
            RenderSystem.applyModelViewMatrix();
            try {
                RenderSystem.setShaderFogStart(Float.MAX_VALUE);
                RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.setShaderColor(1, 1, 1, 1);
                Matrix4f view = new Matrix4f().lookAt(0, 0, 0, (float)normal.x, 0, (float)normal.z, 0, 1, 0);
                // runAsFancy keeps Fabulous-only entity targets from rebinding the world framebuffer.
                RenderSystem.runAsFancy(() -> {
                    for (LivingEntity entity : entities) renderEntity(mc, entity, eye, view, partial);
                    reflections.endBatch();
                });
            } finally {
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
                reflecting = false;
            }
        } finally { reflecting = false; }
    }
    private static <T extends Entity> void renderEntity(Minecraft mc, T entity, Vec3 camera, Matrix4f view, float partial) {
        EntityRenderer<? super T> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
        PoseStack pose = new PoseStack();
        pose.last().pose().set(view);
        pose.last().normal().set(new Matrix3f(view));
        Vec3 offset = renderer.getRenderOffset(entity, partial);
        pose.translate(Mth.lerp(partial, entity.xOld, entity.getX())-camera.x+offset.x,
                Mth.lerp(partial, entity.yOld, entity.getY())-camera.y+offset.y,
                Mth.lerp(partial, entity.zOld, entity.getZ())-camera.z+offset.z);
        // Direct renderer call excludes dispatcher shadows, flames and debug boxes.
        renderer.render(entity, Mth.rotLerp(partial, entity.yRotO, entity.getYRot()), partial, pose,
                reflections, renderer.getPackedLightCoords(entity, partial));
    }
    private static void drawGlass(PoseStack poses, Direction facing, double x0, double x1, double y0, double y1) {
        try (GlState old = new GlState()) {
            poses.pushPose();
            try {
                poses.translate(.5, 0, .5);
                poses.mulPose(com.mojang.math.Axis.YP.rotationDegrees(switch (facing) {
                    case EAST -> -90; case SOUTH -> 180; case WEST -> 90; default -> 0;
                }));
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.disableCull();
                RenderSystem.disableBlend();
                RenderSystem.setShaderColor(.94F, .97F, 1, 1);
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, target.getColorTextureId());
                Matrix4f matrix = poses.last().pose();
                // Offset only the rendered surface from the cuboid to avoid z-fighting.
                float z = -1F/16F-.001F;
                GLASS_BUFFER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                GLASS_BUFFER.vertex(matrix, (float)x0, (float)y0, z).uv(0, 0).endVertex();
                GLASS_BUFFER.vertex(matrix, (float)x1, (float)y0, z).uv(1, 0).endVertex();
                GLASS_BUFFER.vertex(matrix, (float)x1, (float)y1, z).uv(1, 1).endVertex();
                GLASS_BUFFER.vertex(matrix, (float)x0, (float)y1, z).uv(0, 1).endVertex();
                BufferUploader.drawWithShader(GLASS_BUFFER.end());
            } finally { poses.popPose(); }
        }
    }
    @Override public int getViewDistance() { return 12; }

    /** Restore the caller's framebuffer, viewport and render state even after a failed entity layer. */
    private static final class GlState implements AutoCloseable {
        final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int activeTexture = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        final int[] viewport = new int[4];
        final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        final VertexSorting sorting = RenderSystem.getVertexSorting();
        final float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        final float[] color = RenderSystem.getShaderColor().clone();
        final net.minecraft.client.renderer.ShaderInstance shader = RenderSystem.getShader();
        final int[] textures = new int[12];
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND), mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int srcRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB),
                dstRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB),
                srcAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA),
                dstAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
        GlState() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer values = stack.mallocInt(4); GL11.glGetIntegerv(GL11.GL_VIEWPORT, values); values.get(viewport);
            }
            for (int i=0; i<textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
        }
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setTextureMatrix(textureMatrix);
            RenderSystem.setShaderFogStart(fogStart); RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
            RenderSystem.setShader(() -> shader);
            for (int i=0; i<textures.length; i++) RenderSystem.setShaderTexture(i,textures[i]);
            RenderSystem.activeTexture(activeTexture);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            RenderSystem.depthMask(mask); RenderSystem.depthFunc(depthFunction);
        }
    }
    @Mod.EventBusSubscriber(modid=IC2NewEra.MOD_ID, bus=Mod.EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(MirrorContent.MIRROR.get(), MirrorRenderer::new);
        }
    }
    @Mod.EventBusSubscriber(modid=IC2NewEra.MOD_ID, value=Dist.CLIENT)
    public static final class Lifecycle {
        @SubscribeEvent public static void frame(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.START) renderedThisFrame = 0;
        }
        @SubscribeEvent public static void labels(RenderNameTagEvent event) {
            if (reflecting) event.setResult(Event.Result.DENY);
        }
        @SubscribeEvent public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
            RenderSystem.recordRenderCall(() -> { if (target != null) { target.destroyBuffers(); target=null; } disabled=false; });
        }
    }
}
