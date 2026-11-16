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
import net.minecraft.server.management.ItemInWorldManager;


import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.*;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** A separate disposable native fixture; never included in the runtime JAR. */
@Mod(modid="portallinked_integration", name="PortalLinked integration fixture", version="1",
        acceptedMinecraftVersions="[1.7.10]", acceptableRemoteVersions="*",
        dependencies="required-after:portallinked")
public final class LegacyIntegration {
    private static final UUID PERSIST = UUID.fromString("731e37df-d67b-4bf2-a9bc-123456789002");
    public static UUID cancel;
    public static int cancellations;
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
    public static boolean protectedTravel(Entity entity) {
        if(entity.getUniqueID().equals(cancel)){cancellations++;return true;}return false;
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (ran || event.phase != TickEvent.Phase.END) return;
        server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server.getTickCounter() < 5) return;
        ran = true;
        String failure = null;
        try {
            overworld = server.worldServerForDimension(0); nether = server.worldServerForDimension(-1);
            check(server.getMinecraftVersion().equals("1.7.10"), "exact Minecraft 1.7.10 native runtime");
            if (System.getProperty("portallinked.phase", "1").equals("2")) restart();
            else { mechanics(); boundaries(); administration(); persistence(); }
            server.getConfigurationManager().saveAllPlayerData();
            for (WorldServer level : server.worldServers) if (level != null) level.saveAllChunks(true,null);
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
        frame(overworld,832,64,0,NativePortal.Axis.X,4,5);
        frame(overworld,800,64,0,NativePortal.Axis.X,2,3);
        frame(overworld,864,64,0,NativePortal.Axis.X,3,4);
        frame(nether,100,64,0,NativePortal.Axis.Z,5,6);
        NativePortal home = NativePortal.at(overworld,832,64,0);
        check(home != null && home.equals(NativePortal.at(overworld,835,68,0)), "whole large frame has one identity");
        frame(overworld,-64,80,-32,NativePortal.Axis.Z,21,21);
        check(NativePortal.at(overworld,-64,100,-12).width==21, "maximum frame and negative coordinates");
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
        NBTTagCompound before=(NBTTagCompound)PortalPair.stored(one).copy();
        cancel=one.getUniqueID();int previous=cancellations;
        travel(one);cancel=null;
        check(cancellations==previous+1, "a foreign native travel cancellation hook executes");
        check(one.dimension==0 && before.equals(PortalPair.stored(one)), "cancelled corrected travel preserves world and saved pair");
        travel(one);
        check(one.dimension==-1, "travel succeeds after protection is removed");
        // Make the remembered portal too small for the actual player body while
        // keeping that frame intact and providing an unobstructed vanilla neighbor.
        frame(overworld,800,64,0,NativePortal.Axis.X,10,10);
        one.dimensions(3,6);
        one.setLocationAndAngles(100.5,64,2.5,0,0);
        check(home.usable(overworld), "blocked-return control retains its full original frame");
        travel(one);
        check(one.dimension==0 && one.posX<820, "oversized blocked return uses normal native portal lookup");
        one.dimensions(.6f,1.8f);
        // A later ordinary journey records this new successful route.
        check(PortalPair.read(one).arrival.x==800, "successful fallback records its actual arrival");
        // Native player NBT and a real respawn transfer retain the pair.
        EntityPlayerMP replacement=server.getConfigurationManager().respawnPlayer(two,0,false);
        check(PortalPair.stored(two).equals(PortalPair.stored(replacement)), "native respawn copies the saved pair");
        Bot loaded=bot("NbtLoad",UUID.randomUUID(),overworld,800.5,64,.5);
        NBTTagCompound serialized=new NBTTagCompound();one.writeToNBT(serialized);loaded.readFromNBT(serialized);
        check(PortalPair.stored(one).equals(PortalPair.stored(loaded)), "native player NBT save and load retains geometry");
        // Use a new distant Nether portal: the engine must generate its own exit.
        frame(nether,5120,64,0,NativePortal.Axis.Z,3,4);
        Bot distant=bot("NewRoute",UUID.randomUUID(),nether,5120.5,64,.5);
        travel(distant);
        check(distant.dimension==0 && Math.abs(distant.posX-40960)<32, "new Nether route preserves native 1:8 scaling and generation");
        check(PortalPair.read(distant)!=null && PortalPair.read(distant).arrival.usable(overworld), "a newly generated active exit establishes its own pair");
        // A destroyed remembered target must also fall back naturally.
        Bot broken=bot("BrokenHome",UUID.randomUUID(),overworld,832.5,64,.5);
        travel(broken);
        overworld.setBlockToAir(832,64,0);
        check(!home.usable(overworld), "destroyed remembered frame becomes ineligible");
        travel(broken);
        check(broken.dimension==0 && broken.posX<820, "destroyed target uses the native neighboring portal");
    }

