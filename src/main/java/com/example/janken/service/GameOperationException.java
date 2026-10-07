package com.example.janken.service;

import java.util.List;
import java.util.Map;

/** HTML再表示に必要な業務エラーだけを保持する。 */
public class GameOperationException extends RuntimeException {
    private List<String> explicitMessages;
    private final int status;
    private final String code;
    private final Map<String, List<String>> fieldErrors;

    public GameOperationException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public GameOperationException(int status, String code, String message,
            Map<String, List<String>> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public GameOperationException(int status, String code, List<String> messages) {
        this(status, code, messages.getFirst());
        this.explicitMessages = List.copyOf(messages);
    }

    public List<String> getErrorMessages() {
        if (explicitMessages != null) { return explicitMessages; }
        // 複数項目のエラーをすべて表示し、同じメッセージの重複は避ける。
        java.util.LinkedHashSet<String> messages = new java.util.LinkedHashSet<>();
        messages.add(getMessage());
        fieldErrors.values().forEach(messages::addAll);
        return List.copyOf(messages);
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, List<String>> getFieldErrors() { return fieldErrors; }

    public static GameOperationException invalidState() {
        return new GameOperationException(409, "INVALID_STATE", "現在の状態・所属ではこの操作を実行できません。");
    }

    public static GameOperationException validation(String field, String message) {
        return new GameOperationException(400, "VALIDATION_ERROR", message, Map.of(field, List.of(message)));
    }
}
