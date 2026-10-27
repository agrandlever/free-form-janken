package com.example.janken.controller;

import com.example.janken.form.OriginalHandForm;
import com.example.janken.service.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 元のエラー契約と、3画面共通の表示分類を分けて確認する。 */
class ScreenErrorPresentationTests {
    private static final String GROUP = "相性4項目のうち最低1項目は負けにしてください。";
    private static final String INDIVIDUAL = "相性は勝ち・負け・引き分けから選択してください。";

    ExtendedModelMap render(String template, GameOperationException error) {
        var screens = mock(ScreenService.class);
        var results = mock(MatchResultService.class);
        var session = new MockHttpSession();
        var input = new OriginalHandForm();
        input.setName("編集中");
        input.setReturnPage(template.equals("rooms") ? "ROOMS" : template.equals("room") ? "ROOM" : "MATCH_RESULT");
        input.setRoomId("ef219247-0452-4687-9631-bd2c70ae0e11");
        input.setResultMatchId(template.equals("match-result") ? "result-id" : null);
        var screen = new ScreenService.Screen("/" + template, template, Map.of(
                "canEditOriginalHand", true, "originalHandForm", input, "roomId", input.getRoomId(),
                "originalHandName", "保存済み"));
        when(screens.current(session)).thenReturn(screen);
        when(results.page(session, "result-id")).thenReturn(screen);
        var model = new ExtendedModelMap();
        assertEquals(template, new ScreenRenderer(screens, results).error(session, input, error, model));
        assertEquals(error.getFieldErrors(), model.get("fieldErrors"));
        assertEquals(error.getErrorMessages(), model.get("errorMessages"));
        assertEquals("VALIDATION_ERROR", model.get("errorCode"));
        assertEquals("保存済み", model.get("originalHandName"));
        assertEquals(true, model.get("originalHandFormOpen"));
        assertEquals("編集中", ((OriginalHandForm)model.get("originalHandForm")).getName());
        return model;
    }

    @ParameterizedTest @ValueSource(strings={"rooms", "room", "match-result"})
    void groupConditionIsOnlyInSummaryAndDoesNotInvalidateAnIndividual(String template) {
        var error = new GameOperationException(400, "VALIDATION_ERROR", "入力内容を確認してください。",
                Map.of("vsOriginal", List.of(GROUP)));
        var model = render(template, error);
        assertEquals(Map.of(), model.get("uiFieldErrors"));
        assertEquals(List.of(GROUP), model.get("errorSummaryMessages"));
        assertEquals(GROUP, model.get("affinityGroupError"));
        assertEquals(List.of(new ScreenRenderer.ErrorFieldLink("affinity-fields", "相性4項目")), model.get("errorFieldLinks"));
    }

    @ParameterizedTest @ValueSource(strings={"rooms", "room", "match-result"})
    void individualAndGroupMessagesCoexistEvenUnderTheSameKey(String template) {
        // 現行Serviceは未選択と全体条件を同時に返さないが、表示分類は省略せず扱う。
        var error = new GameOperationException(400, "VALIDATION_ERROR", "入力内容を確認してください。", Map.of(
                "name", List.of("名前エラー"), "vsRock", List.of(INDIVIDUAL),
                "vsOriginal", List.of(INDIVIDUAL, GROUP)));
        var model = render(template, error);
        assertEquals(Map.of("name", List.of("名前エラー"), "vsRock", List.of("グーへの相性を選んでください。"),
                "vsOriginal", List.of("他のオリジナル手への相性を選んでください。")), model.get("uiFieldErrors"));
        assertEquals(List.of(GROUP), model.get("errorSummaryMessages"));
        assertEquals(4, ((List<?>)model.get("errorFieldLinks")).size());
    }

    @ParameterizedTest @ValueSource(strings={"rooms", "room", "match-result"})
    void missingOrInvalidOriginalRelationRemainsAnIndividualError(String template) {
        var model = render(template, GameOperationException.validation("vsOriginal", INDIVIDUAL));
        assertNull(model.get("affinityGroupError"));
        assertEquals(List.of(), model.get("errorSummaryMessages"));
        assertEquals(Map.of("vsOriginal", List.of("他のオリジナル手への相性を選んでください。")), model.get("uiFieldErrors"));
        assertEquals(List.of(new ScreenRenderer.ErrorFieldLink("vsOriginal-WIN", "他のオリジナル手への相性")), model.get("errorFieldLinks"));
    }

    @ParameterizedTest @ValueSource(strings={"rooms", "room", "match-result"})
    void unrecognizedMessagesAreNotAssumedToBeGroupConditions(String template) {
        var model = render(template, GameOperationException.validation("vsOriginal", "相性4項目の別エラー"));
        assertNull(model.get("affinityGroupError"));
        assertEquals(Map.of("vsOriginal", List.of("相性4項目の別エラー")), model.get("uiFieldErrors"));
    }
}
