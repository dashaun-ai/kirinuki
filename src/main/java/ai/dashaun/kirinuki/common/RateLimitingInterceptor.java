package ai.dashaun.kirinuki.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

public class RateLimitingInterceptor implements HandlerInterceptor {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, List<Long>> requests = new ConcurrentHashMap<>();

    public RateLimitingInterceptor(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = clientKey(request);
        long now = System.currentTimeMillis();
        long cutoff = now - windowMillis;
        AtomicBoolean overLimit = new AtomicBoolean(false);
        requests.compute(key, (ignored, timestamps) -> {
            List<Long> recent = timestamps == null ? new ArrayList<>() : new ArrayList<>(timestamps);
            recent.removeIf(timestamp -> timestamp < cutoff);
            if (recent.size() >= maxRequests) {
                overLimit.set(true);
                return recent;
            }
            recent.add(now);
            return recent;
        });
        if (overLimit.get()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return false;
        }
        return true;
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String remote = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].strip();
        return request.getMethod() + ":" + request.getRequestURI() + ":" + remote;
    }
}
