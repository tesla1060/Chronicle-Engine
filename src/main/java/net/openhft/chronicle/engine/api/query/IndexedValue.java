/*
 * Copyright 2016 higherfrequencytrading.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.openhft.chronicle.engine.api.query;

import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.util.ObjectUtils;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/*
 * Created by rob on 27/04/2016.
 */
public class IndexedValue<V extends Marshallable> implements Demarshallable, Marshallable {

    private long index;
    private long timePublished;
    private long maxIndex;
    private boolean isEndOfSnapshot;

    @Nullable
    private V v;
    private boolean isSnapshot;


    private transient Object k;

    public IndexedValue() {
    }

    @UsedViaReflection
    private IndexedValue(@NotNull WireIn wire) {
        readMarshallable(wire);
    }

    IndexedValue(Object k, V v, long index) {
        this.v = v;
        this.k = k;
        this.index = index;
    }

    IndexedValue(V v, long index) {
        this.v = v;
        this.index = index;
    }

    /**
     * @return the maximum index that is currently available, you can compare this index with the
     * {@code index} to see how many records the currently even is behind. However you should avoid using this to
     * determine when you have reached the end of a snapshot because with illiquid data that has a aggressive filter,
     * you may never ( or only after a long time ), receive a message that has an index larger than {@code maxIndex}, as such you should use
     * net.openhft.chronicle.engine.api.query.IndexedValue#isEndOfSnapshot instead
     */
    public long maxIndex() {
        return maxIndex;
    }

    @NotNull
    IndexedValue maxIndex(long maxIndex) {
        this.maxIndex = maxIndex;
        return this;
    }

    /**
     * @return the {@code index} in the chronicle queue, of this event
     */
    public long index() {
        return index;
    }

    @NotNull
    public IndexedValue index(long index) {
        this.index = index;
        return this;
    }

    @Nullable
    public V v() {
        return v;
    }

    @NotNull
    public IndexedValue v(V v) {
        this.v = v;
        return this;
    }

    public Object k() {
        return k;
    }

    @NotNull
    public IndexedValue k(Object k) {
        this.k = k;
        return this;
    }

    @Override
    public void writeMarshallable(@NotNull WireOut wire) {
        wire.write("i").int64_0x(index);
        wire.write("v").typedMarshallable(v);
        wire.write("t").int64(timePublished);
        wire.write("m").int64(maxIndex);
        wire.write("e").bool(isEndOfSnapshot);

    }

    @Override
    public String toString() {
        return Marshallable.$toString(this);
    }

    public long timePublished() {
        return timePublished;
    }

    @NotNull
    IndexedValue timePublished(long timePublished) {
        this.timePublished = timePublished;
        return this;
    }


    private final Function<Class, ReadMarshallable> reuseFunction = new Function<Class, ReadMarshallable>() {
        private final Map<Class<? extends ReadMarshallable>, ReadMarshallable> map = new ConcurrentHashMap<>();

        @Override
        public ReadMarshallable apply(@NotNull Class aClass) {
            assert aClass != null;
            return map.computeIfAbsent(aClass, ObjectUtils::newInstance);
        }
    };


    @Override
    public void readMarshallable(@NotNull WireIn wire) throws IORuntimeException {
        index = wire.read(() -> "i").int64();
        ValueIn read = wire.read(() -> "v");
        v = read.typedMarshallable(reuseFunction);
        timePublished = wire.read(() -> "t").int64();
        maxIndex = wire.read(() -> "m").int64();
        isEndOfSnapshot = wire.read(() -> "e").bool();
    }


    /**
     * @return {@code true} may be received in response to a subscription,
     * if no data is available on the index queue then nothing will be returned.
     * A message will contain this flag to denote that this message is the
     * last message in a batch of messages that makes up the snapshot, and may be on a message
     * that has arrive to chronicle engine since you asked for the snapshot
     */
    public boolean isEndOfSnapshot() {
        return isEndOfSnapshot;
    }

    IndexedValue isEndOfSnapshot(boolean endOfSnapshot) {
        isEndOfSnapshot = endOfSnapshot;
        return this;
    }


}

