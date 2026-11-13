package com.leipishu.pvpocket.client.render;

//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
import net.minecraft.client.renderer.RenderType;
//?}

/**
 * The vanilla render types this mod draws with.
 *
 * <p>1.21.11 moved both the type (to {@code rendertype.RenderType}, a pipeline description) and the
 * static factories (to {@code rendertype.RenderTypes}), so the pair is wrapped here instead of
 * being marked at each call site.
 */
public final class PvRenderTypes {

    private PvRenderTypes() {
    }

    /** Position+colour quads with culling off: the cheapest type for overlay geometry. */
    public static RenderType debugQuads() {
//? if >=1.21.11 {
        return RenderTypes.debugQuads();
//?} else {
        return RenderType.debugQuads();
//?}
    }
}
