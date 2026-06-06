package com.eventledger.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Kafka configuration: topic declarations and a dedicated dead-letter
 * listener container factory.
 *
 * <h3>Topic beans</h3>
 * <p>Declaring {@link NewTopic} beans causes {@code KafkaAdmin} to verify (and if absent,
 * create) the corresponding topics on application startup. When a topic already exists
 * with matching settings the operation is a no-op — Kafka is idempotent for topic creation.</p>
 *
 * <h3>DLQ container factory</h3>
 * <p>The dead-letter topic receives generic {@code Map<String,Object>} payloads, not
 * {@code EventRequest} objects. A separate consumer factory is wired with
 * {@link JsonDeserializer} configured to produce {@code Object} (Map) values,
 * avoiding class-cast issues in {@link com.eventledger.kafka.DeadLetterConsumer}.</p>
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ── Topic declarations ────────────────────────────────────────────────────

    @Bean
    public NewTopic inboundTopic() {
        return TopicBuilder.name(KafkaTopics.INBOUND).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic processedTopic() {
        return TopicBuilder.name(KafkaTopics.PROCESSED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic deadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.DEAD_LETTER).partitions(1).replicas(1).build();
    }

    // ── DLQ-specific consumer factory & listener container ───────────────────

    /**
     * Consumer factory for the dead-letter topic.
     * Uses {@code Object} as the value type so the deserializer produces a
     * {@code LinkedHashMap} (the Jackson default for JSON objects), which the
     * {@link DeadLetterConsumer} can safely cast to {@code Map<String,Object>}.
     */
    @Bean
    public ConsumerFactory<String, Object> dlqConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "event-ledger-dlq-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.LinkedHashMap");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Listener container factory for the dead-letter consumer.
     * Named {@code dlqContainerFactory} — referenced in
     * {@link com.eventledger.kafka.DeadLetterConsumer#consumeDeadLetter}.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> dlqContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dlqConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
