package brave.kafka.clients;

import brave.Span;
import brave.Tracing;
import brave.internal.Nullable;
import brave.propagation.MutableTraceContext;
import brave.propagation.MutableTraceContext.Extractor;
import brave.propagation.TraceContext.Injector;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.internals.NoOpConsumerRebalanceListener;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;

/**
 * Kafka Consumer decorator. Read records headers to create and complete a child of the incoming
 * producers span if possible.
 */
final class TracingConsumer<K, V> implements Consumer<K, V> {

  final Tracing tracing;
  final Injector<Headers> injector;
  final Extractor<Headers> extractor;
  final Consumer<K, V> delegate;
  @Nullable final String remoteServiceName;

  TracingConsumer(Tracing tracing, Consumer<K, V> delegate, @Nullable String remoteServiceName) {
    this.delegate = delegate;
    this.tracing = tracing;
    this.injector = tracing.propagation().injector(KafkaPropagation.HEADER_SETTER);
    this.extractor = tracing.propagationFactory().extractor(KafkaPropagation.HEADER_GETTER);
    this.remoteServiceName = remoteServiceName;
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public ConsumerRecords<K, V> poll(Duration timeout) {
    return poll(timeout.toMillis());
  }

  /** This */
  @Override public ConsumerRecords<K, V> poll(long timeout) {
    ConsumerRecords<K, V> records = delegate.poll(timeout);
    if (records.isEmpty() || tracing.isNoop()) return records;
    Map<String, Span> consumerSpansForTopic = new LinkedHashMap<>();
    MutableTraceContext extracted = new MutableTraceContext();
    for (TopicPartition partition : records.partitions()) {
      String topic = partition.topic();
      List<ConsumerRecord<K, V>> recordsInPartition = records.records(partition);
      for (int i = 0, length = recordsInPartition.size(); i < length; i++) {
        ConsumerRecord<K, V> record = recordsInPartition.get(i);
        extractor.extract(record.headers(), extracted);

        // If we extracted neither a trace context, nor request-scoped data (extra),
        // make or reuse a span for this topic
        if (extracted.isEmpty()) {
          Span consumerSpanForTopic = consumerSpansForTopic.get(topic);
          if (consumerSpanForTopic == null) {
            consumerSpansForTopic.put(topic,
                consumerSpanForTopic = tracing.tracer().nextSpan().name("poll")
                    .kind(Span.Kind.CONSUMER)
                    .tag(KafkaTags.KAFKA_TOPIC_TAG, topic)
                    .start());
          }
          // no need to remove propagation headers as we failed to extract anything
          injector.inject(consumerSpanForTopic.context(), record.headers());
        } else { // we extracted request-scoped data, so cannot share a consumer span.
          Span span = tracing.tracer().nextSpan(extracted);
          if (!span.isNoop()) {
            span.name("poll").kind(Span.Kind.CONSUMER).tag(KafkaTags.KAFKA_TOPIC_TAG, topic);
            if (remoteServiceName != null) span.remoteServiceName(remoteServiceName);
            span.start().finish(); // span won't be shared by other records
          }
          // remove prior propagation headers from the record
          tracing.propagation().keys().forEach(key -> record.headers().remove(key));
          injector.inject(span.context(), record.headers());
          extracted.clear();
        }
      }
    }
    consumerSpansForTopic.values()
        .forEach(span -> span.remoteServiceName(remoteServiceName).finish());
    return records;
  }

  @Override public Set<TopicPartition> assignment() {
    return delegate.assignment();
  }

  @Override public Set<String> subscription() {
    return delegate.subscription();
  }

  @Override public void subscribe(Collection<String> topics) {
    delegate.subscribe(topics);
  }

  @Override public void subscribe(Collection<String> topics, ConsumerRebalanceListener callback) {
    delegate.subscribe(topics, callback);
  }

  @Override public void assign(Collection<TopicPartition> partitions) {
    delegate.assign(partitions);
  }

  @Override public void subscribe(Pattern pattern, ConsumerRebalanceListener callback) {
    delegate.subscribe(pattern, callback);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 1.0
  public void subscribe(Pattern pattern) {
    delegate.subscribe(pattern, new NoOpConsumerRebalanceListener());
  }

  @Override public void unsubscribe() {
    delegate.unsubscribe();
  }

  @Override public void commitSync() {
    delegate.commitSync();
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public void commitSync(Duration timeout) {
    delegate.commitSync(timeout);
  }

  @Override public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
    delegate.commitSync(offsets);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets, Duration timeout) {
    delegate.commitSync(offsets, timeout);
  }

  @Override public void commitAsync() {
    delegate.commitAsync();
  }

  @Override public void commitAsync(OffsetCommitCallback callback) {
    delegate.commitAsync(callback);
  }

  @Override public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets,
      OffsetCommitCallback callback) {
    delegate.commitAsync(offsets, callback);
  }

  @Override public void seek(TopicPartition partition, long offset) {
    delegate.seek(partition, offset);
  }

  @Override public void seekToBeginning(Collection<TopicPartition> partitions) {
    delegate.seekToBeginning(partitions);
  }

  @Override public void seekToEnd(Collection<TopicPartition> partitions) {
    delegate.seekToEnd(partitions);
  }

  @Override public long position(TopicPartition partition) {
    return delegate.position(partition);
  }

  public long position(TopicPartition partition, Duration timeout) {
    return delegate.position(partition, timeout);
  }

  @Override public OffsetAndMetadata committed(TopicPartition partition) {
    return delegate.committed(partition);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public OffsetAndMetadata committed(TopicPartition partition, Duration timeout) {
    return delegate.committed(partition, timeout);
  }

  @Override public Map<MetricName, ? extends Metric> metrics() {
    return delegate.metrics();
  }

  @Override public List<PartitionInfo> partitionsFor(String topic) {
    return delegate.partitionsFor(topic);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
    return delegate.partitionsFor(topic, timeout);
  }

  @Override public Map<String, List<PartitionInfo>> listTopics() {
    return delegate.listTopics();
  }

  @Override public Map<String, List<PartitionInfo>> listTopics(Duration timeout) {
    return delegate.listTopics(timeout);
  }

  @Override public Set<TopicPartition> paused() {
    return delegate.paused();
  }

  @Override public void pause(Collection<TopicPartition> partitions) {
    delegate.pause(partitions);
  }

  @Override public void resume(Collection<TopicPartition> partitions) {
    delegate.resume(partitions);
  }

  @Override public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
      Map<TopicPartition, Long> timestampsToSearch) {
    return delegate.offsetsForTimes(timestampsToSearch);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
      Map<TopicPartition, Long> timestampsToSearch, Duration timeout) {
    return delegate.offsetsForTimes(timestampsToSearch, timeout);
  }

  @Override
  public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
    return delegate.beginningOffsets(partitions);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions,
      Duration timeout) {
    return delegate.beginningOffsets(partitions, timeout);
  }

  @Override public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
    return delegate.endOffsets(partitions);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions,
      Duration timeout) {
    return delegate.endOffsets(partitions, timeout);
  }

  @Override public void close() {
    delegate.close();
  }

  @Override public void close(long timeout, TimeUnit unit) {
    delegate.close(timeout, unit);
  }

  // Do not use @Override annotation to avoid compatibility issue version < 2.0
  public void close(Duration timeout) {
    delegate.close(timeout);
  }

  @Override public void wakeup() {
    delegate.wakeup();
  }
}
