package com.example.janken.controller;

import com.example.janken.form.LoginForm;
import com.example.janken.form.RoomActionForm;
import com.example.janken.form.RoomEnterForm;
import com.example.janken.form.RoomRuleForm;
import com.example.janken.service.GameOperationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = {AuthController.class, RoomController.class, GameController.class})
public class HtmlErrorHandler {
    private final ScreenRenderer screens;
    public HtmlErrorHandler(ScreenRenderer screens) { this.screens = screens; }

    @ExceptionHandler(GameOperationException.class)
    public String handle(GameOperationException error, HttpServletRequest request,
            HttpServletResponse response, Model model) {
        Object form;
        switch (request.getRequestURI()) {
            case "/login" -> {
                LoginForm login = new LoginForm();
                login.setUsername(request.getParameter("username"));
                form = login;
            }
            case "/rooms/enter" -> {
                RoomEnterForm enter = new RoomEnterForm();
                enter.setRoomName(request.getParameter("roomName"));
                form = enter;
            }
            case "/room/start", "/room/leave", "/room/ready", "/room/ready/cancel" -> {
                RoomActionForm action = new RoomActionForm();
                action.setRoomId(request.getParameter("roomId"));
                form = action;
            }
            case "/match-result/return" -> {
                var result = new com.example.janken.form.MatchResultReturnForm();
                result.setRoomId(request.getParameter("roomId"));
                result.setMatchId(request.getParameter("matchId"));
                form = result;
            }
            case "/room/rules" -> {
                RoomRuleForm rules = new RoomRuleForm();
                rules.setRoomId(request.getParameter("roomId"));
                rules.setTargetWins(request.getParameter("targetWins"));
                rules.setPreventConsecutiveSameOriginalHand(Boolean.parseBoolean(request.getParameter("preventConsecutiveSameOriginalHand")));
                form = rules;
            }
            default -> form = new LoginForm();
        }
        response.setStatus(error.getStatus());
        return screens.error(request.getSession(false), form, error, model);
    }
}
