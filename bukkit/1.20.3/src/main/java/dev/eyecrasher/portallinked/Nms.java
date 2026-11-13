// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

/** Exact-SDK reflection names for the two native namespaces used by Bukkit servers. */
public final class Nms {
    private static final boolean SPIGOT = isSpigot();
    private static final Properties SYMBOLS = load();
    private Nms() {}

    private static boolean isSpigot() {
        try { Class.forName("net.minecraft.server.level.ServerPlayer", false, Nms.class.getClassLoader()); return false; }
        catch (ClassNotFoundException ex) { return true; }
    }
    private static Properties load() {
        Properties values = new Properties();
        try (var input = Nms.class.getResourceAsStream("/portallinked-spigot.properties")) {
            if (input == null) throw new IOException("Missing PortalLinked SDK symbol subset");
            values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return values;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    public static Class<?> type(String canonical) throws ClassNotFoundException {
        return Class.forName(SPIGOT ? SYMBOLS.getProperty("c." + canonical, canonical) : canonical);
    }
    private static String lookup(Class<?> owner, String key) {
        String mapped = SYMBOLS.getProperty(key.replace("@", owner.getName()));
        if (mapped != null) return mapped;
        for (Class<?> parent : owner.getInterfaces()) {
            mapped = lookup(parent, key);
            if (mapped != null) return mapped;
        }
        return owner.getSuperclass() == null ? null : lookup(owner.getSuperclass(), key);
    }
    private static String fieldName(Class<?> owner, String name) {
        String mapped = SPIGOT ? lookup(owner, "f.@#" + name) : null;
        return mapped == null ? name : mapped;
    }
    private static String methodName(Class<?> owner, String name, Class<?>[] parameters) {
        String signature = Arrays.stream(parameters).map(Class::getName).collect(Collectors.joining(",", "(", ")"));
        String mapped = SPIGOT ? lookup(owner, "m.@#" + name + signature) : null;
        // CraftBukkit replaces the original eight-argument factory with a
        // named extension. That nine-argument method keeps its canonical name.
        if (name.equals("createPortalInfo") && parameters.length == 9
                && parameters[8].getName().endsWith(".CraftPortalEvent")) return name;
        return mapped == null ? name : mapped;
    }
    public static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        return owner.getField(fieldName(owner, name));
    }
    public static Field declaredField(Class<?> owner, String name) throws NoSuchFieldException {
        return owner.getDeclaredField(fieldName(owner, name));
    }
    public static Method method(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        return owner.getMethod(methodName(owner, name, parameters), parameters);
    }
    public static Method declaredMethod(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        return owner.getDeclaredMethod(methodName(owner, name, parameters), parameters);
    }
}
