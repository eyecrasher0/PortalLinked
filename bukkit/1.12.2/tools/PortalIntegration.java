// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.v1_12_R1.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Separate fixture exercising the actual 1.12.2 collision, travel and player-save APIs. */
public final class PortalIntegration extends JavaPlugin implements Listener {
    private static final String PREFIX = "portallinked:last_crossing:";
    private static final UUID PERSIST = UUID.fromString("731e37df-d67b-4bf2-a9bc-123456789012");
    private final List<String> checks = new ArrayList<>();
    private final List<Bot> bots = new ArrayList<>();
    private final Map<UUID,List<String>> replies = new HashMap<>();
    private MinecraftServer server;
    private WorldServer overworld, nether;
    private JavaPlugin subject;
    private boolean cancelPortal, cancelTeleport;
    private UUID protectedPlayer;
    private int cancellations;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, this::run, 5);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void protectPortal(PlayerPortalEvent event) {
        if (cancelPortal && event.getPlayer().getUniqueId().equals(protectedPlayer)) {
            event.setCancelled(true); cancellations++;
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void protectTeleport(PlayerTeleportEvent event) {
        if (!(event instanceof PlayerPortalEvent) && cancelTeleport
                && event.getCause()==PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && event.getPlayer().getUniqueId().equals(protectedPlayer)) {
            event.setCancelled(true); cancellations++;
        }
    }
    private void run() {
        String failure=null;
        try {
            server=((org.bukkit.craftbukkit.v1_12_R1.CraftServer)Bukkit.getServer()).getServer();
            overworld=((org.bukkit.craftbukkit.v1_12_R1.CraftWorld)Bukkit.getWorlds().stream()
                    .filter(w->w.getEnvironment()==World.Environment.NORMAL).findFirst().get()).getHandle();
            nether=((org.bukkit.craftbukkit.v1_12_R1.CraftWorld)Bukkit.getWorlds().stream()
                    .filter(w->w.getEnvironment()==World.Environment.NETHER).findFirst().get()).getHandle();
            subject=(JavaPlugin)Bukkit.getPluginManager().getPlugin("PortalLinked");
            check(server.getVersion().equals("1.12.2"),"exact Minecraft1.12.2 native runtime");
            check(subject!=null && subject.isEnabled(),"runtime plugin enabled");
            check(subject.getDescription().getAuthors().equals(Arrays.asList("EyeCrasher")),"EyeCrasher metadata");
            if (System.getProperty("portal.phase","1").equals("2")) restart();
            else { mechanics(); boundaries(); ceilingPolicy(); unchangedEntities(); administration(); persistence(); }
            server.getPlayerList().savePlayers();
            for (World world:Bukkit.getWorlds()) world.save();
        } catch(Throwable ex) { ex.printStackTrace(); failure=ex.toString(); }
        try {
            Map<String,Object> outcome=new LinkedHashMap<>();outcome.put("checks",checks);outcome.put("failure",failure);
            Files.write(Paths.get("result-"+System.getProperty("portal.phase","1")+".json"),
                    new com.google.gson.GsonBuilder().serializeNulls().create().toJson(outcome).getBytes(StandardCharsets.UTF_8));
        } catch(IOException ex) { throw new AssertionError(ex); }
        Bukkit.shutdown();
    }
    private void mechanics() throws Exception {
        frame(overworld,832,64,0,true,4,5);frame(overworld,800,64,0,true,2,3);
        frame(overworld,864,64,0,true,3,4);frame(nether,100,64,0,false,5,6);
        Bot control=bot("VanillaControl",UUID.randomUUID(),nether,100.5,64,.5);
        travel(control);check(control.dimension==0 && control.locX<820,"unlinked native control selects neighboring base: dimension="+control.dimension+", x="+control.locX+", world="+control.world.getWorld().getName());
        Bot one=bot("FirstPlayer",UUID.randomUUID(),overworld,832.5,64,.5);
        Bot two=bot("SecondPlayer",UUID.randomUUID(),overworld,864.5,64,.5);
        one.getBukkitEntity().addScoreboardTag("unrelated:keep-me");
        travel(one);travel(two);
        check(one.dimension==-1 && two.dimension==-1,"actual native forward journeys reach shared Nether portal");
        check(data(one)!=null && data(two)!=null,"successful crossings save individual player tags");
        check(readInt(data(one),17)==832 && readInt(data(two),17)==864,"two UUIDs retain separate departure frames");
        travel(one);travel(two);
        check(one.dimension==0 && one.locX>830 && one.locX<840,"remembered return reaches first base");
        check(two.dimension==0 && two.locX>862,"shared portal returns second UUID to its own base");
        byte[] before=data(one).clone();protectedPlayer=one.getUniqueID();cancelPortal=true;int n=cancellations;
        travel(one);cancelPortal=false;
        check(cancellations==n+1 && one.dimension==0 && Arrays.equals(before,data(one)),"cancelled portal preserves world and pair");
        cancelTeleport=true;n=cancellations;travel(one);cancelTeleport=false;protectedPlayer=null;
        check(cancellations==n+1 && one.dimension==0 && Arrays.equals(before,data(one)),"cancelled final teleport preserves world and pair");
        travel(one);check(one.dimension==-1,"travel recovers after foreign protection");
        frame(overworld,800,64,0,true,10,10);one.dimensions(3,6);
        one.setPositionRotation(100.5,64,2.5,0,0);travel(one);
        check(one.dimension==0 && one.locX<820,"oversized blocked return uses native fallback");
        check(readInt(data(one),48)==800,"successful fallback records actual neighboring arrival");
        one.dimensions(.6f,1.8f);
        check(one.getBukkitEntity().getScoreboardTags().contains("unrelated:keep-me"),"unrelated scoreboard tags survive link updates");
        byte[] respawn=data(two).clone();server.getPlayerList().moveToWorld(two,0,false);
        check(Arrays.equals(respawn,data(two)),"actual native respawn preserves stored link");
        NBTTagCompound save=new NBTTagCompound();one.save(save);
        Bot loaded=bot("NbtLoad",UUID.randomUUID(),overworld,800.5,64,.5);loaded.f(save);
        check(Arrays.equals(data(one),data(loaded)),"actual native NBT round trip preserves link");
        frame(nether,5120,64,0,false,3,4);Bot distant=bot("NewRoute",UUID.randomUUID(),nether,5120.5,64,.5);
        travel(distant);
        check(distant.dimension==0 && Math.abs(distant.locX-40960)<32,"new Nether route retains native1:8 scaling");
        check(data(distant)!=null && distant.getBukkitEntity().getLocation().getBlock().getType()==Material.PORTAL,
                "missing target produces a new active native exit and records pair");
        Bot broken=bot("BrokenHome",UUID.randomUUID(),overworld,832.5,64,.5);travel(broken);
        overworld.setTypeAndData(new BlockPosition(832,64,0),Blocks.AIR.getBlockData(),3);travel(broken);
        check(broken.dimension==0 && broken.locX<820,"destroyed remembered frame falls back natively");
    }
    private void boundaries() throws Exception {
        frame(nether,120,70,128,true,4,5);frame(nether,126,70,128,true,2,3);frame(overworld,1000,70,1024,false,4,5);
        Bot home=bot("NetherHome",UUID.randomUUID(),nether,120.5,70,128.5);travel(home);
        check(home.dimension==0 && readInt(data(home),17)==120,"journey can start with a home in the Nether");
        travel(home);check(home.dimension==-1 && home.locX<123,"reverse journey returns to original Nether home");
        frame(overworld,832,64,0,true,4,5);Bot border=bot("BorderProbe",UUID.randomUUID(),overworld,832.5,64,.5);travel(border);
        double original=overworld.getWorldBorder().getSize();overworld.getWorldBorder().setSize(1640);travel(border);
        check(border.dimension==0 && border.locX<820,"border-excluded saved frame falls back natively");overworld.getWorldBorder().setSize(original);
        Bot corrupt=bot("CorruptProbe",UUID.randomUUID(),nether,100.5,64,.5);
        corrupt.getBukkitEntity().addScoreboardTag(PREFIX+"invalid!");travel(corrupt);
        check(corrupt.dimension==0 && data(corrupt)!=null,"successful ordinary travel replaces corrupt owned data");
        check(owned(corrupt)==1,"only one owned tag remains after corrupt-data recovery");
        corrupt.getBukkitEntity().addScoreboardTag(PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{99}));
        travel(corrupt);check(data(corrupt)!=null && owned(corrupt)==1,"ambiguous duplicate tags recover via an ordinary journey");
        Bot full=bot("TagLimit",UUID.randomUUID(),overworld,864.5,64,.5);travel(full);
        check(data(full)!=null,"tag-limit control establishes a native pair");
        for(int i=0;full.getScoreboardTags().size()<1024;i++)full.addScoreboardTag("other:"+i);
        check(full.getScoreboardTags().size()==1024,"native tag limit control is full");
        byte[] fullLink=data(full);full.setPositionRotation(readInt(fullLink,48)+.5,readInt(fullLink,52),readInt(fullLink,56)+.5,0,0);
        travel(full);check(data(full)!=null && owned(full)==1 && full.getScoreboardTags().size()==1024,"owned link slot updates at native tag limit");
        Bot foreign=bot("ForeignTags",UUID.randomUUID(),overworld,864.5,64,.5);
        for(int i=0;i<1024;i++)foreign.addScoreboardTag("foreign:"+i);
        travel(foreign);
        check(foreign.dimension==-1 && data(foreign)==null && foreign.getScoreboardTags().size()==1024
                && foreign.getScoreboardTags().contains("foreign:0") && foreign.getScoreboardTags().contains("foreign:1023"),
                "full foreign tag storage keeps travel native and preserves every foreign slot");
        foreign.removeScoreboardTag("foreign:1023");foreign.setPositionRotation(100.5,64,2.5,0,0);travel(foreign);
        check(foreign.dimension==0 && data(foreign)!=null && foreign.getScoreboardTags().size()==1024,
                "link recording recovers when an owned slot becomes available");
    }
    private void ceilingPolicy() throws Exception {
        Object config;
        try { config=nether.getClass().getField("paperConfig").get(nether); }
        catch(NoSuchFieldException ex){return;}
        java.lang.reflect.Field setting=config.getClass().getField("netherVoidTopDamage");
        boolean previous=setting.getBoolean(config);
        frame(nether,6000,128,0,true,2,3);
        ClassLoader loader=subject.getClass().getClassLoader();Class<?> portals=Class.forName("dev.eyecrasher.portallinked.Portals",true,loader);
        java.lang.reflect.Method at=portals.getDeclaredMethod("at",org.bukkit.block.Block.class);at.setAccessible(true);
        Object portal=((Optional<?>)at.invoke(null,nether.getWorld().getBlockAt(6000,128,0))).get();
        java.lang.reflect.Method usable=portals.getDeclaredMethod("usable",portal.getClass(),World.class);usable.setAccessible(true);
        try{
            setting.setBoolean(config,false);check((Boolean)usable.invoke(null,portal,nether.getWorld()),"intact roof frame is usable with native ceiling damage disabled");
            setting.setBoolean(config,true);check(!(Boolean)usable.invoke(null,portal,nether.getWorld()),"Paper1.12.2 live ceiling policy excludes saved roof frame");
        }finally{setting.setBoolean(config,previous);}
    }
    private void unchangedEntities() throws Exception {
        Bot end=bot("EndProbe",UUID.randomUUID(),overworld,864.5,64,.5);travel(end);travel(end);
        byte[] remembered=data(end).clone();BlockPosition position=new BlockPosition(800,65,0);
        overworld.setTypeAndData(position,Blocks.END_PORTAL.getBlockData(),2);end.setPositionRotation(800.5,65,.5,0,0);
        Blocks.END_PORTAL.a(overworld,position,overworld.getType(position),(Entity)end);
        check(end.dimension==1 && Arrays.equals(remembered,data(end)),"actual End portal preserves the Nether pair");
        EntityPig pig=new EntityPig(nether);pig.setPositionRotation(100.5,64,.5,0,0);nether.addEntity(pig);
        UUID pigId=pig.getUniqueID();entityTravel(pig);
        Entity moved=overworld.getEntity(pigId);
        check(moved instanceof EntityPig && moved.getScoreboardTags().isEmpty(),"native mob travel remains unchanged");
        EntityItem item=new EntityItem(nether,100.5,64,.5,new ItemStack(Items.DIAMOND,7));nether.addEntity(item);
        UUID itemId=item.getUniqueID();entityTravel(item);Entity arrived=overworld.getEntity(itemId);
        check(arrived instanceof EntityItem && ((EntityItem)arrived).getItemStack().getCount()==7,"native item travel retains its full stack");
    }
    private void entityTravel(Entity entity) {
        entity.portalCooldown=0;BlockPosition entry=new BlockPosition(entity.locX,entity.locY,entity.locZ);
        for(int tick=0;tick<=entity.Z()+1&&!entity.dead;tick++){
            Blocks.PORTAL.a(entity.world,entry,entity.world.getType(entry),entity);entity.postTick();
        }
    }
    private void administration() throws Exception {
        Bot admin=bot("ConfigAdmin",UUID.randomUUID(),overworld,864.5,64,.5);
        Bot user=bot("ConfigUser",UUID.randomUUID(),overworld,864.5,64,.5);travel(user);byte[] before=data(user).clone();
        command(user,"status");check(last(user).contains("permission"),"ordinary player cannot administer links");admin.getBukkitEntity().setOp(true);
        command(admin,"status");check(last(admin).contains("enabled=true"),"operator status reports enabled defaults");
        config(false,Collections.emptyList(),false);command(admin,"reload");travel(user);travel(user);
        check(Arrays.equals(before,data(user)),"disabled mode preserves pair in both directions");
        List<String> invalid=Arrays.asList("enabled: 'false'\nexcluded-worlds: []\ndebug: false\n",
            "enabled: true\nexcluded-worlds: world\ndebug: false\n","enabled: true\nexcluded-worlds: [42]\ndebug: false\n",
            "enabled: true\nexcluded-worlds: ['']\ndebug: false\n","enabled: true\nexcluded-worlds: []\ndebug: 'false'\n",
            "enabled: null\nexcluded-worlds: []\ndebug: false\n","enabled: true\nexcluded-worlds: []\n","[unclosed");
        for(String text:invalid){Files.write(subject.getDataFolder().toPath().resolve("config.yml"),text.getBytes(StandardCharsets.UTF_8));
            command(admin,"reload");check(last(admin).contains("could not be reloaded"),"invalid configuration reports reload failure");
            command(admin,"status");check(last(admin).contains("enabled=false") && Arrays.equals(before,data(user)),"invalid reload preserves settings and data");}
        for(World world:Arrays.asList(overworld.getWorld(),nether.getWorld())){
            config(true,Arrays.asList(world.getName()),false);command(admin,"reload");travel(user);travel(user);
            check(Arrays.equals(before,data(user)),"either excluded endpoint prevents pair updates: "+world.getName());}
        config(true,Collections.emptyList(),true);command(admin,"reload");
        command(admin,"status");check(last(admin).contains("debug=true"),"debug option applies through reload");
        command(admin,"forget ConfigUser");check(data(user)==null,"operator forget removes selected player's pair");
        check(user.getScoreboardTags().isEmpty(),"selected player has no owned tags after forget");
        user.addScoreboardTag("other:survives-forget");travel(user);command(admin,"forget ConfigUser");
        check(data(user)==null && user.getScoreboardTags().contains("other:survives-forget"),"forget preserves unrelated player tags");
        travel(admin);check(data(admin)!=null,"operator has an individual ordinary journey");
        command(admin,"forget");check(data(admin)==null,"self forget clears sender's pair");
        config(true,Collections.emptyList(),false);command(admin,"reload");
    }
    private void persistence() throws Exception {
        Bot player=bot("PersistedPlayer",PERSIST,overworld,864.5,64,.5);travel(player);
        check(data(player)!=null && player.dimension==-1,"persistence journey establishes pair");
        Files.write(Paths.get("expected-player-data.bin"),data(player));server.getPlayerList().savePlayers();
        File file=new File(new File(overworld.getWorld().getWorldFolder(),"playerdata"),PERSIST+".dat");
        check(file.isFile(),"actual native player save exists");
        NBTTagCompound disk;try(InputStream in=new FileInputStream(file)){disk=NBTCompressedStreamTools.a(in);}
        NBTTagList tags=disk.getList("Tags",8);boolean found=false;
        for(int i=0;i<tags.size();i++)if(tags.getString(i).equals(PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(data(player))))found=true;
        check(found,"actual player NBT contains the remembered link tag");
    }
    private void restart() throws Exception {
        Bot player=bot("PersistedPlayer",PERSIST,nether,100.5,64,.5);server.getPlayerList().a(player);
        byte[] expected=Files.readAllBytes(Paths.get("expected-player-data.bin"));
        check(Arrays.equals(expected,data(player)),"link survives a full native server process restart");
        player.getBukkitEntity().setOp(true);command(player,"status");check(last(player).contains("enabled=false"),"invalid startup disables linking");
        travel(player);check(player.dimension==0 && player.locX<820 && Arrays.equals(expected,data(player)),"disabled startup retains pair through ordinary return");
        server.getPlayerList().moveToWorld(player,-1,true,new Location(nether.getWorld(),100.5,64,.5),false);
        config(true,Collections.emptyList(),false);command(player,"reload");travel(player);
        check(player.dimension==0 && player.locX>862,"valid reload restores remembered route after restart");
    }
    private Bot bot(String name,UUID id,WorldServer level,double x,double y,double z) throws Exception {
        Bot bot=new Bot(level,new GameProfile(id,name));NetworkManager network=new NetworkManager(EnumProtocolDirection.SERVERBOUND);
        network.channel=new EmbeddedChannel();network.l=new InetSocketAddress("127.0.0.1",54321);
        bot.playerConnection=new PlayerConnection(server,network,bot){
            @Override public void sendPacket(Packet<?> packet){if(packet instanceof PacketPlayOutChat){
                try { java.lang.reflect.Field f=PacketPlayOutChat.class.getDeclaredField("a");f.setAccessible(true);
                    IChatBaseComponent value=(IChatBaseComponent)f.get(packet);if(value!=null)replies.computeIfAbsent(id,k->new ArrayList<>()).add(value.toPlainText());
                }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}}}
        };
        network.setPacketListener(bot.playerConnection);bot.dimension=level.dimension;bot.setPositionRotation(x,y,z,0,0);
        bot.abilities.canInstantlyBuild=true;bot.abilities.isInvulnerable=true;server.getPlayerList().players.add(bot);
        for(String field:Arrays.asList("j","playersByName")){
            java.lang.reflect.Field f=PlayerList.class.getDeclaredField(field);f.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Object,EntityPlayer> map=(Map<Object,EntityPlayer>)f.get(server.getPlayerList());
            map.put(field.equals("j")?id:name,bot);
        }
        bots.add(bot);return bot;
    }
    private void travel(Bot player) {
        // Native 1.12 exits can leave the feet just outside a remembered frame.
        // Center the test player for their next actual block collision.
        if(player.getBukkitEntity().getLocation().getBlock().getType()!=Material.PORTAL){
            byte[] previousLink=data(player);
            if(previousLink!=null){
                java.nio.ByteBuffer in=java.nio.ByteBuffer.wrap(previousLink);in.position(32);
                UUID arrivalWorld=new UUID(in.getLong(),in.getLong());
                if(arrivalWorld.equals(player.world.getWorld().getUID()))
                    player.setPositionRotation(readInt(previousLink,48)+.5,readInt(previousLink,52),readInt(previousLink,56)+.5,0,0);
            }
        }
        player.portalCooldown=0;BlockPosition entry=new BlockPosition(player.locX,player.locY,player.locZ);int from=player.dimension,previous=cancellations;
        check(player.world.getType(entry).getBlock()==Blocks.PORTAL,"native departure is an active portal");
        for(int tick=0;tick<=player.Z()+1;tick++){
            Blocks.PORTAL.a(player.world,entry,player.world.getType(entry),(Entity)player);
            player.postTick();
            if(player.dimension!=from||cancellations!=previous)break;
        }
    }
    private void frame(WorldServer level,int x,int y,int z,boolean axisX,int width,int height){
        int dx=axisX?1:0,dz=axisX?0:1;
        for(int row=-1;row<=height;row++)for(int col=-1;col<=width;col++)
            level.setTypeAndData(new BlockPosition(x+dx*col,y+row,z+dz*col),row==-1||row==height||col==-1||col==width
                ?Blocks.OBSIDIAN.getBlockData():Blocks.PORTAL.getBlockData().set(BlockPortal.AXIS,axisX?EnumDirection.EnumAxis.X:EnumDirection.EnumAxis.Z),2);
    }
    private byte[] data(EntityPlayer player){String value=null;for(String tag:player.getScoreboardTags())if(tag.startsWith(PREFIX)){
        if(value!=null)return null;value=tag.substring(PREFIX.length());}if(value==null)return null;
        try{byte[] b=Base64.getUrlDecoder().decode(value);return b.length==63&&b[0]==1?b:null;}catch(IllegalArgumentException ex){return null;}}
    private int owned(EntityPlayer player){int n=0;for(String tag:player.getScoreboardTags())if(tag.startsWith(PREFIX))n++;return n;}
    private int readInt(byte[] bytes,int offset){return java.nio.ByteBuffer.wrap(bytes).getInt(offset);}
    private void command(Bot bot,String action){Bukkit.dispatchCommand(bot.getBukkitEntity(),"portallinked "+action);}
    private String last(Bot bot){List<String> list=replies.get(bot.getUniqueID());return list==null||list.isEmpty()?"":list.get(list.size()-1);}
    private void config(boolean enabled,List<String> excluded,boolean debug)throws Exception{
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("enabled",enabled);yaml.set("excluded-worlds",excluded);yaml.set("debug",debug);
        yaml.save(new File(subject.getDataFolder(),"config.yml"));}
    private void check(boolean value,String name){if(!value)throw new AssertionError(name);checks.add(name);getLogger().info("PASS "+name);}
    private final class Bot extends EntityPlayer {
        Bot(WorldServer level,GameProfile profile){super(PortalIntegration.this.server,level,profile,new PlayerInteractManager(level));}
        void dimensions(float width,float height){setSize(width,height);}
    }
}
