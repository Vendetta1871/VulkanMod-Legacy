#!/usr/bin/env python3
"""Mean absolute difference of two captures, 0..255, ignoring the hotbar and the crosshair."""
import sys
from PIL import Image

a = Image.open(sys.argv[1]).convert("RGB")
b = Image.open(sys.argv[2]).convert("RGB")
if a.size != b.size:
    print("size differs")
    sys.exit(1)
w, h = a.size
total = count = 0
pa, pb = a.load(), b.load()
for y in range(0, int(h * 0.88), 2):
    for x in range(0, w, 2):
        if abs(x - w // 2) < 12 and abs(y - h // 2) < 12:
            continue
        p, q = pa[x, y], pb[x, y]
        total += abs(p[0] - q[0]) + abs(p[1] - q[1]) + abs(p[2] - q[2])
        count += 3
print("diff=%.2f" % (total / count))
