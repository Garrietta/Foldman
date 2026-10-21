#!/usr/bin/env python3
"""Builds the app's adaptive launcher icon from art/icon.svg.

    python3 tools/svg_to_icon.py

Writes res/drawable/foldman_icon_background.xml, foldman_icon_foreground.xml and
foldman_icon_monochrome.xml (Android's themed-icon layer). It understands just what icon.svg
uses: rect (with rx), circle and path; solid and linear/radial gradient fills; opacity; one
outer <g> transform of the form translate·scale·rotate·translate. Shapes before the
"Foreground layer" comment go in the background layer, the rest in the foreground.
"""
import math, pathlib, re, xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG = ROOT / "art/icon.svg"
RES = ROOT / "app/src/main/res/drawable"
NS = "{http://www.w3.org/2000/svg}"


def num(v, default=0.0):
    return float(v) if v is not None else default


def rect_path(x, y, w, h, rx):
    rx = min(rx, w / 2, h / 2)
    if rx <= 0:
        return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}z"
    return (f"M{x + rx:g},{y:g}h{w - 2 * rx:g}a{rx:g},{rx:g} 0 0 1 {rx:g},{rx:g}v{h - 2 * rx:g}"
            f"a{rx:g},{rx:g} 0 0 1 {-rx:g},{rx:g}h{-(w - 2 * rx):g}a{rx:g},{rx:g} 0 0 1 {-rx:g},{-rx:g}"
            f"v{-(h - 2 * rx):g}a{rx:g},{rx:g} 0 0 1 {rx:g},{-rx:g}z")


def circle_path(cx, cy, r):
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1 0 {2 * r:g},0a{r:g},{r:g} 0 1 0 {-2 * r:g},0z"


def argb(color, opacity=1.0):
    c = color.lstrip("#")
    a = round(255 * opacity)
    return f"#{a:02X}{c.upper()}"


def path_bbox(d):
    """Bounding box of the path's points (arc bulges ignored); handles M L H V A Z, absolute or relative."""
    pts, x, y = [], 0.0, 0.0
    for cmd, args in re.findall(r"([MLHVAZmlhvaz])([^MLHVAZmlhvaz]*)", d):
        v = [float(n) for n in re.findall(r"-?\d*\.?\d+", args)]
        rel = cmd.islower()
        c = cmd.upper()
        step = {"M": 2, "L": 2, "H": 1, "V": 1, "A": 7, "Z": 0}[c]
        for i in range(0, len(v), step or 1) if step else []:
            a = v[i:i + step]
            if c in "ML":
                x, y = (x + a[0], y + a[1]) if rel else (a[0], a[1])
            elif c == "H":
                x = x + a[0] if rel else a[0]
            elif c == "V":
                y = y + a[0] if rel else a[0]
            elif c == "A":
                x, y = (x + a[5], y + a[6]) if rel else (a[5], a[6])
            pts.append((x, y))
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)


def shape(el):
    """(pathData, bbox) for a shape element, applying a translate() on the element itself."""
    tag = el.tag.replace(NS, "")
    dx = dy = 0.0
    t = el.get("transform")
    if t:
        m = re.match(r"translate\(([-\d.]+)[ ,]+([-\d.]+)\)", t)
        dx, dy = float(m.group(1)), float(m.group(2))
    if tag == "rect":
        x, y = num(el.get("x")) + dx, num(el.get("y")) + dy
        w, h = num(el.get("width")), num(el.get("height"))
        return rect_path(x, y, w, h, num(el.get("rx"))), (x, y, w, h)
    if tag == "circle":
        cx, cy, r = num(el.get("cx")) + dx, num(el.get("cy")) + dy, num(el.get("r"))
        return circle_path(cx, cy, r), (cx - r, cy - r, 2 * r, 2 * r)
    if tag == "path":
        d = el.get("d")
        return d, path_bbox(d)
    return None, None


def fill_xml(fill, opacity, bbox, grads, indent):
    pad = " " * indent
    if not fill.startswith("url("):
        return f'{pad}android:fillColor="{argb(fill, opacity)}"', ""
    g = grads[fill[5:-1]]
    x, y, w, h = bbox
    stops = "".join(
        f'\n{pad}        <item android:offset="{s["offset"]:g}" android:color="{argb(s["color"], s["opacity"] * opacity)}" />'
        for s in g["stops"])
    if g["type"] == "linear":
        head = (f'android:type="linear" android:startX="{x + g["x1"] * w:g}" android:startY="{y + g["y1"] * h:g}" '
                f'android:endX="{x + g["x2"] * w:g}" android:endY="{y + g["y2"] * h:g}"')
    else:
        head = (f'android:type="radial" android:centerX="{x + g["cx"] * w:g}" android:centerY="{y + g["cy"] * h:g}" '
                f'android:gradientRadius="{g["r"] * max(w, h):g}"')
    child = (f'\n{pad}<aapt:attr name="android:fillColor">\n{pad}    <gradient {head}>{stops}\n'
             f'{pad}    </gradient>\n{pad}</aapt:attr>')
    return "", child


