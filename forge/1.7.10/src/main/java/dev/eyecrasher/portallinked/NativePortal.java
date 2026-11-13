// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Objects;
import net.minecraft.block.BlockPortal;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

public final class NativePortal {
    public enum Axis { X, Z }
    public final String world;
    public final int x, y, z, width, height;
    public final Axis axis;
    public NativePortal(String world, int x, int y, int z, Axis axis, int width, int height) {
        if (!world.equals("minecraft:overworld") && !world.equals("minecraft:the_nether"))
            throw new IllegalArgumentException("Portal dimension");
        if (axis == null || width < 2 || width > 21 || height < 3 || height > 21
                || Math.abs((long)x)>29_999_984 || Math.abs((long)z)>29_999_984)
            throw new IllegalArgumentException("Portal geometry");
        this.world=world;this.x=x;this.y=y;this.z=z;this.axis=axis;this.width=width;this.height=height;
    }
    public static String world(WorldServer level) {
        int dimension=level.provider.dimensionId;
        return dimension==0?"minecraft:overworld":dimension==-1?"minecraft:the_nether":"";
    }
    public static NativePortal touching(EntityPlayerMP player) { return touching(player.getServerForPlayer(),player); }
    public static NativePortal touching(WorldServer level, EntityPlayerMP player) {
        NativePortal centered=at(level,MathHelper.floor_double(player.posX),MathHelper.floor_double(player.posY),MathHelper.floor_double(player.posZ));
        if(centered!=null)return centered;
        AxisAlignedBB body=player.boundingBox;
        for(int y=MathHelper.floor_double(body.minY);y<=MathHelper.floor_double(body.maxY);y++)
            for(int x=MathHelper.floor_double(body.minX);x<=MathHelper.floor_double(body.maxX);x++)
                for(int z=MathHelper.floor_double(body.minZ);z<=MathHelper.floor_double(body.maxZ);z++){
                    NativePortal result=at(level,x,y,z);if(result!=null)return result;
                }
        return null;
    }
    public static NativePortal at(WorldServer level,int x,int y,int z) {
        if(world(level).isEmpty() || level.getBlock(x,y,z)!=Blocks.portal)return null;
        int metadata=BlockPortal.func_149999_b(level.getBlockMetadata(x,y,z));
        if(metadata==0){
            // Vanilla 1.7 creates portal interiors with metadata0; rendering may
            // assign their axis later. The complete obsidian frame disambiguates it.
            NativePortal alongX=inspect(level,x,y,z,Axis.X),alongZ=inspect(level,x,y,z,Axis.Z);
            return alongX!=null && alongZ!=null?null:alongX!=null?alongX:alongZ;
        }
        Axis axis=metadata==1?Axis.X:metadata==2?Axis.Z:null;
        return axis==null?null:inspect(level,x,y,z,axis);
    }
    private static NativePortal inspect(WorldServer level,int x,int y,int z,Axis axis) {
        int dx=axis==Axis.X?1:0,dz=axis==Axis.Z?1:0,steps=0;
        while(portal(level,x,y-1,z,axis)){if(++steps>=21)return null;y--;}
        steps=0;
        while(portal(level,x-dx,y,z-dz,axis)){if(++steps>=21)return null;x-=dx;z-=dz;}
        int width=0,height=0;
        while(portal(level,x+dx*width,y,z+dz*width,axis))if(++width>21)return null;
        while(portal(level,x,y+height,z,axis))if(++height>21)return null;
        if(width<2||height<3)return null;
        for(int row=0;row<height;row++){
            if(!obsidian(level,x-dx,y+row,z-dz)||!obsidian(level,x+dx*width,y+row,z+dz*width))return null;
            for(int col=0;col<width;col++)if(!portal(level,x+dx*col,y+row,z+dz*col,axis))return null;
        }
        for(int col=0;col<width;col++)
            if(!obsidian(level,x+dx*col,y-1,z+dz*col)||!obsidian(level,x+dx*col,y+height,z+dz*col))return null;
        return new NativePortal(world(level),x,y,z,axis,width,height);
    }
    public boolean usable(WorldServer level) {
        return world.equals(world(level)) && y>0 && (long)y+height<level.getHeight()
                && equals(at(level,x,y,z));
    }
    private static boolean portal(WorldServer level,int x,int y,int z,Axis axis) {
        if(y<0 || y>=level.getHeight() || level.getBlock(x,y,z)!=Blocks.portal)return false;
        int metadata=BlockPortal.func_149999_b(level.getBlockMetadata(x,y,z));
        return metadata==0 || metadata==(axis==Axis.X?1:2);
    }
    private static boolean obsidian(WorldServer level,int x,int y,int z) {
        return y>=0 && y<level.getHeight() && level.getBlock(x,y,z)==Blocks.obsidian;
    }
    public NBTTagCompound write(){
        NBTTagCompound value=new NBTTagCompound();value.setString("world",world);
        value.setInteger("x",x);value.setInteger("y",y);value.setInteger("z",z);
        value.setString("axis",axis.name());value.setInteger("width",width);value.setInteger("height",height);return value;
    }
    public static NativePortal read(NBTTagCompound value){
        for(String key:new String[]{"x","y","z","width","height"})if(!value.hasKey(key,3))throw new IllegalArgumentException("Missing integer geometry");
        if(!value.hasKey("world",8)||!value.hasKey("axis",8))throw new IllegalArgumentException("Missing portal identity");
        return new NativePortal(value.getString("world"),value.getInteger("x"),value.getInteger("y"),value.getInteger("z"),
                Axis.valueOf(value.getString("axis")),value.getInteger("width"),value.getInteger("height"));
    }
    @Override public boolean equals(Object other){
        if(!(other instanceof NativePortal))return false;NativePortal p=(NativePortal)other;
        return world.equals(p.world)&&x==p.x&&y==p.y&&z==p.z&&axis==p.axis&&width==p.width&&height==p.height;
    }
    @Override public int hashCode(){return Objects.hash(world,x,y,z,axis,width,height);}
}
