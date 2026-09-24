#!/usr/bin/env python3
"""Regenerate the synthetic SynCal-1 teaching curves used by the case pack.

SynCal-1 is NOT IntCal. It is a deterministic synthetic curve designed so the
workbench acceptance scenarios are reproducible offline:
  - wiggles produce multi-modal posteriors (curve multimodality)
  - a flat plateau at 1950 calBP (v1) / 2010 calBP (v2) exercises BCE/CE crossing
  - knot grid 600..2400 calBP exercises boundary interpolation diagnostics
Usage: python3 case-pack/tools/make_curve.py
"""
import math, os

A, P = 60.0, 150.0  # wiggle amplitude / period (14C years)

def smooth(u):
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)

def wig(t):
    return A * math.sin(2 * math.pi * t / P)

def make(plateau, sigma):
    b0, p0, p1, b1, level = plateau
    def c14(t):
        base = t + wig(t)
        if t <= b0 or t >= b1:
            return base
        if p0 <= t <= p1:
            return level
        if b0 < t < p0:
            u = smooth((t - b0) / (p0 - b0))
            return (1 - u) * base + u * level
        u = smooth((b1 - t) / (b1 - p1))
        return (1 - u) * base + u * level
    rows = []
    t = 600
    while t <= 2400:
        rows.append((t, round(c14(t), 3), sigma))
        t += 10
    return rows

def write(path, name, version, rows, note):
    with open(path, "w") as f:
        f.write(f"# {name} v{version} -- synthetic teaching curve (NOT IntCal)\n")
        f.write(f"# {note}\n")
        f.write("# columns: calBP (calendar years before 1950), c14AgeBP (conventional 14C age), sigma (1 sd, 14C years)\n")
        f.write("calBP,c14AgeBP,sigma\n")
        for t, c, s in rows:
            f.write(f"{t},{c},{s}\n")

here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, "..", "carbon-candidates")
write(os.path.join(out, "syncal1_v1.csv"), "SynCal-1", "1.0",
      make((1860, 1900, 2000, 2040, 1950.0), 15),
      "plateau 1900..2000 calBP flat at 1950 (BCE/CE crossing teaching case)")
write(os.path.join(out, "syncal1_v2.csv"), "SynCal-1", "2.0",
      make((1940, 1980, 2060, 2100, 2010.0), 10),
      "curve upgrade: plateau moved to 1980..2060 calBP flat at 2010, tighter sigma")
print("wrote syncal1_v1.csv and syncal1_v2.csv")
