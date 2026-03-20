package com.android.commands.monkey.ape.llm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

/**
 * Compresses and encodes screenshots for LLM consumption.
 *
 * Compresses and encodes screenshots for VLM consumption. Supports two modes:
 * resize (max-edge 1000px) and raw (original device resolution).
 *
 * The static calculateResizedDimensions() method is extracted so it can be tested
 * in JUnit without requiring Android Bitmap APIs.
 */
public class ImageProcessor {

    private static final int MAX_EDGE_PX = 1000;
    private static final int JPEG_QUALITY = 80;

    /**
     * Compress a PNG screenshot to JPEG, optionally resize, and return as base64.
     *
     * @param pngBytes raw PNG bytes from the screenshot capture
     * @param resize   if true, resize to MAX_EDGE_PX longest edge; if false, keep original resolution
     * @return base64-encoded JPEG string, or null if processing fails
     */
    public String processScreenshot(byte[] pngBytes, boolean resize) {
        if (pngBytes == null || pngBytes.length == 0) return null;

        try {
            // Decode PNG to Bitmap
            Bitmap original = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.length);
            if (original == null) return null;

            Bitmap toCompress;
            if (resize) {
                // Calculate target dimensions maintaining aspect ratio
                int[] dims = calculateResizedDimensions(original.getWidth(), original.getHeight(), MAX_EDGE_PX);
                int targetW = dims[0];
                int targetH = dims[1];

                if (targetW == original.getWidth() && targetH == original.getHeight()) {
                    toCompress = original;
                } else {
                    toCompress = Bitmap.createScaledBitmap(original, targetW, targetH, true);
                }
            } else {
                // Raw mode: no resize, send at original device resolution
                toCompress = original;
            }

            // Compress to JPEG
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            toCompress.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            byte[] jpegBytes = out.toByteArray();

            return Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Calculate new dimensions that fit within maxEdge × maxEdge while preserving
     * the original aspect ratio. If the image already fits, returns the original size.
     *
     * Extracted as a static method so it can be unit-tested without Android APIs.
     *
     * @param origWidth  original image width in pixels
     * @param origHeight original image height in pixels
     * @param maxEdge    maximum length for the longest edge
     * @return int[]{newWidth, newHeight}
     */
    public static int[] calculateResizedDimensions(int origWidth, int origHeight, int maxEdge) {
        if (origWidth <= 0 || origHeight <= 0 || maxEdge <= 0) {
            return new int[]{origWidth, origHeight};
        }
        if (origWidth <= maxEdge && origHeight <= maxEdge) {
            return new int[]{origWidth, origHeight};
        }
        double scale = (double) maxEdge / Math.max(origWidth, origHeight);
        int newWidth = (int) Math.round(origWidth * scale);
        int newHeight = (int) Math.round(origHeight * scale);
        // Ensure at least 1 pixel in each dimension
        newWidth = Math.max(1, newWidth);
        newHeight = Math.max(1, newHeight);
        return new int[]{newWidth, newHeight};
    }
}
