#!/usr/bin/env python3
"""Export a connection netlist from a Fritzing .fzz sketch.

Usage: python export_fzz_nets.py <file.fzz>

Prints one net per block: every connected group of part connectors.
Wire instances are merged away; a group with fewer than two part
connectors is not printed. Part titles come from the sketch instances.
"""
import sys
import xml.etree.ElementTree as ET
import zipfile


class DSU:
    def __init__(self):
        self.parent = {}

    def find(self, x):
        self.parent.setdefault(x, x)
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]
            x = self.parent[x]
        return x

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[rb] = ra


def main(path):
    z = zipfile.ZipFile(path)
    fz = [n for n in z.namelist() if n.endswith(".fz")][0]
    root = ET.fromstring(z.read(fz).decode("utf-8"))

    dsu = DSU()
    part = {}          # modelIndex -> (title, moduleIdRef)
    connectors = {}    # (modelIndex, connectorId) -> label
    labels = {}        # net label title -> first modelIndex (same title = same net)

    for inst in root.iter("instance"):
        mid = inst.get("modelIndex")
        title = (inst.findtext("title") or "?").strip()
        module = inst.get("moduleIdRef", "")
        is_wire = "wire" in module.lower()
        is_label = "netlabel" in module.lower()
        part[mid] = ("(wire)" if is_wire else title, module)
        wire_keys = []
        if is_label:
            # Net labels with the same title are one net in Fritzing.
            labels.setdefault(title, mid)
        for conn in inst.iter("connector"):
            cid = conn.get("connectorId")
            key = (mid, cid)
            dsu.find(key)
            if is_wire:
                # A wire shorts all of its own connector ends together;
                # the file only records pin-to-wire-end references.
                wire_keys.append(key)
            elif is_label:
                connectors[key] = title
            else:
                connectors[key] = f"{title}({cid})"
            for c in conn.iter("connect"):
                dsu.union(key, (c.get("modelIndex"), c.get("connectorId")))
        for key in wire_keys[1:]:
            dsu.union(wire_keys[0], key)

    # Merge every connector of same-titled net label instances.
    for inst in root.iter("instance"):
        if "netlabel" not in (inst.get("moduleIdRef") or "").lower():
            continue
        mid = inst.get("modelIndex")
        title = (inst.findtext("title") or "?").strip()
        dsu.union((labels[title], "connector0"), (mid, "connector0"))

    nets = {}
    for key, label in connectors.items():
        nets.setdefault(dsu.find(key), []).append(label)

    print(f"Parts: {sum(1 for t, m in part.values() if t not in ('(wire)',) and 'netlabel' not in m.lower())}")
    for t, m in part.values():
        if t != "(wire)" and "netlabel" not in m.lower():
            print(f"  {t:24s} {m}")
    print(f"\nNets with >= 2 members: {sum(1 for v in nets.values() if len(v) >= 2)}")
    for members in sorted(nets.values(), key=lambda v: sorted(v)[0]):
        if len(members) >= 2:
            print(f"\nNET ({len(members)}):")
            for m in sorted(set(members)):
                print(f"  {m}")


if __name__ == "__main__":
    main(sys.argv[1])
