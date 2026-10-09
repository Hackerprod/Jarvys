from pathlib import Path
from xml.sax.saxutils import escape
P=Path(__file__).parent
counter=100
ids={}
def ident(name):
 global counter
 if name not in ids: ids[name]=f'0:{counter}'; counter+=1
 return ids[name]
def attrs(d):return ' '.join(f'{k}="{escape(str(v))}"' for k,v in d.items())
def node(name, children, **kw):return f'<Node {attrs(dict(name=name,id=ident(name),**kw))}>{children}</Node>'
def shape(name, geom, color=None, stroke=None, **kw):
 paint=''
 if color:paint+=f'<Fill name="Fill"><SolidColor colorValue="{color}" name="Color"/></Fill>'
 if stroke:paint+=f'<Stroke thickness="{stroke[1]}" cap="round" join="round" name="Line"><SolidColor colorValue="{stroke[0]}" name="Ink"/></Stroke>'
 return f'<Shape {attrs(dict(name=name,id=ident(name),**kw))}>{geom}{paint}</Shape>'
def ell(name,x,y,w,h,color,**kw):return shape(name,f'<Ellipse width="{w}" height="{h}" originX="0.5" originY="0.5" name="Path"/>',color,x=x,y=y,**kw)
def path(points, closed=True, radius=0):
 # Vertices authored clockwise in screen coordinates where closed.
 return f'<PointsPath isClosed="{str(closed).lower()}" isClockwise="true" name="Path">'+''.join(f'<StraightVertex x="{x}" y="{y}" radius="{radius}"/>' for x,y in points)+'</PointsPath>'
def poly(name,pts,color,**kw):return shape(name,path(pts),color,**kw)
def line(name,pts,color='FF33594D',width=3,**kw):return shape(name,path(pts,False),stroke=(color,width),**kw)
ink='FF294D43'; mint='FF92CA99'; cream='FFF7F2CD'; deep='FF578C68'; peach='FFF1AD73'; light='FFBCE2A4'
# Foreground details first. All geometry is original vector work.
face=''
for side,xx in [('L',-31),('R',31)]:
 eye=node('Eye'+side,ell('Glint'+side,-2,-5,4,4,'FFFFFFFF')+ell('Pupil'+side,0,0,13,23,ink),x=xx,y=-14)
 face+=eye
