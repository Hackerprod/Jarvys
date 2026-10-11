package com.jarvys.factory.contract;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdaptiveIconPolicyTest {
    @Test public void defaultKeepsAuthoredCanvasAndExplicitInsetsAreTyped() {
        AdaptiveIconPolicy p=new AdaptiveIconPolicy("artwork/foreground.png","#12aBef");
        assertEquals(0,p.insetPercent); assertEquals(p,new AdaptiveIconPolicy(p.foreground,p.background,0));
        assertEquals(20,new AdaptiveIconPolicy("artwork/layers.json","#FFFFFF",20).insetPercent);
    }
    @Test public void pathsCannotEscapeOrBecomeExecutableResources() {
        for(String path:new String[]{"", "/icon.png","../icon.png","art/../icon.png","art/.hidden.png","art//icon.png","art\\icon.png","a\n.png","icon.xml","icon.svg","icon.java","icon.png/","./icon.png"})
            assertThrows(IllegalArgumentException.class,()->new AdaptiveIconPolicy(path,"#FFFFFF"));
    }
    @Test public void colorsAreOpaqueAndInsetsBounded() {
        for(String color:new String[]{"red","#FFF","#00112233","url(x)","#GGHHII","#FFFFFF "})
            assertThrows(IllegalArgumentException.class,()->new AdaptiveIconPolicy("a.png",color));
        for(int inset:new int[]{-1,41,Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class,()->new AdaptiveIconPolicy("a.png","#FFFFFF",inset));
    }
}
