package com.cware.ai.controller;
import com.cware.ai.dto.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/coupang/purchase-options")
public class PurchaseOptionController {
    private final PurchaseOptionInferenceService service;
    public PurchaseOptionController(PurchaseOptionInferenceService service) { this.service=service; }
    @PostMapping("/infer")
    public InferenceResponse infer(@Valid @RequestBody InferenceRequest request,
            @RequestParam(name="testMode", defaultValue="false") boolean testMode) {
        return service.infer(request, testMode);
    }
}
