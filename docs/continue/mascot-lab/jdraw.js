/* Jarvys, Canvas 2D. Reference coordinates; feet pivot (627,1186). */
(function (g) {
  var CX = 627, PY = 1186;
  function mix(a,b,t) { return a+(b-a)*t; }
  function rgb(a,b,t) { return 'rgb('+Math.round(mix(a[0],b[0],t))+','+Math.round(mix(a[1],b[1],t))+','+Math.round(mix(a[2],b[2],t))+')'; }
  function radial(c,x,y,r,s) { var a=c.createRadialGradient(x,y,0,x,y,r),i; for(i=0;i<s.length;i++)a.addColorStop(s[i][0],s[i][1]);return a; }
  function linear(c,x,y,X,Y,s) { var a=c.createLinearGradient(x,y,X,Y),i;for(i=0;i<s.length;i++)a.addColorStop(s[i][0],s[i][1]);return a; }
  function ell(c,x,y,rx,ry,rot) { c.beginPath();c.ellipse(x,y,Math.max(.01,rx),Math.max(.01,ry),rot||0,0,Math.PI*2); }
  function blob(c,x,y,rx,ry,s,rot) { c.save();c.translate(x,y);c.rotate(rot||0);c.scale(1,ry/rx);c.fillStyle=radial(c,0,0,rx,s);c.beginPath();c.arc(0,0,rx,0,Math.PI*2);c.fill();c.restore(); }
  function glow(c,x,y,rx,ry,color,a,rot) { blob(c,x,y,rx,ry,[[0,'rgba('+color+','+a+')'],[.48,'rgba('+color+','+(a*.61)+')'],[1,'rgba('+color+',0)']],rot); }
  function rim(c,path,w,a,fade) {
    var i,t,up,dn,aa,gr;
    c.save();path(c);c.clip();for(i=0;i<18;i++){
      t=i/18;aa=.045*a;up=rgb([176,189,244],[243,252,255],t*t);dn=rgb([155,176,235],[205,228,255],t*t);
      up=up.replace('rgb(','rgba(').replace(')',','+aa+')');dn=dn.replace('rgb(','rgba(').replace(')',','+aa+')');
      if(fade){gr=linear(c,0,fade[0],0,fade[1],[[0,path===curl?up:dn],[1,'rgba(210,234,255,0)']]);}
      else{gr=linear(c,0,325,0,790,[[0,up],[1,dn]]);}
      path(c);c.lineWidth=w*Math.pow(1-t,1.45);c.strokeStyle=gr;c.stroke();
    }
    path(c);c.lineWidth=3.2;c.strokeStyle=fade?linear(c,0,fade[0],0,fade[1],[[0,path===curl?'rgba(236,251,255,.65)':'rgba(191,225,255,.58)'],[1,'rgba(220,244,255,0)']]):linear(c,0,325,0,790,[[0,'rgba(245,253,255,.82)'],[1,'rgba(200,229,255,.72)']]);c.stroke();c.restore();
  }
  function head(c) {
    c.beginPath();c.moveTo(614,237);c.bezierCurveTo(552,256,470,287,425,338);
    c.bezierCurveTo(373,370,340,419,316,472);c.bezierCurveTo(300,526,302,609,313,674);
    c.bezierCurveTo(320,735,345,770,398,785);c.bezierCurveTo(448,805,514,793,554,795);
    c.bezierCurveTo(608,806,664,803,712,794);c.bezierCurveTo(766,793,822,802,868,781);
    c.bezierCurveTo(924,757,939,717,944,656);c.bezierCurveTo(959,586,966,527,945,480);
    c.bezierCurveTo(918,423,869,356,816,307);c.bezierCurveTo(786,270,716,235,614,237);c.closePath();
  }
  function body(c) {
    c.beginPath();c.moveTo(503,780);c.bezierCurveTo(467,795,433,830,419,871);
    c.bezierCurveTo(399,920,416,959,426,984);c.bezierCurveTo(425,1039,444,1081,480,1121);
    c.bezierCurveTo(512,1158,547,1192,575,1188);c.bezierCurveTo(611,1185,624,1149,623,1094);
    c.bezierCurveTo(625,1089,630,1090,631,1095);c.bezierCurveTo(628,1146,644,1187,677,1188);
    c.bezierCurveTo(713,1191,756,1146,786,1103);c.bezierCurveTo(818,1060,830,1016,825,979);
    c.bezierCurveTo(851,950,849,899,828,861);c.bezierCurveTo(807,819,777,793,745,782);
    c.bezierCurveTo(665,766,580,768,503,780);c.closePath();
  }
  function earMain(c) {
    c.beginPath();c.moveTo(363,445);c.bezierCurveTo(318,459,296,496,258,528);
    c.bezierCurveTo(217,560,163,581,143,628);c.bezierCurveTo(129,653,128,685,139,707);
    c.bezierCurveTo(155,735,196,722,223,709);c.bezierCurveTo(262,687,286,654,304,615);
    c.bezierCurveTo(331,578,366,523,363,445);c.closePath();
  }
  function earSmall(c) {
    c.beginPath();c.moveTo(345,559);c.bezierCurveTo(302,599,278,640,245,674);c.bezierCurveTo(234,691,239,711,233,727);
    c.bezierCurveTo(227,748,220,764,232,776);c.bezierCurveTo(246,798,286,783,310,762);
    c.bezierCurveTo(355,730,368,673,367,616);c.bezierCurveTo(368,591,359,573,345,559);c.closePath();
  }
  function earInner(c) {
    c.beginPath();c.moveTo(322,541);c.bezierCurveTo(304,589,281,614,239,625);
    c.bezierCurveTo(194,638,165,648,155,678);c.bezierCurveTo(147,700,156,716,175,720);
    c.bezierCurveTo(231,720,280,661,303,610);c.bezierCurveTo(317,583,321,558,322,541);c.closePath();
  }
  function drawEar(c,side,ang) {
    c.save();if(side>0){c.translate(1254,0);c.scale(-1,1);}c.translate(340,545);c.rotate(ang);c.translate(-340,-545);
    c.fillStyle=linear(c,330,611,251,784,[[0,'#dddff4'],[.42,'#b4b9ef'],[.78,'#c1bcf6'],[1,'#d7d9fa']]);earSmall(c);c.fill();
    c.save();earSmall(c);c.clip();glow(c,295,740,52,110,'193,126,246',.74,.5);glow(c,246,765,48,45,'178,220,255',.8);c.restore();rim(c,earSmall,25,1.15);
    c.fillStyle=linear(c,305,495,188,706,[[0,'#e5e2f1'],[.4,'#dfdcf0'],[.75,'#d2d5f4'],[1,'#c6e7fc']]);earMain(c);c.fill();
    c.save();earMain(c);c.clip();glow(c,268,567,97,104,'255,252,247',.62,-.5);
    c.fillStyle=linear(c,274,577,202,723,[[0,'#717cff'],[.35,'#9891fc'],[.65,'#c9a3f8'],[1,'#b7ecff']]);earInner(c);c.fill();
    glow(c,189,667,45,47,'221,159,247',.7);glow(c,234,610,93,29,'241,238,253',.31,-.47);glow(c,243,704,74,35,'158,242,255',.88,-.58);
    c.fillStyle='rgba(242,250,255,.75)';ell(c,239,656,1.6,1.6);c.fill();ell(c,200,690,1.1,1.1);c.fill();c.restore();rim(c,earMain,23,1.2);c.restore();
  }
  function curl(c) {
    c.beginPath();c.moveTo(486,401);c.bezierCurveTo(448,397,420,378,426,343);
    c.bezierCurveTo(431,302,467,277,511,252);c.bezierCurveTo(561,226,612,200,610,161);
    c.bezierCurveTo(614,133,599,109,579,104);c.bezierCurveTo(562,99,550,112,553,125);
    c.bezierCurveTo(557,139,545,144,532,136);c.bezierCurveTo(510,124,507,108,517,86);
    c.bezierCurveTo(530,58,558,49,588,49);c.bezierCurveTo(643,47,702,78,744,123);
    c.bezierCurveTo(787,168,810,222,798,270);c.bezierCurveTo(791,315,744,351,689,380);
    c.bezierCurveTo(619,416,551,437,486,401);c.closePath();
  }
  function curlBlue(c) {
    c.beginPath();c.moveTo(431,333);c.bezierCurveTo(443,297,475,272,514,250);
    c.bezierCurveTo(569,222,613,198,610,160);c.bezierCurveTo(614,133,599,109,579,104);
    c.bezierCurveTo(562,99,550,112,553,125);c.bezierCurveTo(557,139,545,144,532,136);
    c.bezierCurveTo(510,124,507,108,517,86);c.bezierCurveTo(530,58,558,49,588,49);
    c.bezierCurveTo(635,47,673,64,696,98);c.bezierCurveTo(724,145,714,187,682,218);
    c.bezierCurveTo(631,264,535,268,477,303);c.bezierCurveTo(450,320,438,340,431,351);c.closePath();
  }
  var HEAD_FIELDS=[[450,440,280,280,0.7,215,206,207],[770,440,330,260,0.9,255,251,245],[620,820,420,200,0.9,176,166,172],[350,610,94,260,0.85,190,188,210],[925,620,85,180,0.7,237,230,230],[500,390,145,100,0.75,213,207,215],[620,480,220,270,0.45,249,236,217],[825,690,140,100,0.65,236,220,214],[420,700,130,100,0.65,173,176,209],[650,685,230,95,0.65,253,241,222],[460,790,140,65,0.65,158,168,227],[820,790,125,65,0.65,173,186,239],[600,800,160,65,0.45,174,170,206],[390,365,110,105,0.8,208,221,255],[850,335,135,110,0.8,225,232,253]];
  var HAND_FIELDS=[{"base":[255,238,213],"fields":[[535,891,115,135,0.9,242,236,230],[432,928,86,136,0.8,166,178,225],[510,1018,150,100,0.8,154,159,193],[480,805,120,100,0.8,189,188,220],[600,924,60,110,0.55,217,224,252]]},{"base":[255,255,229],"fields":[[535,891,115,135,0.9,241,232,231],[432,928,86,136,0.8,218,216,239],[510,1018,150,100,0.8,174,168,193],[480,805,120,100,0.8,220,213,226],[600,924,60,110,0.55,142,141,202]]}];
  function materialField(c,d,off) {
    var color=d[5]+','+d[6]+','+d[7],a=d[4],yy=d[1]+(off||0)*Math.max(0,Math.min(1,(d[1]-790)/200));
    blob(c,d[0],yy,d[2],d[3],[[0,'rgba('+color+','+a+')'],[.3,'rgba('+color+','+(a*.85)+')'],[.6,'rgba('+color+','+(a*.4)+')'],[.85,'rgba('+color+','+(a*.07)+')'],[1,'rgba('+color+',0)']]);
  }
  function headMaterial(c) {
    c.fillStyle=linear(c,0,220,0,330,[[0,'#f7f1ec'],[1,'rgb(227,224,234)']]);c.fillRect(120,0,1010,830);
    for(var i=0;i<HEAD_FIELDS.length;i++)materialField(c,HEAD_FIELDS[i],0);
  }
  function drawSwirl(c,sway) {
    c.save();c.translate(613,365);c.rotate(sway*.075);c.translate(-613,-365);
    c.save();curl(c);c.clip();headMaterial(c);
    glow(c,744,227,68,137,'255,249,246',.74,.36);glow(c,780,211,25,108,'220,198,252',.48,-.25);
    c.fillStyle=linear(c,0,45,0,335,[[0,'#9cbbf5'],[.3,'#9abdfa'],[.54,'#a0c6fa'],[.72,'rgba(166,224,254,.94)'],[.88,'rgba(191,235,253,.65)'],[1,'rgba(213,242,252,0)']]);curlBlue(c);c.fill();
    glow(c,692,147,51,83,'174,153,250',.64,-.3);glow(c,581,189,69,94,'166,244,255',.83,.5);
    glow(c,576,62,108,28,'201,214,255',.68,.1);glow(c,535,111,31,27,'185,230,255',.65);glow(c,510,282,99,37,'204,249,255',.44,-.55);
    var stars=[[585,83,1],[633,95,1.3],[668,118,1.7],[644,146,1.5],[597,200,1.1],[663,197,1.9],[576,233,1.2],[550,124,1],[665,156,.9],[622,71,.8]],i;
    for(i=0;i<stars.length;i++){var p=stars[i];c.fillStyle='rgba(245,255,255,.9)';ell(c,p[0],p[1],p[2],p[2]);c.fill();}
    c.restore();rim(c,curl,22,.8,[292,387]);c.restore();
  }
  function lid(c,o) {
    var sx=mix(-60,-71,o),sy=mix(15,12,o);
    c.beginPath();c.moveTo(sx,sy);
    c.bezierCurveTo(mix(-42.5,-76,o),mix(26.5,-46.5,o),mix(-20.25,-37,o),mix(32.5,-78,o),mix(1.5,0,o),mix(32.625,-78,o));
    c.bezierCurveTo(mix(23.25,40,o),mix(32.75,-78,o),mix(44.5,72,o),mix(27,-46.5,o),mix(60,72,o),sy);
    c.bezierCurveTo(mix(44.5,73,o),mix(27,60,o),mix(23.25,47,o),mix(32.75,76,o),mix(1.5,10,o),mix(32.625,76,o));
    c.bezierCurveTo(mix(-20.25,-31,o),mix(32.5,78,o),mix(-42.5,-67,o),mix(26.5,63.2,o),sx,sy);c.closePath();
  }
  function drawEye(c,x,side,P) {
    var o=Math.max(0,Math.min(1.15,P.open)), ox=side<0?12:-12, lx=P.lx*13,ly=P.ly*11,dir=side<0?1:-1;
    c.save();c.translate(x,605);c.lineJoin='round';c.lineCap='round';
    if(o<.001||P.happy>.5){c.lineCap='round';c.lineWidth=7;c.strokeStyle='#51313d';c.beginPath();c.moveTo(-60,15);if(P.happy>.5)c.bezierCurveTo(-37,-31,33,-31,60,15);else c.bezierCurveTo(-25,38,29,39,60,15);c.stroke();c.restore();return;}
    glow(c,0,-1,90,94,'95,60,74',.3*o);
    c.fillStyle=linear(c,-70,0,65,25,[[0,'#cfcae4'],[.35,'#eee8f2'],[.68,'#f6f2f8'],[1,'#d1cce5']]);lid(c,o);c.fill();c.lineWidth=9;c.strokeStyle=linear(c,0,mix(15,-85,o),0,mix(40,55,o),[[0,'#301b27'],[.38,'#39232e'],[.7,'rgba(67,40,54,.45)'],[1,'rgba(77,44,60,0)']]);lid(c,o);c.stroke();
    c.save();lid(c,o);c.clip();
    c.save();c.translate(ox+lx,3+ly);ell(c,0,0,63,73);c.clip();
    c.fillStyle=radial(c,7*dir,-16,86,[[0,'#100c22'],[.4,'#18112f'],[.64,'#25205b'],[.83,'#32398e'],[1,'#535acf']]);c.fillRect(-70,-90,140,180);
    glow(c,-33*dir,27,34,48,'89,151,252',.84);glow(c,34*dir,31,28,40,'104,75,245',.73);glow(c,-9*dir,52,62,34,'154,241,255',.98);glow(c,0,66,40,13,'189,248,255',.72);
    blob(c,8*dir,-7,46,54,[[0,'rgba(10,9,27,.96)'],[.65,'rgba(11,10,30,.88)'],[1,'rgba(11,10,30,0)']]);
    glow(c,-35*dir,-48,22,35,'86,83,181',.23,-.3*dir);
    var dots=[[-35,-32,2.1],[-46,4,2],[44,5,1.8],[-7,-7,.8],[33,29,1.5],[-28,44,1.3],[24,57,1.2],[-43,26,1.4],[36,-30,1.1]],i;
    for(i=0;i<dots.length;i++){var q=dots[i];c.fillStyle='#d8f3ff';ell(c,q[0]*dir,q[1],q[2],q[2]);c.fill();}
    var gx=side<0?28:-24;glow(c,gx,-37,17,18,'247,212,244',.25);c.fillStyle='#fff9f5';ell(c,gx,-37,11.5,14,side<0?-.55:.55);c.fill();
    c.restore();
    c.fillStyle=linear(c,0,-80,0,-45,[[0,'rgba(24,14,36,.72)'],[1,'rgba(30,17,40,0)']]);c.fillRect(-85,-105,170,80);c.restore();
    c.restore();
  }
  function drawBrow(c,x,side,raise,worry) {
    c.save();c.translate(x,476-raise*18);c.rotate(side*worry*.35);if(side>0)c.scale(-1,1);
    c.fillStyle=linear(c,0,-12,0,15,[[0,'#b4a8b9'],[1,'#91889f']]);c.beginPath();c.moveTo(-24,14);c.bezierCurveTo(-21,0,-1,-11,15,-10);c.bezierCurveTo(26,-10,28,-3,19,0);c.bezierCurveTo(2,2,-12,8,-22,15);c.bezierCurveTo(-24,16,-25,16,-24,14);c.fill();c.restore();
  }
  function drawMouth(c,P) {
    var m=P.mouth,mo=Math.max(0,P.mopen);c.save();c.translate(629,665);c.lineJoin='round';c.lineCap='round';c.lineWidth=6;
    if(mo>.04){var w=36+mo*20,h=10+mo*63;c.beginPath();if(m>=0){c.moveTo(-w,0);c.bezierCurveTo(-w*.45,11,w*.45,11,w,0);c.bezierCurveTo(w*.88,h,-w*.85,h,-w,0);}else{c.moveTo(-w,12);c.bezierCurveTo(-w*.7,-14,w*.7,-14,w,12);c.bezierCurveTo(w*.7,12+h,-w*.7,12+h,-w,12);}c.closePath();c.fillStyle='#402838';c.fill();c.strokeStyle='#432a35';c.lineWidth=3;c.stroke();c.save();c.clip();glow(c,3,m>=0?h:22+h,w*.9,h*.8,'214,126,148',.86);c.restore();}
    else{c.beginPath();c.moveTo(-43,0);c.quadraticCurveTo(0,m*61,43,0);c.strokeStyle='rgba(119,80,96,.18)';c.lineWidth=11;c.stroke();c.strokeStyle='#422c35';c.lineWidth=6;c.stroke();}c.restore();
  }
  function hand(c,off) {
    c.beginPath();c.moveTo(490,790);c.bezierCurveTo(452,809,416,852+off*.12,411,900+off*.3);
    c.bezierCurveTo(399,963+off*.7,436,999+off,492,1016+off);c.bezierCurveTo(547,1033+off,589,1016+off,594,982+off);
    c.bezierCurveTo(603,931+off,558,882+off*.55,526,839+off*.15);c.bezierCurveTo(511,817,509,802,490,790);c.closePath();
  }
  function drawHand(c,side,off) {
    c.save();if(side>0){c.translate(1254,0);c.scale(-1,1);}var path=function(q){hand(q,off);};
    var mat=HAND_FIELDS[side<0?0:1],i;c.fillStyle='rgb('+mat.base.join(',')+')';path(c);c.fill();
    c.save();path(c);c.clip();for(i=0;i<mat.fields.length;i++)materialField(c,mat.fields[i],off);c.restore();rim(c,path,35,.8,[1013,866]);c.restore();
  }
  function diamond(c,r) {c.beginPath();c.moveTo(0,-r);c.bezierCurveTo(4,-r,9,-r*.37,r*.86,-2);c.quadraticCurveTo(r*.91,0,r*.86,2);c.bezierCurveTo(17,r*.28,10,r*.62,0,r);c.bezierCurveTo(-10,r*.62,-17,r*.28,-r*.86,2);c.quadraticCurveTo(-r*.91,0,-r*.86,-2);c.bezierCurveTo(-9,-r*.37,-4,-r,0,-r);c.closePath();}
  function drawStar(c,P) {
    var s=Math.max(.2,P.star),r=P.red;c.save();c.translate(629,861);c.rotate(P.spin||0);
    glow(c,0,0,105*s,110*s,r>.5?'255,133,153':'132,190,255',.55);
    c.scale(s,s);c.fillStyle=radial(c,0,0,62,[[0,'#f6e8fa'],[.25,rgb([179,187,246],[255,191,204],r)],[.75,rgb([115,172,247],[251,131,153],r)],[1,rgb([174,221,255],[255,184,195],r)]]);diamond(c,55);c.fill();
    c.strokeStyle=r>.5?'#ffd4dc':'#bdffff';c.lineWidth=2;diamond(c,55);c.stroke();glow(c,0,0,38,44,'255,241,255',.85);
    c.save();c.scale(.37,.54);diamond(c,38);c.fillStyle='#fff9ff';c.fill();c.restore();c.restore();
  }
  g.JARVYS_DEFAULT={y:0,sx:1,sy:1,tilt:0,earL:0,earR:0,swirl:0,lx:0,ly:0,open:1,happy:0,mouth:.6,mopen:0,blush:.5,star:1,browL:0,browR:0,worry:0,handL:0,handR:0,red:0,spin:0};
  g.drawJarvys=function(c,P){
    P=P||g.JARVYS_DEFAULT;c.save();c.translate(CX,PY);c.translate(0,P.y);c.rotate(P.tilt);c.scale(P.sx,P.sy);c.translate(-CX,-PY);
    c.fillStyle=linear(c,0,785,0,1191,[[0,'#c4bdcf'],[.3,'#e5dfe0'],[.56,'#c3bdc8'],[.75,'#d3cbce'],[1,'#a5aed3']]);body(c);c.fill();
    c.save();body(c);c.clip();glow(c,530,1030,111,63,'110,101,140',.42);glow(c,724,1030,111,63,'110,101,140',.36);glow(c,545,1101,75,99,'241,230,214',.26,-.5);glow(c,725,1095,80,105,'249,237,218',.36,.5);glow(c,436,1060,68,139,'126,157,224',.38,-.35);glow(c,628,1095,48,101,'116,137,209',.4);c.restore();rim(c,body,39,1.4);
    drawEar(c,-1,P.earL);drawEar(c,1,P.earR);
    drawHand(c,-1,P.handL);drawHand(c,1,P.handR);drawStar(c,P);
    c.save();head(c);c.clip();headMaterial(c);c.restore();rim(c,head,77,1.1);
    drawSwirl(c,P.swirl);
    glow(c,420,695,83,46,'255,160,169',P.blush*.85);glow(c,843,695,78,43,'255,166,170',P.blush*.95);
    c.fillStyle='rgba(255,249,241,'+(P.blush*.95)+')';ell(c,413,692,2.2,2.2);c.fill();ell(c,429,700,1.2,1.2);c.fill();ell(c,845,692,2.2,2.2);c.fill();ell(c,859,700,1.1,1.1);c.fill();
    drawBrow(c,488,-1,P.browL,P.worry);drawBrow(c,777,1,P.browR,P.worry);drawEye(c,468,-1,P);drawEye(c,786,1,P);drawMouth(c,P);c.restore();
  };
})(window);