face+=line('BrowL',[(-8,0),(8,-1)],ink,3,x=-31,y=-38,opacity=0)
face+=line('BrowR',[(-8,-1),(8,0)],ink,3,x=31,y=-38,opacity=0)
face+=poly('Beak',[(-8,3),(8,3),(0,13)],peach)
face+=ell('BlushL',-46,7,17,8,'FFE7B68A')+ell('BlushR',46,7,17,8,'FFE7B68A')
# Soft heart-shaped paper face made from a closed rounded polygon.
face+=shape('FaceMask',path([(-59,-36),(-43,-53),(-14,-47),(0,-31),(14,-47),(43,-53),(59,-36),(53,19),(23,41),(0,49),(-23,41),(-53,19)],radius=13),cream)
head=node('Head',face,y=-27)
wingL=node('WingL',line('WingVeinL',[(0,0),(-23,47)],'FF619971',2)+poly('WingPaperL',[(3,-6),(-36,12),(-42,58),(-5,40)],deep),x=-67,y=-1)
wingR=node('WingR',line('WingVeinR',[(0,0),(23,47)],'FF619971',2)+poly('WingPaperR',[(-3,-6),(5,40),(42,58),(36,12)],deep),x=67,y=-1)
crest=node('Crest',line('CrestVein',[(0,0),(14,-31)],deep,2)+poly('CrestLeaf',[(0,2),(-2,-23),(23,-47),(27,-22)],light),x=6,y=-79)
body=poly('ChestFold',[(0,-29),(39,35),(0,74),(-39,35)],'FFADD9A3')
body+=line('ChestSeam',[(0,-13),(0,66)],'FF84BB8B',2)
body+=shape('OwlPaper',path([(-70,-92),(-31,-72),(0,-81),(31,-72),(70,-92),(77,-16),(63,47),(0,85),(-63,47),(-77,-16)],radius=9),mint)
body+=poly('Tail',[(0,65),(25,76),(0,109),(-25,76)],deep)
feet=line('FootL',[(-11,0),(0,-3),(11,0)],peach,5,x=-27,y=91)+line('FootR',[(-11,0),(0,-3),(11,0)],peach,5,x=27,y=91)
mascot=node('Brisa',head+crest+wingL+wingR+body+feet,x=200,y=207)
# Mode-specific semantic marks: seed thoughts, paper task, invitation, completed sprout,
# concern, pause, queue seeds and external waiting signal.
icons={}
icons[1]=node('ThinkingMark',ell('ThoughtSmall',-23,16,5,5,deep)+ell('ThoughtMid',-9,1,8,8,deep)+ell('ThoughtBig',10,-12,12,12,deep),x=293,y=101,opacity=0)
icons[2]=node('WorkingMark',line('TaskFold',[(0,-17),(0,16)],deep,2)+poly('TaskLeaf',[(-17,-15),(6,-23),(20,-8),(13,18),(-8,24),(-18,5)],light),x=290,y=237,rotation=.3,opacity=0)
icons[3]=node('WaitingUserMark',line('Invitation',[(-7,-10),(-4,-15),(5,-15),(9,-11),(9,-5),(0,1),(0,5)],deep,3.5)+ell('InvitationDot',0,15,5,5,deep)+ell('InvitationHalo',0,0,42,52,'FFF4EFD4'),x=290,y=115,opacity=0)
icons[4]=node('DoneMark',line('DoneCheck',[(-13,0),(-3,10),(17,-13)],ink,5)+ell('DoneMedallion',0,0,47,47,'FFCFEBAB'),x=295,y=130,opacity=0)
icons[5]=node('ErrorMark',line('ConcernStem',[(0,-12),(0,3)],'FF8E6445',4)+ell('ConcernDot',0,12,5,5,'FF8E6445')+shape('ConcernPlate',path([(-23,18),(0,-24),(23,18)],radius=6),'FFFFD5A0'),x=297,y=129,opacity=0)
icons[6]=node('InterruptedMark',line('PauseL',[(-6,-11),(-6,11)],deep,5)+line('PauseR',[(6,-11),(6,11)],deep,5)+ell('PausePlate',0,0,43,43,'FFE5EBCF'),x=289,y=133,opacity=0)
icons[7]=node('QueuedMark',''.join(ell('QueueSeed'+str(i),i*15-15,0,8,12,deep if i==0 else 'FFAACCB0',rotation=.3) for i in range(3)),x=200,y=336,opacity=0)
icons[8]=node('ProviderMark',line('ProviderBranch',[(-20,6),(-7,-7),(7,-7),(20,6)],deep,2)+''.join(ell('Signal'+str(i),i*17-17,18,6,6,deep) for i in range(3)),x=298,y=110,opacity=0)
shadow=ell('GroundShadow',200,310,103,12,'16294D43')
# Every timeline resets the complete animated surface, preventing stale prior poses.
def base(mode):
 d={('Brisa','x'):200,('Brisa','y'):207,('Brisa','scaleX'):1,('Brisa','scaleY'):1,('Brisa','rotation'):0,('Head','rotation'):0,('Head','y'):-27,('Crest','rotation'):0,('WingL','rotation'):0,('WingR','rotation'):0,('EyeL','scaleY'):1,('EyeR','scaleY'):1,('EyeL','x'):-31,('EyeR','x'):31,('EyeL','rotation'):0,('EyeR','rotation'):0,('BrowL','opacity'):0,('BrowR','opacity'):0,('BrowL','rotation'):0,('BrowR','rotation'):0,('WorkingMark','rotation'):.3,('WorkingMark','y'):237,('GroundShadow','scaleX'):1}
 for i,n in enumerate(['ThinkingMark','WorkingMark','WaitingUserMark','DoneMark','ErrorMark','InterruptedMark','QueuedMark','ProviderMark'],1):d[(n,'opacity')]=int(i==mode)
 for n in ['ThoughtSmall','ThoughtMid','ThoughtBig','Signal0','Signal1','Signal2']:d[(n,'opacity')]=1
 if mode==1:d.update({('Head','rotation'):-.18,('EyeL','x'):-28,('EyeR','x'):34,('WingL','rotation'):-.3})
 if mode==2:d.update({('Head','rotation'):.08,('WingL','rotation'):-.45,('WingR','rotation'):.45,('EyeL','scaleY'):.82,('EyeR','scaleY'):.82})
 if mode==3:d.update({('Head','rotation'):.16,('WingR','rotation'):-1.65,('EyeL','scaleY'):1.05,('EyeR','scaleY'):1.05})
 if mode==4:d.update({('WingL','rotation'):1.0,('WingR','rotation'):-1.0,('EyeL','scaleY'):.3,('EyeR','scaleY'):.3,('EyeL','rotation'):-.16,('EyeR','rotation'):.16})
 if mode==5:d.update({('Head','rotation'):-.08,('Crest','rotation'):-.65,('BrowL','opacity'):1,('BrowR','opacity'):1,('BrowL','rotation'):-.3,('BrowR','rotation'):.3,('WingL','rotation'):-.2,('WingR','rotation'):.2})
 if mode==6:d.update({('WingL','rotation'):-.7,('WingR','rotation'):.7,('EyeL','scaleY'):.18,('EyeR','scaleY'):.18,('Brisa','y'):215,('Crest','rotation'):.25})
 if mode==7:d.update({('Brisa','y'):213,('EyeL','scaleY'):.6,('EyeR','scaleY'):.6,('Head','rotation'):.07})
 if mode==8:d.update({('Head','rotation'):-.12,('EyeL','x'):-27,('EyeR','x'):35,('WingL','rotation'):-.12,('WingR','rotation'):.12})
 return d