def path_xml(el, grads, indent, mono=None):
    d, bbox = shape(el)
    if d is None:
        return ""
    pad = " " * indent
    opacity = num(el.get("opacity"), 1.0)
    if mono is not None:
        attr, child = f'{pad}    android:fillColor="{argb("FFFFFF", mono * opacity)}"', ""
    else:
        attr, child = fill_xml(el.get("fill"), opacity, bbox, grads, indent + 4)
    lines = [f"{pad}<path", f'{pad}    android:pathData="{d}"']
    if attr:
        lines.append("    " + attr if not attr.startswith(pad + "    ") else attr)
    if child:
        return "\n".join(lines) + ">" + child + f"\n{pad}</path>"
    return "\n".join(lines) + " />"


def vector(body, comment):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Generated from art/icon.svg by tools/svg_to_icon.py; edit the SVG, not this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}
</vector>
'''


def main():
    text = SVG.read_text()
    root = ET.fromstring(text)
    grads = {}
    for g in root.iter():
        tag = g.tag.replace(NS, "")
        if tag not in ("linearGradient", "radialGradient"):
            continue
        stops = [{"offset": num(s.get("offset")), "color": s.get("stop-color"), "opacity": num(s.get("stop-opacity"), 1.0)}
                 for s in g if s.tag.replace(NS, "") == "stop"]
        if tag == "linearGradient":
            grads[g.get("id")] = {"type": "linear", "x1": num(g.get("x1")), "y1": num(g.get("y1")),
                                  "x2": num(g.get("x2"), 1), "y2": num(g.get("y2")), "stops": stops}
        else:
            grads[g.get("id")] = {"type": "radial", "cx": num(g.get("cx"), .5), "cy": num(g.get("cy"), .5),
                                  "r": num(g.get("r"), .5), "stops": stops}

    background = [el for el in root if el.tag.replace(NS, "") in ("rect", "circle", "path")]
    group = next(el for el in root if el.tag.replace(NS, "") == "g")
    m = re.match(r"translate\(([-\d.]+) ([-\d.]+)\) scale\(([-\d.]+)\) rotate\(([-\d.]+)\) translate\(([-\d.]+) ([-\d.]+)\)",
                 group.get("transform"))
    tx, ty, sc, rot, px, py = (float(v) for v in m.groups())
    # SVG: T(tx,ty)·S·R·T(px,py). VectorDrawable group: T(translate)·T(pivot)·R·S·T(-pivot), so
    # with pivot = -(px,py) the translate is (tx,ty) minus the pivot.
    pivot = (-px, -py)
    group_open = (f'    <group\n        android:pivotX="{pivot[0]:g}"\n        android:pivotY="{pivot[1]:g}"\n'
                  f'        android:rotation="{rot:g}"\n        android:scaleX="{sc:g}"\n        android:scaleY="{sc:g}"\n'
                  f'        android:translateX="{tx - pivot[0]:g}"\n        android:translateY="{ty - pivot[1]:g}">')
    fg = [el for el in group if el.tag.replace(NS, "") in ("rect", "circle", "path")]

    RES.joinpath("foldman_icon_background.xml").write_text(vector(
        "\n".join(path_xml(el, grads, 4) for el in background), "Launcher icon background: blue enamel."))
    RES.joinpath("foldman_icon_foreground.xml").write_text(vector(
        group_open + "\n" + "\n".join(path_xml(el, grads, 8) for el in fg) + "\n    </group>",
        "Launcher icon foreground: a folding player with a mixtape."))
    # Themed icons tint one layer; keep the outline and the window, drop the fine detail.
    mono_alpha = {"#000000": 0.0}
    mono = []
    for el in fg:
        fill = el.get("fill", "")
        if mono_alpha.get(fill) == 0.0:
            continue
        light = fill in ("#E8EAED",) or fill.startswith("url(#hub") or fill.startswith("url(#silver") or fill.startswith("url(#frame")
        mono.append(path_xml(el, grads, 8, mono=1.0 if light else 0.55))
    RES.joinpath("foldman_icon_monochrome.xml").write_text(vector(
        group_open + "\n" + "\n".join(mono) + "\n    </group>", "Launcher icon for themed (monochrome) icons."))
    print("Wrote", ", ".join(f.name for f in RES.glob("foldman_icon_*.xml")))


if __name__ == "__main__":
    main()
