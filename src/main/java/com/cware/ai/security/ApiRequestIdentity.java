package com.cware.ai.security;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 인증한 서버가 설정한 식별자만 사용하며 클라이언트 헤더를 로그에 전달하지 않는다. */
public final class ApiRequestIdentity {
    public static final String ATTRIBUTE = ApiRequestIdentity.class.getName() + ".id";
    private ApiRequestIdentity() {}
    public static String currentId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof String id) return id;
        return "internal-call";
    }
}
