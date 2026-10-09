"""Two original geometric mascots with independently authored movement tracks."""
import json
from pathlib import Path
from writer import compile_scene

HERE = Path(__file__).parent

def group(name, parent="Artboard", **kw):
    return dict(kind="group", name=name, parent=parent, **kw)

def shape(name, kind, x, y, width, height, color, parent="Body", **kw):
    return dict(kind=kind, name=name, parent=parent, x=x, y=y,
                width=width, height=height, color=int(color, 16), **kw)

def track(node, prop, keys):
    return dict(node=node, property=prop, keys=keys)

def animation(duration, loop, definitions):
    return dict(duration=duration, loop=loop, tracks=[track(*d) for d in definitions])

def miga():
    # Front-to-back draw order. A round courier with capsule feet and a satchel.
    nodes = [group("Body", x=128, y=132),
        shape("EyeL", "ellipse", -16, -11, 8, 12, "FF24374A"),
        shape("EyeR", "ellipse", 16, -11, 8, 12, "FF24374A"),
        shape("Smile", "rectangle", 0, 10, 16, 4, "FF24374A", radius=2),
        shape("CheekL", "ellipse", -29, 4, 12, 7, "FFF19883"),
        shape("CheekR", "ellipse", 29, 4, 12, 7, "FFF19883"),
        shape("Satchel", "rectangle", 36, 25, 25, 29, "FFCC7650", radius=6, rotation=-0.15),
        shape("BodyShell", "ellipse", 0, 0, 110, 98, "FFFFD790"),
        shape("FootL", "rectangle", -25, 49, 30, 15, "FFCC7650", radius=7),
        shape("FootR", "rectangle", 25, 49, 30, 15, "FFCC7650", radius=7),
        shape("Shadow", "ellipse", 128, 206, 105, 12, "FFD8DEE5", parent="Artboard")]
    idle = [("Body", "y", [[0,132],[60,130],[120,132]]),
            ("Body", "rotation", [[0,0]]), ("Body", "scaleY", [[0,1],[60,1.025],[120,1]]),
            ("FootL", "rotation", [[0,0]]), ("FootR", "rotation", [[0,0]])]
    active = [("Body", "y", [[0,132],[12,136],[30,112],[48,136],[60,132]]),
              ("Body", "rotation", [[0,-.07],[30,.07],[60,-.07]]),
              ("Body", "scaleY", [[0,1],[12,.92],[30,1.08],[48,.92],[60,1]]),
              ("FootL", "rotation", [[0,-.15],[30,.25],[60,-.15]]),
              ("FootR", "rotation", [[0,.15],[30,-.25],[60,.15]])]
    reduced = [(n,p,[[0,v]]) for n,p,v in [("Body","y",132),("Body","rotation",0),("Body","scaleY",1),("FootL","rotation",0),("FootR","rotation",0)]]
    return dict(name="Miga",nodes=nodes,animations=dict(Idle=animation(120,True,idle),Active=animation(60,True,active),Reduced=animation(1,False,reduced)))

def tallo():
    # Potted sprout: fixed pot, swaying stalk, independently rotating leaf groups.
    nodes = [group("Body",x=128,y=161), group("Stem",parent="Body",x=0,y=-10),
        group("LeafL",parent="Stem",x=-3,y=-49),
        shape("LeafLBlade","ellipse",-19,-8,51,23,"FF58B78B",parent="LeafL",rotation=.4),
        group("LeafR",parent="Stem",x=3,y=-62),
        shape("LeafRBlade","ellipse",20,-11,54,25,"FF83D694",parent="LeafR",rotation=-.5),
        shape("Stalk","rectangle",0,-31,8,70,"FF4A9A76",parent="Stem",radius=4),
        shape("EyeL","ellipse",-15,7,7,10,"FF513E62"),
        shape("EyeR","ellipse",15,7,7,10,"FF513E62"),
        shape("Smile","rectangle",0,23,13,4,"FF513E62",radius=2),
        shape("PotRim","rectangle",0,-13,104,18,"FFC69ED7",radius=5),
        shape("Pot","rectangle",0,14,89,69,"FFDBC0E6",radius=18),
        shape("Shadow","ellipse",128,221,97,12,"FFD8DEE5",parent="Artboard")]
    idle = [("Stem","rotation",[[0,-.035],[90,.035],[180,-.035]]),
            ("LeafL","rotation",[[0,0]]),("LeafR","rotation",[[0,0]])]
    active = [("Stem","rotation",[[0,-.11],[36,.11],[72,-.11]]),
              ("LeafL","rotation",[[0,-.25],[18,.3],[36,-.25],[54,.3],[72,-.25]]),
              ("LeafR","rotation",[[0,.3],[24,-.35],[48,.3],[72,.3]])]
    reduced = [(n,"rotation",[[0,0]]) for n in ("Stem","LeafL","LeafR")]
    return dict(name="Tallo",nodes=nodes,animations=dict(Idle=animation(180,True,idle),Active=animation(72,True,active),Reduced=animation(1,False,reduced)))

if __name__ == "__main__":
    (HERE/"scenes").mkdir(exist_ok=True); (HERE/"artifacts").mkdir(exist_ok=True)
    for build in (miga,tallo):
        scene = build(); name = scene["name"].lower()
        (HERE/"scenes"/f"{name}.json").write_text(json.dumps(scene,indent=2)+"\n")
        binary = compile_scene(scene)
        (HERE/"artifacts"/f"{name}.riv").write_bytes(binary)
        print(name, len(binary))
