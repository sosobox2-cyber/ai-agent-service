package com.cware.ai.evaluation;

import com.cware.ai.security.LocalEvaluationAccess;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class LocalEvaluationAccessTest {
    private static final String ROOT = "/api/v1/evaluation";
    private static final String CASE = ROOT + "/datasets/00000000-0000-0000-0000-000000000001/cases/c00001";
    @Test void localExceptionRequiresExplicitOptInAndLoopbackPublication() {
        var request = new MockHttpServletRequest("GET",ROOT+"/datasets");
        request.setServerName("127.0.0.1");
        assertThat(new LocalEvaluationAccess(false,"127.0.0.1").allows(request,request.getRequestURI())).isFalse();
        assertThat(new LocalEvaluationAccess(true,"0.0.0.0").allows(request,request.getRequestURI())).isFalse();
        assertThat(new LocalEvaluationAccess(true,"127.0.0.1").allows(request,request.getRequestURI())).isTrue();
        request.setServerName("external.example");
        assertThat(new LocalEvaluationAccess(true,"127.0.0.1").allows(request,request.getRequestURI())).isFalse();
    }
    @Test void localEvaluationRunsAreAllowedButOtherApisStayProtected() {
        var access = new LocalEvaluationAccess(true,"127.0.0.1");
        var request = new MockHttpServletRequest("POST",CASE+"/mock-runs");
        assertThat(access.allows(request,CASE+"/mock-runs")).isTrue();
        assertThat(access.allows(request,CASE+"/runs")).isTrue();
        assertThat(access.allows(request,"/api/v1/coupang/purchase-options/infer")).isFalse();
        request.setMethod("GET");
        assertThat(access.allows(request,CASE)).isTrue();
        request.setMethod("PUT");
        assertThat(access.allows(request,CASE+"/reviews/2")).isTrue();
    }
    @Test void crossOriginAndCrossSiteRequestsCannotUseLocalException() {
        var access = new LocalEvaluationAccess(true,"127.0.0.1");
        var request = new MockHttpServletRequest("POST",ROOT+"/sample");
        request.setServerName("localhost"); request.setServerPort(8082);
        request.addHeader("Origin","http://evil.example");
        assertThat(access.available(request)).isFalse();
        request.removeHeader("Origin"); request.addHeader("Origin","http://localhost:8081");
        assertThat(access.available(request)).isFalse();
        request.removeHeader("Origin"); request.addHeader("Origin","http://localhost:8082");
        assertThat(access.available(request)).isTrue();
        request.addHeader("Sec-Fetch-Site","cross-site");
        assertThat(access.available(request)).isFalse();
    }
    @Test void productionSampleUsesSameLocalReviewBoundaryAsImportedCases() {
        var access = new LocalEvaluationAccess(true, "127.0.0.1");
        String sample = CASE.replace("c00001", "p20261029-12abcdef");
        var request = new MockHttpServletRequest("GET", sample);
        assertThat(access.allows(request, sample)).isTrue();
        request.setMethod("PUT");
        assertThat(access.allows(request, sample + "/reviews/1")).isTrue();
        assertThat(access.allows(request, sample.replace("12abcdef", "../escape") + "/reviews/1")).isFalse();
        request.setServerName("external.example");
        assertThat(access.allows(request, sample + "/reviews/1")).isFalse();
    }
}
