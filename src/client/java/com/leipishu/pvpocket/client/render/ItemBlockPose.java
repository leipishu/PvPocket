package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.combat.CombatState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/**
 * The 1.8 first-person sword block pose, with no loader code in it.
 *
 * <p>The display transform must be skipped before {@link #apply} runs: this pose replaces it rather
 * than composing with it. Each loader supplies its own injection point.
 *
 * <p>With the display transform gone the model is a flat quad in the hand frame, and the block pose
 * is that quad rolled a quarter turn in the screen plane, about the model centre.
 *
 * <p>The lift is derived from {@link #HAND_SCALE} and {@link #GRIP}, so the hilt rather than the
 * quad's centre lands on the hand. Left-handed players keep the vanilla pose.
 */
public final class ItemBlockPose {

    /**
     * Scale of the hand in the block pose.
     *
     * <p>1.8 used 0.4, but it also drew the sprite at a fixed 1.7 display scale. 0.5 keeps a 1.8
     * sword from reading undersized next to a modern one.
     */
    private static final float HAND_SCALE = 0.5F;

    /** The quarter turn that takes 1.8's idle silhouette to its block silhouette. */
    private static final float BLOCK_ROLL = 90.0F;

    /** Where the hand sits relative to the item anchor, in blocks. */
    private static final float HAND_OFFSET_X = 0.08F;

    private static final float HAND_OFFSET_Y = -0.08F;

    /**
     * Distance from the quad's centre to the hilt, in model units.
     *
     * <p>The hilt is the bottom-left corner of a 1x1 sprite inset by two transparent pixels:
     * {@code (0.10, 0.10)} in model space, {@code (-0.40, -0.40)} after
     * {@code translate(-0.5, -0.5, -0.5)}.
     */
    private static final float GRIP = 0.40F;

    private ItemBlockPose() {
    }

    /**
     * Whether this particular render pass should get the 1.8 block pose.
     *
     * @param stack      the stack being rendered
     *
     * @param renderMode which hand / perspective this pass is for
     */
    public static boolean wants(ItemStack stack, ItemDisplayContext renderMode) {
        if (!(stack.getItem() instanceof SwordItem)) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null || !CombatState.isBlocking(player.getUUID())) {
            return false;
        }

        // Only the hand that is blocking: a sword in the off hand keeps its idle pose.
        ItemDisplayContext mainHand = player.getMainArm() == HumanoidArm.RIGHT
                ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        return renderMode == mainHand;
    }

    /**
     * Builds the whole hand pose. The caller must have skipped the display transform: this replaces
     * it and does not compose with it.
     */
    public static void apply(PoseStack matrices) {
        // The quarter turn maps (-GRIP, -GRIP) to (+GRIP, -GRIP), so the cancelling lift is its
        // negation.
        float gripX = GRIP * HAND_SCALE;
        float gripY = -GRIP * HAND_SCALE;

        matrices.translate(HAND_OFFSET_X - gripX, HAND_OFFSET_Y - gripY, 0.0F);
        matrices.scale(HAND_SCALE, HAND_SCALE, HAND_SCALE);
        matrices.mulPose(Axis.ZP.rotationDegrees(BLOCK_ROLL));

        // ItemRenderer's own translate(-0.5, -0.5, -0.5) follows this call, so the quarter turn
        // happens about the model's centre rather than its corner.
    }
}
