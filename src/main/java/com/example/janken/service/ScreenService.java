package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.OriginalHand;
import com.example.janken.form.OriginalHandForm;
import com.example.janken.form.OriginalHandDeleteForm;
import com.example.janken.domain.Room;
import com.example.janken.domain.enums.UserState;
import com.example.janken.form.LoginForm;
import com.example.janken.form.RoomActionForm;
import com.example.janken.form.RoomEnterForm;
import com.example.janken.form.RoomRuleForm;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.RoomStore;
import com.example.janken.store.UserStore;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** アクセス制御と表示用コピー生成を、同じ共有ロック内で行う。 */
@Service
public class ScreenService {
    private final GameStateLock lock;
    private final SessionUserAccess access;
    private final RoomStore rooms;
    private final UserStore users;

    public ScreenService(GameStateLock lock, SessionUserAccess access, RoomStore rooms, UserStore users) {
        this.lock = lock;
        this.access = access;
        this.rooms = rooms;
        this.users = users;
    }

    public record MemberView(String username, boolean host, boolean ready) { }
    public record Screen(String path, String template, Map<String, Object> model) { }

    public Screen current(HttpSession session) {
        synchronized (lock) {
            GameUser user = access.find(session);
            Map<String, Object> model = new LinkedHashMap<>();
            if (user == null) {
                model.put("loginForm", new LoginForm());
                return screen("/", "login", model);
            }
            model.put("username", user.getUsername());
            model.put("userState", user.getState());
            if (user.getState() == UserState.ROOM_NONE) {
                model.put("roomEnterForm", new RoomEnterForm());
                originalHandModel(user, "ROOMS", null, model);
                return screen("/rooms", "rooms", model);
            }
            if ((user.getState() != UserState.ROOM_WAITING && user.getState() != UserState.READY) || user.getCurrentRoomId() == null) {
                throw GameOperationException.invalidState();
            }
            Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
            if (!room.getMemberIds().contains(user.getId())) { throw GameOperationException.invalidState(); }
            model.put("roomId", room.getId().toString());
            model.put("roomName", room.getName());
            model.put("isHost", user.getId().equals(room.getHostUserId()));
            java.util.List<MemberView> members = room.getMemberIds().stream().map(id -> {
                GameUser member = users.findById(id).orElseThrow(GameOperationException::invalidState);
                return new MemberView(member.getUsername(), id.equals(room.getHostUserId()), member.getState() == UserState.READY);
            }).toList();
            model.put("members", members);
            model.put("readyMembers", members.stream()
                    .filter(MemberView::ready).toList());
            model.put("targetWins", room.getTargetWins());
            model.put("preventConsecutiveSameOriginalHand", room.isPreventConsecutiveSameOriginalHand());
            model.put("currentMatchId", room.getCurrentMatchId());
            model.put("matchRunning", room.getCurrentMatchId() != null);
            RoomRuleForm ruleForm = new RoomRuleForm();
            ruleForm.setRoomId(room.getId().toString());
            ruleForm.setTargetWins(Integer.toString(room.getTargetWins()));
            ruleForm.setPreventConsecutiveSameOriginalHand(room.isPreventConsecutiveSameOriginalHand());
            model.put("roomRuleForm", ruleForm);
            RoomActionForm form = new RoomActionForm();
            form.setRoomId(room.getId().toString());
            model.put("roomActionForm", form);
            originalHandModel(user, "ROOM", room.getId().toString(), model);
            return screen("/room", "room", model);
        }
    }

    private void originalHandModel(GameUser user, String returnPage, String roomId, Map<String, Object> model) {
        OriginalHand hand = user.getOriginalHand();
        OriginalHandForm form = new OriginalHandForm();
        form.setReturnPage(returnPage);
        form.setRoomId(roomId);
        if (hand != null) {
            // 表示中にDomainが更新されても変化しない、本人用のコピーを作る。
            form.setName(hand.getName());
            form.setVsRock(hand.getVsRock());
            form.setVsScissors(hand.getVsScissors());
            form.setVsPaper(hand.getVsPaper());
            form.setVsOriginal(hand.getVsOriginal());
        }
        OriginalHandDeleteForm delete = new OriginalHandDeleteForm();
        delete.setReturnPage(returnPage);
        delete.setRoomId(roomId);
        model.put("canEditOriginalHand", user.getState() == UserState.ROOM_NONE || user.getState() == UserState.ROOM_WAITING);
        model.put("hasOriginalHand", hand != null);
        model.put("originalHandName", hand == null ? "" : hand.getName());
        model.put("originalHandForm", form);
        model.put("originalHandDeleteForm", delete);
        model.put("originalHandFormOpen", false);
    }

    private Screen screen(String path, String template, Map<String, Object> model) {
        // Domain参照を含めず、ロック解放後のHTML描画に安全なコピーを返す。
        return new Screen(path, template, Collections.unmodifiableMap(model));
    }
}
