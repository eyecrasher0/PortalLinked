// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Contact capture and native placement hooks for the 1.7 server API. */
public final class PortalTransformer implements IClassTransformer,Opcodes {
    private static final String HOOK="dev/eyecrasher/portallinked/PortalHooks";
    private static final String ENTITY="Lnet/minecraft/entity/Entity;";
    private static final String WORLD="Lnet/minecraft/world/WorldServer;";
    @Override public byte[] transform(String name,String target,byte[] bytes){
        boolean entity=target.equals("net.minecraft.entity.Entity"),block=target.equals("net.minecraft.block.BlockPortal");
        if(bytes==null || (!entity && !block && !target.equals("net.minecraft.world.Teleporter")))return bytes;
        ClassNode type=new ClassNode();new ClassReader(bytes).accept(type,0);int count=0;
        FieldNode world=null,cache=null;
        if(!entity && !block)for(Object raw:type.fields){FieldNode field=(FieldNode)raw;
            if(field.desc.equals("Lnet/minecraft/util/LongHashMap;") && (field.access&ACC_STATIC)==0)cache=field;
            if(field.desc.equals(WORLD) && (field.access&ACC_STATIC)==0){
                if(world!=null)throw new IllegalStateException("Ambiguous teleporter world");world=field;
            }
        }
        for(Object raw:type.methods){
            MethodNode method=(MethodNode)raw;
            if(entity && method.desc.equals("()V") && (method.name.equals("onEntityUpdate")||method.name.equals("func_70030_z"))){
                for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode){
                    MethodInsnNode call=(MethodInsnNode)instruction;
                    if(!call.desc.equals("(I)V") || !(call.name.equals("travelToDimension")||call.name.equals("func_71027_c")))continue;
                    InsnList before=new InsnList();before.add(new InsnNode(DUP2));
                    before.add(new MethodInsnNode(INVOKESTATIC,HOOK,"portalStarted","("+ENTITY+"I)V",false));
                    method.instructions.insertBefore(call,before);
                    InsnList after=new InsnList();after.add(new VarInsnNode(ALOAD,0));
                    after.add(new MethodInsnNode(INVOKESTATIC,HOOK,"portalFinished","("+ENTITY+")V",false));
                    method.instructions.insert(call,after);count++;
                }
            }else if(block && method.desc.equals("(Lnet/minecraft/world/World;III"+ENTITY+")V")){
                for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode){
                    MethodInsnNode call=(MethodInsnNode)instruction;
                    if(!call.desc.equals("()V") || !(call.name.equals("setInPortal")||call.name.equals("func_70063_aa")))continue;
                    InsnList before=new InsnList();before.add(new InsnNode(DUP));
                    before.add(new VarInsnNode(ILOAD,2));before.add(new VarInsnNode(ILOAD,3));before.add(new VarInsnNode(ILOAD,4));
                    before.add(new MethodInsnNode(INVOKESTATIC,HOOK,"portalContact","("+ENTITY+"III)V",false));
                    method.instructions.insertBefore(call,before);count++;
                }
            }else if(!entity && !block && method.desc.equals("("+ENTITY+"DDDF)Z")
                    && (method.name.equals("placeInExistingPortal")||method.name.equals("func_77184_b"))){
                if(world==null || cache==null)throw new IllegalStateException("Missing teleporter world or cache");
                for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==IRETURN){
                    InsnList resolved=new InsnList();resolved.add(new InsnNode(DUP));resolved.add(new VarInsnNode(ALOAD,1));
                    resolved.add(new VarInsnNode(ALOAD,0));resolved.add(new FieldInsnNode(GETFIELD,type.name,world.name,WORLD));
                    resolved.add(new VarInsnNode(ALOAD,0));resolved.add(new FieldInsnNode(GETFIELD,type.name,cache.name,cache.desc));
                    resolved.add(new MethodInsnNode(INVOKESTATIC,HOOK,"portalPlaced","(Z"+ENTITY+WORLD+"Lnet/minecraft/util/LongHashMap;)V",false));
                    method.instructions.insertBefore(instruction,resolved);
                }
                LabelNode nativeRoute=new LabelNode();InsnList before=new InsnList();
                before.add(new VarInsnNode(ALOAD,1));before.add(new VarInsnNode(ALOAD,0));
                before.add(new FieldInsnNode(GETFIELD,type.name,world.name,WORLD));before.add(new VarInsnNode(FLOAD,8));
                before.add(new MethodInsnNode(INVOKESTATIC,HOOK,"returnToSaved","("+ENTITY+WORLD+"F)Z",false));
                before.add(new JumpInsnNode(IFEQ,nativeRoute));before.add(new InsnNode(ICONST_1));before.add(new InsnNode(IRETURN));
                before.add(nativeRoute);before.add(new FrameNode(F_SAME,0,null,0,null));method.instructions.insert(before);count++;
            }
        }
        if(count!=1)throw new IllegalStateException("PortalLinked expected one native hook in "+target+", got "+count);
        ClassWriter output=new ClassWriter(ClassWriter.COMPUTE_MAXS);type.accept(output);return output.toByteArray();
    }
}
