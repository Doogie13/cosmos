package cope.cosmos.client.features.modules.movement;

import cope.cosmos.asm.mixins.accessor.ICPacketPlayer;
import cope.cosmos.asm.mixins.accessor.IEntityFireworkRocket;
import cope.cosmos.client.events.MicrowaveEvent;
import cope.cosmos.client.events.PacketEvent;
import cope.cosmos.client.events.RenderRotationsEvent;
import cope.cosmos.client.events.TravelEvent;
import cope.cosmos.client.features.modules.Category;
import cope.cosmos.client.features.modules.Module;
import cope.cosmos.client.features.setting.Setting;
import cope.cosmos.util.player.InventoryUtil;
import cope.cosmos.util.player.MotionUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.init.Items;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketPlayerTryUseItem;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static net.minecraft.entity.EntityLivingBase.SWIM_SPEED;

public class ElytraFlight extends Module {

    public static ElytraFlight INSTANCE;
    public ElytraFlight() {
        super("ElytraFLight", Category.MOVEMENT, "Fly infinitely with elytras");
    }

    //region Settings

    public static Setting<Mode> mode = new Setting<>("Mode", Mode.CONTROL).setDescription("Mode to fly with");
    public static Setting<Up> upMode = new Setting<>("UpMode", Up.GLIDE).setDescription("Method of gaining altitude");
    public static Setting<TakeOff> takeoff = new Setting<>("TakeOff", TakeOff.NORMAL).setDescription("Method of taking off");
    public static Setting<Double> speed = new Setting<>("Speed", 0d, 2d, 10d, 1).setDescription("Speed to fly with Control and Packet modes");
    public static Setting<Double> accelerateTicks = new Setting<>("AccelerateTicks", 0d, 0d, 20d, 0).setDescription("Ticks to accelerate to full speed");
    public static Setting<Double> glideSpeed = new Setting<>("GlideSpeed", 0.00001d, 0.00001d, 1d, 2).setDescription("Passive height loss in Control and Packet modes");
    public static Setting<Double> upSpeed = new Setting<>("UpSpeed", 0d, 1d, 10d, 2).setDescription("Height increase when using Jump up mode for Control or Packet");
    public static Setting<Double> upPitch = new Setting<>("UpPitch", 0d, 30d, 90d, 0).setDescription("Pitch to emulate when using Glide up mode for Control or Packet");
    public static Setting<Double> upStep = new Setting<>("UpStep", 1d, 25d, 90d, 0).setDescription("Max pitch change at once to go up");
    public static Setting<Double> upTicks = new Setting<>("UpTicks", 1d, 40d, 50d, 0).setDescription("Ticks to do vanilla gliding up");
    public static Setting<Double> downSpeed = new Setting<>("DownSpeed", 0d, 1d, 10d, 2).setDescription("Height decrease in Control and Packet modes");
    public static Setting<Boolean> packetUp = new Setting<>("PacketUp", false).setDescription("Allow packet ElytraFlight to go up at the cost of some durability");
    public static Setting<Boolean> cancel = new Setting<>("CancelRocketsOnRubberband", true).setDescription("Cancel any active rockets if we rubberband");
    public static Setting<Boolean> antiMicrowave = new Setting<>("NoElytraSounds", false).setDescription("Turn off the microwave that is turned on when elytra flying");
    public static Setting<Boolean> pauseOnLiquid = new Setting<>("PauseOnLiquid", false).setDescription("Wait when the player is in a liquid");

    // automatic boost settings (don't delete)
    //public static Setting<Integer> emergeSpeed = new Setting<>("EmergeSpeed", 0, 1, 10, 0).setDescription("Height decrease in Control and Packet modes");
    //public static Setting<Integer> boostTicks = new Setting<>("BoostTicks", 0, 1, 200, 0).setDescription("Height decrease in Control and Packet modes");
    //public static Setting<Integer> targetY = new Setting<>("TargetY", 0, 200, 350, 0).setDescription("Height decrease in Control and Packet modes");

    //endregion

