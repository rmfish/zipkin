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
package zipkin2.storage.cassandra.integration;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import zipkin.Span;
import zipkin.TestObjects;
import zipkin.internal.V2StorageComponent;
import zipkin.storage.StorageComponent;
import zipkin.storage.StrictTraceIdFalseTest;

import static org.assertj.core.api.Assertions.assertThat;

abstract class CassandraStrictTraceIdFalseTest extends StrictTraceIdFalseTest {

  protected abstract zipkin2.storage.StorageComponent v2Storage();

  @Override protected final StorageComponent storage() {
    return V2StorageComponent.create(v2Storage());
  }

  public CassandraStrictTraceIdFalseTest() {
    // check everything is ok
    storage().check();
  }

  /**
   * When {@link StorageComponent.Builder#strictTraceId(boolean)} is true and {@link
   * Span#traceIdHigh} is not zero, the span is stored a second time, with {@link Span#traceId}
   * zero. This allows spans to be looked up by the low bits of the trace ID at the cost of extra
   * storage. When spans are retrieved by {@link Span#traceIdHigh} as zero, they are returned as
   * {@link Span#traceIdHigh} zero because unlike the old schema, the original structs are not
   * persisted.
   */
  @Test
  @Override
  public void getTrace_retrieves128bitTraceIdByLower64Bits_mixed() {
    List<Span> trace = new ArrayList<>(TestObjects.TRACE);
    trace.set(0, trace.get(0).toBuilder().traceIdHigh(1).build());
    // pretend the others downgraded to 64-bit trace IDs

    accept(trace.toArray(new Span[0]));

    // Implicitly in both cases, we are looking up by traceIdHigh=0, so the traces returned also
    // have traceIdHigh == 0
    assertThat(store().getTrace(0L, trace.get(0).traceId))
        .containsExactlyElementsOf(TestObjects.TRACE);
    assertThat(store().getTrace(trace.get(0).traceIdHigh, trace.get(0).traceId))
        .containsExactlyElementsOf(TestObjects.TRACE);
  }
}
