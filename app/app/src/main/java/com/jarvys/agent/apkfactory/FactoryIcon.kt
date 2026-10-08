package com.jarvys.agent.apkfactory

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.graphics.toColorInt
import androidx.core.graphics.createBitmap
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Bounded geometric icons let Coding create artwork with ordinary text files; no SVG/script engine. */
internal object FactoryIcon {
    const val SIZE = 192
    fun render(path: String, bytes: ByteArray): ByteArray = if (path.endsWith(".png", true)) validatePng(bytes) else {
        require(path.endsWith(".json", true) && bytes.size <= 16 * 1024) { "Icon must be a PNG or bounded vector JSON file" }
        val json = FactoryJson.objectFrom(bytes, 16 * 1024)
        fields(json, setOf("schemaVersion", "background", "shapes"))
        require(json.opt("schemaVersion") == 1) { "Unsupported icon schemaVersion" }
        val shapes = json.opt("shapes") as? JSONArray ?: error("Icon shapes must be an array")
        require(shapes.length() in 1..32) { "Use 1–32 icon shapes" }
        val bitmap = createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(color(json.opt("background")))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until shapes.length()) {
                val shape = shapes.opt(i) as? JSONObject ?: error("Each icon shape must be an object")
                paint.color = color(shape.opt("fill"))
                when (shape.opt("type")) {
                    "circle" -> {
                        fields(shape, setOf("type", "cx", "cy", "r", "fill"))
                        val x = number(shape.opt("cx")); val y = number(shape.opt("cy")); val r = number(shape.opt("r"))
                        require(r > 0 && x-r >= 0 && y-r >= 0 && x+r <= SIZE && y+r <= SIZE) { "Icon circle exceeds its bounds" }
                        canvas.drawCircle(x, y, r, paint)
                    }
                    "rect" -> {
                        fields(shape, setOf("type", "x", "y", "width", "height", "fill"))
                        val x = number(shape.opt("x")); val y = number(shape.opt("y")); val w = number(shape.opt("width")); val h = number(shape.opt("height"))
                        require(w > 0 && h > 0 && x+w <= SIZE && y+h <= SIZE) { "Icon rectangle exceeds its bounds" }
                        canvas.drawRect(x, y, x+w, y+h, paint)
                    }
                    "polygon" -> {
                        fields(shape, setOf("type", "points", "fill"))
                        val points = shape.opt("points") as? JSONArray ?: error("Polygon points must be an array")
                        require(points.length() in 3..32) { "Polygon needs 3–32 points" }
                        val polygon = Path()
                        for (index in 0 until points.length()) {
                            val pair = points.opt(index) as? JSONArray ?: error("Point must be [x,y]")
                            require(pair.length() == 2) { "Point must be [x,y]" }
                            val x = number(pair.opt(0)); val y = number(pair.opt(1))
                            if (index == 0) polygon.moveTo(x,y) else polygon.lineTo(x,y)
                        }
                        polygon.close(); canvas.drawPath(polygon, paint)
                    }
                    else -> error("Unsupported icon shape; only circle, rect and polygon are available")
                }
            }
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode icon" }
            output.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun validatePng(bytes: ByteArray): ByteArray {
        require(bytes.size in 24..FactorySpec.MAX_FILE_BYTES && bytes.sliceArray(0..7).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) { "Icon is not a PNG" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth in 48..1024 && bounds.outHeight in 48..1024) { "Icon dimensions must be 48–1024 pixels per side" }
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("PNG icon cannot be decoded")
        return try {
            val output = ByteArrayOutputStream()
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not normalize PNG icon" }
            output.toByteArray()
        } finally { image.recycle() }
    }
    private fun color(value: Any?): Int {
        require(value is String && value.matches(Regex("#[0-9a-fA-F]{6}"))) { "Icon colors must be opaque #RRGGBB strings" }
        return value.toColorInt()
    }
    private fun number(value: Any?): Float {
        require(value is Number) { "Icon coordinates must be numbers" }
        val number = value.toDouble()
        require(number.isFinite() && number in 0.0..SIZE.toDouble()) { "Icon coordinate outside 0–192" }
        return number.toFloat()
    }
    private fun fields(json: JSONObject, expected: Set<String>) {
        require(json.keys().asSequence().toSet() == expected) { "Unexpected icon fields" }
    }
}
