package community.campus;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
class Admin {
    private final Store store;
    private final MeterRegistry metrics;

    Admin(Store store, MeterRegistry metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @GetMapping("/status")
    Map<String,Object> status() {
        var result = new LinkedHashMap<>(store.one("""
            select count(*) filter (where attempts < 8) as pending_mail,
                   count(*) filter (where attempts >= 8) as failed_mail
            from mail_queue
            """));
        result.put("http", httpMetrics());
        result.put("uptime_seconds", gauge("process.uptime"));
        result.put("db_pool", new DatabasePool(gauge("hikaricp.connections.active"), gauge("hikaricp.connections.max")));
        return result;
    }

    private HttpMetrics httpMetrics() {
        long requests = 0;
        long failures = 0;
        double durationMillis = 0;
        for (var timer : metrics.find("http.server.requests").timers()) {
            var uri = timer.getId().getTag("uri");
            if (uri == null || !uri.startsWith("/api/v1/") || uri.startsWith("/api/v1/admin/")) continue;
            long count = timer.count();
            requests += count;
            durationMillis += timer.totalTime(TimeUnit.MILLISECONDS);
            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("5")) failures += count;
        }
        return new HttpMetrics(requests, failures, requests == 0 ? null : durationMillis / requests);
    }

    private Double gauge(String name) {
        var meter = metrics.find(name).gauge();
        if (meter == null) return null;
        double value = meter.value();
        return Double.isFinite(value) ? value : null;
    }

    record HttpMetrics(long requests, long serverErrors, Double averageDurationMs) {}
    record DatabasePool(Double active, Double max) {}
}
