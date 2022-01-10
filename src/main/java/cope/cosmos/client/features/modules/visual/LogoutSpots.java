package cope.cosmos.client.features.modules.visual;

import cope.cosmos.client.events.PacketEvent;
import cope.cosmos.client.features.modules.Category;
import cope.cosmos.client.features.modules.Module;
import cope.cosmos.client.features.modules.client.Colors;
import cope.cosmos.client.features.setting.Setting;
import cope.cosmos.util.client.ColorUtil;
import cope.cosmos.util.render.RenderBuilder;
import cope.cosmos.util.render.RenderUtil;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class LogoutSpots extends Module {

    public static LogoutSpots INSTANCE;
    public LogoutSpots() {
        super("LogoutSpots", Category.VISUAL, "Show where players log out in render distance");
        INSTANCE = this;
    }

    // We allow none since if none is selected, just the nametag will render
    public static Setting<RenderBuilder.Box> main = new Setting<>("BoxStyle", RenderBuilder.Box.FILL).setDescription("Visual style for the box").setExclusion(RenderBuilder.Box.GLOW, RenderBuilder.Box.REVERSE);


    @Override
    public void onEnable() {
        arrays.clear();
    }

    List<Object[]> arrays = NonNullList.create(); // totally the best way to do this
    int dimension = -1; // clear on dimension change
    String server = ""; // clear on server change

    @SubscribeEvent
    public void onPacketEvent(PacketEvent.PacketReceiveEvent event) {

        // tab list packet
        if (event.getPacket() instanceof SPacketPlayerListItem)
            // removing players
            if (((SPacketPlayerListItem) event.getPacket()).getAction().equals(SPacketPlayerListItem.Action.REMOVE_PLAYER)) {
                // for each player removed
                for (SPacketPlayerListItem.AddPlayerData playerData : ((SPacketPlayerListItem) event.getPacket()).getEntries())
                    // if they aren't us
                    if (playerData.getProfile().getId() != mc.player.getUniqueID()) {

                        if (dimension != mc.player.dimension) {
                            dimension = mc.player.dimension;
                            arrays.clear();
                        }

                        if (!server.equals(Objects.requireNonNull(mc.getCurrentServerData()).serverIP) || mc.isIntegratedServerRunning()) {
                            server = mc.isIntegratedServerRunning() ? "SinglePlayer" : Objects.requireNonNull(mc.getCurrentServerData()).serverIP;
                            arrays.clear();
                        }

                        if (mc.world.getPlayerEntityByUUID(playerData.getProfile().getId()) == null)
                            return;

                        EntityPlayer player = Objects.requireNonNull(mc.world.getPlayerEntityByUUID(playerData.getProfile().getId()));

                        // no unloaded players appearing at 0,0 for no reason
                        if (!mc.world.loadedEntityList.contains(player))
                            return;

                        Vec3d pos = (player.getPositionVector());
                        String name = (player.getName());
                        String time = new SimpleDateFormat("k:mm:ss").format(new Date());
                        AxisAlignedBB box = player.getEntityBoundingBox();

                        arrays.add(new Object[]{pos, box, name, time, playerData.getProfile().getId()});

                    }
            // add player packet
            } else if (((SPacketPlayerListItem) event.getPacket()).getAction().equals(SPacketPlayerListItem.Action.ADD_PLAYER))
                // for each player added
                for (SPacketPlayerListItem.AddPlayerData playerData : ((SPacketPlayerListItem) event.getPacket()).getEntries())
                    // remove from our logout spots list if their UUID matches ours
                    arrays.removeIf(o -> o[4].equals(playerData.getProfile().getId()));
    }

    @Override
    public void onRender3D() {

        for (Object[] obj : arrays) {

            Vec3d vec = (Vec3d) obj[0];
            AxisAlignedBB box = (AxisAlignedBB) obj[1];
            String name = (String) obj[2];
            String time = (String) obj[3];

            RenderUtil.drawBox(new RenderBuilder()
                    .position(box)
                    .height(0) // box does these
                    .length(0)
                    .width(0)
                    .color(Colors.color.getValue())
                    .box(main.getValue())
                    .setup()
                    .line(2)
                    .cull(false)
                    .shade(false)
                    .alpha(false)
                    .depth(true)
                    .blend()
                    .texture()
            );

            RenderUtil.drawNametag(vec.addVector(-(box.maxX - box.minX), 0, -(box.maxZ - box.minZ)), 1.8f, name + " (" + time + ")");
        }
    }
}
