package com.example.janken.controller;

import com.example.janken.form.RoomActionForm;
import com.example.janken.form.RoomEnterForm;
import com.example.janken.service.RoomService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class RoomController {
    private final RoomService rooms;
    private final ScreenRenderer screens;
    public RoomController(RoomService rooms, ScreenRenderer screens) { this.rooms = rooms; this.screens = screens; }

    @GetMapping("/rooms")
    public String rooms(HttpServletRequest request, Model model) {
        return screens.get(request.getSession(false), "/rooms", model);
    }

    @GetMapping("/room")
    public String room(HttpServletRequest request, Model model) {
        return screens.get(request.getSession(false), "/room", model);
    }

    @PostMapping("/rooms/enter")
    public String enter(@ModelAttribute RoomEnterForm form, HttpServletRequest request) {
        // Formを先に検証して400を返さず、Serviceで状態→入力の順に判定する。
        rooms.enterRoom(request.getSession(false), form.getRoomName());
        return "redirect:/room";
    }

    @PostMapping("/room/leave")
    public String leave(@ModelAttribute RoomActionForm form, HttpServletRequest request) {
        rooms.leaveRoom(request.getSession(false), form.getRoomId());
        return "redirect:/rooms";
    }
}
