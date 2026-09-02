package org.openphc.cce.common.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.openphc.cce.common.event.IntelligenceTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class IntelligenceTriggerProducer {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceTriggerProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;
    private final long confirmTimeoutMs;
    private final Counter publishedCounter;
    private final Timer publishDurationTimer;

    public IntelligenceTriggerProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                       KafkaTopicProperties topicProperties,
                                       MeterRegistry meterRegistry,
                                       @Value("${cce.intelligence.publish-confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topicProperties.getIntelligenceTriggers();
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.publishedCounter = Counter.builder("cce.events.intelligence.published")
                .description("Intelligence trigger events published to Kafka")
                .register(meterRegistry);
        this.publishDurationTimer = Timer.builder("cce.intelligence.publish.duration")
                .description("Time to publish intelligence trigger event to Kafka")
                .register(meterRegistry);
    }

    /**
     * Publish an intelligence trigger event and wait for the broker to acknowledge it.
     *
     * <p>The caller records the event in the same transaction as the completion or deviation that
     * fired it, and persists whether it reached Kafka. Confirming the send is what makes that flag
     * trustworthy — with {@code acks=all} the acknowledgement means the record is replicated, so a
     * {@code true} here is a durable publish.
     *
     * <p>Never throws: a failed or timed-out send is reported as {@code false} so the caller can
     * commit the event log (keeping the payload replayable) rather than rolling back the clinical
     * work that triggered it.
     *
     * @return true if the broker acknowledged the record within the confirm timeout
     */
    public boolean publishAndConfirm(IntelligenceTriggerEvent event) {
        try {
            publish(event).get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while awaiting publish acknowledgement: id={}", event.getId());
            return false;
        } catch (TimeoutException e) {
            log.error("Publish not acknowledged within {}ms: id={}, topic={}",
                    confirmTimeoutMs, event.getId(), topic);
            return false;
        } catch (ExecutionException e) {
            // publish()'s completion handler already logged the underlying cause
            return false;
        }
    }

    /**
     * Publish an intelligence trigger event to Kafka.
     * Asynchronous: logs failures but does not throw, so the main transaction is not affected.
     *
     * @param event the intelligence trigger event
     * @return a CompletableFuture with the send result
     */
    public CompletableFuture<SendResult<String, Object>> publish(IntelligenceTriggerEvent event) {
        String key = event.getIntelligenceEventId().toString();

        Timer.Sample sample = Timer.start();
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, event);

        future.whenComplete((result, ex) -> {
            sample.stop(publishDurationTimer);
            if (ex == null) {
                publishedCounter.increment();
                log.info("Published intelligence trigger event: id={}, intelligenceEventId={}, topic={}, partition={}, offset={}",
                        event.getId(), key, topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish intelligence trigger event: id={}, intelligenceEventId={}, topic={}",
                        event.getId(), key, topic, ex);
            }
        });

        return future;
    }
}
