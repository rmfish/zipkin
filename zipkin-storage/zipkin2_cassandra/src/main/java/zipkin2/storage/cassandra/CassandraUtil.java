/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.storage.cassandra;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.LocalDate;
import com.datastax.driver.core.PreparedStatement;
import com.google.common.base.Function;
import com.google.common.collect.Sets;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import zipkin2.Span;
import zipkin2.internal.Nullable;
import zipkin2.storage.QueryRequest;

final class CassandraUtil {
  static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  /**
   * Zipkin's {@link QueryRequest#annotationQuery()} are equals match. Not all tag keys are lookup
   * keys. For example, {@code sql.query} isn't something that is likely to be looked up by value and
   * indexing that could add a potentially kilobyte partition key on {@link Schema#TABLE_SPAN}
   */
  static final int LONGEST_VALUE_TO_INDEX = 256;

  // Time window covered by a single bucket of the Span Duration Index, in seconds. Default: 1 day
  private static final long DURATION_INDEX_BUCKET_WINDOW_SECONDS
      = Long.getLong("zipkin.store.cassandra.internal.durationIndexBucket", 24 * 60 * 60);

  public static int durationIndexBucket(long ts_micro) {
    // if the window constant has microsecond precision, the division produces negative values
    return (int) ((ts_micro / DURATION_INDEX_BUCKET_WINDOW_SECONDS) / 1000000);
  }

  /**
   * Returns keys that concatenate the serviceName associated with an annotation or a tags.
   *
   * @see QueryRequest#annotationQuery()
   */
  static Set<String> annotationKeys(Span span) {
    Set<String> annotationKeys = new LinkedHashSet<>();
    span.annotations().forEach((a) -> annotationKeys.add(a.value()));

    for (Map.Entry<String,String> tag : span.tags().entrySet()) {
      if (tag.getValue().length() > LONGEST_VALUE_TO_INDEX) continue;

      // Using colon to allow allow annotation query search to work on key
      annotationKeys.add(tag.getKey());
      annotationKeys.add(tag.getKey() + ":" + tag.getValue());
    }
    return annotationKeys;
  }

  static List<String> annotationKeys(QueryRequest request) {
    Set<String> annotationKeys = new LinkedHashSet<>();
    for (Map.Entry<String, String> e : request.annotationQuery().entrySet()) {
      if (e.getValue().isEmpty()) {
        annotationKeys.add(e.getKey());
      } else {
        annotationKeys.add(e.getKey() + ":" + e.getValue());
      }
    }
    return new ArrayList<>(annotationKeys);
  }

  static BoundStatement bindWithName(PreparedStatement prepared, String name) {
    return new NamedBoundStatement(prepared, name);
  }

  /** Used to assign a friendly name when tracing and debugging */
  static final class NamedBoundStatement extends BoundStatement {

    final String name;

    NamedBoundStatement(PreparedStatement statement, String name) {
      super(statement);
      this.name = name;
    }

    @Override public String toString() {
      return name;
    }
  }

  static Function<List<Map<String, Long>>, Collection<String>> intersectKeySets() {
    return (Function) IntersectKeySets.INSTANCE;
  }

  static Function<Map<String, Long>, Collection<String>> traceIdsSortedByDescTimestamp() {
    return TraceIdsSortedByDescTimestamp.INSTANCE;
  }

  enum IntersectKeySets implements Function<List<Map<Object, ?>>, Collection<Object>> {
    INSTANCE;

    @Override public Collection<Object> apply(@Nullable List<Map<Object, ?>> input) {
      Set<Object> traceIds = Sets.newLinkedHashSet(input.get(0).keySet());
      for (int i = 1; i < input.size(); i++) {
        traceIds.retainAll(input.get(i).keySet());
      }
      return traceIds;
    }
  }

  enum TraceIdsSortedByDescTimestamp implements Function<Map<String, Long>, Collection<String>> {
    INSTANCE;

    @Override public Collection<String> apply(@Nullable Map<String, Long> map) {
      // timestamps can collide, so we need to add some random digits on end before using them as keys
      SortedMap<BigInteger, String> sorted = new TreeMap<>(Collections.reverseOrder());
      map.entrySet().forEach(e ->
        sorted.put(
            BigInteger.valueOf(e.getValue()).multiply(OFFSET).add(BigInteger.valueOf(RAND.nextInt())),
            e.getKey())
      );
      return sorted.values();
    }

    private static final Random RAND = new Random(System.nanoTime());
    private static final BigInteger OFFSET = BigInteger.valueOf(Integer.MAX_VALUE);
  }

  static List<LocalDate> getDays(long endTs, @Nullable Long lookback) {
    long to = midnightUTC(endTs);
    long startMillis = endTs - (lookback != null ? lookback : endTs);
    long from = startMillis <= 0 ? 0 : midnightUTC(startMillis); // >= 1970

    List<LocalDate> days = new ArrayList<>();
    for (long time = from; time <= to; time += TimeUnit.DAYS.toMillis(1)) {
      days.add(LocalDate.fromMillisSinceEpoch(time));
    }
    return days;
  }

  /** For bucketed data floored to the day. For example, dependency links. */
  static long midnightUTC(long epochMillis) {
    Calendar day = Calendar.getInstance(UTC);
    day.setTimeInMillis(epochMillis);
    day.set(Calendar.MILLISECOND, 0);
    day.set(Calendar.SECOND, 0);
    day.set(Calendar.MINUTE, 0);
    day.set(Calendar.HOUR_OF_DAY, 0);
    return day.getTimeInMillis();
  }
}
