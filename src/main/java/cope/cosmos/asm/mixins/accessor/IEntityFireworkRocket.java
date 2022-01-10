package cope.cosmos.asm.mixins.accessor;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityFireworkRocket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityFireworkRocket.class)
public interface IEntityFireworkRocket {

    @Accessor("boostedEntity")
    EntityLivingBase getBoostedEntity();

}
