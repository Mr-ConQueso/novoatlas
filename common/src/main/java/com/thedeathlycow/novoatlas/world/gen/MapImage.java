package com.thedeathlycow.novoatlas.world.gen;

import net.minecraft.util.Mth;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.MappedByteBuffer;

public record MapImage(
        int width,
        int height,
        int[][] pixels,
        MappedByteBuffer buffer,
        Type type
) {
    public enum Type {
        BIOME_MAP,
        HEIGHTMAP
    }

    public static MapImage fromBufferedImage(BufferedImage image, Type type) {
        int width = image.getWidth();
        int height = image.getHeight();

        int[][] pixels = type == Type.BIOME_MAP ? getColorPixels(image, width, height) : getGrayScalePixels(image, width, height);

        return new MapImage(width, height, pixels, null, type);
    }

    public static MapImage fromBinary(MappedByteBuffer buffer, Type type) {
        // Read the 8-byte header written by the Python script
        int width = buffer.getInt(0);
        int height = buffer.getInt(4);

        // Safety Net: If dimensions are wildly impossible, the header is missing or corrupted.
        // This prevents the silent integer overflow and gives you a clear error in the console.
        if (width <= 0 || height <= 0 || width > 100000 || height > 100000) {
            throw new IllegalStateException("Corrupted MapImage header! Read Width: " + width +
                    ", Height: " + height + ". Ensure you ran the latest Python script to add the 8-byte header.");
        }

        return new MapImage(width, height, null, buffer, type);
    }

    public int getPixel(int x, int z) {
        if (this.buffer != null) {
            // Cast to 'long' guarantees the multiplication never overflows Java's 32-bit limits
            long index = ((long) z * this.width) + x;
            int byteOffset = 8; // Skip the 8-byte header

            if (this.type == Type.HEIGHTMAP) {
                // Cast the final position back to int, as MappedByteBuffer requires an int index
                return this.buffer.getShort((int) (byteOffset + index * 2)) & 0xFFFF;
            } else {
                return this.buffer.getInt((int) (byteOffset + index * 4)) >>> 8;
            }
        } else {
            return this.pixels[x][z];
        }
    }

    private static int[][] getGrayScalePixels(BufferedImage image, int width, int height) {
        int[][] pixels = new int[width][height];
        Raster raster = image.getRaster();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                pixels[x][y] = raster.getSample(x, y, 0);
            }
        }

        return pixels;
    }

    private static int[][] getColorPixels(BufferedImage image, int width, int height) {
        int[] data = new int[width * height];
        image.getRGB(0, 0, width, height, data, 0, width);

        int x = 0;
        int y = 0;
        int[][] pixels = new int[width][height];

        for (int datum : data) {
            if (x >= width) {
                x = 0;
                y++;
            }
            pixels[x++][y] = datum & 0xffffff;
        }

        return pixels;
    }

    public int sample(int x, int z, MapInfo info) {
        return this.sample(x, z, info, Integer.MIN_VALUE);
    }

    public int sample(int x, int z, MapInfo info, int fallback) {
        double xR = (x / info.horizontalScale()) + this.width() / 2.0;
        double zR = (z / info.horizontalScale()) + this.height() / 2.0;

        if (xR < 0 || zR < 0 || xR >= this.width() || zR >= this.height()) {
            return fallback;
        }

        if (this.type == Type.HEIGHTMAP) {
            double height = info.interpolationStrategy().interpolate(xR, zR, this);
            return Mth.floor(info.verticalScale() * height + info.startingY());
        } else {
            int truncatedX = Mth.floor(xR);
            int truncatedZ = Mth.floor(zR);
            return this.getPixel(truncatedX, truncatedZ);
        }
    }

    public int getTruncated(double x, double z) {
        int truncatedX = Mth.floor(x);
        int truncatedZ = Mth.floor(z);
        return this.getPixel(truncatedX, truncatedZ);
    }
}