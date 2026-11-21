package com.betterclouddrive.web.warehouse;

import com.betterclouddrive.common.dto.WarehouseEvent.Channel;
import com.betterclouddrive.common.dto.WarehouseEvent.Mode;
import com.betterclouddrive.service.WarehouseEventService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarehouseTransferFilterTest {
    private final WarehouseEventService events = mock(WarehouseEventService.class);
    private final WarehouseTransferFilter filter = new WarehouseTransferFilter(events, new SimpleMeterRegistry());

    private MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/api/v1/download/12");
        request.setAsyncSupported(true);
        request.setAttribute(WarehouseTransferContext.ATTRIBUTE,
                new WarehouseTransferContext(1L, 2L, 12L, Channel.API, Mode.VERSION));
        return request;
    }

    @Test void successfulRangeCountsOnlyAcceptedBytesAndPreservesIdentity() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request("GET"), response, (req, res) -> {
            var http = (HttpServletResponse) res;
            http.setStatus(206);
            http.setHeader("Content-Disposition", "attachment");
            http.getOutputStream().write(new byte[7]);
        });
        verify(events).transfer(any(), eq(true), eq(1L), eq(2L), eq(12L), eq(Channel.API), eq(Mode.VERSION), eq(false), eq(0L));
        verify(events).transfer(any(), eq(false), eq(1L), eq(2L), eq(12L), eq(Channel.API), eq(Mode.VERSION), eq(true), eq(7L));
    }

    @Test void zeroByteDownloadIsSuccessfulButHeadAndJsonErrorsAreNotTransfers() throws Exception {
        filter.doFilter(request("GET"), new MockHttpServletResponse(), (req, res) ->
                ((HttpServletResponse) res).setHeader("Content-Disposition", "attachment"));
        verify(events).transfer(any(), eq(false), any(), any(), any(), any(), any(), eq(true), eq(0L));
        clearInvocations(events);
        filter.doFilter(request("HEAD"), new MockHttpServletResponse(), (req, res) ->
                ((HttpServletResponse) res).setHeader("Content-Disposition", "attachment"));
        filter.doFilter(request("GET"), new MockHttpServletResponse(), (req, res) -> res.getOutputStream().write("{}".getBytes()));
        verifyNoInteractions(events);
    }

    @Test void synchronousReadFailureRecordsFailedPartialTransfer() {
        assertThatThrownBy(() -> filter.doFilter(request("GET"), new MockHttpServletResponse(), (req, res) -> {
            ((HttpServletResponse) res).setHeader("Content-Disposition", "attachment");
            res.getOutputStream().write(new byte[3]);
            throw new IOException("controlled read failure");
        })).isInstanceOf(IOException.class);
        verify(events).transfer(any(), eq(false), any(), any(), any(), any(), any(), eq(false), eq(3L));
    }

    @Test void asyncCompletionWaitsForStreamAndRecordsOnce() throws Exception {
        var request = request("GET");
        var response = new MockHttpServletResponse();
        AsyncContext[] async = new AsyncContext[1];
        filter.doFilter(request, response, (req, res) -> {
            ((HttpServletResponse) res).setHeader("Content-Disposition", "attachment");
            async[0] = req.startAsync();
            res.getOutputStream().write(new byte[5]);
        });
        verify(events, never()).transfer(any(), eq(false), any(), any(), any(), any(), any(), anyBoolean(), anyLong());
        async[0].complete();
        verify(events, times(1)).transfer(any(), eq(false), any(), any(), any(), any(), any(), eq(true), eq(5L));
    }

    @Test void timeoutLeavesStartedFactUnknown() throws Exception {
        var request = request("GET");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ((HttpServletResponse) res).setHeader("Content-Disposition", "attachment");
            req.startAsync();
        });
        var async = (org.springframework.mock.web.MockAsyncContext) request.getAsyncContext();
        for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
        async.complete();
        verify(events).transfer(any(), eq(true), any(), any(), any(), any(), any(), eq(false), eq(0L));
        verify(events, never()).transfer(any(), eq(false), any(), any(), any(), any(), any(), anyBoolean(), anyLong());
    }

    @Test void internalAndUnboundAttachmentReadsAreExcluded() throws Exception {
        for (String path : new String[] {"/api/v1/office/files/12", "/api/v1/thumbnail/12"}) {
            var request = new MockHttpServletRequest("GET", path);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                ((HttpServletResponse) res).setHeader("Content-Disposition", "attachment");
                res.getOutputStream().write(new byte[9]);
            });
        }
        verifyNoInteractions(events);
    }

    @Test void missingTerminalPersistenceKeepsStartedAttemptAndEmitsFailureMetric() throws Exception {
        var registry = new SimpleMeterRegistry();
        var measured = new WarehouseTransferFilter(events, registry);
        doThrow(new IllegalStateException("controlled failure")).when(events)
                .transfer(any(), eq(false), any(), any(), any(), any(), any(), anyBoolean(), anyLong());
        measured.doFilter(request("GET"), new MockHttpServletResponse(), (req, res) -> {
            ((HttpServletResponse) res).setHeader("Content-Disposition", "attachment");
            res.getOutputStream().write(new byte[3]);
        });
        verify(events).transfer(any(), eq(true), any(), any(), any(), any(), any(), eq(false), eq(0L));
        assertThat(registry.get("bcd_warehouse_capture_failures").tag("kind", "download_result").counter().count()).isEqualTo(1);
    }
}
