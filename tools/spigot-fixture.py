#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
from pathlib import Path
import hashlib,json,re,urllib.request
import argparse, os, subprocess, zipfile
parser=argparse.ArgumentParser(description="Compose the pinned Spigot SDK namespace for a native fixture.")
parser.add_argument('--builder',type=Path,required=True)
parser.add_argument('--minecraft',required=True)
parser.add_argument('--java',default='java')
parser.add_argument('--input',type=Path)
parser.add_argument('--output',type=Path)
parser.add_argument('--reverse',action='store_true',help='Remap an exact native SDK to its development namespace')
parser.add_argument('--runtime-symbols',type=Path)
args=parser.parse_args()
if bool(args.input)!=bool(args.output):parser.error('--input and --output must be provided together')
builder=args.builder.resolve();mc=args.minecraft
info=json.loads((builder/'BuildData/info.json').read_text())
if info['minecraftVersion'] not in {mc,mc+'_unobfuscated'}:parser.error('Spigot SDK targets another Minecraft release')
p=builder/'work'/'portallinked-official-mappings.txt'
digest=info['mappingsUrl'].split('/')[-2]
if not p.exists() or hashlib.sha1(p.read_bytes()).hexdigest()!=digest:
 with urllib.request.urlopen(info['mappingsUrl'],timeout=60) as f:data=f.read()
 if hashlib.sha1(data).hexdigest()!=digest:raise RuntimeError('Mapping hash mismatch')
 p.write_bytes(data)
official={}
for line in p.read_text().splitlines():
 if line and not line.startswith((' ','#')):
  original,obf=line[:-1].split(' -> ');official[original.replace('.','/')]=obf.replace('.','/')
classes={}
for line in (builder/'BuildData/mappings'/info['classMappings']).read_text().splitlines():
 a=line.split()
 if len(a)==2 and not a[0].startswith('#'):classes[a[0]]=a[1]
def spigot_class(obfuscated):
 if obfuscated in classes:return classes[obfuscated]
 if '$' in obfuscated:
  parent,nested=obfuscated.rsplit('$',1)
  return spigot_class(parent)+'$'+nested
 return obfuscated
aliases={canonical:spigot_class(obf) for canonical,obf in official.items()}
reverse={v:k for k,v in aliases.items()}
def remap_desc(d):
 return re.sub(r'L([^;]+);',lambda m:'L'+reverse.get(m[1],m[1])+';',d)
def parameters(desc):
 params=[];i=1
 primitives={'B':'byte','C':'char','D':'double','F':'float','I':'int','J':'long','S':'short','Z':'boolean'}
 while desc[i]!=')':
  start=i
  while desc[i]=='[':i+=1
  if desc[i]=='L':
   end=desc.index(';',i);name=desc[i+1:end].replace('/','.');i=end+1
  else:name=primitives[desc[i]];i+=1
  if start<i and desc[start]=='[':name=desc[start:i].replace('/','.')
  params.append(name)
 return '('+','.join(params)+')'
full=['# Composed SDK mapping, development only.']
for canonical,spigot in sorted(aliases.items()):
 if canonical!=spigot:full.append(canonical+' '+spigot)
symbols={}
# Only reflection symbols used by the runtime portal adapter and native fixture.
owners={
 'net/minecraft/world/entity/Entity','net/minecraft/world/entity/PortalProcessor',
 'net/minecraft/world/level/block/Blocks','net/minecraft/world/level/block/NetherPortalBlock',
 'net/minecraft/world/phys/Vec3','net/minecraft/world/phys/AABB',
 'net/minecraft/world/level/portal/TeleportTransition','net/minecraft/world/level/portal/DimensionTransition',
 'net/minecraft/world/level/portal/PortalInfo','net/minecraft/world/level/portal/PortalShape',
 'net/minecraft/world/level/CollisionGetter',
 'net/minecraft/core/Vec3i','net/minecraft/core/BlockPos','net/minecraft/network/Connection',
 'net/minecraft/server/players/PlayerList','net/minecraft/server/level/ServerPlayer',
 'net/minecraft/world/level/dimension/LevelStem',
 'net/minecraft/server/dedicated/DedicatedServer','net/minecraft/server/dedicated/DedicatedServerProperties'
}
fields={'portalTime','portalProcess','portalEntrancePos','isInsidePortal','portalCooldown','allowNether','enteredNetherPosition','pos','NETHER','NETHER_PORTAL','DO_NOTHING','ZERO','channel','address','playersByUUID','playersByName'}
methods={'isSamePortal','isInsidePortalThisTick','getEntryPosition','getX','getY','getZ','getBoundingBox',
 'position','pos','subtract','move','noCollision','getDimensionTransitionFromExit','handlePortal','entityInside','checkInsideBlocks',
 'getDimensions','getPose','getDeltaMovement','getYRot','getXRot','getRelativePosition','createPortalInfo','getPortalWaitTime','getDimensionChangingDelay','handleNetherPortal','getProperties'}
