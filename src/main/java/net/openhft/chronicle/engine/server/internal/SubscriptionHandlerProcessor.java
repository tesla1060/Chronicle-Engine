package net.openhft.chronicle.engine.server.internal;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.map.KVSSubscription;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.subscribe;
import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.unSubscribe;
import static net.openhft.chronicle.engine.server.internal.SubscriptionHandlerProcessor.EventId.*;
import static net.openhft.chronicle.wire.CoreFields.reply;
import static net.openhft.chronicle.wire.CoreFields.tid;
import static net.openhft.chronicle.wire.WriteMarshallable.EMPTY;

/**
 * Created by Rob Austin
 */
public class SubscriptionHandlerProcessor {

    private RequestContext requestContext;
    private Queue<Consumer<Wire>> publisher;
    private AssetTree assetTree;
    final StringBuilder eventName = new StringBuilder();

    private final Map<Long, Object> tidToListener = new ConcurrentHashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionHandlerProcessor.class);
    private Wire outWire;
    private KVSSubscription subscription;

    void process(final Wire inWire,
                 final RequestContext requestContext,
                 final Queue<Consumer<Wire>> publisher,
                 final AssetTree assetTree, final long tid,
                 final Wire outWire, final KVSSubscription subscription) {
        this.outWire = outWire;
        this.subscription = subscription;
        this.requestContext = requestContext;
        this.publisher = publisher;
        this.assetTree = assetTree;
        dataConsumer.accept(inWire, tid);
    }

    private final BiConsumer<WireIn, Long> dataConsumer = new BiConsumer<WireIn, Long>() {

        @Override
        public void accept(final WireIn inWire, Long inputTid) {

            eventName.setLength(0);
            final ValueIn valueIn = inWire.readEventName(eventName);

            if (registerTopicSubscriber.contentEquals(eventName)) {

                final TopicSubscriber listener = new TopicSubscriber() {

                    @Override
                    public void onMessage(final Object topic, final Object message) throws InvalidSubscriberException {

                        publisher.add(publish -> {
                            publish.writeDocument(true, wire -> wire.writeEventName(tid).int64
                                    (inputTid));
                            publish.writeNotReadyDocument(false, wire -> wire.write(reply)
                                    .marshallable(m -> {
                                        m.write(() -> "topic").object(topic);
                                        m.write(() -> "message").object(message);
                                    }));
                        });
                    }
                };

                valueIn.marshallable(m -> {
                    final Class kClass = m.read(() -> "keyType").typeLiteral();
                    final Class vClass = m.read(() -> "valueType").typeLiteral();

                    tidToListener.put(inputTid, listener);
                    assetTree.registerTopicSubscriber(requestContext.name(), kClass, vClass, listener);
                });
                return;
            }

            if (unregisterTopicSubscriber.contentEquals(eventName))

            {
                TopicSubscriber listener = (TopicSubscriber) tidToListener.remove(inputTid);
                if (listener == null) {
                    LOG.warn("No subscriber to present to unsubscribe (" + inputTid + ")");
                    return;
                }
                assetTree.unregisterTopicSubscriber(requestContext.name(), listener);
                // no more data.
                publisher.add(publish -> {
                    publish.writeDocument(true, wire -> wire.writeEventName(tid).int64(inputTid));
                    publish.writeDocument(false, wire -> wire.write(reply).typedMarshallable(null));
                });

                return;
            }

            if (subscribe.contentEquals(eventName)) {
                Class eventClass = valueIn.typeLiteral();
                Subscriber<Object> listener = e -> {
                    publisher.add(publish -> {
                        publish.writeDocument(true, wire -> wire.writeEventName(CoreFields.tid).int64(inputTid));
                        publish.writeNotReadyDocument(false, wire -> wire.write(reply).object(e));
                    });
                };
                tidToListener.put(inputTid, listener);
                assetTree.registerSubscriber(requestContext.name(), eventClass, listener);

                return;
            }
            if (unSubscribe.contentEquals(eventName)) {
                Subscriber<Object> listener = (Subscriber) tidToListener.remove(inputTid);
                if (listener == null) {
                    LOG.warn("No subscriber to present to unsubscribe (" + inputTid + ")");
                    return;
                }
                assetTree.unregisterSubscriber(requestContext.name(), listener);
                // no more data.
                publisher.add(publish -> {
                    publish.writeDocument(true, wire -> wire.writeEventName(CoreFields.tid).int64(inputTid));
                    publish.writeDocument(false, wire -> wire.write(reply).typedMarshallable(null));
                });

                return;
            }

            outWire.writeDocument(true, wire -> outWire.writeEventName(tid).int64(inputTid));

            writeData(out -> {

                if (topicSubscriberCount.contentEquals(eventName)) {
                    outWire.writeEventName(reply).int8(subscription.topicSubscriberCount());
                    return;
                }

                if (keySubscriberCount.contentEquals(eventName)) {
                    outWire.writeEventName(reply).int8(subscription.keySubscriberCount());
                    return;
                }

                if (entrySubscriberCount.contentEquals(eventName)) {
                    outWire.writeEventName(reply).int8(subscription.entrySubscriberCount());
                }

            });
        }
    };

    /**
     * write and exceptions and rolls back if no data was written
     */
    void writeData(@NotNull Consumer<WireOut> c) {
        outWire.writeDocument(false, out -> {

            final long position = outWire.bytes().position();
            try {

                c.accept(outWire);
            } catch (Exception exception) {
                outWire.bytes().position(position);
                outWire.writeEventName(() -> "exception").throwable(exception);
            }

            // write 'reply : {} ' if no data was sent
            if (position == outWire.bytes().position()) {
                outWire.writeEventName(reply).marshallable(EMPTY);
            }
        });
        if (YamlLogging.showServerWrites)
            try {
                System.out.println("server-writes:\n" +
                        Wires.fromSizePrefixedBlobs(outWire.bytes(), 0, outWire.bytes().position()));
            } catch (Exception e) {

                System.out.println("server-writes:\n" +
                        Bytes.toString(outWire.bytes(), 0, outWire.bytes().position()));
            }
    }

    public enum EventId implements ParameterizeWireKey {
        unSubscribe,
        subscribe,
        registerTopicSubscriber,
        unregisterTopicSubscriber,
        entrySubscriberCount,
        topicSubscriberCount,
        keySubscriberCount;

        private final WireKey[] params;

        <P extends WireKey> EventId(P... params) {
            this.params = params;
        }

        @NotNull
        public <P extends WireKey> P[] params() {
            return (P[]) this.params;
        }
    }

}
