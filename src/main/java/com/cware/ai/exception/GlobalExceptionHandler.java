package com.cware.ai.exception;

import com.cware.ai.config.InferenceProperties;
import com.cware.ai.dto.InferenceResponse;
import com.cware.ai.dto.ServerAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;

@RestControllerAdvice
/** 컨트롤러 예외를 동일한 응답 계약으로 변환하고 공개 가능한 오류 정보만 반환한다. */
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
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
        // 예외 메시지·본문에는 키나 상품정보가 섞일 수 있으므로 응답에는 넣지 않는다.
        // 다만 여기서 처리하면 프레임워크가 더 이상 기록하지 않으므로, 서버 로그에는 반드시 남겨
        // 운영 중 장애의 원인을 추적할 수 있게 한다. 로그는 클라이언트에 노출되지 않는다.
        LOG.error("처리되지 않은 예외로 요청이 실패했습니다.", e);
        return ResponseEntity.internalServerError().body(error("INTERNAL_ERROR","요청 처리 중 오류가 발생했습니다.",List.of()));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<InferenceResponse> missingResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND","요청한 화면 또는 API를 찾을 수 없습니다.",List.of()));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<InferenceResponse> uploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error("UPLOAD_TOO_LARGE","파일은 32MB 이하여야 합니다.",List.of()));
    }
    private InferenceResponse error(String code,String reason,List<String> errors) {
        // 오류 응답에는 적용 가능한 매핑을 넣지 않고 서버 판정과 설정된 버전을 포함한다.
        return new InferenceResponse(null,false,false,0,List.of(),List.of(),reason,errors,code,
                null,properties.promptVersion(),"NONE").withAssessments(null,
                    new ServerAssessment(code, reason, properties.confidenceThreshold()));
    }
}
