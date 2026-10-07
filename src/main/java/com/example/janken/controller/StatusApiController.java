package com.example.janken.controller;

import com.example.janken.service.GameOperationException;
import com.example.janken.service.StatusService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTMLのエラー処理から独立し、未ログイン・入力不正もJSONで返す。 */
@RestController
public class StatusApiController {
    private final StatusService service;
    public StatusApiController(StatusService service) { this.service = service; }

    @GetMapping("/api/status")
    public StatusService.StatusView status(@RequestParam(required = false) String matchId,
            HttpServletRequest request) {
        return service.status(request.getSession(false), matchId);
    }

    public record FieldError(String field, String message) { }
    public record ErrorView(String code, String message, List<FieldError> errors) { }

    @ExceptionHandler(GameOperationException.class)
    public ResponseEntity<ErrorView> error(GameOperationException error) {
        var fields = error.getFieldErrors().entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(message -> new FieldError(e.getKey(), message))).toList();
        return ResponseEntity.status(error.getStatus()).body(new ErrorView(error.getCode(), error.getMessage(), fields));
    }
}
