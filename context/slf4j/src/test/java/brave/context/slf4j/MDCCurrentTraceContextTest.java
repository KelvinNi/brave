package brave.context.slf4j;

import brave.internal.HexCodec;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import brave.test.propagation.CurrentTraceContextTest;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

public class MDCCurrentTraceContextTest extends CurrentTraceContextTest {

  @Override protected CurrentTraceContext newCurrentTraceContext() {
    return MDCCurrentTraceContext.create(CurrentTraceContext.Default.create());
  }

  @Override protected void verifyImplicitContext(@Nullable TraceContext context) {
    if (context != null) {
      assertThat(MDC.get("traceId"))
          .isEqualTo(context.traceIdString());
      long parentId = context.parentIdAsLong();
      assertThat(MDC.get("parentId"))
          .isEqualTo(parentId != 0L ? HexCodec.toLowerHex(parentId) : null);
      assertThat(MDC.get("spanId"))
          .isEqualTo(HexCodec.toLowerHex(context.spanId()));
    } else {
      assertThat(MDC.get("traceId"))
          .isNull();
      assertThat(MDC.get("parentId"))
          .isNull();
      assertThat(MDC.get("spanId"))
          .isNull();
    }
  }
}