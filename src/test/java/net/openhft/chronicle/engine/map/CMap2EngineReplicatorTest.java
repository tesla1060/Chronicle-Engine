package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.NativeBytesStore;
import net.openhft.chronicle.engine.api.EngineReplication.ModificationIterator;
import net.openhft.chronicle.engine.api.EngineReplication.ReplicationEntry;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.ChronicleMapBuilder;
import net.openhft.chronicle.wire.TextWire;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static java.nio.ByteBuffer.allocateDirect;
import static net.openhft.chronicle.bytes.NativeBytesStore.wrap;
import static net.openhft.chronicle.hash.replication.SingleChronicleHashReplication.builder;

/**
 * Created by Rob Austin
 */

public class CMap2EngineReplicatorTest {

    /**
     * tests when the put has come locally from the server map
     */
    @Test
    public void testLocalPut() throws Exception {

        final CMap2EngineReplicator replicator = new CMap2EngineReplicator(null);

        ChronicleMap<String, String> map = ChronicleMapBuilder.of(String.class, String.class).
                replication(builder().engineReplication(replicator).createWithId((byte) 2)).create();

        final ModificationIterator modificationIterator = replicator.acquireModificationIterator((byte) 1);
        map.put("hello", "world");

        BlockingQueue<ReplicationEntry> q = new ArrayBlockingQueue<>(1);
        modificationIterator.forEach(e -> {
            q.add(e);
        });

        final ReplicationEntry entry = q.take();

        Assert.assertEquals("hello", new TextWire(entry.key().bytes()).getValueIn().text());

        final Bytes value = entry.value();
        Assert.assertEquals("world", (value == null) ? null : new TextWire(value).getValueIn().text());

        Assert.assertEquals(2, entry.identifier());

    }

    /**
     * tests when the put has come locally from another map with a remote identifer
     */
    @Test
    public void testRemotePut() throws Exception {

        final CMap2EngineReplicator replicator = new CMap2EngineReplicator(null);

        ChronicleMap map = ChronicleMapBuilder.of(String.class, String.class).
                replication(builder().engineReplication(replicator).createWithId((byte) 2)).create();

        final Bytes<ByteBuffer> key = NativeBytesStore.wrap(allocateDirect(1024)).bytes();
        final Bytes<ByteBuffer> value = wrap(allocateDirect(1024)).bytes();

        key.write("hello".getBytes());
        value.write("world".getBytes());

        key.flip();
        value.flip();

        final Bytes<ByteBuffer> keyBytes = key.bytes();
        final Bytes<ByteBuffer> valueBytes = value.bytes();

        replicator.put(keyBytes, valueBytes, (byte) 1, System.currentTimeMillis());

        Assert.assertEquals("world", map.get("hello"));

    }



}
