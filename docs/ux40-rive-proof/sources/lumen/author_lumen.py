from pathlib import Path
from xml.sax.saxutils import escape
root=Path(__file__).parent
# Original declarative artwork and choreography authored for this proof.
# Common modes are an integration contract, not a fixed shared visual template.
parts=[]
def shape(name,x,y,w,h,color,kind='Rectangle',id=None,radius=0,rotation=0,extra=''):
    ident=f' id="0:{id}"' if id else ''
    geom=f'<{kind} width="{w}" height="{h}"'+(f' cornerRadiusTL="{radius}"' if kind=='Rectangle' else '')+f' {extra}/>'
    return f'<Shape name="{name}" x="{x}" y="{y}" rotation="{rotation}"{ident}>{geom}<Fill><SolidColor colorValue="{color}"/></Fill></Shape>'
parts.append('<Rive version="1" kind="fragment">')
parts.append('<Artboard name="Mascot" id="0:2" width="256" height="256" styleId="0:5" defaultStateMachineId="0:7" viewModelId="0:40" viewModelInstanceId="0:41"><LayoutComponentStyle id="0:5" name="Artboard Style"/>')
# Face and inset panel are on top (Rive front-to-back declaration order).
parts.append('<Node name="Lantern" id="0:100" x="128" y="124">')
parts.append(shape('Left eye',-22,-11,11,22,'FF86FFF0',id=110,radius=5.5))
parts.append(shape('Right eye',22,-11,11,22,'FF86FFF0',id=111,radius=5.5))
parts.append(shape('Mouth',0,13,19,5,'FFC5FFF5',id=112,radius=2.5))
parts.append(shape('Left cheek',-37,13,9,4,'FF8F84DA',radius=2))
parts.append(shape('Right cheek',37,13,9,4,'FF8F84DA',radius=2))
parts.append(shape('Visor glint',-19,-31,34,4,'FFB0B9F5',radius=2))
parts.append(shape('Visor',0,-9,103,65,'FF242946',radius=22))
parts.append(shape('Panel rim',0,-8,114,77,'FFBDC3FD',radius=26))
parts.append(shape('Hull highlight',-41,-39,9,26,'FFFFFFFF',radius=4,rotation=0.5))
parts.append(shape('Hull',0,0,140,117,'FFE7E7FF',radius=43))
# Angular magnetic fins separate the silhouette from ordinary rounded robot badges.
parts.append('<Node name="Left fin" id="0:120" x="-66" y="13">'+shape('Fin cap',-9,0,28,50,'FF6D71BB','Triangle',rotation=-1.5707963)+'</Node>')
parts.append('<Node name="Right fin" id="0:121" x="66" y="13">'+shape('Fin cap',9,0,28,50,'FF6D71BB','Triangle',rotation=1.5707963)+'</Node>')
parts.append(shape('Antenna bulb',0,-87,18,18,'FFFFC66D','Ellipse',id=130))
parts.append(shape('Antenna stalk',0,-71,5,20,'FF858AB7',radius=2.5))
parts.append(shape('Belly signal',0,52,26,7,'FF5EE5CB',id=140,radius=3.5))
parts.append(shape('Jet outer',0,69,26,29,'FF91EEE2','Triangle',id=150,rotation=3.14159265))
parts.append(shape('Jet core',0,65,11,17,'FFD6FFF5','Triangle',rotation=3.14159265))
parts.append('</Node>')
parts.append(shape('Floating shadow',128,216,96,12,'FFCBD3E7','Ellipse',id=160))
# Discrete state data binding. View-model properties, not deprecated SM inputs.
parts.append('<StateMachine name="MascotMotion" id="0:7"><StateMachineLayer name="Presence" id="0:8"><AnyState x="200" y="-200">')
for state in range(18):
    mode=state%9; reduced=state>=9
    parts.append(f'<StateTransition stateToId="0:{200+state}" duration="0"><TransitionViewModelCondition opValue="equal"><TransitionPropertyViewModelComparator><BindablePropertyNumber><DataBindContext sourcePathIds="0:40-0:45" propertyKey="636"/></BindablePropertyNumber></TransitionPropertyViewModelComparator><TransitionValueNumberComparator value="{mode}"/></TransitionViewModelCondition><TransitionViewModelCondition opValue="equal"><TransitionPropertyViewModelComparator><BindablePropertyBoolean><DataBindContext sourcePathIds="0:40-0:46" propertyKey="634"/></BindablePropertyBoolean></TransitionPropertyViewModelComparator><TransitionValueBooleanComparator value="{str(reduced).lower()}"/></TransitionViewModelCondition></StateTransition>')
parts.append('</AnyState><ExitState x="700" y="-200"/><EntryState x="0" y="0"><StateTransition stateToId="0:200"/></EntryState>')
for state in range(18):
    parts.append(f'<AnimationState x="{200+(state%6)*200}" y="{(state//6)*150}" reset="true" animationId="0:{300+state}" id="0:{200+state}"/>')
