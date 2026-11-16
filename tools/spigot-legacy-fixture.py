#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
"""Compose the exact legacy Spigot SDK namespace for Minecraft 1.16.5 and 1.17.x."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import struct
import zipfile
import tempfile
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--builder', type=Path, required=True)
parser.add_argument('--minecraft', choices=['1.16.5', '1.17', '1.17.1'], required=True)
parser.add_argument('--java', default='java')
parser.add_argument('--input', type=Path)
parser.add_argument('--output', type=Path)
parser.add_argument('--reverse', action='store_true')
parser.add_argument('--runtime-symbols', type=Path)
args = parser.parse_args()
if bool(args.input) != bool(args.output):
    parser.error('--input and --output must be supplied together')
builder = args.builder.resolve()
info = json.loads((builder / "BuildData/info.json").read_text())
if info["minecraftVersion"] != args.minecraft:
    parser.error("Spigot SDK targets another Minecraft release")
mapping_hash = '41285beda6d251d190f2bf33beadd4fee187df7a' if args.minecraft == '1.16.5' else info['mappingsUrl'].split('/')[-2]
mapping_file = builder / 'work/portallinked-official-mappings.txt'
if not mapping_file.exists() or hashlib.sha1(mapping_file.read_bytes()).hexdigest() != mapping_hash:
    with urllib.request.urlopen('https://piston-data.mojang.com/v1/objects/' + mapping_hash + '/server.txt', timeout=60) as response:
        payload = response.read()
    if hashlib.sha1(payload).hexdigest() != mapping_hash:
        raise RuntimeError('Official SDK mapping hash mismatch')
    mapping_file.write_bytes(payload)
lines = mapping_file.read_text().splitlines()
official = {}
for line in lines:
    if line and not line.startswith((' ', '#')):
        canonical, obfuscated = line[:-1].split(' -> ')
        official[canonical.replace('.', '/')] = obfuscated.replace('.', '/')
class_map = {}
for line in (builder / ('BuildData/mappings/' + info['classMappings'])).read_text().splitlines():
    parts = line.split()
    if len(parts) == 2 and not line.startswith('#'):
        class_map[parts[0]] = parts[1]
def spigot_class(obfuscated):
    if obfuscated in class_map:
        return class_map[obfuscated]
    if '$' in obfuscated:
        owner, nested = obfuscated.rsplit('$', 1)
        return spigot_class(owner) + '$' + nested
    return obfuscated
aliases = {canonical: spigot_class(obfuscated) for canonical, obfuscated in official.items()}
def versioned(name):
    return 'net/minecraft/server/v1_16_R3/' + name.rsplit('/', 1)[-1] if args.minecraft == '1.16.5' and name.startswith('net/minecraft/') else name
members = {}
for line in (builder / ('BuildData/mappings/' + info['memberMappings'])).read_text().splitlines():
    parts = line.split()
    if len(parts) in (3, 4) and not line.startswith('#'):
        members[tuple(parts[:-1])] = parts[-1]
# Spigot's member map omits inherited overrides. Resolve their parent names
# from the pinned vanilla SDK instead of retaining a conflicting obfuscated name.
def parents(payload):
    offset = 8
    count = struct.unpack_from('>H', payload, offset)[0]; offset += 2
    pool = [None] * count
    index = 1
    while index < count:
        tag = payload[offset]; offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', payload, offset)[0]; offset += 2
            pool[index] = payload[offset:offset + length].decode('utf-8', errors='replace'); offset += length
        elif tag == 7:
            pool[index] = struct.unpack_from('>H', payload, offset)[0]; offset += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): offset += 4
        elif tag in (5, 6): offset += 8; index += 1
        elif tag in (8, 16, 19, 20): offset += 2
        elif tag == 15: offset += 3
        else: raise RuntimeError('Unknown class constant-pool tag: ' + str(tag))
        index += 1
    _, own, parent, interfaces = struct.unpack_from('>HHHH', payload, offset)
    indices = ([parent] if parent else []) + list(struct.unpack_from('>' + 'H' * interfaces, payload, offset + 8))
    return pool[pool[own]], [pool[pool[value]] for value in indices]
hierarchy = {}
with zipfile.ZipFile(builder / ('work/minecraft_server.' + args.minecraft + '.jar')) as sdk:
    for entry in sdk.namelist():
        if entry.endswith('.class'):
            own, inherited = parents(sdk.read(entry))
            hierarchy[spigot_class(own)] = [spigot_class(parent) for parent in inherited]
def member_name(owner, obfuscated, desc=None):
    key = (owner, obfuscated) if desc is None else (owner, obfuscated, desc)
    if key in members:
        return members[key]
    for parent in hierarchy.get(owner, []):
        value = member_name(parent, obfuscated, desc)
        if value != obfuscated:
            return value
    return obfuscated
def descriptor(name, classes):
    arrays = 0
    while name.endswith('[]'):
        arrays += 1
        name = name[:-2]
    primitive = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S', 'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}
    result = primitive.get(name)
    if result is None:
        canonical = name.replace('.', '/')
        result = 'L' + classes.get(canonical, canonical) + ';'
    return '[' * arrays + result
full = [before + ' ' + versioned(after) for before, after in sorted(aliases.items())]
# Keep only symbols used by the bounded portal adapter and native test fixture.
fields = {'portalTime', 'portalEntrancePos', 'isInsidePortal', 'portalCooldown', 'allowNether', 'enteredNetherPosition', 'pos', 'NETHER', 'NETHER_PORTAL', 'ZERO', 'channel', 'address', 'playersByUUID', 'playersByName'}
methods = {'getX', 'getY', 'getZ', 'getBoundingBox', 'position', 'subtract', 'move', 'noCollision', 'entityInside', 'checkInsideBlocks', 'getDimensions', 'getPose', 'getDeltaMovement', 'getRelativePosition', 'createPortalInfo', 'getPortalWaitTime', 'handleNetherPortal', 'getProperties'}
symbols = {}
owner = None
for line in lines:
    if not line or line.startswith('#'):
        continue
    if not line.startswith(' '):
        owner = line.split(' -> ')[0].replace('.', '/')
        continue
    signature, obfuscated = line.strip().split(' -> ')
    signature = re.sub(r'^\d+:\d+:', '', signature)
    signature = re.sub(r':\d+(?::\d+)?$', '', signature)
    spigot = aliases[owner]
    runtime_owner = versioned(spigot).replace('/', '.')
    if '(' in signature:
        match = re.fullmatch(r'(\S+) ([^(]+)\(([^)]*)\)', signature)
        if match is None:
            raise RuntimeError('Unrecognized method signature: ' + signature)
        result, name, parameters = match.groups()
        parameters = parameters.split(',') if parameters else []
        canonical_desc = '(' + ''.join(descriptor(value, {}) for value in parameters) + ')' + descriptor(result, {})
        spigot_desc = '(' + ''.join(descriptor(value, aliases) for value in parameters) + ')' + descriptor(result, aliases)
        renamed = member_name(spigot, obfuscated, spigot_desc)
        if name not in ('<init>', '<clinit>'):
            full.append(owner + ' ' + name + ' ' + canonical_desc + ' ' + renamed)
        if name in methods:
            runtime_parameters = []
            for value in parameters:
                if value.endswith('[]'):
                    runtime_parameters.append(descriptor(value, {k: versioned(v) for k, v in aliases.items()}).replace('/', '.'))
                else:
                    runtime_parameters.append(versioned(aliases.get(value.replace('.', '/'), value.replace('.', '/'))).replace('/', '.'))
            symbols['m.' + runtime_owner + '#' + name + '(' + ','.join(runtime_parameters) + ')'] = renamed
    else:
        _, name = signature.split(' ')
        renamed = member_name(spigot, obfuscated)
        full.append(owner + ' ' + name + ' ' + renamed)
        if name in fields:
            symbols['f.' + runtime_owner + '#' + name] = renamed
# CraftBukkit's cause-aware overload is absent from Mojang's vanilla map.
full.append('net/minecraft/server/level/ServerPlayer portallinkedChangeDimension (Lnet/minecraft/server/level/ServerLevel;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Lnet/minecraft/world/entity/Entity; b')
needed = {'net/minecraft/world/entity/Entity', 'net/minecraft/server/level/ServerLevel', 'net/minecraft/server/level/ServerPlayer', 'net/minecraft/core/BlockPos', 'net/minecraft/BlockUtil$FoundRectangle', 'net/minecraft/world/level/portal/PortalShape', 'net/minecraft/world/level/portal/PortalInfo', 'net/minecraft/core/Direction$Axis', 'net/minecraft/world/entity/Pose', 'net/minecraft/world/entity/EntityDimensions', 'net/minecraft/world/phys/Vec3', 'net/minecraft/world/phys/AABB', 'net/minecraft/world/level/block/NetherPortalBlock', 'net/minecraft/world/level/block/Blocks', 'net/minecraft/world/level/dimension/LevelStem'}
for canonical in needed:
    symbols['c.' + canonical.replace('/', '.')] = versioned(aliases[canonical]).replace('/', '.')
if args.runtime_symbols:
    args.runtime_symbols.write_text('# SPDX-License-Identifier: GPL-3.0-only\n# Copyright (C) 2026 EyeCrasher\n# Reflection symbol subset from the pinned legacy Spigot SDK.\n' + '\n'.join(key + '=' + value for key, value in sorted(symbols.items())) + '\n')
if args.input:
    with tempfile.NamedTemporaryFile(mode='w', prefix='portallinked-116-', suffix='.csrg', dir=builder / 'work', delete=False) as output:
        output.write('\n'.join(full) + '\n')
        mapping = Path(output.name)
    if args.reverse:
        inverse = []
        for line in full:
            parts = line.split()
            if len(parts) == 2:
                inverse.append(parts[1] + ' ' + parts[0])
            elif len(parts) == 3:
                inverse.append(versioned(aliases[parts[0]]) + ' ' + parts[2] + ' ' + parts[1])
            else:
                desc = re.sub(r'L([^;]+);', lambda match: 'L' + versioned(aliases.get(match[1], match[1])) + ';', parts[2])
                inverse.append(versioned(aliases[parts[0]]) + ' ' + parts[3] + ' ' + desc + ' ' + parts[1])
        # Paper adds nested classes whose friendly names can coincide with a
        # vanilla class after its outer owner is remapped. Preserve both in the
        # private development SDK with a distinct name for the added class.
        class_inverse = {line.split()[0]: line.split()[1] for line in inverse if len(line.split()) == 2}
        destinations = set(class_inverse.values())
        with zipfile.ZipFile(args.input) as sdk:
            for entry in sorted(sdk.namelist()):
                if not entry.endswith('.class'):
                    continue
                original = entry[:-6]
                if original in class_inverse or '$' not in original:
                    continue
                outer = original
                while '$' in outer and outer not in class_inverse:
                    outer = outer.rsplit('$', 1)[0]
                if outer not in class_inverse:
                    continue
                transformed = class_inverse[outer] + original[len(outer):]
                if transformed in destinations:
                    extra = transformed + '$PortalLinkedSdkExtra' + hashlib.sha256(original.encode()).hexdigest()[:8]
                    inverse.append(original + ' ' + extra)
                    destinations.add(extra)
        mapping.write_text('\n'.join(inverse) + '\n')
    classpath = [builder / 'BuildData/bin/SpecialSource.jar', args.input.resolve(),
                 *([] if args.reverse else [builder.parent / 'portallinked-development.jar']),
                 *sorted((builder.parent / 'libraries').rglob('*.jar'))]
    subprocess.run([args.java, '-cp', os.pathsep.join(map(str, classpath)), 'net.md_5.specialsource.SpecialSource', '--live', '--stable', '--in-jar', str(args.input.resolve()), '--srg-in', str(mapping), '--out-jar', str(args.output.resolve())], check=True)
print('Composed exact legacy Spigot namespace for Minecraft ' + args.minecraft)
