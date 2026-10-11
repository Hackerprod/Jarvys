package com.jarvys.agent.apkfactory
import android.graphics.BitmapFactory
import android.graphics.Color
import com.jarvys.factory.contract.AdaptiveIconPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveIconRenderingTest {
    private fun source()=JSONObject().put("schemaVersion",1).put("shapes",JSONArray().put(JSONObject().put("type","rect")
        .put("x",0).put("y",0).put("width",192).put("height",192).put("fill","#FF0000")))
    private fun render(inset:Int)=FactoryIcon.renderAdaptive(AdaptiveIconPolicy("fg.json","#102030",inset),source().toString().toByteArray())
    @Test fun authoredCanvasIsPreservedUnlessPaddingWasExplicitlyRequested() {
        val full=render(0);val inset=render(20)
        val a=BitmapFactory.decodeByteArray(full.foreground(),0,full.foreground().size)
        val b=BitmapFactory.decodeByteArray(inset.foreground(),0,inset.foreground().size)
        val bg=BitmapFactory.decodeByteArray(full.background(),0,full.background().size)
        try { assertEquals(192,a.width);assertEquals(192,a.height);assertEquals(Color.RED,a.getPixel(0,0));assertEquals(0,Color.alpha(b.getPixel(0,0)))
            assertEquals(Color.RED,b.getPixel(96,96));assertEquals(Color.rgb(16,32,48),bg.getPixel(0,0));assertEquals(bg.getPixel(0,0),bg.getPixel(191,191))
        } finally { a.recycle();b.recycle();bg.recycle() }
        assertArrayEquals(full.foreground(),render(0).foreground());assertArrayEquals(inset.background(),render(20).background())
    }
    @Test fun pngCanvasCanBeUsedWithoutAddingSecondPadding() {
        val generated=render(20).foreground()
        val retained=FactoryIcon.renderAdaptive(AdaptiveIconPolicy("fg.png","#FFFFFF"),generated).foreground()
        assertArrayEquals(generated,retained)
    }
    @Test fun foregroundJsonIsTransparentAndCannotSmuggleBackgroundOrScripts() {
        for (field in listOf("background","script","xml")) {
            val json=source().put(field,"#FFFFFF")
            assertThrows(Exception::class.java){FactoryIcon.renderAdaptive(AdaptiveIconPolicy("fg.json","#FFFFFF"),json.toString().toByteArray())}
        }
        assertThrows(Exception::class.java){FactoryIcon.render("icon.json",source().toString().toByteArray())}
    }
}
