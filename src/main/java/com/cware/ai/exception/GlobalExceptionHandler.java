package com.cware.ai.exception;

import com.cware.ai.config.InferenceProperties;
import com.cware.ai.dto.InferenceResponse;
import com.cware.ai.dto.ServerAssessment;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;

@RestControllerAdvice
/** 컨트롤러 예외를 동일한 응답 계약으로 변환하고 공개 가능한 오류 정보만 반환한다. */
public class GlobalExceptionHandler {
    private final InferenceProperties properties;
    public GlobalExceptionHandler(InferenceProperties properties) { this.properties=properties; }
    @ExceptionHandler(InferenceException.class)
    public ResponseEntity<InferenceResponse> handle(InferenceException e) {
        return ResponseEntity.status(e.status()).body(error(e.code(),e.getMessage(),e.errors()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<InferenceResponse> invalid(MethodArgumentNotValidException e) {
        // 입력 원문 대신 실패한 필드명만 공개해 민감한 값이 오류 응답에 포함되지 않도록 한다.
        List<String> errors=e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField()+": 입력 조건을 만족하지 않습니다.").distinct().toList();
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST","요청 필드를 확인하세요.",errors));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InferenceResponse> malformed(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(error("INVALID_JSON","JSON 형식 또는 필드가 잘못되었습니다.",List.of()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<InferenceResponse> invalidParameter(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "요청 매개변수를 확인하세요.", List.of()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<InferenceResponse> unexpected(Exception e) {
        // 외부 예외 메시지/본문에는 키와 상품정보가 포함될 수 있으므로 반환하거나 기록하지 않는다.
        return ResponseEntity.internalServerError().body(error("INTERNAL_ERROR","요청 처리 중 오류가 발생했습니다.",List.of()));
    }
    private InferenceResponse error(String code,String reason,List<String> errors) {
        // 오류 응답에는 적용 가능한 매핑을 넣지 않고 서버 판정과 설정된 버전을 포함한다.
        return new InferenceResponse(null,false,false,0,List.of(),List.of(),reason,errors,code,
                null,properties.promptVersion(),"NONE").withAssessments(null,
                    new ServerAssessment(code, reason, properties.confidenceThreshold()));
    }
}
