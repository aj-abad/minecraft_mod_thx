#!/usr/bin/env python3
"""Generate Blockbench .bbmodel files from vanilla/THX model geometry.

 - helicopter, boat: entity models (ModelRenderer boxes) -> modded_entity format.
   Minecraft entity models are Y-DOWN; Blockbench is Y-UP, so we negate Y for
   positions/pivots and negate rotation angles. Box sizes + UV offsets map 1:1
   (UV space = ModelRenderer default 64x32).
 - glass_pane: a block model -> java_block format, 16-unit block space, Y-up,
   assembled from vanilla template_glass_pane_post/side (post + 4 sides).
"""
import base64, json, os, struct

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(HERE, "..", "src", "main", "resources", "assets", "thx", "textures", "entity", "helicopter.png")

def uuid(n):
    return "00000000-0000-4000-8000-{:012d}".format(n)

def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    return struct.unpack(">II", head[16:24])

# ---- entity models (modded_entity) ----------------------------------------
# name, uv[u,v], rotationPoint(rx,ry,rz), addBox offset(ox,oy,oz), size(sx,sy,sz), rot deg(ax,ay,az)
HELICOPTER = [
    ("bottom",     (0, 22),  (0.0,    2.0,  0.0), (-5.0, -4.0, -1.0), (10, 8, 2), (90, 0,   0)),
    ("frontWall",  (0, 4),   (-5.5,   0.0,  0.0), (-5.0, -1.5, -0.5), (10, 3, 1), (0,  270, 0)),
    ("backWall",   (0, 9),   (5.5,    0.0,  0.0), (-5.0, -1.5, -0.5), (10, 3, 1), (0,  90,  0)),
    ("leftWall",   (25, 19), (0.0,    0.0, -4.5), (-5.0, -1.5, -0.5), (10, 3, 1), (0,  0,   0)),
    ("rightWall",  (25, 24), (0.0,    0.0,  4.5), (-5.0, -1.5, -0.5), (10, 3, 1), (0,  180, 0)),
    ("mainRotor",  (0, 0),   (2.0,  -11.7,  0.0), (-15.0, 0.0, -0.5), (30, 0, 1), (0,  0,   0)),
    ("tailRotor",  (0, 2),   (16.0, -7.0,   0.7), (-4.0, -0.5,  0.0), (8,  1, 0), (0,  0,   0)),
    ("tail",       (42, 29), (12.0, -7.0,   0.0), (-5.0, -1.0, -0.5), (10, 2, 1), (0,  0,   0)),
    ("rotor2",     (58, 11), (6.5,  -5.0,   0.0), (-0.5, -5.5, -1.0), (1, 11, 2), (0,  0,   0)),
    ("rotor3",     (48, 25), (4.0,  -11.0,  0.0), (-3.0, -0.5, -1.0), (6,  1, 2), (0,  0,   0)),
    ("windshield", (22, 2),  (-5.5, -4.5,   0.0), (-4.5, -3.5,  0.0), (9,  7, 0), (0,  270, 0)),
]
# ModelBoat: b0=24 (len), b1=6 (wall h), b2=20 (width), b3=4 (rp.y)
BOAT = [
    ("boatBottom", (0, 8), (0.0,   4.0,  0.0), (-12.0, -8.0, -3.0), (24, 16, 4), (90, 0,   0)),
    ("boatSide1",  (0, 0), (-11.0, 4.0,  0.0), (-10.0, -7.0, -1.0), (20, 6,  2), (0,  270, 0)),
    ("boatSide2",  (0, 0), (11.0,  4.0,  0.0), (-10.0, -7.0, -1.0), (20, 6,  2), (0,  90,  0)),
    ("boatSide3",  (0, 0), (0.0,   4.0, -9.0), (-10.0, -7.0, -1.0), (20, 6,  2), (0,  180, 0)),
    ("boatSide4",  (0, 0), (0.0,   4.0,  9.0), (-10.0, -7.0, -1.0), (20, 6,  2), (0,  0,   0)),
]

def gen_modded(name, boxes, tex_path):
    elements, outliner = [], []
    for i, (bn, uv, rp, off, size, rot) in enumerate(boxes):
        rx, ry, rz = rp; ox, oy, oz = off; sx, sy, sz = size; ax, ay, az = rot
        frm = [rx + ox, -(ry + oy + sy), rz + oz]              # Y-up: negate Y, swap min/max
        to  = [rx + ox + sx, -(ry + oy), rz + oz + sz]
        origin = [rx, -ry, rz]
        cid, gid = uuid(i), uuid(100 + i)
        elements.append({"name": bn, "box_uv": True, "uv_offset": list(uv),
                         "from": frm, "to": to, "origin": origin, "rotation": [0, 0, 0],
                         "autouv": 0, "color": i % 8, "uuid": cid})
        outliner.append({"name": bn, "origin": origin, "rotation": [-ax, -ay, -az],
                        "uuid": gid, "export": True, "isOpen": False, "children": [cid]})
    model = {"meta": {"format_version": "4.5", "model_format": "modded_entity", "box_uv": True},
             "name": name, "model_identifier": name,
             "modded_entity_version": "1.7", "modded_entity_flip_y": True,
             "resolution": {"width": 64, "height": 32},
             "elements": elements, "outliner": outliner, "textures": []}
    if tex_path:
        w, h = png_size(tex_path)
        with open(tex_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode("ascii")
        model["textures"] = [{"name": os.path.basename(tex_path), "id": "0", "particle": False,
                              "render_mode": "default", "visible": True, "mode": "bitmap", "saved": False,
                              "uuid": uuid(999), "uv_width": 64, "uv_height": 32, "width": w, "height": h,
                              "source": "data:image/png;base64," + b64}]
    write(name, model)

# ---- block model (java_block) ----------------------------------------------
# Full 4-way glass pane: 2x16x2 post + four 2x16x7 side panels (from vanilla templates).
PANE = [
    ("post",       [7, 0, 7], [9, 16, 9]),
    ("side_north", [7, 0, 0], [9, 16, 7]),
    ("side_south", [7, 0, 9], [9, 16, 16]),
    ("side_west",  [0, 0, 7], [7, 16, 9]),
    ("side_east",  [9, 0, 7], [16, 16, 9]),
]

def gen_block(name, boxes):
    elements, outliner = [], []
    for i, (bn, frm, to) in enumerate(boxes):
        cid = uuid(200 + i)
        faces = {d: {"uv": [0, 0, 16, 16], "texture": None} for d in
                 ["north", "east", "south", "west", "up", "down"]}
        elements.append({"name": bn, "box_uv": False, "from": frm, "to": to,
                        "autouv": 0, "color": i % 8, "uuid": cid, "faces": faces})
        outliner.append(cid)
    model = {"meta": {"format_version": "4.5", "model_format": "java_block", "box_uv": False},
             "name": name, "resolution": {"width": 16, "height": 16},
             "elements": elements, "outliner": outliner, "textures": []}
    write(name, model)

def write(name, model):
    out = os.path.join(HERE, name + ".bbmodel")
    with open(out, "w") as f:
        json.dump(model, f, indent=2)
    print("wrote", os.path.basename(out), "-", len(model["elements"]), "elements")

gen_modded("helicopter", HELICOPTER, TEX)
gen_modded("boat", BOAT, None)
gen_block("glass_pane", PANE)
