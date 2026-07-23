package dev.dev48v.inventory.exactlyonce;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

// Day 32 — the DEDUP KEY: a Kafka record's physical coordinates (topic, partition, offset). This triple is
// globally unique across the log, so it is the natural identity of "this exact delivery". It is modelled as
// an @Embeddable composite id (used as the @EmbeddedId of ProcessedEvent) rather than a surrogate key,
// because the identity IS the business meaning here — there is nothing to generate, and existsById(key) is
// the whole dedup check.
//
// A JPA composite-id class must be Serializable and implement equals()/hashCode() on ALL id fields — that is
// how the persistence context compares keys and how existsById locates a row. Getting this wrong (e.g.
// forgetting a field) would silently break dedup, so all three fields participate.
@Embeddable
public class ProcessedEventKey implements Serializable {

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "partition_id", nullable = false)
    private int partitionId;

    @Column(name = "kafka_offset", nullable = false)
    private long kafkaOffset;

    // JPA requires a no-arg constructor to rebuild keys on load.
    protected ProcessedEventKey() {
    }

    public ProcessedEventKey(String topic, int partitionId, long kafkaOffset) {
        this.topic = topic;
        this.partitionId = partitionId;
        this.kafkaOffset = kafkaOffset;
    }

    public String getTopic() {
        return topic;
    }

    public int getPartitionId() {
        return partitionId;
    }

    public long getKafkaOffset() {
        return kafkaOffset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProcessedEventKey that)) {
            return false;
        }
        return partitionId == that.partitionId
                && kafkaOffset == that.kafkaOffset
                && Objects.equals(topic, that.topic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topic, partitionId, kafkaOffset);
    }

    @Override
    public String toString() {
        return topic + "-" + partitionId + "@" + kafkaOffset;
    }
}
