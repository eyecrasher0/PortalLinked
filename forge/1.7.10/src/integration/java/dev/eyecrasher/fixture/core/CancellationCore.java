// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture.core;

import java.util.Map;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("PortalLinked cancellation fixture")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1002)
@IFMLLoadingPlugin.TransformerExclusions("dev.eyecrasher.fixture.core")
public final class CancellationCore implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass(){return new String[]{CancellationTransformer.class.getName()};}
    public String getModContainerClass(){return null;}
    public String getSetupClass(){return null;}
    public void injectData(Map<String,Object> data){}
    public String getAccessTransformerClass(){return null;}
}
