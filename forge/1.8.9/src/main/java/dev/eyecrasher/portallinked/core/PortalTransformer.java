// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Two bounded native hooks; dimension-travel protection events stay in place. */
public final class PortalTransformer implements IClassTransformer, Opcodes {
    private static final String HOOK = "dev/eyecrasher/portallinked/PortalHooks";
    private static final String ENTITY = "Lnet/minecraft/entity/Entity;";
    private static final String POSITION = "Lnet/minecraft/util/BlockPos;";
    private static final String WORLD = "Lnet/minecraft/world/WorldServer;";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        boolean entity = "net.minecraft.entity.Entity".equals(transformedName);
        if (bytes == null || (!entity && !"net.minecraft.world.Teleporter".equals(transformedName))) return bytes;
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        int patched = 0;
        if (entity) {
            FieldNode portalPosition = null;
            for (FieldNode field : type.fields)
                if (POSITION.equals(field.desc) && (field.access & ACC_STATIC) == 0) {
                    if (portalPosition != null) throw new IllegalStateException("Ambiguous native portal position");
                    portalPosition = field;
                }
            if (portalPosition == null) throw new IllegalStateException("Native portal position is missing");
            for (MethodNode method : type.methods) {
                if (!method.desc.equals("()V") || !(method.name.equals("onEntityUpdate") || method.name.equals("func_70030_z"))) continue;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!call.desc.equals("(I)V")
                            || !(call.name.equals("travelToDimension") || call.name.equals("func_71027_c"))) continue;
                    InsnList before = new InsnList();
                    before.add(new InsnNode(DUP2));
                    before.add(new VarInsnNode(ALOAD, 0));
                    before.add(new FieldInsnNode(GETFIELD, type.name, portalPosition.name, POSITION));
                    before.add(new MethodInsnNode(INVOKESTATIC, HOOK, "portalStarted", "(" + ENTITY + "I" + POSITION + ")V", false));
                    method.instructions.insertBefore(call, before);
                    InsnList after = new InsnList();
                    after.add(new VarInsnNode(ALOAD, 0));
                    after.add(new MethodInsnNode(INVOKESTATIC, HOOK, "portalFinished", "(" + ENTITY + ")V", false));
                    method.instructions.insert(call, after);
                    patched++;
                }
            }
        } else {
            FieldNode world = null;
            for (FieldNode field : type.fields)
                if (WORLD.equals(field.desc) && (field.access & ACC_STATIC) == 0) {
                    if (world != null) throw new IllegalStateException("Ambiguous native portal world");
                    world = field;
                }
            if (world == null) throw new IllegalStateException("Native portal world is missing");
            for (MethodNode method : type.methods) {
                if (!method.desc.equals("(" + ENTITY + "F)Z")
                        || !(method.name.equals("placeInExistingPortal") || method.name.equals("func_180620_b"))) continue;
                LabelNode nativeRoute = new LabelNode();
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(ALOAD, 1));
                hook.add(new VarInsnNode(ALOAD, 0));
                hook.add(new FieldInsnNode(GETFIELD, type.name, world.name, WORLD));
                hook.add(new VarInsnNode(FLOAD, 2));
                hook.add(new MethodInsnNode(INVOKESTATIC, HOOK, "returnToSaved", "(" + ENTITY + WORLD + "F)Z", false));
                hook.add(new JumpInsnNode(IFEQ, nativeRoute));
                hook.add(new InsnNode(ICONST_1));
                hook.add(new InsnNode(IRETURN));
                hook.add(nativeRoute);
                hook.add(new FrameNode(F_SAME, 0, null, 0, null));
                method.instructions.insert(hook);
                patched++;
            }
        }
        if (patched != 1) throw new IllegalStateException("PortalLinked expected one native hook in " + transformedName + ", got " + patched);
        ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(output);
        return output.toByteArray();
    }
}
