package com.thedeathlycow.novoatlas.world.gen;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.thedeathlycow.novoatlas.registry.ImageManager;
import com.thedeathlycow.novoatlas.registry.NovoAtlasResourceKeys;
import com.thedeathlycow.novoatlas.world.gen.biome.provider.ColorMapBiomeProvider;
import com.thedeathlycow.novoatlas.world.gen.biome.provider.LayeredMapBiomeProvider;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

public record MapInfo(
        MapImageReference heightMap,
        Optional<MapImageReference> fluidHeightMap,
        ColorMapBiomeProvider surfaceBiomes,
        Optional<LayeredMapBiomeProvider> caveBiomes,
        int startingY,
        int surfaceRange,
        Optional<MapScaleConfig> scaling,
        Optional<Integer> seaLevel
) {
    public MapInfo(
            MapImageReference heightMap,
            Optional<MapImageReference> fluidHeightMap,
            ColorMapBiomeProvider surfaceBiomes,
            Optional<LayeredMapBiomeProvider> caveBiomes,
            int startingY,
            int surfaceRange,
            Optional<MapScaleConfig> scaling
    ) {
        this(heightMap, fluidHeightMap, surfaceBiomes, caveBiomes, startingY, surfaceRange, scaling, Optional.empty());
    }

    public record MapImageReference(ResourceKey<MapImage> key, String mode) {
        public static final Codec<MapImageReference> HEIGHTMAP_CODEC = Codec.either(
                ResourceKey.codec(NovoAtlasResourceKeys.HEIGHTMAP),
                RecordCodecBuilder.<MapImageReference>create(instance -> instance.group(
                        // 1. Key comes first (matches the record constructor)
                        ResourceKey.codec(NovoAtlasResourceKeys.HEIGHTMAP).fieldOf("map").forGetter(MapImageReference::key),
                        // 2. Mode comes second
                        Codec.STRING.optionalFieldOf("mode", "default").forGetter(MapImageReference::mode)
                ).apply(instance, MapImageReference::new))
        ).xmap(
                either -> either.map(k -> new MapImageReference(k, "default"), ref -> ref),
                ref -> ref.mode().equals("default") ? Either.left(ref.key()) : Either.right(ref)
        );
    }

    public static final Codec<MapInfo> DIRECT_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    MapImageReference.HEIGHTMAP_CODEC
                            .fieldOf("height_map")
                            .forGetter(MapInfo::heightMap),
                    MapImageReference.HEIGHTMAP_CODEC
                            .optionalFieldOf("fluid_height_map")
                            .forGetter(MapInfo::fluidHeightMap),
                    ColorMapBiomeProvider.CODEC.codec()
                            .fieldOf("surface_biomes")
                            .forGetter(MapInfo::surfaceBiomes),
                    LayeredMapBiomeProvider.CODEC.codec()
                            .optionalFieldOf("cave_biomes")
                            .forGetter(MapInfo::caveBiomes),
                    Codec.INT
                            .fieldOf("starting_y")
                            .forGetter(MapInfo::startingY),
                    ExtraCodecs.POSITIVE_INT
                            .optionalFieldOf("surface_range", 16)
                            .forGetter(MapInfo::surfaceRange),
                    MapScaleConfig.CODEC
                            .optionalFieldOf("scaling")
                            .forGetter(MapInfo::scaling),
                    Codec.INT
                            .optionalFieldOf("sea_level")
                            .forGetter(MapInfo::seaLevel)
            ).apply(instance, MapInfo::new)
    );

    public static final Codec<Holder<MapInfo>> CODEC = RegistryFileCodec.create(NovoAtlasResourceKeys.MAP_INFO, DIRECT_CODEC);

    public static MapImage lookupHeightmap(MapImageReference ref) {
        MapImage image = ImageManager.HEIGHTMAP.getImage(ref.key());
        Objects.requireNonNull(image, "Missing height map image " + ref.key());

        // Strict enforcement: If binary is requested, crash if it loaded as a default PNG.
        if ("binary".equals(ref.mode()) && image.buffer() == null) {
            throw new IllegalStateException("Expected binary mode for " + ref.key() + " but found default map format!");
        }

        return image;
    }

    public static MapImage lookupBiomeMap(ResourceKey<MapImage> map) {
        return Objects.requireNonNull(ImageManager.BIOME_MAP.getImage(map), "Missing biome map image " + map);
    }

    public int getHeightMapElevation(int x, int z, int fallback) {
        return lookupHeightmap(this.heightMap).sample(x, z, this, fallback);
    }

    public int getHeightMapElevation(int x, int z) {
        return lookupHeightmap(this.heightMap).sample(x, z, this);
    }

    public int getFluidHeightMapElevation(int x, int z, int seaLevel) {
        if (this.fluidHeightMap.isPresent()) {
            return lookupHeightmap(this.fluidHeightMap.get()).sample(x, z, this, seaLevel);
        } else {
            return seaLevel;
        }
    }

    @NotNull
    public Holder<Biome> getBiome(int x, int y, int z, @NotNull Holder<Biome> defaultBiome) {
        if (this.caveBiomes.isPresent()) {
            Holder<Biome> caveBiome = this.getCaveBiome(x, y, z, this.caveBiomes.get());
            if (caveBiome != null) {
                return caveBiome;
            }
        }

        Holder<Biome> surfaceBiome = this.surfaceBiomes.getBiome(x, y, z, this);
        return surfaceBiome != null ? surfaceBiome : defaultBiome;
    }

    public float horizontalScale() {
        if (this.scaling.isPresent()) {
            return this.scaling.get().horizontalScale().value();
        } else {
            return 1.0f;
        }
    }

    public InterpolationStrategy interpolationStrategy() {
        if (this.scaling.isPresent()) {
            return this.scaling.get().horizontalScale().interpolation();
        } else {
            return MapScaleConfig.HorizontalConfig.DEFAULT.interpolation();
        }
    }

    public float verticalScale() {
        if (this.scaling.isPresent()) {
            return this.scaling.get().verticalScale();
        } else {
            return 1.0f;
        }
    }

    @Nullable
    private Holder<Biome> getCaveBiome(int x, int y, int z, LayeredMapBiomeProvider caveBiomes) {
        int height = this.getHeightMapElevation(x, z, Integer.MIN_VALUE);

        if (height == Integer.MIN_VALUE) {
            return null;
        }

        if (y <= height - this.surfaceRange) {
            Holder<Biome> caveBiome = caveBiomes.getBiome(x, y, z, this);
            if (caveBiome != null) {
                return caveBiome;
            }
        }

        return null;
    }
}