    private void boundaries() throws Exception {
        // Repeat the wrong-neighbor case with the player's home in the Nether,
        // then use the same physical frame from different touched blocks.
        frame(nether,120,70,128,NativePortal.Axis.X,4,5);
        frame(nether,126,70,128,NativePortal.Axis.X,2,3);
        frame(overworld,1000,70,1024,NativePortal.Axis.Z,4,5);
        Bot home=bot("NetherHome",UUID.randomUUID(),nether,120.5,70,128.5);
        travel(home);
        check(home.dimension==0, "a Nether-home forward journey reaches the Overworld");
        PortalPair trip=PortalPair.read(home);
        NativePortal arrival=trip==null?null:trip.arrival;
        check(arrival!=null && PortalPair.read(home)!=null && PortalPair.read(home).departure.x==120, "Nether-home journey records its actual departure");
        home.setLocationAndAngles(arrival.x+.5,arrival.y+1,arrival.z+1.5,0,0);
        travel(home);
        check(home.dimension==-1 && home.posX<123 && home.posZ>125, "a different block in the arrival frame returns to the Nether home");
        frame(overworld,832,64,0,NativePortal.Axis.X,4,5);
        Bot corrupt=bot("CorruptProbe",UUID.randomUUID(),nether,100.5,64,.5);
        NBTTagCompound unknown=new NBTTagCompound();unknown.setInteger("version",99);
        NBTTagCompound persisted=new NBTTagCompound();persisted.setTag(PortalPair.KEY,unknown);
        corrupt.getEntityData().setTag("PlayerPersisted",persisted);
        check(PortalPair.read(corrupt)==null, "unknown persisted schema is ignored");
        travel(corrupt);
        check(corrupt.dimension==0 && PortalPair.read(corrupt)!=null, "a successful native journey replaces corrupt saved data");
        NBTTagCompound before=(NBTTagCompound)PortalPair.stored(corrupt).copy();
        overworld.setBlock(3000,65,3000,Blocks.end_portal,0,2);
        corrupt.setLocationAndAngles(3000.5,65,3000.5,0,0);
        Blocks.end_portal.onEntityCollidedWithBlock(overworld,3000,65,3000,corrupt);
        check(corrupt.dimension==1 && before.equals(PortalPair.stored(corrupt)), "an actual End portal leaves the Nether pair unchanged");
        EntityPig pig=new EntityPig(nether);pig.setLocationAndAngles(100.5,64,.5,0,0);nether.spawnEntityInWorld(pig);
        UUID pigId=pig.getUniqueID();nativeEntityTravel(pig);
        Entity moved=find(overworld,pigId);
        check(moved instanceof EntityPig && !moved.getEntityData().hasKey(PortalPair.KEY), "native mob travel remains unchanged");
        EntityItem item=new EntityItem(nether,100.5,64,.5,new ItemStack(Items.diamond,7));nether.spawnEntityInWorld(item);
        UUID itemId=item.getUniqueID();nativeEntityTravel(item);
        Entity movedItem=find(overworld,itemId);
        check(movedItem instanceof EntityItem && ((EntityItem)movedItem).getEntityItem().stackSize==7, "native item travel retains its full stack");
    }
    private void nativeEntityTravel(Entity entity) {
        entity.timeUntilPortal=0;
        for(int tick=0;tick<=entity.getMaxInPortalTime()+1 && !entity.isDead;tick++){
            int x=net.minecraft.util.MathHelper.floor_double(entity.posX),y=net.minecraft.util.MathHelper.floor_double(entity.posY),z=net.minecraft.util.MathHelper.floor_double(entity.posZ);
            Blocks.portal.onEntityCollidedWithBlock(entity.worldObj,x,y,z,entity);entity.onEntityUpdate();
        }
    }

    private void administration() throws Exception {
        Bot player=bot("ConfigPlayer",UUID.randomUUID(),overworld,800.5,64,.5);
        travel(player);
        NBTTagCompound stored=(NBTTagCompound)PortalPair.stored(player).copy();
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
        NBTTagCompound other=(NBTTagCompound)PortalPair.stored(neighbor).copy();
        check(command(player,"portallinked forget")==1 && PortalPair.read(player)==null, "self forget clears the selected player's pair");
        check(other.equals(PortalPair.stored(neighbor)), "forget preserves another UUID's pair");
        command(player,"portallinked forget ConfigNeighbor");
        check(PortalPair.read(neighbor)==null, "operator can forget an online player's pair");
    }

