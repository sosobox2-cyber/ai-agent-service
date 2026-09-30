package com.cware.ai.exception;
import java.util.List;
import org.springframework.http.HttpStatus;
public class InferenceException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final List<String> errors;
    public InferenceException(String code, HttpStatus status, String message) { this(code,status,message,List.of()); }
    public InferenceException(String code, HttpStatus status, String message, List<String> errors) {
        super(message); this.code=code; this.status=status; this.errors=List.copyOf(errors);
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public List<String> errors() { return errors; }
}
