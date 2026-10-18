package com.cware.ai.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 루프백에만 공개된 로컬 평가 UI의 경로를 허용한다. */
@Component
public class LocalEvaluationAccess {
    private final boolean enabled;
    private final String bindAddress;
    public LocalEvaluationAccess(@Value("${app.evaluation.local-ui-enabled:false}") boolean enabled,
            @Value("${app.evaluation.local-bind-address:${server.address:127.0.0.1}}") String bindAddress) {
        this.enabled = enabled; this.bindAddress = bindAddress;
    }
    private static boolean loopback(String host) {
        return host != null && Set.of("localhost","127.0.0.1","::1","[::1]").contains(host.toLowerCase(Locale.ROOT));
    }
    public boolean available(HttpServletRequest request) {
        // Docker도 같은 HOST_ADDRESS 값으로 게시 포트와 이 설정을 함께 구성한다.
        if (!enabled || !loopback(bindAddress) || !loopback(request.getServerName())) return false;
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) return false;
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
            return request.getScheme().equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(request.getServerName()) && port == request.getServerPort()
                    && uri.getUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
    public boolean allows(HttpServletRequest request, String path) {
        if (!available(request)) return false;
        String method = request.getMethod(), root = "/api/v1/evaluation";
        if (method.equals("POST") && (path.equals(root + "/sample") || path.equals(root + "/datasets"))) return true;
        String dataset = root + "/datasets/[a-f0-9-]{36}";
        if (method.equals("GET")) return path.equals(root + "/datasets")
                || path.matches(dataset + "/(?:cases(?:/c[0-9]+)?|backup)");
        if (method.equals("PUT")) return path.matches(dataset + "/cases/c[0-9]+/reviews/[0-9]+");
        return method.equals("POST") && path.matches(dataset + "/cases/c[0-9]+/(?:mock-runs|runs)");
    }
}
