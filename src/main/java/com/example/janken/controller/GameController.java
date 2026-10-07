package com.example.janken.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class GameController {
    private final ScreenRenderer screens;
    public GameController(ScreenRenderer screens) { this.screens = screens; }
    @GetMapping("/play")
    public String play(@RequestParam(required = false) String matchId, HttpServletRequest request, Model model) {
        return screens.play(request.getSession(false), matchId, model);
    }
}