names=['idle','thinking','working','waitingUser','done','error','interrupted','queued','waitingProvider']
def cond(kind,prop,key,value):return f'<TransitionViewModelCondition opValue="equal"><TransitionPropertyViewModelComparator><BindableProperty{kind}><DataBindContext sourcePathIds="0:40-{prop}" propertyKey="{key}"/></BindableProperty{kind}></TransitionPropertyViewModelComparator><TransitionValue{kind}Comparator value="{value}"/></TransitionViewModelCondition>'
trans=''
for static in [False,True]:
 for mode in range(9):
  sid=500+mode+(9 if static else 0)
  trans+=f'<StateTransition stateToId="0:{sid}" duration="{0 if static else 120}">'+cond('Number','0:42',636,mode)+cond('Boolean','0:43',634,str(static).lower())+'</StateTransition>'
machine=f'<StateMachine name="MascotController" id="0:7"><StateMachineLayer name="Status" id="0:8"><EntryState x="0" y="0"><StateTransition stateToId="0:500"/></EntryState><AnyState x="0" y="-180">{trans}</AnyState><ExitState x="0" y="300"/>'
for static in [False,True]:
 for mode in range(9):
  sid=500+mode+(9 if static else 0)
  machine+=f'<AnimationState x="{200+mode*150}" y="{200 if static else 0}" animationId="0:{600+mode+(9 if static else 0)}" reset="true" id="0:{sid}"/>'
