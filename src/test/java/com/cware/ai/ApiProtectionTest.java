package com.cware.ai;

import com.cware.ai.security.ApiRateLimiter;
import com.cware.ai.security.ApiRequestIdentity;
import com.cware.ai.security.ApiProtectionConfig;
import com.cware.ai.exception.GlobalExceptionHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.security.enabled=true", "app.security.api-key=dummy-service-test-key",
        "app.security.api-key-id=internal-test", "app.security.rate-limit.enabled=true",
        "app.security.rate-limit.per-minute=3", "app.ai.usage-jsonl-path=target/security-test-usage.jsonl"})
@AutoConfigureMockMvc
class ApiProtectionTest {
    static final String URL = "/api/v1/coupang/purchase-options/infer";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ChatModel model;

    @Test void authBlocksBeforeAiAndAuthenticatedCallsHaveLimitAndSafeLogIdentity() throws Exception {
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage(mapper.writeValueAsString(Fixtures.proposal())),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
        when(model.call(any(Prompt.class))).thenReturn(response);
        var body = mapper.writeValueAsString(Fixtures.request());
        Path logPath = Path.of("target/security-test-usage.jsonl");
        long previousLines = Files.exists(logPath) ? Files.readAllLines(logPath).size() : 0;
        for (String key : Arrays.asList(null, "", "wrong-dummy-key")) {
            var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
            if (key != null) request.header("X-API-Key", key);
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.aiUsage").isEmpty());
        }
        mvc.perform(post(URL).header("X-API-Key", "dummy-service-test-key", "dummy-service-test-key")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        verifyNoInteractions(model);
        for (int i = 0; i < 3; i++) mvc.perform(post(URL).header("X-API-Key", "dummy-service-test-key")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post(URL).header("X-API-Key", "dummy-service-test-key")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
        verify(model, times(3)).call(any(Prompt.class));
        assertThat(Files.readAllLines(logPath)).hasSize((int) previousLines + 3);
        String log = Files.readString(logPath);
        assertThat(log).contains("\"apiKeyId\":\"internal-test\"").doesNotContain("dummy-service-test-key", "X-API-Key");
        assertThat(ApiRequestIdentity.currentId()).isEqualTo("internal-call");
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(post(URL + ";ignored").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    @Test void limiterResetsAfterSixtySecondsAndSeparatesIds() {
        class MutableClock extends Clock {
            Instant value = Instant.EPOCH;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return value; }
        }
        var clock = new MutableClock();
        var limiter = new ApiRateLimiter(3, clock);
        for (int i = 0; i < 3; i++) assertThat(limiter.retryAfter("a")).isZero();
        assertThat(limiter.retryAfter("a")).isEqualTo(60);
        assertThat(limiter.retryAfter("b")).isZero();
        clock.value = Instant.EPOCH.plusMillis(59_001);
        assertThat(limiter.retryAfter("a")).isEqualTo(1);
        clock.value = Instant.EPOCH.plusSeconds(60);
        assertThat(limiter.retryAfter("a")).isZero();
    }

    @Test void disabledAuthenticationNeedsNoKeyAndRateLimitCanBeDisabledIndependently() throws Exception {
        var errors = new GlobalExceptionHandler(Fixtures.properties());
        var config = new ApiProtectionConfig(false, "", "internal-test", false, 3, mapper, errors);
        var filter = config.apiProtectionFilter().getFilter();
        var request = new MockHttpServletRequest("POST", URL);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(req.getAttribute(ApiRequestIdentity.ATTRIBUTE)).isEqualTo("security-disabled"));
        var enabled = new ApiProtectionConfig(true, "dummy-key", "internal-test", false, 1, mapper, errors);
        for (int i = 0; i < 4; i++) {
            var authenticated = new MockHttpServletRequest("POST", URL);
            authenticated.addHeader("X-API-Key", "dummy-key");
            enabled.apiProtectionFilter().getFilter().doFilter(authenticated, new MockHttpServletResponse(), (req, res) ->
                    assertThat(req.getAttribute(ApiRequestIdentity.ATTRIBUTE)).isEqualTo("internal-test"));
        }
    }

    @Test void enabledSecurityFailsClosedWhenKeyMissingOrSecretUsedAsId() {
        var errors = new GlobalExceptionHandler(Fixtures.properties());
        assertThatThrownBy(() -> new ApiProtectionConfig(true, "", "internal-test", true, 30, mapper, errors))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiProtectionConfig(true, "dummy-secret", "dummy-secret", true, 30, mapper, errors))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("dummy-secret");
    }

    @Test void concurrentRequestsCannotExceedBucketLimit() {
        var limiter = new ApiRateLimiter(3, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        long accepted = java.util.stream.IntStream.range(0, 100).parallel()
                .filter(i -> limiter.retryAfter("internal-test") == 0).count();
        assertThat(accepted).isEqualTo(3);
    }

    @Test void apiNamespaceBoundaryProtectsApiButDoesNotMatchSimilarPageNames() throws Exception {
        var config = new ApiProtectionConfig(true, "dummy-key", "internal-test", true, 30,
                mapper, new GlobalExceptionHandler(Fixtures.properties()));
        for (String path : List.of("/api", URL, "/api;ignored/v1/coupang/purchase-options/infer")) {
            var response = new MockHttpServletResponse();
            config.apiProtectionFilter().getFilter().doFilter(new MockHttpServletRequest("POST", path), response,
                    (req, res) -> { throw new AssertionError("Unauthenticated API reached controller"); });
            assertThat(response.getStatus()).isEqualTo(401);
        }
        for (String path : List.of("/api-reference.html", "/api-reference.md", "/apiary")) {
            var reached = new java.util.concurrent.atomic.AtomicBoolean();
            config.apiProtectionFilter().getFilter().doFilter(new MockHttpServletRequest("GET", path),
                    new MockHttpServletResponse(), (req, res) -> reached.set(true));
            assertThat(reached).isTrue();
        }
    }
}
