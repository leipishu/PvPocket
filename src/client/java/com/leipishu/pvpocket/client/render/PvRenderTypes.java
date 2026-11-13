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
 * <p>1.21.11 split the type itself (now {@code rendertype.RenderType}, a pipeline description) from
 * the static factories that used to live on it ({@code rendertype.RenderTypes}). Both the class and
 * the factory move, so the pair is wrapped rather than marked at each call site.
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