    private void persistence() throws Exception {
        Bot player=bot("PersistedPlayer",PERSIST,overworld,864.5,64,.5);travel(player);
        check(PortalPair.read(player)!=null && player.dimension==-1, "persistence fixture establishes its native forward journey");
        try(OutputStream out=Files.newOutputStream(Paths.get("expected-pair.nbt"))){CompressedStreamTools.writeCompressed(PortalPair.stored(player),out);}
        server.getConfigurationManager().saveAllPlayerData();
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
        server.getConfigurationManager().readPlayerDataFromFile(player);
        NBTTagCompound expected;
        try(InputStream in=Files.newInputStream(Paths.get("expected-pair.nbt"))){expected=CompressedStreamTools.readCompressed(in);}
        check(expected.equals(PortalPair.stored(player)), "pair survives an actual server process restart");
        player.admin=true;command(player,"portallinked status");
        check(player.replies.get(player.replies.size()-1).contains("enabled=false"), "invalid startup disables linking");
        travel(player);
        check(player.dimension==0 && player.posX<820 && expected.equals(PortalPair.stored(player)), "disabled startup preserves data during an ordinary native return");
        // Restore the recorded arrival without creating a new portal journey.
        server.getConfigurationManager().transferPlayerToDimension(player,-1,nether.getDefaultTeleporter());
        player.setLocationAndAngles(100.5,64,.5,0,0);
        writeConfig(true,"",false);command(player,"portallinked reload");
        travel(player);
        check(player.dimension==0 && player.posX>862, "a valid reload restores the remembered route after restart");
    }

    private Entity find(WorldServer world,UUID id) {
        for(Object raw:world.loadedEntityList){Entity entity=(Entity)raw;if(id.equals(entity.getUniqueID()))return entity;}
        return null;
    }
    private Bot bot(String name,UUID id,WorldServer world,double x,double y,double z) {
        Bot player=new Bot(server,world,new GameProfile(id,name));
        NetworkManager connection=new NetworkManager(false);
        ReflectionHelper.setPrivateValue(NetworkManager.class,connection,new EmbeddedChannel(new io.netty.channel.ChannelOutboundHandlerAdapter()),"channel","field_150746_k");
        ReflectionHelper.setPrivateValue(NetworkManager.class,connection,new InetSocketAddress("127.0.0.1",0),"socketAddress","field_150743_l");
        player.playerNetServerHandler=new NetHandlerPlayServer(server,connection,player);
        connection.setNetHandler(player.playerNetServerHandler);
        player.dimension=world.provider.dimensionId;
        player.capabilities.isCreativeMode=true;player.capabilities.disableDamage=true;
        player.capabilities.allowFlying=true;player.capabilities.isFlying=true;
        player.setLocationAndAngles(x,y,z,0,0);
        server.getConfigurationManager().playerEntityList.add(player);bots.add(player);
        return player;
    }
    private void travel(EntityPlayerMP player) {
        player.timeUntilPortal=0;
        NativePortal contact=NativePortal.touching(player);
        if(contact==null){
            // 1.7 exits can stand half a block outside the plane. Walk the test
            // player back into the nearest intact frame, independent of saved links.
            double nearest=Double.POSITIVE_INFINITY;
            int px=net.minecraft.util.MathHelper.floor_double(player.posX),py=net.minecraft.util.MathHelper.floor_double(player.posY),pz=net.minecraft.util.MathHelper.floor_double(player.posZ);
            for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++){
                NativePortal candidate=NativePortal.at(player.getServerForPlayer(),px+dx,py+dy,pz+dz);
                double distance=dx*dx+dy*dy+dz*dz;
                if(candidate!=null && distance<nearest){contact=candidate;nearest=distance;}
            }
        }
        check(contact!=null && contact.usable(player.getServerForPlayer()),"native departure uses an active framed portal");
        player.setLocationAndAngles(contact.x+.5,contact.y,contact.z+.5,player.rotationYaw,player.rotationPitch);
        int x=net.minecraft.util.MathHelper.floor_double(player.posX),y=net.minecraft.util.MathHelper.floor_double(player.posY),z=net.minecraft.util.MathHelper.floor_double(player.posZ);
        int source=player.dimension,cancelled=cancellations;
        for(int tick=0;tick<=player.getMaxInPortalTime()+1;tick++){
            Blocks.portal.onEntityCollidedWithBlock(player.worldObj,x,y,z,player);player.onEntityUpdate();
            if(player.dimension!=source || cancellations!=cancelled)break;
        }
    }
    private void frame(WorldServer world,int x,int y,int z,NativePortal.Axis axis,int width,int height) {
        int dx=axis==NativePortal.Axis.X?1:0,dz=axis==NativePortal.Axis.Z?1:0;
        for(int row=-1;row<=height;row++)for(int col=-1;col<=width;col++)
            if(row==-1||row==height||col==-1||col==width)
                world.setBlock(x+dx*col,y+row,z+dz*col,Blocks.obsidian,0,2);
        for(int row=0;row<height;row++)for(int col=0;col<width;col++)
            world.setBlock(x+dx*col,y+row,z+dz*col,Blocks.portal,axis==NativePortal.Axis.X?1:2,2);
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
        Bot(MinecraftServer server,WorldServer world,GameProfile profile){super(server,world,profile,new ItemInWorldManager(world));}
        void dimensions(float width,float height){setSize(width,height);}
        @Override public boolean canCommandSenderUseCommand(int level,String name){return level==0||admin;}
        @Override public void addChatMessage(IChatComponent message){replies.add(message.getUnformattedText());}
    }
}
