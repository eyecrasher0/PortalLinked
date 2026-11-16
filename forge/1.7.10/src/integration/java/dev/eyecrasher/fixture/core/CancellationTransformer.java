// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** 1.7 Forge has no cancellable dimension event; emulate a separate protection coremod. */
public final class CancellationTransformer implements IClassTransformer,Opcodes {
    public byte[] transform(String name,String target,byte[] bytes){
        if(bytes==null || !target.equals("net.minecraft.entity.player.EntityPlayerMP"))return bytes;
        ClassNode type=new ClassNode();new ClassReader(bytes).accept(type,0);int count=0;
        for(Object raw:type.methods){
            MethodNode method=(MethodNode)raw;
            if(!method.desc.equals("(I)V") || !(method.name.equals("travelToDimension")||method.name.equals("func_71027_c")))continue;
            LabelNode proceed=new LabelNode();InsnList hook=new InsnList();hook.add(new VarInsnNode(ALOAD,0));
            hook.add(new MethodInsnNode(INVOKESTATIC,"dev/eyecrasher/fixture/LegacyIntegration","protectedTravel","(Lnet/minecraft/entity/Entity;)Z",false));
            hook.add(new JumpInsnNode(IFEQ,proceed));hook.add(new InsnNode(RETURN));hook.add(proceed);hook.add(new FrameNode(F_SAME,0,null,0,null));
            method.instructions.insert(hook);count++;
        }
        if(count!=1)throw new IllegalStateException("Missing native protection fixture hook");
        ClassWriter output=new ClassWriter(ClassWriter.COMPUTE_MAXS);type.accept(output);return output.toByteArray();
    }
}