    float spoofedPitch;
    int gliding,
            ticksFlying,
            pitchTicks;

    @Override
    public void onEnable() {
        spoofedPitch = 0;
        gliding = 0;
        ticksFlying = 0;
        pitchTicks = 0;
    }

    @SubscribeEvent
    public void onTravel(TravelEvent event) {

        // don't continue if in water or lava and pauseOnLiquid is enabled
        if ((mc.player.isInWater() || mc.player.isInLava()) && pauseOnLiquid.getValue()) {
            event.setCanceled(false);
            return;
        }

        // if we aren't already ElytraFlying or the mode is packet (packet takes off by itself)
        if (!mc.player.isElytraFlying() && !mode.getValue().equals(Mode.PACKET)) {

            // reset variables
            ticksFlying = 0;
            pitchTicks = 0;

            // if we are falling and holding space
            if (!mc.player.onGround && mc.player.motionY < 0 && mc.player.movementInput.jump) {

                switch (takeoff.getValue()) {

                    case PACKETFLY: {

                        getCosmos().getTickManager().setClientTicks(1);

                        // move down slightly every other tick to not get kicked by the vanilla anticheat
                        mc.player.connection.sendPacket(new CPacketPlayer.Position(mc.player.posX, mc.player.posY - (mc.player.ticksExisted % 2 == 0 ? 0.1 : 0), mc.player.posZ, false));

                        // 70 + 40 = 110 so it will count as bounds packet and not be over 100 on any one axis to bypass better
                        // (a bounds packet being a position packet over 100 blocks away)
                        double[] dir = MotionUtil.getMoveSpeed(70);

                        // send a bounds packet
                        mc.player.connection.sendPacket(new CPacketPlayer.Position(
                                mc.player.posX + dir[0],
                                mc.player.posY > 40 ? mc.player.posY - 40 : mc.player.posY + 40, // never below or above world limits
                                mc.player.posZ + dir[1],
                                // so it can't deal fall damage (it shouldn't anyways but it technically could)
                                false));

                        // start elytraflying
                        mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING));

                        break;
                    }
                    case SLOW: {

                        // set timer to .1 so we fall slower
                        getCosmos().getTickManager().setClientTicks(.1f);
                        mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING));

                        break;
                    }
                    case NORMAL: {

                        // just send the start flying packet every tick when falling
                        getCosmos().getTickManager().setClientTicks(1);
                        mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING));

                        break;
                    }
                    case MOTION: {

                        // freeze motion in mid air and send fly packet
                        getCosmos().getTickManager().setClientTicks(1);
                        mc.player.motionY = .000001;
                        mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING));

                        break;
                    }
                }
            }

        } else {

            // don't use timer if we were using it to take off
            getCosmos().getTickManager().setClientTicks(1);

            switch (mode.getValue()) {
                case BOOST: {

                    // get our elytra movement
                    Vec3d move = getElytraMovement(
                            mc.player.rotationPitch
                    );

                    // if holding jump, boost ourselves
                    if (mc.player.movementInput.jump) {

                        move = boostMovement(move);

                    }

                    // set our velocity
                    mc.player.setVelocity(move.x, move.y, move.z);
                    spoofedPitch = mc.player.rotationPitch;

                    break;
                }
                // both modes use almost exactly the same logic so we copy them
                case PACKET:
                case CONTROL: {

                    ticksFlying++;

                    boolean continuing = true;

                    if (mode.getValue().equals(Mode.CONTROL) || packetUp.getValue()) {
                        if (mc.player.movementInput.jump) {
                            switch (upMode.getValue()) {

                                // go directly upwards
                                case FLY: {
                                    mc.player.motionY = upSpeed.getValue();
                                    break;
                                }

                                // use vanilla gliding to go upwards
                                case GLIDE: {

                                    // If we are falling faster than our glide speed
                                    if (mc.player.motionY >= -glideSpeed.getValue()) {

                                        if (gliding > accelerateTicks.getValue() + 1) {

                                            float thisPitch = (float) Math.min((gliding - accelerateTicks.getValue()) * upStep.getValue(), upPitch.getValue());

                                            Vec3d move = getElytraMovement(
                                                    -thisPitch
                                            );

                                            mc.player.setVelocity(move.x, move.y, move.z);

                                            continuing = false;

                                        } else if (!mc.player.movementInput.forwardKeyDown && gliding == accelerateTicks.getValue() + 1) {

                                            double[] dir = MotionUtil.getMoveSpeedInDirection(
                                                    Math.min((speed.getValue() / accelerateTicks.getValue()) * ticksFlying, speed.getValue()),
                                                    mc.player.rotationYaw
                                            );

                                            mc.player.setVelocity(dir[0], glideSpeed.getValue(), dir[1]);
                                            spoofedPitch = 0;

                                            continuing = false;

                                        } else {

                                            double[] dir = MotionUtil.getMoveSpeedInDirection(
                                                    Math.min((speed.getValue() / accelerateTicks.getValue()) * ticksFlying, speed.getValue()),
                                                    mc.player.rotationYaw
                                            );

                                            mc.player.setVelocity(dir[0], glideSpeed.getValue(), dir[1]);
                                            spoofedPitch = 0;
                                            continuing = false;

                                        }

                                        gliding++;

                                        if (gliding > upTicks.getValue() + accelerateTicks.getValue() + 1) {
                                            gliding = 0;
                                            ticksFlying = 0;
                                        }
                                    } else {

                                        double[] dir = MotionUtil.getMoveSpeedInDirection(
                                                Math.min((speed.getValue() / accelerateTicks.getValue()) * ticksFlying, speed.getValue()),
                                                mc.player.rotationYaw
                                        );

                                        mc.player.setVelocity(dir[0], glideSpeed.getValue(), dir[1]);
                                        spoofedPitch = 0;

                                        continuing = false;

                                    }
                                    break;
                                }
                            }
                        }
                    }
                    // if we are using packet mode and not going up
                    if ((!mc.player.movementInput.jump || !packetUp.getValue()) && mode.getValue().equals(Mode.PACKET))
                        // restart elytra state but still fly on some NCP configs
                        mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING));

                    if (continuing) {

                        // reset glide state
                        gliding = 0;

                        // reset spoofed pitch
                        spoofedPitch = 0;

                        // get flying speed
                        double[] dir = MotionUtil.getMoveSpeed(
                                // accelerate until at max speed
                                Math.min((speed.getValue() / accelerateTicks.getValue()) * ticksFlying, speed.getValue())
                        );

                        // fly forwards, go down if necessary
                        mc.player.setVelocity(dir[0], (mc.player.movementInput.sneak && !mc.player.movementInput.jump) ? -downSpeed.getValue() : glideSpeed.getValue(), dir[1]);

                    }
                }
            }

            // cancel vanilla movements
            event.setCanceled(true);

            // if still we have to reset acceleration timer
            if (Math.abs(mc.player.motionX) + Math.abs(mc.player.motionZ) == 0)
                ticksFlying = 0;

        }
    }

    //region Water Handling
    public Vec3d getLiquidPush(Material materialIn, Entity entityIn) {
        int j2 = MathHelper.floor(mc.player.getEntityBoundingBox().minX);
        int k2 = MathHelper.ceil(mc.player.getEntityBoundingBox().maxX);
        int l2 = MathHelper.floor(mc.player.getEntityBoundingBox().minY);
        int i3 = MathHelper.ceil(mc.player.getEntityBoundingBox().maxY);
        int j3 = MathHelper.floor(mc.player.getEntityBoundingBox().minZ);
        int k3 = MathHelper.ceil(mc.player.getEntityBoundingBox().maxZ);


        Vec3d vec3d = Vec3d.ZERO;
        BlockPos.PooledMutableBlockPos blockpos$pooledmutableblockpos = BlockPos.PooledMutableBlockPos.retain();

        for (int l3 = j2; l3 < k2; ++l3) {
            for (int i4 = l2; i4 < i3; ++i4) {
                for (int j4 = j3; j4 < k3; ++j4) {
                    blockpos$pooledmutableblockpos.setPos(l3, i4, j4);
                    IBlockState iBlockState = mc.world.getBlockState(blockpos$pooledmutableblockpos);
                    Block block = iBlockState.getBlock();

                    Boolean result = block.isEntityInsideMaterial(mc.world, blockpos$pooledmutableblockpos, iBlockState, entityIn, i3, materialIn, false);
                    if (result != null && result) {
                        // Forge: When requested call blocks modifyAcceleration method, and more importantly cause this method to return true, which results in an entity being "inWater"
                        vec3d = block.modifyAcceleration(mc.world, blockpos$pooledmutableblockpos, entityIn, vec3d);
                        continue;
                    } else if (result != null) continue;

                    if (iBlockState.getMaterial() == materialIn) {
                        double d0 = (float) (i4 + 1) - BlockLiquid.getLiquidHeightPercent(iBlockState.getValue(BlockLiquid.LEVEL));

                        if ((double) i3 >= d0) {
                            vec3d = block.modifyAcceleration(mc.world, blockpos$pooledmutableblockpos, entityIn, vec3d);
                        }
                    }
                }
            }
        }

        blockpos$pooledmutableblockpos.release();

        if (vec3d.lengthVector() > 0.0D && entityIn.isPushedByWater()) {
            vec3d = vec3d.normalize();
            return new Vec3d(vec3d.x * 0.014D, vec3d.y * 0.014D, vec3d.z * 0.014D);
        } else
            return new Vec3d(0, 0, 0);

    }

    public List<BlockPos> getLiquidBlocks(AxisAlignedBB bb) {

        List<BlockPos> list = NonNullList.create();

        int minX = (int) Math.floor(bb.minX);
        int maxX = (int) Math.ceil(bb.maxX);
        int minY = (int) Math.floor(bb.minY);
        int maxY = (int) Math.ceil(bb.maxY);
        int minZ = (int) Math.floor(bb.minZ);
        int maxZ = (int) Math.ceil(bb.maxZ);

        // For each position, we add
        for (int x = minX; x < maxX; x++)
            for (int y = minY; y < maxY; y++)
                for (int z = minZ; z < maxZ; z++)
                    // Only blocks which push us
                    if (mc.world.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof BlockLiquid)
                        list.add(new BlockPos(x, y, z));

        return list;
    }

    Vec3d getWaterSlowDown() {

        double d0 = mc.player.posY;
        float f1 = .8f;
        float f2 = 0.02F;
        float f3 = (float) EnchantmentHelper.getDepthStriderModifier(mc.player);

        if (f3 > 3.0F) {
            f3 = 3.0F;
        }

        if (!mc.player.onGround) {
            f3 *= 0.5F;
        }

        if (f3 > 0.0F) {
            f1 += (0.54600006F - f1) * f3 / 3.0F;
            f2 += (mc.player.getAIMoveSpeed() - f2) * f3 / 3.0F;
        }

        Vec3d move = moveRelative(0, 0, 1, f2);

        double motionX = move.x;
        double motionY = move.z;
        double motionZ = move.z;

        motionX *= f1;
        motionY *= 0.800000011920929D;
        motionZ *= f1;

        if (!mc.player.hasNoGravity()) {
            mc.player.motionY -= 0.02D;
        }

        if (mc.player.collidedHorizontally && mc.player.isOffsetPositionInLiquid(motionX, motionY + 0.6000000238418579D - mc.player.posY + d0, motionZ)) {
            mc.player.motionY = 0.30000001192092896D;
        }

        return new Vec3d(motionX,motionY,motionZ);

    }

    public Vec3d moveRelative(float strafe, float up, float forward, float friction) {
        float netMovement = strafe * strafe + up * up + forward * forward;
        if (netMovement >= 1.0E-4F)
        {
            netMovement = MathHelper.sqrt(netMovement);
            if (netMovement < 1.0F) netMovement = 1.0F;
            netMovement = friction / netMovement;
            strafe = strafe * netMovement;
            up = up * netMovement;
            forward = forward * netMovement;
            if(mc.player.isInWater() || mc.player.isInLava())
            {
                strafe = strafe * (float)mc.player.getEntityAttribute(SWIM_SPEED).getAttributeValue();
                up = up * (float)mc.player.getEntityAttribute(SWIM_SPEED).getAttributeValue();
                forward = forward * (float) mc.player.getEntityAttribute(SWIM_SPEED).getAttributeValue();
            }
            float f1 = MathHelper.sin(mc.player.rotationYaw * 0.017453292F);
            float f2 = MathHelper.cos(mc.player.rotationYaw * 0.017453292F);
            double motionX = strafe * f2 - forward * f1;
            double motionY = up;
            double motionZ = forward * f2 + strafe * f1;
            return new Vec3d(motionX,motionY,motionZ);
        }
        return Vec3d.ZERO;
    }
    //endregion

    //region Elytra Movement Handling
    /**
     * Minecraft's code for elytra, liquids and firework rockets so we can emulate elytra movement perfectly
     * @param pitch custom pitch to emulate
     * */
    Vec3d getElytraMovement(float pitch) {

        double motionX = mc.player.motionX;
        double motionY = mc.player.motionY;
        double motionZ = mc.player.motionZ;

        List<BlockPos> blacklist = NonNullList.create();

        for (BlockPos pos : getLiquidBlocks(mc.player.getEntityBoundingBox())) {

            // Make sure we don't count the same water twice
            if (!blacklist.contains(pos)) {

                blacklist.add(pos);

                Vec3d push = getLiquidPush(mc.world.getBlockState(pos).getMaterial(), mc.player);

                motionX += push.x;
                motionY += push.y;
                motionZ += push.z;

            }
        }

        if (!getLiquidBlocks(mc.player.getEntityBoundingBox()).isEmpty()) {
            Vec3d waterSlowDown = getWaterSlowDown();
            motionX *= waterSlowDown.x;
            motionY *= waterSlowDown.y;
            motionZ *= waterSlowDown.z;
        }

        // Make sure we are looking at the correct angles
        spoofedPitch = pitch;

        // Rendering fall damage properly
        if (mc.player.motionY > -0.5D) {
            mc.player.fallDistance = 1.0F;
        }

        Vec3d vec3d = getVectorForRotation(pitch, mc.player.rotationYaw);
        float f = pitch * 0.017453292F;
        double d6 = Math.sqrt(vec3d.x * vec3d.x + vec3d.z * vec3d.z);
        double d8 = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double d1 = vec3d.lengthVector();
        float f4 = MathHelper.cos(f);
        f4 = (float) ((double) f4 * (double) f4 * Math.min(1.0D, d1 / 0.4D));
        motionY += -0.08D + (double) f4 * 0.06D;

        if (motionY < 0.0D && d6 > 0.0D) {
            double d2 = motionY * -0.1D * (double) f4;
            motionY += d2;
            motionX += vec3d.x * d2 / d6;
            motionZ += vec3d.z * d2 / d6;
        }

        if (f < 0.0F) {
            double d10 = d8 * (double) (-MathHelper.sin(f)) * 0.04D;
            motionY += d10 * 3.2D;
            motionX -= vec3d.x * d10 / d6;
            motionZ -= vec3d.z * d10 / d6;
        }

        if (d6 > 0.0D) {
            motionX += (vec3d.x / d6 * d8 - motionX) * 0.1D;
            motionZ += (vec3d.z / d6 * d8 - motionZ) * 0.1D;
        }

        motionX *= 0.9900000095367432D;
        motionY *= 0.9800000190734863D;
        motionZ *= 0.9900000095367432D;

        if (getActiveRocket(false).isEmpty()) {

            motionX += vec3d.x * 0.1D + (vec3d.x * 1.5D - motionX) * 0.5D;
            motionY += vec3d.y * 0.1D + (vec3d.y * 1.5D - motionY) * 0.5D;
            motionZ += vec3d.z * 0.1D + (vec3d.z * 1.5D - motionZ) * 0.5D;

        }

        return new Vec3d(motionX, motionY, motionZ);

    }

    /**
     * Boosts passed elytra movement such that NCP is bypassed
     * @param vec elytra movement vector
     * */
    Vec3d boostMovement(Vec3d vec) {

        float yawRad = (float) Math.toRadians(mc.player.rotationYaw);
        return vec.addVector(-(Math.sin(yawRad) * 0.05f), 0, Math.cos(yawRad) * 0.05f);

    }

    // mc code which is usually protected
    Vec3d getVectorForRotation(float pitch, float yaw) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3d((f1 * f2), f3, (f * f2));
    }

    //endregion

    List<EntityFireworkRocket> getActiveRocket(boolean remove) {

        List<EntityFireworkRocket> rockets = NonNullList.create();

        mc.world.getLoadedEntityList().stream()
                .filter(Objects::nonNull)
                .filter(e -> e instanceof EntityFireworkRocket)
                .forEach(e -> {
                    if (((IEntityFireworkRocket) e).getBoostedEntity().equals(mc.player))
                        if (remove)
                            mc.world.removeEntity(e);
                        else
                            rockets.add((EntityFireworkRocket) e);

                });

        return rockets;

    }

    @SubscribeEvent
    public void onMicrowave(MicrowaveEvent event) {

        // cancel microwave noises
        event.setCanceled(antiMicrowave.getValue());

    }

    @SubscribeEvent
    public void onPacketSend(PacketEvent.PacketSendEvent event) {
        if (event.getPacket() instanceof CPacketPlayer) {

            CPacketPlayer packet = (CPacketPlayer) event.getPacket();
            ((ICPacketPlayer) packet).setPitch(spoofedPitch);
        }
    }

    @SubscribeEvent
    public void onPacketRecieve(PacketEvent.PacketReceiveEvent event) {

        if (event.getPacket() instanceof SPacketPlayerPosLook && cancel.getValue()) {

            mc.player.setVelocity(0,0,0);

            // If we rubberband and any rockets are present, delete the rockets (Credit to lambda for the idea)
            getActiveRocket(true);
        }
    }

    @SubscribeEvent
    public void onRenderRotation(RenderRotationsEvent event) {

        if (!mc.player.isElytraFlying())
            return;

        // Render our rotation client side
        event.setPitch(spoofedPitch);
        event.setYaw(mc.player.rotationYaw);

        event.setCanceled(true);

    }

    public enum Mode {
        /**
         * Speed up on demand when using vanilla elytra movement
         */
        BOOST,
        /**
         * Full control over movement
         */
        CONTROL,
        /**
         * Semi Full control over movement with moving up being impossible
         */
        PACKET,
    }

    public enum Up {
        /**
         * Does not allow upward movement
         * */
        NONE,

        /**
         * Flies directly
         * */
        FLY,

        /**
         * Glides up using vanilla elytra movement
         * */
        GLIDE
    }

    public enum TakeOff {

        /**
         * Spams takeoff packets
         * */
        NORMAL,

        /**
         * Uses timer and spams takeoff packets
         * */
        SLOW,
        PACKETFLY,

        /**
         * Packetflies to hover and spams takeoff packets
         * */
        MOTION,

        /**
         * Does not attempt to take off
         * */
        NONE

    }

    // in preparation for automatic boost elytrafly, don't delete
    enum BoostPhase {

        /**
         * If we are idle
         * */
        GLIDING,

        /**
         * If we are gaining momentum
         * */
        BOOSTING,

        /**
         * If we are looking up to gain altitude
         * */
        EMERGING

    }

    // in preparation for rocket elytrafly, don't delete
    enum RocketPhase {

        /**
         * If we are currently using rockets to go flat
         * */
        GLIDING,

        /**
         * If we are trying to go down
         * */
        LOWERING,

        /**
         * If we are trying to go up
         * */
        GAINING

    }

}
