// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.core;

import java.util.Map;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("PortalLinked")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions("dev.eyecrasher.portallinked.core")
public final class PortalLinkedCore implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() { return new String[] { PortalTransformer.class.getName() }; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) {}
    @Override public String getAccessTransformerClass() { return null; }
}
