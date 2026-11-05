package com.betterclouddrive.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Digest of the actual bytes consumed from storage, independent of client metadata. */
public record ContentDigest(long size, String md5) {
    public static ContentDigest read(InputStream input) throws IOException {
        Stream stream = new Stream(input);
        byte[] buffer = new byte[64 * 1024];
        while (stream.read(buffer) != -1) { /* consume without retaining content */ }
        return stream.result();
    }

    public static final class Stream extends FilterInputStream {
        private final MessageDigest digest;
        private long size;

        public Stream(InputStream input) {
            super(input);
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("MD5 unavailable", e);
            }
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) { digest.update((byte) value); size++; }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count > 0) { digest.update(bytes, offset, count); size += count; }
            return count;
        }

        @Override
        public long skip(long length) throws IOException {
            byte[] buffer = new byte[(int) Math.min(64 * 1024L, Math.max(0, length))];
            long skipped = 0;
            while (skipped < length) {
                int count = read(buffer, 0, (int) Math.min(buffer.length, length - skipped));
                if (count == -1) break;
                skipped += count;
            }
            return skipped;
        }

        @Override public boolean markSupported() { return false; }
        @Override public synchronized void reset() throws IOException { throw new IOException("Digest stream cannot reset"); }

        public ContentDigest result() {
            return new ContentDigest(size, HexFormat.of().formatHex(digest.digest()));
        }
    }
}
