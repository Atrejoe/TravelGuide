"""Exits 0 when two car screenshots show the same media centre screen, whatever the status bar
(clock) and the dock (recently used apps) at the bottom show; 1 when the screen itself differs.

Usage: python same-app-area.py <old.png> <new.png>   (needs Pillow)
"""
import sys

from PIL import Image, ImageChops

old, new = (Image.open(path).convert("RGB") for path in sys.argv[1:3])
if old.size != new.size:
    sys.exit(1)
width, height = old.size
# Between the status bar (top 10%) and the dock (bottom 12.5%).
app = (0, int(height * 0.11), width, int(height * 0.86))
sys.exit(0 if ImageChops.difference(old.crop(app), new.crop(app)).getbbox() is None else 1)
