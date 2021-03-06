/*
 * Copyright (c) 2013, 2018 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.rope;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.core.Hashing;
import org.truffleruby.core.string.StringOperations;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class RopeCache {

    private final Hashing hashing;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final WeakHashMap<StringKey, BytesKey> javaStringToBytes = new WeakHashMap<>();
    private final WeakHashMap<BytesKey, WeakReference<Rope>> bytesToRope = new WeakHashMap<>();

    private final Set<BytesKey> keys = new HashSet<>();

    private int byteArrayReusedCount;
    private int ropesReusedCount;
    private int ropeBytesSaved;

    public RopeCache(Hashing hashing) {
        this.hashing = hashing;
    }

    public Rope getRopeUTF8(String string) {
        return getRope(string);
    }

    public Rope getRope(Rope string) {
        return getRope(string.getBytes(), string.getEncoding(), string.getCodeRange());
    }

    public Rope getRope(Rope string, CodeRange codeRange) {
        return getRope(string.getBytes(), string.getEncoding(), codeRange);
    }

    @TruffleBoundary
    public Rope getRope(String string) {
        final StringKey stringKey = new StringKey(string, hashing);

        lock.readLock().lock();

        try {
            final BytesKey key = javaStringToBytes.get(stringKey);

            if (key != null) {
                final WeakReference<Rope> ropeReference = bytesToRope.get(key);

                if (ropeReference != null) {
                    final Rope rope = ropeReference.get();

                    if (rope != null) {
                        return rope;
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();

        try {
            final Rope rope = StringOperations.encodeRope(string, UTF8Encoding.INSTANCE);

            BytesKey key = javaStringToBytes.get(stringKey);

            if (key == null) {
                key = new BytesKey(rope.getBytes(), UTF8Encoding.INSTANCE, hashing);
                javaStringToBytes.put(stringKey, key);
            }

            WeakReference<Rope> ropeReference = bytesToRope.get(key);

            if (ropeReference == null || ropeReference.get() == null) {
                ropeReference = new WeakReference<>(rope);
                bytesToRope.put(key, ropeReference);
            }

            return rope;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @TruffleBoundary
    public Rope getRope(byte[] bytes, Encoding encoding, CodeRange codeRange) {
        final BytesKey key = new BytesKey(bytes, encoding, hashing);

        lock.readLock().lock();

        try {
            final WeakReference<Rope> ropeReference = bytesToRope.get(key);

            if (ropeReference != null) {
                final Rope rope = ropeReference.get();

                if (rope != null) {
                    ++ropesReusedCount;
                    ropeBytesSaved += rope.byteLength();

                    return rope;
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        // The only time we should have a null encoding is if we want to find a rope with the same logical byte[] as
        // the one supplied to this method. If we've made it this far, no such rope exists, so return null immediately
        // to back out of the recursive call.
        if (encoding == null) {
            return null;
        }

        lock.writeLock().lock();

        try {
            final WeakReference<Rope> ropeReference = bytesToRope.get(key);

            if (ropeReference != null) {
                final Rope rope = ropeReference.get();

                if (rope != null) {
                    return rope;
                }
            }

            // At this point, we were unable to find a rope with the same bytes and encoding (i.e., a direct match).
            // However, there may still be a rope with the same byte[] and sharing a direct byte[] can still allow some
            // reference equality optimizations. So, do another search but with a marker encoding. The only guarantee
            // we can make about the resulting rope is that it would have the same logical byte[], but that's good enough
            // for our purposes.
            final Rope ropeWithSameBytesButDifferentEncoding = getRope(bytes, null, codeRange);

            final Rope rope;
            if (ropeWithSameBytesButDifferentEncoding != null) {
                rope = RopeOperations.create(ropeWithSameBytesButDifferentEncoding.getBytes(), encoding, codeRange);

                ++byteArrayReusedCount;
                ropeBytesSaved += rope.byteLength();
            } else {
                rope = RopeOperations.create(bytes, encoding, codeRange);
            }

            bytesToRope.put(key, new WeakReference<>(rope));

            // TODO (nirvdrum 30-Mar-16): Revisit this. The purpose is to keep all keys live so the weak rope table never expunges results. We don't want that -- we want something that naturally ties to lifetime. Unfortunately, the old approach expunged live values because the key is synthetic. See also FrozenStrings
            keys.add(key);

            return rope;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean contains(Rope rope) {
        final BytesKey key = new BytesKey(rope.getBytes(), rope.getEncoding(), hashing);

        lock.readLock().lock();

        try {
            return bytesToRope.get(key) != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getByteArrayReusedCount() {
        return byteArrayReusedCount;
    }

    public int getRopesReusedCount() {
        return ropesReusedCount;
    }

    public int getRopeBytesSaved() {
        return ropeBytesSaved;
    }

    public int totalRopes() {
        return bytesToRope.size();
    }

}
