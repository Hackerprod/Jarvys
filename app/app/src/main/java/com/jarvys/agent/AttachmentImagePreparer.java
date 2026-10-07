package com.jarvys.agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.util.Base64;
import android.util.Base64OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Memory-budgeted platform decoding; re-encoding strips EXIF/GPS before provider transmission. */
public final class AttachmentImagePreparer {
    // Decode + transform + encode + base64 buffers can coexist. This is a heap budget, not a file-size cap.
    static final long WORKING_BYTES_PER_PIXEL = 48;

    private AttachmentImagePreparer() { }

    static long availableHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(1L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
    }

    public static ConversationTurn.Image prepare(Context context, File file, String name, CancellationToken token) {
        return prepare(context, file, name, availableHeapBytes(), token);
    }

    static ConversationTurn.Image prepare(Context context, File file, String name,
                                           long memoryBudget, CancellationToken token) {
        if (file == null || !file.isFile()) throw failure(context, R.string.attachment_model_missing, name, null);
        int sampleFloor = 1;
        while (true) {
            token.throwIfCancelled();
            Bitmap decoded = null;
            Bitmap opaque = null;
            int width = 0;
            int height = 0;
            try {
                decoded = Build.VERSION.SDK_INT >= 28 ? Api28.decode(file, memoryBudget, sampleFloor)
                        : decodeLegacy(file, memoryBudget, sampleFloor);
                if (decoded == null) throw new IOException("The platform decoder returned no image");
                width = decoded.getWidth();
                height = decoded.getHeight();
                token.throwIfCancelled();
                opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(android.graphics.Color.WHITE);
                canvas.drawBitmap(decoded, 0, 0, null);
                decoded.recycle();
                decoded = null;
                String encoded = encode(opaque, Bitmap.CompressFormat.JPEG);
                token.throwIfCancelled();
                return new ConversationTurn.Image(encoded, width, height);
            } catch (IOException | IllegalArgumentException invalid) {
                throw failure(context, R.string.attachment_model_decode, name, invalid);
            } catch (OutOfMemoryError lowMemory) {
                if ((width == 1 && height == 1) || sampleFloor >= (1 << 30)) {
                    throw failure(context, R.string.attachment_model_memory, name, lowMemory);
                }
                sampleFloor *= 2;
                memoryBudget = Math.min(memoryBudget, availableHeapBytes());
            } finally {
                if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                if (opaque != null && !opaque.isRecycled()) opaque.recycle();
            }
        }
    }

    static ImageEditInput prepareForEdit(Context context, File file, long memoryBudget, CancellationToken token) {
        int sampleFloor = 1;
        while (true) {
            token.throwIfCancelled();
            Bitmap bitmap = null;
            int width = 0;
            int height = 0;
            try {
                if (file == null || !file.isFile()) throw new IOException("Missing selected image");
                bitmap = Build.VERSION.SDK_INT >= 28 ? Api28.decode(file, memoryBudget, sampleFloor)
                        : decodeLegacy(file, memoryBudget, sampleFloor);
                if (bitmap == null) throw new IOException("Could not decode selected image");
                width = bitmap.getWidth();
                height = bitmap.getHeight();
                token.throwIfCancelled();
                String encoded = encode(bitmap, Bitmap.CompressFormat.PNG);
                token.throwIfCancelled();
                return new ImageEditInput("image/png", encoded);
            } catch (IOException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException("The selected image is unavailable or cannot be decoded", invalid);
            } catch (OutOfMemoryError lowMemory) {
                if ((width == 1 && height == 1) || sampleFloor >= (1 << 30)) {
                    throw new IllegalStateException("Not enough memory to prepare the selected image", lowMemory);
                }
                sampleFloor *= 2;
                memoryBudget = Math.min(memoryBudget, availableHeapBytes());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }
    }

    private static String encode(Bitmap bitmap, Bitmap.CompressFormat format) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (Base64OutputStream base64 = new Base64OutputStream(output, Base64.NO_WRAP)) {
                if (!bitmap.compress(format, 100, base64)) throw new IOException("The platform could not encode image");
            }
            return output.toString(StandardCharsets.US_ASCII.name());
        }
    }

    static int sampleSize(int width, int height, long memoryBudget, int floor) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image dimensions");
        int sample = Math.max(1, floor);
        long pixelBudget = Math.max(1L, memoryBudget / WORKING_BYTES_PER_PIXEL);
        while (ceilDiv(width, sample) * ceilDiv(height, sample) > pixelBudget && sample < (1 << 30)) {
            sample *= 2;
        }
        return sample;
    }

    private static long ceilDiv(int value, int divisor) { return ((long) value + divisor - 1) / divisor; }

    static Bitmap decodeLegacy(File file, long memoryBudget, int floor) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Unsupported or corrupt image");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, memoryBudget, floor);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("Unsupported or corrupt image");
        try {
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException ignored) { }
            Matrix matrix = orientationMatrix(orientation);
            if (matrix.isIdentity()) return bitmap;
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (OutOfMemoryError | RuntimeException error) {
            bitmap.recycle();
            throw error;
        }
    }

    static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: break;
        }
        return matrix;
    }

    private static IllegalStateException failure(Context context, int message, String name, Throwable cause) {
        return new IllegalStateException(context.getString(message, name), cause);
    }

    private static final class Api28 {
        private Api28() { }
        static Bitmap decode(File file, long memoryBudget, int floor) throws IOException {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSampleSize(sampleSize(info.getSize().getWidth(), info.getSize().getHeight(), memoryBudget, floor));
                decoder.setOnPartialImageListener(error -> false);
            });
        }
    }
}