parts.append('</StateMachineLayer></StateMachine>')
# Baseline keys on every animated property prevent carry-over from previous states.
base={(100,'x'):128,(100,'y'):124,(100,'rotation'):0,(100,'scaleX'):1,(100,'scaleY'):1,(110,'scaleY'):1,(111,'scaleY'):1,(110,'rotation'):0,(111,'rotation'):0,(112,'scaleX'):1,(112,'y'):13,(120,'rotation'):0,(121,'rotation'):0,(130,'scaleX'):1,(130,'scaleY'):1,(140,'opacity'):1,(150,'scaleY'):0.75,(160,'scaleX'):1}
states=[
('Idle',120,True,{(100,'y'):[(0,124),(60,121),(120,124)],(110,'scaleY'):[(0,1),(93,1),(97,.12),(101,1),(120,1)],(111,'scaleY'):[(0,1),(93,1),(97,.12),(101,1),(120,1)]}),
('Thinking',100,True,{(100,'rotation'):[(0,-.09),(50,.1),(100,-.09)],(110,'scaleY'):[(0,.62),(50,.85),(100,.62)],(111,'scaleY'):[(0,.85),(50,.62),(100,.85)],(112,'scaleX'):[(0,.4)],(130,'scaleX'):[(0,1),(50,1.35),(100,1)],(130,'scaleY'):[(0,1),(50,1.35),(100,1)]}),
('Working',60,True,{(100,'y'):[(0,123),(15,116),(30,123),(45,116),(60,123)],(120,'rotation'):[(0,-.2),(30,.4),(60,-.2)],(121,'rotation'):[(0,.2),(30,-.4),(60,.2)],(150,'scaleY'):[(0,.6),(15,1.3),(30,.7),(45,1.4),(60,.6)],(160,'scaleX'):[(0,1),(15,.85),(30,1),(45,.85),(60,1)]}),
('WaitingUser',1,False,{(100,'rotation'):[(0,.12)],(110,'scaleY'):[(0,1.2)],(111,'scaleY'):[(0,1.2)],(112,'scaleX'):[(0,.4)],(120,'rotation'):[(0,-.4)],(121,'rotation'):[(0,.4)],(150,'scaleY'):[(0,.35)]}),
('Done',60,False,{(100,'y'):[(0,124),(15,105),(35,128),(60,124)],(100,'scaleX'):[(0,1),(15,1.04),(35,.98),(60,1)],(110,'scaleY'):[(0,.35)],(111,'scaleY'):[(0,.35)],(110,'rotation'):[(0,-.18)],(111,'rotation'):[(0,.18)],(112,'scaleX'):[(0,1.25)],(120,'rotation'):[(0,-.6)],(121,'rotation'):[(0,.6)]}),
('Error',45,False,{(100,'x'):[(0,128),(6,119),(12,137),(18,120),(24,136),(32,128),(45,128)],(110,'rotation'):[(0,.3)],(111,'rotation'):[(0,-.3)],(110,'scaleY'):[(0,.45)],(111,'scaleY'):[(0,.45)],(112,'scaleX'):[(0,.65)],(112,'y'):[(0,18)],(150,'scaleY'):[(0,.2)]}),
('Interrupted',1,False,{(100,'y'):[(0,137)],(100,'rotation'):[(0,-.08)],(110,'scaleY'):[(0,.18)],(111,'scaleY'):[(0,.18)],(112,'scaleX'):[(0,.6)],(140,'opacity'):[(0,.2)],(150,'scaleY'):[(0,.1)]}),
('Queued',1,False,{(100,'y'):[(0,130)],(110,'scaleY'):[(0,.65)],(111,'scaleY'):[(0,.65)],(112,'scaleX'):[(0,.5)],(140,'opacity'):[(0,.45)],(150,'scaleY'):[(0,.25)]}),
('WaitingProvider',1,False,{(100,'rotation'):[(0,-.12)],(110,'scaleY'):[(0,.4)],(111,'scaleY'):[(0,.9)],(112,'scaleX'):[(0,.45)],(120,'rotation'):[(0,.25)],(121,'rotation'):[(0,-.25)],(150,'scaleY'):[(0,.3)]})]
for name,duration,loop,changes in list(states):
    # Reduced motion retains a meaningful pose while all timelines settle.
    still={k:[(0,keys[-1][1] if name in ('Done','Error') else keys[len(keys)//2][1])] for k,keys in changes.items()}
    if name=='Idle':
        still[(110,'scaleY')]=[(0,1)]
        still[(111,'scaleY')]=[(0,1)]
    states.append((name+'Reduced',1,False,still))
for mode,(name,duration,loop,changes) in enumerate(states):
    props={k:[(0,v)] for k,v in base.items()};props.update(changes)
    parts.append(f'<LinearAnimation name="{name}" id="0:{300+mode}" duration="{duration}"'+(' loopValue="loop"' if loop else '')+'>')
    for obj in sorted(set(k[0] for k in props)):
        parts.append(f'<KeyedObject objectId="0:{obj}">')
        for (target,prop),keys in props.items():
            if target!=obj:continue
            parts.append(f'<KeyedProperty property="{prop}">')
            for frame,value in keys:parts.append(f'<KeyFrameDouble frame="{frame}" value="{value}" interpolationType="linear"/>')
            parts.append('</KeyedProperty>')
        parts.append('</KeyedObject>')
    parts.append('</LinearAnimation>')
parts.append('</Artboard><ViewModel name="MascotState" id="0:40" defaultInstanceId="0:41"><ViewModelPropertyNumber name="mode" id="0:45"/><ViewModelPropertyBoolean name="reducedMotion" id="0:46"/><ViewModelInstance name="Default" id="0:41" exports="true"><ViewModelInstanceNumber viewModelPropertyId="0:45" propertyValue="0"/><ViewModelInstanceBoolean viewModelPropertyId="0:46" propertyValue="false"/></ViewModelInstance></ViewModel></Rive>')
root.joinpath('scene.rml').write_text('\n'.join(parts)+'\n')
