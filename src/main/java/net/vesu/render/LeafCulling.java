package net.vesu.render;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.vesu.Initializer;
import net.vesu.shader.PackManager;
import net.vesu.vulkan.rt.RtScene;

/**
 * Leaf culling at chunk build (setting {@code leafCulling}), after Celeritas Leaf Culling.
 * Fancy leaves draw every face, also between two leaf blocks, so a canopy is solid layers of
 * alpha-tested quads.
 *
 * <ul>
 *   <li>hollow: a face is dropped when the next two blocks behind it are leaves;</li>
 *   <li>solid: a leaf block covered on all six sides by leaves or solid faces is buried, and
 *       faces toward a buried block are dropped; aggressive only asks for the four sides.</li>
 * </ul>
 *
 * In the solid modes the buried blocks themselves go to the SOLID layer ({@code RenderChunkM}):
 * no alpha test, and they occlude what is behind them like fast leaves. Not while a shader
 * pack runs (its terrain programs treat the layers differently) or with ray tracing (cutout
 * geometry is alpha-tested by the shadow rays), and then the solid modes drop no faces either:
 * a buried block that stays alpha-tested would show the emptied inside of the canopy through
 * its holes (and let light through under RT). Chunks are rebuilt when that changes.
 * Runs on the chunk worker threads; the neighbours read stay inside the chunk cache, which
 * holds the whole neighbouring columns.
 */
public final class LeafCulling {

    public static final String[] MODES = {"off", "hollow", "aggressive", "solid"};
    private static final int OFF = 0, HOLLOW = 1, AGGRESSIVE = 2, SOLID = 3;

    private static volatile int mode = SOLID;
    private static volatile boolean fancy = true;
    private static volatile boolean reroute = true;

    private LeafCulling() {}

    private static int parse(String name) {
        if (name != null) {
            for (int i = 0; i < MODES.length; i++) {
                if (MODES[i].equals(name)) return i;
            }
        }
        return SOLID;
    }

    /**
     * Once per client tick: picks up the setting, the graphics level and the pack / RT state.
     * Rebuilds the chunks when what a rebuild would produce changed.
     */
    public static void update() {
        Minecraft mc = Minecraft.getMinecraft();
        int m = parse(Initializer.CONFIG.leafCulling);
        boolean f = mc.gameSettings.fancyGraphics;
        boolean r = PackManager.active() == null && !RtScene.enabled();
        // Fancy graphics: vanilla rebuilds the chunks itself.
        boolean rebuild = f && f == fancy && (m != mode || (m >= AGGRESSIVE && r != reroute));
        mode = m;
        fancy = f;
        reroute = r;
        if (rebuild && mc.renderGlobal != null && mc.world != null) mc.renderGlobal.loadRenderers();
    }

    /** Hook of {@code BlockLeaves.shouldSideBeRendered}: true drops the face. */
    public static boolean cullSide(IBlockAccess world, BlockPos pos, EnumFacing side) {
        int m = mode;
        if (m == OFF || !fancy || (m >= AGGRESSIVE && !reroute)) return false;
        if (m == HOLLOW) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(pos);
            return isLeaves(world.getBlockState(p.move(side))) && isLeaves(world.getBlockState(p.move(side)));
        }
        return buried(world, pos.offset(side), m == AGGRESSIVE);
    }

    /**
     * Hook of {@code Block.canRenderInLayer} in the chunk build, for leaves only: the layers a
     * buried leaf block goes to, or null for its own.
     */
    public static Boolean renderInLayer(IBlockAccess world, BlockPos pos, BlockRenderLayer layer) {
        int m = mode;
        if (m < AGGRESSIVE || !fancy || !reroute || !buried(world, pos, m == AGGRESSIVE)) return null;
        return layer == BlockRenderLayer.SOLID;
    }

    public static boolean isLeaves(Block block) {
        return block instanceof BlockLeaves;
    }

    private static boolean isLeaves(IBlockState state) {
        return state.getBlock() instanceof BlockLeaves;
    }

    /** A leaf block whose sides are all leaves or solid faces (aggressive: the four horizontal ones). */
    private static boolean buried(IBlockAccess world, BlockPos pos, boolean aggressive) {
        if (!isLeaves(world.getBlockState(pos))) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (EnumFacing dir : EnumFacing.VALUES) {
            if (aggressive && dir.getAxis() == EnumFacing.Axis.Y) continue;
            p.setPos(pos).move(dir);
            IBlockState s = world.getBlockState(p);
            if (isLeaves(s)) continue;
            if (!s.isSideSolid(world, p, dir.getOpposite())) return false;
        }
        return true;
    }
}
