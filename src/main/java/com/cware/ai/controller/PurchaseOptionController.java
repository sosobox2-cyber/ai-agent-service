package com.cware.ai.controller;
import com.cware.ai.dto.*;
import com.cware.ai.evaluation.ProductionSampleRecorder;
import com.cware.ai.service.PurchaseOptionInferenceService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/coupang/purchase-options")
/** 구매옵션 추론 HTTP 요청을 받아 서비스의 실제 AI 또는 모의 추출 흐름으로 전달한다. */
public class PurchaseOptionController {
    private final PurchaseOptionInferenceService service;
    private final ObjectProvider<ProductionSampleRecorder> samples;
    public PurchaseOptionController(PurchaseOptionInferenceService service, ObjectProvider<ProductionSampleRecorder> samples) {
        this.service=service; this.samples=samples;
    }
    @PostMapping("/infer")
    public InferenceResponse infer(@Valid @RequestBody InferenceRequest request,
            @RequestParam(name="testMode", defaultValue="false") boolean testMode) {
        // @Valid로 필드 조건을 검사한 요청을 전달하며, 테스트 모드는 명시적으로 선택해야 한다.
        InferenceResponse response = service.infer(request, testMode);
        // 평가 기능이 켜진 환경에서만 운영 승인 결과 일부를 정답 확정용 표본으로 남긴다. 평가 화면의 재실행은 대상이 아니다.
        samples.ifAvailable(recorder -> recorder.offer(request, response));
        return response;
    }
}
