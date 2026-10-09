package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LauncherIconTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun drawable(id: Int) = app.resources.getDrawable(id, app.theme)
    private fun appRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(cwd, cwd.parentFile).first { File(it, "app/src/main/AndroidManifest.xml").isFile }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    @Test
    @Config(sdk = [24, 25, 26, 32, 35], qualifiers = "en-rUS-mdpi")
    fun manifestAndPackageManagerUseDedicatedLauncherResources() {
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        assertEquals(BuildConfig.APPLICATION_ID, app.packageName)
        assertEquals(android.R.style.Theme_Material_Light_NoActionBar, app.applicationInfo.theme)
        val manifest = File(appRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round).forEach { id ->
            val icon = drawable(id)
            if (Build.VERSION.SDK_INT >= 26) assertTrue(icon is AdaptiveIconDrawable)
            else assertTrue(icon is BitmapDrawable)
        }
    }

    @Test
    @Config(sdk = [24, 25], qualifiers = "en-rUS-mdpi")
    fun legacyLauncherAndRoundDecodeWithExpectedTransparentEdges() {
        listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round).forEach { id ->
            val icon = drawable(id) as BitmapDrawable
            assertEquals(48, icon.intrinsicWidth)
            assertEquals(48, icon.intrinsicHeight)
            val bitmap = icon.bitmap
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(255, Color.alpha(bitmap.getPixel(24, 24)))
        }
        val normal = (drawable(R.mipmap.ic_launcher) as BitmapDrawable).bitmap
        val round = (drawable(R.mipmap.ic_launcher_round) as BitmapDrawable).bitmap
        assertFalse(normal.sameAs(round))
    }

    @Test
    @Config(sdk = [26, 32, 35], qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun adaptiveLayersAreOpaqueBackgroundAndTransparentBitmap() {
        listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round).forEach { id ->
            val icon = drawable(id) as AdaptiveIconDrawable
            assertEquals(Color.rgb(17, 24, 39), (icon.background as ColorDrawable).color)
            val foreground = icon.foreground as BitmapDrawable
            assertEquals(108, foreground.intrinsicWidth)
            assertEquals(108, foreground.intrinsicHeight)
            assertEquals(0, Color.alpha(foreground.bitmap.getPixel(0, 0)))
            assertTrue(Color.alpha(foreground.bitmap.getPixel(54, 54)) in 248..255)
            if (Build.VERSION.SDK_INT >= 33) assertNull(icon.monochrome)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun densityAssetsAndOriginalArtworkMatchContract() {
        val root = appRoot()
        val source = File(root, "artwork/jarvys-app-icon-source.png")
        assertEquals("5e0f5ae21b58bb4fbdea80abc80af69cea3095354374293e5f1b06fe969558a4", sha(source))
        val original = BitmapFactory.decodeFile(source.path)
        assertEquals(1269, original.width)
        assertEquals(1240, original.height)
        assertTrue(original.hasAlpha())
        mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)
            .forEach { (density, scale) ->
                for (name in listOf("ic_launcher", "ic_launcher_round")) {
                    val bitmap = BitmapFactory.decodeFile(File(root, "app/src/main/res/mipmap-$density/$name.png").path)
                    assertEquals((48 * scale).toInt(), bitmap.width)
                    assertEquals(bitmap.width, bitmap.height)
                    assertTrue(bitmap.hasAlpha())
                }
                val foreground = BitmapFactory.decodeFile(File(root, "app/src/main/res/drawable-$density/ic_launcher_foreground.png").path)
                assertEquals((108 * scale).toInt(), foreground.width)
                assertEquals(foreground.width, foreground.height)
                assertTrue(foreground.hasAlpha())
            }
    }

    @Test fun notificationsAndFactoryKeepTheirOriginalResources() {
        val root = appRoot()
        assertEquals("fcf9571890d636a4e6d65e199bdd0ecf47174ea29735a34df041bd667a27154f",
            sha(File(root, "app/src/main/res/drawable/ic_jarvys.xml")))
        assertEquals("4a20adff2fb6a52ef3db2dd5e2f703f82aaa76bb901bea409804b51b9c3bed6f",
            sha(File(root, "apk-runtime/src/main/res/drawable-nodpi/factory_icon.png")))
        listOf("Autonomy.kt", "ApprovalNotificationCenter.kt").forEach { name ->
            assertTrue(File(root, "app/src/main/java/com/jarvys/agent/connectors/$name").readText()
                .contains(".setSmallIcon(R.drawable.ic_jarvys)"))
        }
        assertTrue(File(root, "apk-runtime/src/main/AndroidManifest.xml").readText()
            .contains("android:icon=\"@drawable/factory_icon\""))
    }

    @Test
    @Config(sdk = [26, 32, 35], qualifiers = "en-rUS-xxxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun adaptiveAndRoundNativePreviewsUseSameRobot() {
        fun render(id: Int): Bitmap = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable(id).apply { setBounds(0, 0, 288, 288); draw(Canvas(bitmap)) }
        }
        val launcher = render(R.mipmap.ic_launcher)
        val round = render(R.mipmap.ic_launcher_round)
        assertTrue(launcher.sameAs(round))
        var cyan = 0
        var light = 0
        for (y in 0 until launcher.height) for (x in 0 until launcher.width) {
            val pixel = launcher.getPixel(x, y)
            if (Color.alpha(pixel) > 240) {
                if (Color.red(pixel) < 110 && Color.green(pixel) > 150 && Color.blue(pixel) > 180) cyan++
                if (Color.red(pixel) > 170 && Color.green(pixel) > 170 && Color.blue(pixel) > 170) light++
            }
        }
        assertTrue("Robot cyan lights must be visible", cyan > 500)
        assertTrue("Robot white body must be visible", light > 3000)
        val destination = System.getenv("JARVYS_UX32_CAPTURE_DIR")?.takeIf { it.isNotBlank() }
        if (destination != null) {
            val directory = File(destination).apply { check(isDirectory || mkdirs()) }
            listOf("launcher" to launcher, "round" to round).forEach { (name, bitmap) ->
                File(directory, "${BuildConfig.FLAVOR}-sdk${Build.VERSION.SDK_INT}-$name.png")
                    .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        }
    }
}
