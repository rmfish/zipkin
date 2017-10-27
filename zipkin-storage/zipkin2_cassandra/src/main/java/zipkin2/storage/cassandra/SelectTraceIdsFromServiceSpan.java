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
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.utils.UUIDs;
import com.google.auto.value.AutoValue;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import zipkin2.Call;
import zipkin2.internal.Nullable;
import zipkin2.storage.cassandra.CassandraSpanStore.TimestampRange;
import zipkin2.storage.cassandra.internal.call.AccumulateAllResults;
import zipkin2.storage.cassandra.internal.call.ResultSetFutureCall;

import static zipkin2.storage.cassandra.Schema.TABLE_TRACE_BY_SERVICE_SPAN;

final class SelectTraceIdsFromServiceSpan extends ResultSetFutureCall {
  @AutoValue static abstract class Input {
    abstract String service();

    abstract String span();

    abstract int bucket();

    @Nullable abstract Long start_duration();

    @Nullable abstract Long end_duration();

    abstract UUID start_ts();

    abstract UUID end_ts();

    abstract int limit_();
  }

  static class Factory {
    final Session session;
    final PreparedStatement selectTraceIdsByServiceSpanName;
    final PreparedStatement selectTraceIdsByServiceSpanNameAndDuration;

    Factory(Session session) {
      this.session = session;
      // separate to avoid: Unsupported unset value for column duration
      this.selectTraceIdsByServiceSpanName = session.prepare(
        QueryBuilder.select("ts", "trace_id")
          .from(TABLE_TRACE_BY_SERVICE_SPAN)
          .where(QueryBuilder.eq("service", QueryBuilder.bindMarker("service")))
          .and(QueryBuilder.eq("span", QueryBuilder.bindMarker("span")))
          .and(QueryBuilder.eq("bucket", QueryBuilder.bindMarker("bucket")))
          .and(QueryBuilder.gte("ts", QueryBuilder.bindMarker("start_ts")))
          .and(QueryBuilder.lte("ts", QueryBuilder.bindMarker("end_ts")))
          .limit(QueryBuilder.bindMarker("limit_"))
      );
      this.selectTraceIdsByServiceSpanNameAndDuration = session.prepare(
        QueryBuilder.select("ts", "trace_id")
          .from(TABLE_TRACE_BY_SERVICE_SPAN)
          .where(QueryBuilder.eq("service", QueryBuilder.bindMarker("service")))
          .and(QueryBuilder.eq("span", QueryBuilder.bindMarker("span")))
          .and(QueryBuilder.eq("bucket", QueryBuilder.bindMarker("bucket")))
          .and(QueryBuilder.gte("ts", QueryBuilder.bindMarker("start_ts")))
          .and(QueryBuilder.lte("ts", QueryBuilder.bindMarker("end_ts")))
          .and(QueryBuilder.gte("duration", QueryBuilder.bindMarker("start_duration")))
          .and(QueryBuilder.lte("duration", QueryBuilder.bindMarker("end_duration")))
          .limit(QueryBuilder.bindMarker("limit_"))
      );
    }

    Call<Set<Entry<String, Long>>> newCall(
      String serviceName,
      String spanName,
      int bucket,
      @Nullable Long minDurationMicros,
      @Nullable Long maxDurationMicros,
      TimestampRange timestampRange,
      int limit) {
      Long start_duration = null, end_duration = null;
      if (minDurationMicros != null) {
        start_duration = minDurationMicros / 1000L;
        end_duration = maxDurationMicros != null ? maxDurationMicros / 1000L : Long.MAX_VALUE;
      }

      Input input = new AutoValue_SelectTraceIdsFromServiceSpan_Input(
        serviceName,
        spanName,
        bucket,
        start_duration,
        end_duration,
        timestampRange.startUUID,
        timestampRange.endUUID,
        limit
      );
      return new SelectTraceIdsFromServiceSpan(
        this,
        minDurationMicros != null
          ? selectTraceIdsByServiceSpanNameAndDuration
          : selectTraceIdsByServiceSpanName,
        input
      ).flatMap(new AccumulateTraceIdTsUuid());
    }
  }

  final Factory factory;
  final PreparedStatement preparedStatement;
  final Input input;

  SelectTraceIdsFromServiceSpan(Factory factory, PreparedStatement preparedStatement, Input input) {
    this.factory = factory;
    this.preparedStatement = preparedStatement;
    this.input = input;
  }

  @Override protected ResultSetFuture newFuture() {
    BoundStatement bound = preparedStatement.bind()
      .setString("service", input.service())
      .setString("span", input.span())
      .setInt("bucket", input.bucket());
    if (input.start_duration() != null) {
      bound.setLong("start_duration", input.start_duration());
      bound.setLong("end_duration", input.end_duration());
    }
    bound.setUUID("start_ts", input.start_ts())
      .setUUID("end_ts", input.end_ts())
      .setInt("limit_", input.limit_())
      .setFetchSize(input.limit_());
    return factory.session.executeAsync(bound);
  }

  @Override public String toString() {
    return input.toString().replace("Input", "SelectTraceIdsFromServiceSpan");
  }

  @Override public SelectTraceIdsFromServiceSpan clone() {
    return new SelectTraceIdsFromServiceSpan(factory, preparedStatement, input);
  }

  static final class AccumulateTraceIdTsUuid
    extends AccumulateAllResults<Set<Entry<String, Long>>> {

    @Override protected Supplier<Set<Entry<String, Long>>> supplier() {
      return LinkedHashSet::new; // because results are not distinct
    }

    @Override protected BiConsumer<Row, Set<Entry<String, Long>>> accumulator() {
      return (row, result) -> result.add(new AbstractMap.SimpleEntry<>(
        row.getString("trace_id"), UUIDs.unixTimestamp(row.getUUID("ts"))
      ));
    }

    @Override public String toString() {
      return "AccumulateTraceIdTsUuid{}";
    }
  }
}
