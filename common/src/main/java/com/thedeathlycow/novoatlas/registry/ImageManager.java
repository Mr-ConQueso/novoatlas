package com.thedeathlycow.novoatlas.registry;

import com.thedeathlycow.novoatlas.NovoAtlas;
import com.thedeathlycow.novoatlas.world.gen.MapImage;
import net.minecraft.core.Registry;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ImageManager {
    public static final ImageManager HEIGHTMAP = new ImageManager(NovoAtlasResourceKeys.HEIGHTMAP, MapImage.Type.HEIGHTMAP);
    public static final ImageManager BIOME_MAP = new ImageManager(NovoAtlasResourceKeys.BIOME_MAP, MapImage.Type.BIOME_MAP);

    private final ResourceKey<Registry<MapImage>> registryKey;
    private final MapImage.Type type;
    private final Map<ResourceKey<MapImage>, MapImage> registry = new IdentityHashMap<>();

    private ImageManager(ResourceKey<Registry<MapImage>> registryKey, MapImage.Type type) {
        this.registryKey = registryKey;
        this.type = type;
    }

    public void reload(ResourceManager resourceManager) {
        Map<ResourceKey<MapImage>, MapImage> updatedRegistry = new IdentityHashMap<>();
        String regPath = NovoAtlas.MOD_ID + "/" + registryKey.location().getPath();

        // 1. Setup persistent cache directory
        // Path cacheDir = FMLPaths.CONFIGDIR.get().resolve("novoatlas/cache");
        // Use this if FMLPaths continues to be problematic
        Path cacheDir = java.nio.file.Paths.get("config", "novoatlas", "cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            NovoAtlas.LOGGER.error("Failed to create cache directory", e);
        }

        // 2. Process PNGs
        var pngConverter = new FileToIdConverter(regPath, ".png");
        pngConverter.listMatchingResources(resourceManager).forEach((loc, res) -> {
            try (InputStream stream = res.open()) {
                BufferedImage image = ImageIO.read(stream);
                updatedRegistry.put(ResourceKey.create(registryKey, pngConverter.fileToId(loc)), MapImage.fromBufferedImage(image, this.type));
            } catch (IOException e) {
                NovoAtlas.LOGGER.error("Failed to load PNG: {}", loc, e);
            }
        });

        // 3. Process BINs with Persistent Caching
        var binConverter = new FileToIdConverter(regPath, ".bin");
        binConverter.listMatchingResources(resourceManager).forEach((loc, res) -> {
            try {
                String safeName = loc.getPath().replace("/", "_");
                Path cachedFile = cacheDir.resolve(safeName);

                // Check if the cache file exists. If it doesn't, we MUST copy it.
                // We don't need to check metadata size anymore, the mere existence of the file
                // is sufficient for this simple implementation.
                if (!Files.exists(cachedFile)) {
                    try (InputStream is = res.open();
                         FileOutputStream fos = new FileOutputStream(cachedFile.toFile())) {
                        is.transferTo(fos);
                    }
                }

                try (RandomAccessFile raf = new RandomAccessFile(cachedFile.toFile(), "r");
                     FileChannel channel = raf.getChannel()) {

                    MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                    updatedRegistry.put(ResourceKey.create(registryKey, binConverter.fileToId(loc)), MapImage.fromBinary(buffer, this.type));
                }
            } catch (IOException e) {
                NovoAtlas.LOGGER.error("Failed to map BIN image: {}", loc, e);
            }
        });

        this.registry.clear();
        this.registry.putAll(updatedRegistry);
        NovoAtlas.LOGGER.info("Reloaded map images for {} (Cache: {})", registryKey, cacheDir);
    }

    @Nullable
    public MapImage getImage(ResourceKey<MapImage> key) {
        return this.registry.get(key);
    }
}