member_maps=list((builder/'work').glob('bukkit-*-members.csrg'))
if len(member_maps)!=1:raise RuntimeError('Use an exact SDK cache with one generated member map')
for mapping in member_maps:
 for line in mapping.read_text().splitlines():
  a=line.split()
  if len(a) not in (3,4) or a[0].startswith('#'):continue
  owner,old=a[:2];canonical=reverse.get(owner,owner);new=a[-1]
  if len(a)==3:full.append(canonical+' '+new+' '+old)
  else:full.append(canonical+' '+new+' '+remap_desc(a[2])+' '+old)
  if canonical not in owners:continue
  if len(a)==3 and new in fields:symbols['f.'+owner.replace('/','.')+'#'+new]=old
  if len(a)==4 and new in methods:symbols['m.'+owner.replace('/','.')+'#'+new+parameters(a[2])]=old
transition=next((name for name in ['net/minecraft/world/level/portal/TeleportTransition',
 'net/minecraft/world/level/portal/DimensionTransition'] if name in aliases),None)
needed={
 'net/minecraft/world/entity/Entity','net/minecraft/world/level/block/Blocks',
 'net/minecraft/core/BlockPos','net/minecraft/server/level/ServerLevel','net/minecraft/server/level/ServerPlayer','net/minecraft/util/BlockUtil$FoundRectangle',
 'net/minecraft/world/phys/Vec3','net/minecraft/world/phys/AABB','net/minecraft/world/level/block/NetherPortalBlock',
 'net/minecraft/world/level/dimension/LevelStem',
}
if transition:
 needed.update({'net/minecraft/world/level/block/Portal',transition,transition+'$Post'+transition.rsplit('/',1)[1]})
else:
 needed.update({'net/minecraft/world/level/portal/PortalInfo','net/minecraft/world/level/portal/PortalShape',
  'net/minecraft/core/Direction$Axis','net/minecraft/world/entity/EntityDimensions','net/minecraft/world/entity/Pose'})
if 'net/minecraft/BlockUtil$FoundRectangle' in aliases:
 needed.remove('net/minecraft/util/BlockUtil$FoundRectangle')
 needed.add('net/minecraft/BlockUtil$FoundRectangle')
for canonical in needed:
 spigot=aliases[canonical]
 if spigot!=canonical:symbols['c.'+canonical.replace('/','.')]=spigot.replace('/','.')
if args.runtime_symbols:
 args.runtime_symbols.write_text('# SPDX-License-Identifier: GPL-3.0-only\n# Copyright (C) 2026 EyeCrasher\n# Reflection symbol subset derived from the pinned Spigot SDK.\n'+'\n'.join(k+'='+v for k,v in sorted(symbols.items()))+'\n')
mapping=builder/'work'/'portallinked-mojang-to-spigot.csrg'
mapping.write_text('\n'.join(full)+'\n')
if args.input:
 development=list((builder/'Spigot/Spigot-Server/target').glob('*-remapped-mojang.jar'))
 if len(development)!=1:raise RuntimeError('Expected one exact remapped development SDK')
 cache=builder.parent
 special_source=builder/'BuildData/bin/SpecialSource.jar'
 classpath=[special_source,args.input.resolve() if args.reverse else development[0],*sorted((cache/'libraries').rglob('*.jar'))]
 subprocess.run([args.java,'-cp',os.pathsep.join(map(str,classpath)),
  'net.md_5.specialsource.SpecialSource','--live','--stable','--in-jar',str(args.input.resolve()),
  '--srg-in',str(mapping),'--out-jar',str(args.output.resolve()),*(['--reverse'] if args.reverse else [])],check=True)
print('Composed exact Spigot namespace for Minecraft '+mc)