machine+='</StateMachineLayer></StateMachine>'
animations=''
for static in [False,True]:
 for mode,name in enumerate(names):
  duration=[180,180,60,180,70,70,30,240,180][mode]
  tracks={k:[(0,v)] for k,v in base(mode).items()}
  def motion(obj,prop,frames):tracks[(obj,prop)]=frames
  if not static:
   if mode==0:
    motion('Brisa','y',[(0,207),(90,203),(180,207)]);motion('Brisa','scaleY',[(0,1),(90,1.018),(180,1)]);motion('Crest','rotation',[(0,-.05),(90,.10),(180,-.05)])
    for e in ['EyeL','EyeR']:motion(e,'scaleY',[(0,1),(130,1),(136,.1),(142,1),(180,1)])
   if mode==1:
    motion('Head','rotation',[(0,-.18),(70,-.23),(140,.04),(180,-.18)]);motion('Crest','rotation',[(0,0),(90,.2),(180,0)])
    for i,n in enumerate(['ThoughtSmall','ThoughtMid','ThoughtBig']):motion(n,'opacity',[(0,.3),(30+i*20,1),(110+i*20,.3),(180,.3)])
   if mode==2:
    motion('WingL','rotation',[(0,-.45),(15,.30),(30,-.45),(45,.30),(60,-.45)]);motion('WingR','rotation',[(0,.30),(15,-.45),(30,.30),(45,-.45),(60,.30)])
    motion('Brisa','y',[(0,207),(15,204),(30,207),(45,204),(60,207)]);motion('WorkingMark','y',[(0,237),(30,216),(60,237)]);motion('WorkingMark','rotation',[(0,.3),(30,-.3),(60,.3)])
   if mode==3:motion('WingR','rotation',[(0,-1.65),(80,-1.45),(160,-1.65),(180,-1.65)])
   if mode==4:
    motion('Brisa','y',[(0,207),(10,214),(25,183),(40,207),(48,210),(70,207)]);motion('Brisa','scaleY',[(0,1),(10,.94),(25,1.05),(40,1),(48,.97),(70,1)]);motion('GroundShadow','scaleX',[(0,1),(25,.7),(40,1),(70,1)])
   if mode==5:motion('Head','rotation',[(0,-.08),(8,.09),(16,-.14),(24,.07),(32,-.08),(70,-.08)])
   if mode==6:motion('Brisa','y',[(0,207),(30,215)])
   if mode==7:motion('Crest','rotation',[(0,0),(120,.12),(240,0)])
   if mode==8:
    for i in range(3):motion('Signal'+str(i),'opacity',[(0,.25),(30+i*25,1),(70+i*25,.25),(180,.25)])
  dur=1 if static else duration
  aid=600+mode+(9 if static else 0)
  animations+=f'<LinearAnimation name="{name}{"_static" if static else ""}" id="0:{aid}" duration="{dur}" loopValue="{"oneShot" if static or mode in [4,5,6] else "loop"}">'
  grouped={}
  for (obj,prop),keys in tracks.items():grouped.setdefault(obj,[]).append((prop,keys))
  for obj,props in grouped.items():
   animations+=f'<KeyedObject objectId="{ident(obj)}">'
   for prop,keys in props:
    animations+=f'<KeyedProperty property="{prop}">'+''.join(f'<KeyFrameDouble frame="{f}" value="{v}" interpolationType="linear"/>' for f,v in keys)+'</KeyedProperty>'
   animations+='</KeyedObject>'
  animations+='</LinearAnimation>'
vm='<ViewModel name="MascotState" id="0:40" defaultInstanceId="0:41"><ViewModelPropertyNumber name="mode" id="0:42"/><ViewModelPropertyBoolean name="reducedMotion" id="0:43"/><ViewModelInstance name="Default" id="0:41" exports="true"><ViewModelInstanceNumber propertyValue="0" viewModelPropertyId="0:42"/><ViewModelInstanceBoolean propertyValue="false" viewModelPropertyId="0:43"/></ViewModelInstance></ViewModel>'
scene='<Rive version="1" kind="fragment"><Artboard name="Mascot" id="0:2" width="400" height="400" styleId="0:5" defaultStateMachineId="0:7" viewModelId="0:40" viewModelInstanceId="0:41"><LayoutComponentStyle name="Artboard Style" id="0:5"/>'+''.join(icons.values())+mascot+shadow+machine+animations+'</Artboard>'+vm+'</Rive>'
# Pretty print for designer inspection.
import xml.dom.minidom
P.joinpath('scene.rml').write_text(xml.dom.minidom.parseString(scene).toprettyxml(indent='    '))
P.joinpath('design-contract.json').write_text(__import__('json').dumps({'name':'Brisa','artboard':'Mascot','viewModel':'MascotState','stateMachine':'MascotController','mode':dict(enumerate(names)),'reducedMotion':'true selects one-frame static version of each mode','assets':'none','runtimeScripts':'none','dimensions':[400,400]},indent=2))
print('Wrote',P/'scene.rml', 'shape ids',counter-100)
