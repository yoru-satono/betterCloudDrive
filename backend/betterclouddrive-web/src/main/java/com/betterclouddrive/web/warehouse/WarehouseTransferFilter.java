package com.betterclouddrive.web.warehouse;

import com.betterclouddrive.service.WarehouseEventService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Measures accepted writes to the servlet stream, never client-side file persistence. */
@Slf4j
@Component
@Order(-90)
@RequiredArgsConstructor
public class WarehouseTransferFilter extends OncePerRequestFilter {
    private final WarehouseEventService events;
    private final MeterRegistry metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "HEAD".equals(request.getMethod())
                || !(request.getRequestURI().startsWith("/api/v1/") || request.getRequestURI().startsWith("/webdav"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var state = new Capture(request, response);
        var trackingResponse = new HttpServletResponseWrapper(response) {
            private ServletOutputStream tracked;
            @Override public ServletOutputStream getOutputStream() throws IOException {
                if (tracked == null) {
                    var delegate = super.getOutputStream();
                    tracked = new ServletOutputStream() {
                        @Override public boolean isReady() { return delegate.isReady(); }
                        @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                        @Override public void write(int value) throws IOException {
                            state.start();
                            try { delegate.write(value); state.bytes.incrementAndGet(); }
                            catch (IOException | RuntimeException error) { state.failed.set(true); throw error; }
                        }
                        @Override public void write(byte[] data, int offset, int length) throws IOException {
                            state.start();
                            try { delegate.write(data, offset, length); state.bytes.addAndGet(length); }
                            catch (IOException | RuntimeException error) { state.failed.set(true); throw error; }
                        }
                        @Override public void flush() throws IOException {
                            try { delegate.flush(); }
                            catch (IOException | RuntimeException error) { state.failed.set(true); throw error; }
                        }
                        @Override public void close() throws IOException {
                            try { delegate.close(); }
                            catch (IOException | RuntimeException error) { state.failed.set(true); throw error; }
                        }
                    };
                }
                return tracked;
            }
        };
        var trackingRequest = new HttpServletRequestWrapper(request) {
            @Override public AsyncContext startAsync() { return startAsync(this, trackingResponse); }
            @Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
                var async = super.startAsync(req, res);
                async.addListener(state);
                return async;
            }
        };
        try {
            chain.doFilter(trackingRequest, trackingResponse);
        } catch (ServletException | IOException | RuntimeException | Error error) {
            state.failed.set(true);
            throw error;
        } finally {
            if (!request.isAsyncStarted()) state.finish();
        }
    }

    private final class Capture implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final UUID attemptId = UUID.randomUUID();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicLong bytes = new AtomicLong();
        private final Long viewerId;
        private WarehouseTransferContext context;

        private Capture(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            this.viewerId = authentication != null && authentication.getPrincipal()
                    instanceof com.betterclouddrive.web.security.UserPrincipal principal ? principal.getUserId() : null;
        }

        private void start() {
            if (started.get() || !(request.getAttribute(WarehouseTransferContext.ATTRIBUTE)
                    instanceof WarehouseTransferContext resolved)) return;
            if (response.getStatus() != 200 && response.getStatus() != 206) return;
            // API errors may use HTTP 200; only an authorized attachment is a file transfer.
            String disposition = response.getHeader("Content-Disposition");
            if (resolved.channel() != com.betterclouddrive.common.dto.WarehouseEvent.Channel.WEBDAV
                    && (disposition == null || !disposition.startsWith("attachment"))) return;
            if (started.compareAndSet(false, true)) {
                context = resolved;
                events.transfer(attemptId, true, context.actorId(), context.teamId(), context.resourceId(),
                        context.channel(), context.mode(), false, 0);
            }
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) return;
            try {
                start(); // Includes legitimate zero-byte attachments.
                if (context == null) {
                    recordPreviewActivity();
                    return;
                }
                events.transfer(attemptId, false, context.actorId(), context.teamId(), context.resourceId(),
                        context.channel(), context.mode(), !failed.get() && response.getStatus() < 400, bytes.get());
            } catch (RuntimeException error) {
                // The durable STARTED fact remains UNKNOWN when its terminal fact cannot be saved.
                metrics.counter("bcd_warehouse_capture_failures", "kind", "download_result").increment();
                log.warn("Warehouse transfer result unavailable; attempt_id={}", attemptId);
            }
        }

        private void recordPreviewActivity() {
            if (viewerId == null || failed.get() || (response.getStatus() != 200 && response.getStatus() != 206)) return;
            String disposition = response.getHeader("Content-Disposition");
            if (disposition == null || !disposition.startsWith("inline")) return;
            String path = request.getRequestURI();
            Long teamId = null;
            var matcher = java.util.regex.Pattern.compile("^/api/v1/teams/(\\d+)/preview/\\d+$").matcher(path);
            if (matcher.matches()) teamId = Long.valueOf(matcher.group(1));
            else if (!path.matches("^/api/v1/preview/\\d+$")) return;
            events.record(com.betterclouddrive.common.dto.WarehouseEvent.Type.USER_ACTIVITY,
                    viewerId, teamId, null, "preview:" + attemptId);
        }

        @Override public void onComplete(AsyncEvent event) { finish(); }
        @Override public void onError(AsyncEvent event) { failed.set(true); finish(); }
        @Override public void onTimeout(AsyncEvent event) {
            try { start(); }
            finally { finished.set(true); } // Timeout is UNKNOWN, not proof of a completed transfer.
        }
        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
    }
}
