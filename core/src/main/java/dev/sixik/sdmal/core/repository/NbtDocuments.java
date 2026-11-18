package dev.sixik.sdmal.core.repository;

import dev.sixik.sdmal.api.repository.RepositoryException;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Bounded, uncompressed NBT encoding shared by repository value and file codecs.
 */
public final class NbtDocuments {
    /**
     * Maximum encoded record size and NBT allocation budget in bytes.
     */
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    /**
     * Prevents construction.
     */
    private NbtDocuments() {
    }

    /**
     * @param tag detached compound
     * @return owned encoded bytes
     */
    public static byte[] encode(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                NbtIo.write(tag, output);
            }
            if (bytes.size() > MAX_BYTES)
                throw new RepositoryException("NBT record exceeds 16 MiB");
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new RepositoryException("Cannot encode NBT repository record", failure);
        }
    }

    /**
     * @param bytes bounded record bytes
     * @return independent compound
     */
    public static CompoundTag decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new RepositoryException("NBT record exceeds 16 MiB");
        //? if >=1.20.3 {
        /*NbtAccounter accountant = NbtAccounter.create(MAX_BYTES);
         *///?} else {
        NbtAccounter accountant = new NbtAccounter(MAX_BYTES);
        //?}
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag = NbtIo.read(input, accountant);
            if (tag == null || input.read() != -1)
                throw new RepositoryException("Invalid or trailing NBT repository data");
            return tag;
        } catch (IOException | RuntimeException failure) {
            throw new RepositoryException("Cannot decode NBT repository record", failure);
        }
    }
}
