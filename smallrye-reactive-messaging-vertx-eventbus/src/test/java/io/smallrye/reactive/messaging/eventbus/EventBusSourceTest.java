package io.smallrye.reactive.messaging.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.After;
import org.junit.Test;

import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.smallrye.reactive.messaging.extension.MediatorManager;
import io.vertx.core.eventbus.DeliveryOptions;

public class EventBusSourceTest extends EventbusTestBase {

    private WeldContainer container;

    @After
    public void cleanup() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    public void testSource() {
        String topic = UUID.randomUUID().toString();
        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        EventBusSource source = new EventBusSource(vertx, new MapBasedConfig(config));

        List<EventBusMessage> messages = new ArrayList<>();
        Flowable.fromPublisher(source.source().buildRs()).cast(EventBusMessage.class).forEach(messages::add);
        AtomicInteger counter = new AtomicInteger();
        new Thread(() -> usage.produceIntegers(topic, 10, true, null,
                counter::getAndIncrement)).start();

        await().atMost(2, TimeUnit.MINUTES).until(() -> messages.size() >= 10);
        assertThat(messages.stream()
                .map(EventBusMessage::getPayload)
                .collect(Collectors.toList()))
                        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    public void testBroadcast() {
        String topic = UUID.randomUUID().toString();
        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        config.put("broadcast", true);
        EventBusSource source = new EventBusSource(vertx, new MapBasedConfig(config));

        List<EventBusMessage> messages1 = new ArrayList<>();
        List<EventBusMessage> messages2 = new ArrayList<>();
        Flowable<EventBusMessage> flowable = Flowable.fromPublisher(source.source().buildRs()).cast(EventBusMessage.class);
        flowable.forEach(messages1::add);
        flowable.forEach(messages2::add);

        AtomicInteger counter = new AtomicInteger();
        new Thread(() -> usage.produceIntegers(topic, 10, true, null,
                counter::getAndIncrement)).start();

        await().atMost(2, TimeUnit.MINUTES).until(() -> messages1.size() >= 10);
        await().atMost(2, TimeUnit.MINUTES).until(() -> messages2.size() >= 10);
        assertThat(messages1.stream().map(EventBusMessage::getPayload)
                .collect(Collectors.toList()))
                        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertThat(messages2.stream().map(EventBusMessage::getPayload)
                .collect(Collectors.toList()))
                        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    public void testAcknowledgement() {
        String topic = UUID.randomUUID().toString();
        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        config.put("use-reply-as-ack", true);
        EventBusSource source = new EventBusSource(vertx, new MapBasedConfig(config));

        Flowable<EventBusMessage> flowable = Flowable.fromPublisher(source.source().buildRs())
                .cast(EventBusMessage.class)
                .flatMapSingle(m -> Single
                        .fromFuture(m.ack().toCompletableFuture().thenApply(x -> m), Schedulers.computation()));

        List<EventBusMessage> messages1 = new ArrayList<>();
        flowable.forEach(messages1::add);

        AtomicBoolean acked = new AtomicBoolean();
        vertx.eventBus().send(topic, 1, rep -> {
            acked.set(true);
        });

        await().untilTrue(acked);
    }

    @Test
    public void testMessageHeader() {
        String topic = UUID.randomUUID().toString();
        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        EventBusSource source = new EventBusSource(vertx, new MapBasedConfig(config));

        Flowable<EventBusMessage> flowable = Flowable.fromPublisher(source.source().buildRs())
                .cast(EventBusMessage.class);

        List<EventBusMessage> messages1 = new ArrayList<>();
        flowable.forEach(messages1::add);

        vertx.eventBus().send(topic, 1, new DeliveryOptions()
                .addHeader("X-key", "value"));

        await().until(() -> messages1.size() == 1);

        EventBusMessage message = messages1.get(0);
        assertThat(message.getHeader("X-key")).contains("value");
        assertThat(message.getHeaders("X-key")).containsExactly("value");
        assertThat(message.getAddress()).isEqualTo(topic);
        assertThat(message.unwrap()).isNotNull();
        assertThat(message.getReplyAddress()).isEmpty();

    }

    @Test
    public void testABeanConsumingTheEventBusMessages() {
        ConsumptionBean bean = deploy();
        await().until(() -> container.select(MediatorManager.class).get().isInitialized());

        List<Integer> list = bean.getResults();
        assertThat(list).isEmpty();

        bean.produce();

        await().atMost(2, TimeUnit.MINUTES).until(() -> list.size() >= 10);
        assertThat(list).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    private ConsumptionBean deploy() {
        Weld weld = baseWeld();
        weld.addBeanClass(ConsumptionBean.class);
        weld.addBeanClass(VertxProducer.class);
        container = weld.initialize();
        return container.getBeanManager().createInstance().select(ConsumptionBean.class).get();
    }

}
