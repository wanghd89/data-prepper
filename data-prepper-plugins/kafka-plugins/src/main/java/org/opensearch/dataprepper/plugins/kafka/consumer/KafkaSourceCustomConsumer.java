/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.dataprepper.plugins.kafka.consumer;

import com.amazonaws.services.schemaregistry.exception.AWSSchemaRegistryException;
import com.amazonaws.services.schemaregistry.serializers.json.JsonDataWithSchema;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.lang3.Range;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.opensearch.dataprepper.buffer.common.BufferAccumulator;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSet;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSetManager;
import org.opensearch.dataprepper.model.buffer.Buffer;
import org.opensearch.dataprepper.model.buffer.SizeOverflowException;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.event.EventMetadata;
import org.opensearch.dataprepper.model.log.JacksonLog;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.plugins.kafka.configuration.KafkaKeyMode;
import org.opensearch.dataprepper.plugins.kafka.configuration.KafkaSourceConfig;
import org.opensearch.dataprepper.plugins.kafka.configuration.TopicConfig;
import org.opensearch.dataprepper.plugins.kafka.util.KafkaTopicMetrics;
import org.opensearch.dataprepper.plugins.kafka.util.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * * A utility class which will handle the core Kafka consumer operation.
 */
public class KafkaSourceCustomConsumer implements Runnable, ConsumerRebalanceListener {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaSourceCustomConsumer.class);
    private static final Long COMMIT_OFFSET_INTERVAL_MS = 300000L;
    private static final int DEFAULT_NUMBER_OF_RECORDS_TO_ACCUMULATE = 1;
    static final String DEFAULT_KEY = "message";

    private volatile long lastCommitTime;
    private KafkaConsumer consumer= null;
    private AtomicBoolean shutdownInProgress;
    private final String topicName;
    private final TopicConfig topicConfig;
    private MessageFormat schema;
    private final BufferAccumulator<Record<Event>> bufferAccumulator;
    private final Buffer<Record<Event>> buffer;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonFactory jsonFactory = new JsonFactory();
    private Map<TopicPartition, OffsetAndMetadata> offsetsToCommit;
    private Map<TopicPartition, Long> ownedPartitionsEpoch;
    private Set<TopicPartition> partitionsToReset;
    private final AcknowledgementSetManager acknowledgementSetManager;
    private final Map<Integer, TopicPartitionCommitTracker> partitionCommitTrackerMap;
    private List<Map<TopicPartition, CommitOffsetRange>> acknowledgedOffsets;
    private final boolean acknowledgementsEnabled;
    private final Duration acknowledgementsTimeout;
    private final KafkaTopicMetrics topicMetrics;
    private long metricsUpdatedTime;
    private final AtomicInteger numberOfAcksPending;

    public KafkaSourceCustomConsumer(final KafkaConsumer consumer,
                                     final AtomicBoolean shutdownInProgress,
                                     final Buffer<Record<Event>> buffer,
                                     final KafkaSourceConfig sourceConfig,
                                     final TopicConfig topicConfig,
                                     final String schemaType,
                                     final AcknowledgementSetManager acknowledgementSetManager,
                                     KafkaTopicMetrics topicMetrics) {
        this.topicName = topicConfig.getName();
        this.topicConfig = topicConfig;
        this.shutdownInProgress = shutdownInProgress;
        this.consumer = consumer;
        this.buffer = buffer;
        this.topicMetrics = topicMetrics;
        this.topicMetrics.register(consumer);
        this.offsetsToCommit = new HashMap<>();
        this.ownedPartitionsEpoch = new HashMap<>();
        this.metricsUpdatedTime = Instant.now().getEpochSecond();
        this.acknowledgedOffsets = new ArrayList<>();
        this.acknowledgementsTimeout = Duration.ofSeconds(Integer.MAX_VALUE);
        // If the timeout value is different from default value, then enable acknowledgements automatically.
        this.acknowledgementsEnabled = sourceConfig.getAcknowledgementsEnabled();
        this.acknowledgementSetManager = acknowledgementSetManager;
        this.partitionCommitTrackerMap = new HashMap<>();
        this.partitionsToReset = Collections.synchronizedSet(new HashSet<>());
        this.schema = MessageFormat.getByMessageFormatByName(schemaType);
        Duration bufferTimeout = Duration.ofSeconds(1);
        this.bufferAccumulator = BufferAccumulator.create(buffer, DEFAULT_NUMBER_OF_RECORDS_TO_ACCUMULATE, bufferTimeout);
        this.lastCommitTime = System.currentTimeMillis();
        this.numberOfAcksPending = new AtomicInteger(0);
    }

    private long getCurrentTimeNanos() {
        Instant now = Instant.now();
        return now.getEpochSecond()*1000000000+now.getNano();
    }

    public void updateOffsetsToCommit(final TopicPartition partition, final OffsetAndMetadata offsetAndMetadata, Range<Long> offsetRange) {
        long min = offsetRange.getMinimum();
        long max = offsetRange.getMaximum();
        topicMetrics.getNumberOfRecordsCommitted().increment(max - min + 1);
        if (Objects.isNull(offsetAndMetadata)) {
            return;
        }
        synchronized (offsetsToCommit) {
            offsetsToCommit.put(partition, offsetAndMetadata);
        }
    }

    private AcknowledgementSet createAcknowledgementSet(Map<TopicPartition, CommitOffsetRange> offsets) {
        AcknowledgementSet acknowledgementSet =
                acknowledgementSetManager.create((result) -> {
                    numberOfAcksPending.decrementAndGet();
                    if (result == true) {
                        topicMetrics.getNumberOfPositiveAcknowledgements().increment();
                        synchronized(this) {
                            acknowledgedOffsets.add(offsets);
                        }
                    } else {
                        topicMetrics.getNumberOfNegativeAcknowledgements().increment();
                        synchronized(this) {
                            offsets.forEach((partition, offsetRange) -> {
                                partitionsToReset.add(partition);
                            });
                        }
                    }
                }, acknowledgementsTimeout);
        return acknowledgementSet;
    }

    public <T> void consumeRecords() throws Exception {
        try {
            ConsumerRecords<String, T> records = consumer.poll(topicConfig.getThreadWaitingTime().toMillis()/2);
            if (Objects.nonNull(records) && !records.isEmpty() && records.count() > 0) {
                Map<TopicPartition, CommitOffsetRange> offsets = new HashMap<>();
                AcknowledgementSet acknowledgementSet = null;
                if (acknowledgementsEnabled) {
                    acknowledgementSet = createAcknowledgementSet(offsets);
                }
                iterateRecordPartitions(records, acknowledgementSet, offsets);
                if (!acknowledgementsEnabled) {
                    offsets.forEach((partition, offsetRange) ->
                            updateOffsetsToCommit(partition,
                                    new OffsetAndMetadata(offsetRange.getOffsets().getMaximum() + 1),
                                    offsetRange.getOffsets()));
                } else {
                    acknowledgementSet.complete();
                    numberOfAcksPending.incrementAndGet();
                }
            }
        } catch (AuthenticationException e) {
            LOG.warn("Authentication error while doing poll(). Will retry after 10 seconds", e);
            topicMetrics.getNumberOfPollAuthErrors().increment();
            Thread.sleep(10000);
        } catch (RecordDeserializationException e) {
            LOG.warn("Deserialization error - topic {} partition {} offset {}",
                    e.topicPartition().topic(), e.topicPartition().partition(), e.offset());
            if (e.getCause() instanceof AWSSchemaRegistryException) {
                LOG.warn("AWSSchemaRegistryException: {}. Retrying after 30 seconds", e.getMessage());
                Thread.sleep(30000);
            } else {
                LOG.warn("Seeking past the error record", e);
                consumer.seek(e.topicPartition(), e.offset()+1);
            }
            topicMetrics.getNumberOfDeserializationErrors().increment();
        }
    }

    private void resetOffsets() {
        if (partitionsToReset.size() > 0) {
            partitionsToReset.forEach(partition -> {
                try {
                    final OffsetAndMetadata offsetAndMetadata = consumer.committed(partition);
                    if (Objects.isNull(offsetAndMetadata)) {
                        consumer.seek(partition, 0L);
                    } else {
                        LOG.info("Seeking partition {} to {}", partition, offsetAndMetadata.offset());
                        consumer.seek(partition, offsetAndMetadata);
                    }
                    partitionCommitTrackerMap.remove(partition.partition());
                } catch (Exception e) {
                    LOG.error("Failed to seek to last committed offset upon negative acknowledgement {}", partition, e);
                }
            });
            partitionsToReset.clear();
        }
    }

    void processAcknowledgedOffsets() {

        acknowledgedOffsets.forEach(offsets -> {
            offsets.forEach((partition, offsetRange) -> {

                if (getPartitionEpoch(partition) == offsetRange.getEpoch()) {
                    try {
                        int partitionId = partition.partition();
                        if (!partitionCommitTrackerMap.containsKey(partitionId)) {
                            OffsetAndMetadata committedOffsetAndMetadata = consumer.committed(partition);
                            Long committedOffset = Objects.nonNull(committedOffsetAndMetadata) ? committedOffsetAndMetadata.offset() : null;
                            LOG.info("Tracking offsets for partition{} starting with committedOffset {}", partition,
                                    committedOffset);
                            partitionCommitTrackerMap.put(partitionId, new TopicPartitionCommitTracker(partition, committedOffset));
                        }
                        OffsetAndMetadata offsetAndMetadata =
                                partitionCommitTrackerMap.get(partitionId).addCompletedOffsets(offsetRange.getOffsets());
                        updateOffsetsToCommit(partition, offsetAndMetadata, offsetRange.getOffsets());
                    } catch (Exception e) {
                        LOG.error("Failed committed offsets upon positive acknowledgement {}", partition, e);
                    }
                }
            });
        });
        acknowledgedOffsets.clear();
    }

    private void commitOffsets(boolean forceCommit) {
        if (topicConfig.getAutoCommit()) {
            return;
        }
        processAcknowledgedOffsets();
        long currentTimeMillis = System.currentTimeMillis();
        if (!forceCommit && (currentTimeMillis - lastCommitTime) < topicConfig.getCommitInterval().toMillis()) {
            return;
        }
        synchronized (offsetsToCommit) {
            if (offsetsToCommit.isEmpty()) {
                return;
            }
            try {
                consumer.commitSync(offsetsToCommit);
            } catch (Exception e) {
                LOG.error("Failed to commit offsets in topic {}", topicName, e);
            }
            offsetsToCommit.clear();
            lastCommitTime = currentTimeMillis;
        }
    }

    Map<TopicPartition, OffsetAndMetadata> getOffsetsToCommit() {
        return offsetsToCommit;
    }

    @Override
    public void run() {
        consumer.subscribe(Arrays.asList(topicName), this);
        Set<TopicPartition> partitions = consumer.assignment();
        synchronized (ownedPartitionsEpoch) {
            final long currentEpoch = getCurrentTimeNanos();
            partitions.forEach((partition) -> {
                final OffsetAndMetadata offsetAndMetadata = consumer.committed(partition);
                LOG.info("Starting consumer with topic partition ({}) offset {}", partition, offsetAndMetadata);
                ownedPartitionsEpoch.put(partition, currentEpoch);
            });
        }

        boolean retryingAfterException = false;
        while (!shutdownInProgress.get()) {
            try {
                if (retryingAfterException) {
                    Thread.sleep(10000);
                }
                synchronized(this) {
                    commitOffsets(false);
                    resetOffsets();
                }
                consumeRecords();
                topicMetrics.update(consumer);
                retryingAfterException = false;
            } catch (Exception exp) {
                LOG.error("Error while reading the records from the topic {}. Retry after 10 seconds", topicName, exp);
                retryingAfterException = true;
            }
        }
        LOG.info("Shutting down, number of acks pending = {}", numberOfAcksPending.get());
        synchronized(this) {
            commitOffsets(true);
        }
    }

    private <T> Record<Event> getRecord(ConsumerRecord<String, T> consumerRecord, int partition) {
        Map<String, Object> data = new HashMap<>();
        Event event;
        Object value = consumerRecord.value();
        String key = (String)consumerRecord.key();
        KafkaKeyMode kafkaKeyMode = topicConfig.getKafkaKeyMode();
        boolean plainTextMode = false;
        try {
            if (value instanceof JsonDataWithSchema) {
                JsonDataWithSchema j = (JsonDataWithSchema)consumerRecord.value();
                value = objectMapper.readValue(j.getPayload(), Map.class);
            } else if (schema == MessageFormat.AVRO || value instanceof GenericRecord) {
                final JsonParser jsonParser = jsonFactory.createParser((String)consumerRecord.value().toString());
                value = objectMapper.readValue(jsonParser, Map.class);
            } else if (schema == MessageFormat.PLAINTEXT) {
                value = (String)consumerRecord.value();
                plainTextMode = true;
            } else if (schema == MessageFormat.JSON) {
                value = objectMapper.convertValue(value, Map.class);
            }
        } catch (Exception e){
            LOG.error("Failed to parse JSON or AVRO record", e);
            topicMetrics.getNumberOfRecordsFailedToParse().increment();
        }
        if (!plainTextMode) {
            if (!(value instanceof Map)) {
                data.put(key, value);
            } else {
                Map<String, Object> valueMap = (Map<String, Object>)value;
                if (kafkaKeyMode == KafkaKeyMode.INCLUDE_AS_FIELD) {
                    valueMap.put("kafka_key", key);
                }
                data = valueMap;
            }
        } else {
            if (Objects.isNull(key)) {
                key = DEFAULT_KEY;
            }
            data.put(key, value);
        }
        event = JacksonLog.builder().withData(data).build();
        EventMetadata eventMetadata = event.getMetadata();
        if (kafkaKeyMode == KafkaKeyMode.INCLUDE_AS_METADATA) {
            eventMetadata.setAttribute("kafka_key", key);
        }
        eventMetadata.setAttribute("kafka_topic", topicName);
        eventMetadata.setAttribute("kafka_partition", String.valueOf(partition));

        return new Record<Event>(event);
    }

    private <T> void iterateRecordPartitions(ConsumerRecords<String, T> records, final AcknowledgementSet acknowledgementSet,
                                             Map<TopicPartition, CommitOffsetRange> offsets) throws Exception {
        for (TopicPartition topicPartition : records.partitions()) {
            final long partitionEpoch = getPartitionEpoch(topicPartition);
            if (partitionEpoch == 0) {
                LOG.info("Skipping partition {}, lost ownership", topicPartition);
                continue;
            }

            List<ConsumerRecord<String, T>> partitionRecords = records.records(topicPartition);
            for (ConsumerRecord<String, T> consumerRecord : partitionRecords) {
                Record<Event> record = getRecord(consumerRecord, topicPartition.partition());
                if (record != null) {
                    // Always add record to acknowledgementSet before adding to
                    // buffer because another thread may take and process
                    // buffer contents before the event record is added
                    // to acknowledgement set
                    if (acknowledgementSet != null) {
                        acknowledgementSet.add(record.getData());
                    }
                    while (true) {
                        try {
                            bufferAccumulator.add(record);
                            break;
                        } catch (SizeOverflowException e) {
                            topicMetrics.getNumberOfBufferSizeOverflows().increment();
                            Thread.sleep(100);
                        }
                    }
                }
            }

            long lastOffset = partitionRecords.get(partitionRecords.size() - 1).offset();
            long firstOffset = partitionRecords.get(0).offset();
            Range<Long> offsetRange = Range.between(firstOffset, lastOffset);
            offsets.put(topicPartition, new CommitOffsetRange(offsetRange, partitionEpoch));
        }
    }

    public void closeConsumer(){
        consumer.close();
    }

    public void shutdownConsumer(){
        consumer.wakeup();
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        synchronized(this) {
            final long epoch = getCurrentTimeNanos();
            for (TopicPartition topicPartition : partitions) {
                if (ownedPartitionsEpoch.containsKey(topicPartition)) {
                    LOG.info("Partition {} already owned", topicPartition);
                    continue;
                }
                final OffsetAndMetadata offsetAndMetadata = consumer.committed(topicPartition);
                LOG.info("Assigned new partition {}, committed offset: {}",topicPartition,
                        Objects.isNull(offsetAndMetadata) ? 0 : offsetAndMetadata.offset());

                partitionCommitTrackerMap.remove(topicPartition.partition());
                ownedPartitionsEpoch.put(topicPartition, epoch);
            }
        }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        synchronized(this) {
            commitOffsets(true);
            for (TopicPartition topicPartition : partitions) {
                if (!ownedPartitionsEpoch.containsKey(topicPartition)) {
                    LOG.info("Partition {} not owned ", topicPartition);
                    continue;
                }
                LOG.info("Revoked partition {}", topicPartition);
                ownedPartitionsEpoch.remove(topicPartition);
                partitionCommitTrackerMap.remove(topicPartition.partition());
            }
        }
    }

    private long getPartitionEpoch(final TopicPartition topicPartition) {
        return ownedPartitionsEpoch.getOrDefault(topicPartition, 0L);
    }
}
