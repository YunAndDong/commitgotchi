package com.commitgotchi.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    @Test
    void createsServerTraceIdIgnoresClientValueAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "client-controlled");
        AtomicReference<String> observedTraceId = new AtomicReference<>();

        new TraceIdFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> observedTraceId.set(MDC.get("traceId"))
        );

        assertThat(observedTraceId.get())
                .matches("[0-9a-f]{32}")
                .isNotEqualTo("client-controlled");
        assertThat(MDC.get("traceId")).isNull();
    }
}
