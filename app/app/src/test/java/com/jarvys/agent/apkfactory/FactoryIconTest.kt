package com.jarvys.agent.apkfactory

import android.graphics.BitmapFactory
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
class FactoryIconTest {
    private fun source()=JSONObject().put("schemaVersion",1).put("background","#102030")
        .put("shapes",JSONArray().put(JSONObject().put("type","circle").put("cx",96).put("cy",96).put("r",60).put("fill","#FFCC00")))
    private fun bytes(value:JSONObject)=value.toString().toByteArray()
    @Test fun vectorArtworkEncodesDeterministicallyAndDecodesAsPng() {
        val first=FactoryIcon.render("icon.json",bytes(source()))
        assertArrayEquals(first,FactoryIcon.render("icon.json",bytes(source())))
        val image=BitmapFactory.decodeByteArray(first,0,first.size)
        assertEquals(192,image.width);assertEquals(192,image.height)
        assertArrayEquals(first,FactoryIcon.render("icon.png",first))
        image.recycle()
    }
    @Test fun malformedOrOverlargeVectorCannotExecuteArbitraryContent() {
        for(change in listOf<(JSONObject)->Unit>({it.put("script","alert(1)")},{it.getJSONArray("shapes").getJSONObject(0).put("r",200)},
            {it.getJSONArray("shapes").getJSONObject(0).put("type","image")},{it.put("background","url(http://example.org)")})) {
            val value=source();change(value)
            assertThrows(Exception::class.java){FactoryIcon.render("icon.json",bytes(value))}
        }
    }
    @Test fun polygonsAndRectanglesStayBounded() {
        val value=source().put("shapes",JSONArray().put(JSONObject().put("type","polygon").put("fill","#FFFFFF")
            .put("points",JSONArray("[[10,10],[180,10],[96,180]]"))).put(JSONObject().put("type","rect").put("fill","#000000")
            .put("x",60).put("y",60).put("width",30).put("height",50)))
        assertTrue(FactoryIcon.render("icon.json",bytes(value)).size>0)
        value.getJSONArray("shapes").getJSONObject(1).put("width",180)
        assertThrows(Exception::class.java){FactoryIcon.render("icon.json",bytes(value))}
    }
    @Test fun indexedPngIsNormalizedToRuntimeSupportedPixelFormat() {
        fun chunk(type:String,data:ByteArray):ByteArray {
            val name=type.toByteArray(Charsets.US_ASCII)
            val crc=java.util.zip.CRC32().apply{update(name);update(data)}
            return java.nio.ByteBuffer.allocate(data.size+12).putInt(data.size).put(name).put(data).putInt(crc.value.toInt()).array()
        }
        val compressed=java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use{it.write(ByteArray(48*49))}
        val header=java.nio.ByteBuffer.allocate(13).putInt(48).putInt(48).put(8).put(3).put(0).put(0).put(0).array()
        val indexed=byteArrayOf(-119,80,78,71,13,10,26,10)+chunk("IHDR",header)+chunk("PLTE",byteArrayOf(-1,0,0))+
            chunk("IDAT",compressed.toByteArray())+chunk("IEND",byteArrayOf())
        val normalized=FactoryIcon.render("palette.png",indexed)
        assertTrue(normalized[25].toInt() in listOf(2,6))
        val image=BitmapFactory.decodeByteArray(normalized,0,normalized.size);assertEquals(48,image.width);image.recycle()
    }
    @Test fun fakeAndHugePngAreRejectedBeforeUnboundedAllocation() {
        assertThrows(Exception::class.java){FactoryIcon.render("icon.png",ByteArray(30))}
        assertThrows(Exception::class.java){FactoryIcon.render("icon.png",ByteArray(FactorySpec.MAX_FILE_BYTES+1))}
    }
}
