package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.OriginalHand;
import com.example.janken.domain.Room;
import com.example.janken.domain.enums.HandRelation;
import com.example.janken.domain.enums.UserState;
import com.example.janken.form.OriginalHandForm;
import com.example.janken.form.OriginalHandDeleteForm;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.RoomStore;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OriginalHandService {
    private static final Set<String> FORBIDDEN_NAMES = Set.of("グー", "チョキ", "パー");
    public static final String FORBIDDEN_NAME_MESSAGE = "オリジナル手の名前に「グー」「チョキ」「パー」は使用できません。";
    private final GameStateLock lock;
    private final SessionUserAccess access;
    private final RoomStore rooms;

    public OriginalHandService(GameStateLock lock, SessionUserAccess access, RoomStore rooms) {
        this.lock = lock;
        this.access = access;
        this.rooms = rooms;
    }

    public String save(HttpSession session, OriginalHandForm form) {
        return save(session, form, Map.of());
    }

    /** 型変換エラーも保持し、状態・対象の確認後に保存用検証を行う。 */
    public String save(HttpSession session, OriginalHandForm form, Map<String, List<String>> inputErrors) {
        synchronized (lock) {
            GameUser user = access.require(session);
            String path = validateTarget(user, form.getReturnPage(), form.getRoomId(), form.getResultMatchId());
            String name = validate(form, inputErrors);
            OriginalHand current = user.getOriginalHand();
            UUID id = current == null ? UUID.randomUUID() : current.getId();
            // 全検証が成功してから、既存IDを持つ設定を一括で反映する。
            // 途中で既存オブジェクトの名前や相性を書き換えない。
            user.setOriginalHand(new OriginalHand(id, name, form.getVsRock(), form.getVsScissors(),
                    form.getVsPaper(), form.getVsOriginal()));
            return path;
        }
    }

    public String delete(HttpSession session, OriginalHandDeleteForm form) {
        synchronized (lock) {
            GameUser user = access.require(session);
            String path = validateTarget(user, form.getReturnPage(), form.getRoomId(), form.getResultMatchId());
            if (user.getOriginalHand() == null) { throw GameOperationException.invalidState(); }
            user.setOriginalHand(null);
            return path;
        }
    }

    private String validateTarget(GameUser user, String returnPage, String roomId, String resultMatchId) {
        // 古い②フォームでも、最新状態が③なら入力検証・変更より先に拒否する。
        if (user.getState() == UserState.READY) { throw GameOperationException.invalidState(); }
        if (!"ROOMS".equals(returnPage) && !"ROOM".equals(returnPage)) {
            throw GameOperationException.validation("returnPage", "返却先の指定が不正です。");
        }
        if ("ROOMS".equals(returnPage)) {
            if (user.getState() != UserState.ROOM_NONE || user.getCurrentRoomId() != null) {
                throw GameOperationException.invalidState();
            }
            if (roomId != null && !roomId.isEmpty()) {
                throw GameOperationException.validation("roomId", "この画面ではルームを指定できません。");
            }
        } else {
            if (user.getState() != UserState.ROOM_WAITING || user.getCurrentRoomId() == null) {
                throw GameOperationException.invalidState();
            }
            UUID target;
            try {
                target = UUID.fromString(roomId == null ? "" : roomId);
                if (!target.toString().equalsIgnoreCase(roomId)) { throw new IllegalArgumentException(); }
            } catch (IllegalArgumentException ex) {
                throw GameOperationException.validation("roomId", "ルームの指定が不正です。");
            }
            if (!target.equals(user.getCurrentRoomId())) { throw GameOperationException.invalidState(); }
            Room room = rooms.findById(target).orElseThrow(GameOperationException::invalidState);
            if (!room.getMemberIds().contains(user.getId())) { throw GameOperationException.invalidState(); }
        }
        if (resultMatchId != null && !resultMatchId.isEmpty()) {
            throw GameOperationException.validation("resultMatchId", "この画面では対戦結果を指定できません。");
        }
        return "ROOMS".equals(returnPage) ? "/rooms" : "/room";
    }

    private String validate(OriginalHandForm form, Map<String, List<String>> inputErrors) {
        Map<String, List<String>> errors = new LinkedHashMap<>(inputErrors);
        String name = null;
        try {
            name = NameInput.normalize(form.getName(), "name", "オリジナル手の名前");
            if (FORBIDDEN_NAMES.contains(name)) { errors.put("name", List.of(FORBIDDEN_NAME_MESSAGE)); }
        } catch (GameOperationException ex) {
            errors.putAll(ex.getFieldErrors());
        }
        HandRelation[] relations = {form.getVsRock(), form.getVsScissors(), form.getVsPaper(), form.getVsOriginal()};
        String[] fields = {"vsRock", "vsScissors", "vsPaper", "vsOriginal"};
        boolean hasLose = false;
        for (int i = 0; i < relations.length; i++) {
            if (relations[i] == null) {
                errors.putIfAbsent(fields[i], List.of("相性は勝ち・負け・引き分けから選択してください。"));
            }
            if (relations[i] == HandRelation.LOSE) { hasLose = true; }
        }
        if (!hasLose && java.util.Arrays.stream(relations).allMatch(java.util.Objects::nonNull)) { errors.put("vsOriginal", List.of("相性4項目のうち最低1項目は負けにしてください。")); }
        if (!errors.isEmpty()) {
            throw new GameOperationException(400, "VALIDATION_ERROR", "入力内容を確認してください。", errors);
        }
        return name;
    }
}
