// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import dev.eyecrasher.portallinked.NativePortal;
import dev.eyecrasher.portallinked.PortalPair;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.BlockPortal;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.*;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** A separate disposable native fixture; never included in the runtime JAR. */
@Mod(modid="portallinked_integration", name="PortalLinked integration fixture", version="1",
        acceptedMinecraftVersions="[1.12.2]", acceptableRemoteVersions="*", serverSideOnly=true,
        dependencies="required-after:portallinked")
public final class LegacyIntegration {
    private static final UUID PERSIST = UUID.fromString("731e37df-d67b-4bf2-a9bc-123456789002");
    private static UUID cancel;
    private static int cancellations;
    private boolean ran;
    private final List<String> checks = new ArrayList<>();
    private MinecraftServer server;
    private WorldServer overworld, nether;
    private final List<Bot> bots = new ArrayList<>();

    @Mod.EventHandler public void initialize(FMLPreInitializationEvent event) {
        if (!Boolean.getBoolean("portallinked.integration")) return;
        MinecraftForge.EVENT_BUS.register(this);
        if (FMLCommonHandler.instance().bus() != MinecraftForge.EVENT_BUS) FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void protect(EntityTravelToDimensionEvent event) {
        if (event.getEntity().getUniqueID().equals(cancel)) { cancellations++; event.setCanceled(true); }
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (ran || event.phase != TickEvent.Phase.END) return;
        server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server.getTickCounter() < 5) return;
        ran = true;
        String failure = null;
        try {
            overworld = server.getWorld(0); nether = server.getWorld(-1);
            check(server.getMinecraftVersion().equals("1.12.2"), "exact Minecraft 1.12.2 native runtime");
            if (System.getProperty("portallinked.phase", "1").equals("2")) restart();
            else { mechanics(); boundaries(); administration(); persistence(); }
            server.getPlayerList().saveAllPlayerData();
            server.saveAllWorlds(true);
        } catch (Throwable ex) { ex.printStackTrace(); failure = ex.toString(); }
        try {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("checks", checks); result.put("failure", failure);
            Files.write(Paths.get("result-" + System.getProperty("portallinked.phase","1") + ".json"),
                    new Gson().toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) { throw new AssertionError(ex); }
        server.initiateShutdown();
    }

    private void mechanics() throws Exception {
        frame(overworld,832,64,0,EnumFacing.Axis.X,4,5);
        frame(overworld,800,64,0,EnumFacing.Axis.X,2,3);
        frame(overworld,864,64,0,EnumFacing.Axis.X,3,4);
        frame(nether,100,64,0,EnumFacing.Axis.Z,5,6);
        NativePortal home = NativePortal.at(overworld,new BlockPos(832,64,0));
        check(home != null && home.equals(NativePortal.at(overworld,new BlockPos(835,68,0))), "whole large frame has one identity");
        frame(overworld,-64,80,-32,EnumFacing.Axis.Z,21,21);
        check(NativePortal.at(overworld,new BlockPos(-64,100,-12)).width==21, "maximum frame and negative coordinates");
        Bot control=bot("VanillaControl",UUID.randomUUID(),nether,100.5,64,.5);
        // No remembered pair: this is the unmodified native neighboring-portal case.
        travel(control);
        check(control.dimension==0 && control.posX<820, "native control chooses the neighboring Overworld base: dimension="+control.dimension+", x="+control.posX);
        Bot one=bot("FirstPlayer",UUID.randomUUID(),overworld,832.5,64,.5);
        Bot two=bot("SecondPlayer",UUID.randomUUID(),overworld,864.5,64,.5);
        travel(one); travel(two);
        check(one.dimension==-1 && two.dimension==-1, "both native arrivals reach the shared Nether portal");
        check(PortalPair.read(one)!=null && PortalPair.read(two)!=null, "successful native crossings save separate player pairs");
        check(PortalPair.read(one).departure.x==832 && PortalPair.read(two).departure.x==864, "UUIDs retain their own departure frames");
        travel(one); travel(two);
        check(one.dimension==0 && one.posX>830 && one.posX<840, "remembered return reaches the first player's original base");
        check(two.dimension==0 && two.posX>862, "shared portal returns the second player to their own base");
        NBTTagCompound before=PortalPair.stored(one).copy();
        cancel=one.getUniqueID();int previous=cancellations;
        travel(one);cancel=null;
        check(cancellations==previous+1, "a foreign lowest-priority travel protection event executes");
        check(one.dimension==0 && before.equals(PortalPair.stored(one)), "cancelled corrected travel preserves world and saved pair");
        travel(one);
        check(one.dimension==-1, "travel succeeds after protection is removed");
        // Make the remembered portal too small for the actual player body while
        // keeping that frame intact and providing an unobstructed vanilla neighbor.
        frame(overworld,800,64,0,EnumFacing.Axis.X,10,10);
        one.dimensions(3,6);
        one.setLocationAndAngles(100.5,64,2.5,0,0);
        check(home.usable(overworld), "blocked-return control retains its full original frame");
        travel(one);
        check(one.dimension==0 && one.posX<820, "oversized blocked return uses normal native portal lookup");
        one.dimensions(.6f,1.8f);
        // A later ordinary journey records this new successful route.
        check(PortalPair.read(one).arrival.x==800, "successful fallback records its actual arrival");
        // Native player NBT and a real respawn transfer retain the pair.
        EntityPlayerMP replacement=server.getPlayerList().recreatePlayerEntity(two,0,false);
        check(PortalPair.stored(two).equals(PortalPair.stored(replacement)), "native respawn copies the saved pair");
        Bot loaded=bot("NbtLoad",UUID.randomUUID(),overworld,800.5,64,.5);
        NBTTagCompound serialized=new NBTTagCompound();one.writeToNBT(serialized);loaded.readFromNBT(serialized);
        check(PortalPair.stored(one).equals(PortalPair.stored(loaded)), "native player NBT save and load retains geometry");
        // Use a new distant Nether portal: the engine must generate its own exit.
        frame(nether,5120,64,0,EnumFacing.Axis.Z,3,4);
        Bot distant=bot("NewRoute",UUID.randomUUID(),nether,5120.5,64,.5);
        travel(distant);
        check(distant.dimension==0 && Math.abs(distant.posX-40960)<32, "new Nether route preserves native 1:8 scaling and generation");
        check(NativePortal.touching(distant)!=null && PortalPair.read(distant)!=null, "a newly generated active exit establishes its own pair");
        // A destroyed remembered target must also fall back naturally.
        Bot broken=bot("BrokenHome",UUID.randomUUID(),overworld,832.5,64,.5);
        travel(broken);
        overworld.setBlockState(new BlockPos(832,64,0),Blocks.AIR.getDefaultState(),3);
        check(!home.usable(overworld), "destroyed remembered frame becomes ineligible");
        travel(broken);
        check(broken.dimension==0 && broken.posX<820, "destroyed target uses the native neighboring portal");
    }

    private void boundaries() throws Exception {
        // Repeat the wrong-neighbor case with the player's home in the Nether,
        // then use the same physical frame from different touched blocks.
        frame(nether,120,70,128,EnumFacing.Axis.X,4,5);
        frame(nether,126,70,128,EnumFacing.Axis.X,2,3);
        frame(overworld,1000,70,1024,EnumFacing.Axis.Z,4,5);
        Bot home=bot("NetherHome",UUID.randomUUID(),nether,120.5,70,128.5);
        travel(home);
        check(home.dimension==0, "a Nether-home forward journey reaches the Overworld");
        NativePortal arrival=NativePortal.touching(home);
        check(arrival!=null && PortalPair.read(home)!=null && PortalPair.read(home).departure.x==120, "Nether-home journey records its actual departure");
        home.setLocationAndAngles(arrival.x+.5,arrival.y+1,arrival.z+1.5,0,0);
        travel(home);
        check(home.dimension==-1 && home.posX<123 && home.posZ>125, "a different block in the arrival frame returns to the Nether home");
        frame(overworld,832,64,0,EnumFacing.Axis.X,4,5);
        Bot border=bot("BorderProbe",UUID.randomUUID(),overworld,832.5,64,.5);
        travel(border);
        double original=overworld.getWorldBorder().getDiameter();
        overworld.getWorldBorder().setTransition(1640);
        travel(border);
        check(border.dimension==0 && border.posX<820, "border-excluded saved target uses native portal lookup");
        overworld.getWorldBorder().setTransition(original);
        Bot corrupt=bot("CorruptProbe",UUID.randomUUID(),nether,100.5,64,.5);
        NBTTagCompound unknown=new NBTTagCompound();unknown.setInteger("version",99);
        NBTTagCompound persisted=new NBTTagCompound();persisted.setTag(PortalPair.KEY,unknown);
        corrupt.getEntityData().setTag("PlayerPersisted",persisted);
        check(PortalPair.read(corrupt)==null, "unknown persisted schema is ignored");
        travel(corrupt);
        check(corrupt.dimension==0 && PortalPair.read(corrupt)!=null, "a successful native journey replaces corrupt saved data");
        NBTTagCompound before=PortalPair.stored(corrupt).copy();
        BlockPos end=new BlockPos(800,65,0);
        overworld.setBlockState(end,Blocks.END_PORTAL.getDefaultState(),2);
        corrupt.setLocationAndAngles(800.5,65,.5,0,0);
        Blocks.END_PORTAL.onEntityCollidedWithBlock(overworld,end,overworld.getBlockState(end),corrupt);
        check(corrupt.dimension==1 && before.equals(PortalPair.stored(corrupt)), "an actual End portal leaves the Nether pair unchanged");
        EntityPig pig=new EntityPig(nether);pig.setLocationAndAngles(100.5,64,.5,0,0);nether.spawnEntity(pig);
        UUID pigId=pig.getUniqueID();nativeEntityTravel(pig);
        Entity moved=overworld.getEntityFromUuid(pigId);
        check(moved instanceof EntityPig && !moved.getEntityData().hasKey(PortalPair.KEY), "native mob travel remains unchanged");
        EntityItem item=new EntityItem(nether,100.5,64,.5,new ItemStack(Items.DIAMOND,7));nether.spawnEntity(item);
        UUID itemId=item.getUniqueID();nativeEntityTravel(item);
        Entity movedItem=overworld.getEntityFromUuid(itemId);
        check(movedItem instanceof EntityItem && ((EntityItem)movedItem).getItem().getCount()==7, "native item travel retains its full stack");
    }
    private void nativeEntityTravel(Entity entity) {
        entity.timeUntilPortal=0;
        for(int tick=0;tick<=entity.getMaxInPortalTime()+1 && !entity.isDead;tick++){
            entity.setPortal(entity.getPosition());entity.onEntityUpdate();
        }
    }

    private void administration() throws Exception {
        Bot player=bot("ConfigPlayer",UUID.randomUUID(),overworld,800.5,64,.5);
        travel(player);
        NBTTagCompound stored=PortalPair.stored(player).copy();
        check(command(player,"portallinked status")==0, "ordinary players cannot use administration commands");
        player.admin=true;
        check(command(player,"portallinked status")==1 && player.replies.get(player.replies.size()-1).contains("enabled=true"), "operator status reports active defaults");
        writeConfig(false,"",false);command(player,"portallinked reload");
        travel(player);travel(player);
        check(stored.equals(PortalPair.stored(player)), "disabled mode preserves stored pairs in both directions");
        String[] invalid={
            "{\"enabled\":\"false\",\"excluded-worlds\":[],\"debug\":false}",
            "{\"enabled\":true,\"excluded-worlds\":\"minecraft:overworld\",\"debug\":false}",
            "{\"enabled\":true,\"excluded-worlds\":[42],\"debug\":false}",
            "{\"enabled\":true,\"excluded-worlds\":[\"Minecraft:overworld\"],\"debug\":false}",
            "{\"enabled\":true,\"excluded-worlds\":[],\"debug\":\"false\"}",
            "{\"enabled\":true,\"excluded-worlds\":[],\"debug\":false,}",
            "{\"enabled\":true,\"excluded-worlds\":[],\"debug\":false} {}"
        };
        for(String text:invalid){
            Files.write(Paths.get("config/portallinked.json"),text.getBytes(StandardCharsets.UTF_8));
            command(player,"portallinked reload");
            check(player.replies.get(player.replies.size()-1).contains("could not be reloaded"), "invalid reload reports failure");
            command(player,"portallinked status");
            check(player.replies.get(player.replies.size()-1).contains("enabled=false") && stored.equals(PortalPair.stored(player)), "invalid reload retains settings and stored pair");
        }
        for(String excluded:Arrays.asList("minecraft:overworld","minecraft:the_nether")){
            writeConfig(true,excluded,false);command(player,"portallinked reload");
            travel(player);travel(player);
            check(stored.equals(PortalPair.stored(player)), "either excluded endpoint prevents recording: "+excluded);
        }
        writeConfig(true,"",false);command(player,"portallinked reload");
        Bot neighbor=bot("ConfigNeighbor",UUID.randomUUID(),overworld,800.5,64,.5);travel(neighbor);
        NBTTagCompound other=PortalPair.stored(neighbor).copy();
        check(command(player,"portallinked forget")==1 && PortalPair.read(player)==null, "self forget clears the selected player's pair");
        check(other.equals(PortalPair.stored(neighbor)), "forget preserves another UUID's pair");
        command(player,"portallinked forget ConfigNeighbor");
        check(PortalPair.read(neighbor)==null, "operator can forget an online player's pair");
    }

    private void persistence() throws Exception {
        Bot player=bot("PersistedPlayer",PERSIST,overworld,864.5,64,.5);travel(player);
        check(PortalPair.read(player)!=null && player.dimension==-1, "persistence fixture establishes its native forward journey");
        try(OutputStream out=Files.newOutputStream(Paths.get("expected-pair.nbt"))){CompressedStreamTools.writeCompressed(PortalPair.stored(player),out);}
        server.getPlayerList().saveAllPlayerData();
        File directory=overworld.getSaveHandler().getWorldDirectory();
        File data=new File(new File(directory,"playerdata"),PERSIST+".dat");
        check(data.isFile(), "the native player data file exists on disk");
        NBTTagCompound disk;
        try(InputStream in=new FileInputStream(data)){disk=CompressedStreamTools.readCompressed(in);}
        check(disk.getCompoundTag("ForgeData").getCompoundTag("PlayerPersisted").getCompoundTag(PortalPair.KEY)
                .equals(PortalPair.stored(player)), "the actual player save contains the remembered pair");
    }
    private void restart() throws Exception {
        Bot player=bot("PersistedPlayer",PERSIST,nether,100.5,64,.5);
        server.getPlayerList().readPlayerDataFromFile(player);
        NBTTagCompound expected;
        try(InputStream in=Files.newInputStream(Paths.get("expected-pair.nbt"))){expected=CompressedStreamTools.readCompressed(in);}
        check(expected.equals(PortalPair.stored(player)), "pair survives an actual server process restart");
        player.admin=true;command(player,"portallinked status");
        check(player.replies.get(player.replies.size()-1).contains("enabled=false"), "invalid startup disables linking");
        travel(player);
        check(player.dimension==0 && player.posX<820 && expected.equals(PortalPair.stored(player)), "disabled startup preserves data during an ordinary native return");
        // Restore the recorded arrival without creating a new portal journey.
        server.getPlayerList().transferPlayerToDimension(player,-1,nether.getDefaultTeleporter());
        player.setLocationAndAngles(100.5,64,.5,0,0);
        writeConfig(true,"",false);command(player,"portallinked reload");
        travel(player);
        check(player.dimension==0 && player.posX>862, "a valid reload restores the remembered route after restart");
    }

    private Bot bot(String name,UUID id,WorldServer world,double x,double y,double z) {
        Bot player=new Bot(server,world,new GameProfile(id,name));
        NetworkManager connection=new NetworkManager(EnumPacketDirection.SERVERBOUND);
        ReflectionHelper.setPrivateValue(NetworkManager.class,connection,new EmbeddedChannel(),"channel","field_150746_k");
        ReflectionHelper.setPrivateValue(NetworkManager.class,connection,new InetSocketAddress("127.0.0.1",0),"socketAddress","field_150743_l");
        player.connection=new NetHandlerPlayServer(server,connection,player);
        connection.setNetHandler(player.connection);
        player.dimension=world.provider.getDimension();
        player.capabilities.isCreativeMode=true;player.capabilities.disableDamage=true;
        player.capabilities.allowFlying=true;player.capabilities.isFlying=true;
        player.setLocationAndAngles(x,y,z,0,0);
        server.getPlayerList().getPlayers().add(player);bots.add(player);
        return player;
    }
    private void travel(EntityPlayerMP player) {
        player.timeUntilPortal=0;
        BlockPos position=player.getPosition();
        check(player.world.getBlockState(position).getBlock()==Blocks.PORTAL, "native departure begins in an active portal");
        int source = player.dimension, cancelled = cancellations;
        for (int tick = 0; tick <= player.getMaxInPortalTime() + 1; tick++) {
            player.setPortal(position);
            player.onEntityUpdate();
            if (player.dimension != source || cancellations != cancelled) break;
        }
    }
    private void frame(WorldServer world,int x,int y,int z,EnumFacing.Axis axis,int width,int height) {
        int dx=axis==EnumFacing.Axis.X?1:0,dz=axis==EnumFacing.Axis.Z?1:0;
        for(int row=-1;row<=height;row++)for(int col=-1;col<=width;col++)
            if(row==-1||row==height||col==-1||col==width)
                world.setBlockState(new BlockPos(x+dx*col,y+row,z+dz*col),Blocks.OBSIDIAN.getDefaultState(),2);
        for(int row=0;row<height;row++)for(int col=0;col<width;col++)
            world.setBlockState(new BlockPos(x+dx*col,y+row,z+dz*col),Blocks.PORTAL.getDefaultState().withProperty(BlockPortal.AXIS,axis),2);
    }
    private void writeConfig(boolean enabled,String excluded,boolean debug)throws IOException{
        Files.createDirectories(Paths.get("config"));
        Files.write(Paths.get("config/portallinked.json"),("{\"enabled\":"+enabled+",\"excluded-worlds\":["+
                (excluded.isEmpty()?"":"\""+excluded+"\"")+"],\"debug\":"+debug+"}").getBytes(StandardCharsets.UTF_8));
    }
    private int command(Bot player,String text){return server.getCommandManager().executeCommand(player,text);}
    private void check(boolean value,String name){if(!value)throw new AssertionError(name);checks.add(name);}

    private static final class Bot extends EntityPlayerMP {
        boolean admin;
        final List<String> replies=new ArrayList<>();
        Bot(MinecraftServer server,WorldServer world,GameProfile profile){super(server,world,profile,new PlayerInteractionManager(world));}
        void dimensions(float width,float height){setSize(width,height);}
        @Override public boolean canUseCommand(int level,String name){return level==0||admin;}
        @Override public void sendMessage(ITextComponent message){replies.add(message.getUnformattedText());}
    }
}
