package net.vesu.mixin.render;

import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.vesu.render.OcclusionCuller;
import org.spongepowered.asm.mixin.Mixin;

/** The occlusion verdict of an entity or block entity, written by the culling thread. */
@Mixin({Entity.class, TileEntity.class})
public abstract class CullableM implements OcclusionCuller.Cullable {

    private volatile boolean vesu$culled;
    private volatile long vesu$visibleUntil;

    @Override public boolean vesu$isCulled() { return vesu$culled; }
    @Override public void vesu$setCulled(boolean culled) { vesu$culled = culled; }
    @Override public long vesu$visibleUntil() { return vesu$visibleUntil; }
    @Override public void vesu$setVisibleUntil(long nanos) { vesu$visibleUntil = nanos; }
}
