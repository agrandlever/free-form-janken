package com.example.janken.service;

/** 名前入力の整形とコードポイント数の最終判定を共通化する。 */
public final class NameInput {
    private NameInput() { }

    public static String normalize(String input, String field, String label) {
        String value = input == null ? "" : input.strip();
        // 補助平面の文字も1文字として数えるため、UTF-16の長さでは判定しない。
        int count = value.codePointCount(0, value.length());
        if (count < 1 || count > 20) {
            throw GameOperationException.validation(field, label + "は前後の空白を除いて1～20文字で入力してください。");
        }
        return value;
    }
}
