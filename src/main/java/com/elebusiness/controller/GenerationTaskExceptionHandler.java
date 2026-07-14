package com.elebusiness.controller;

import com.elebusiness.service.ActiveGenerationTaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GenerationTaskExceptionHandler {

    @ExceptionHandler(ActiveGenerationTaskException.class)
    public ResponseEntity<Map<String, Object>> handleActiveTask(ActiveGenerationTaskException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "error", exception.getMessage()));
    }
}
