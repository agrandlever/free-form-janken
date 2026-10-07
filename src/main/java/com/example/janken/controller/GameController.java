package com.example.janken.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.example.janken.form.HandSelectionForm;
import com.example.janken.service.MatchService;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class GameController {
    private final ScreenRenderer screens;
    private final MatchService matches;
    public GameController(ScreenRenderer screens, MatchService matches) { this.screens = screens; this.matches = matches; }
    @GetMapping("/play")
    public String play(@RequestParam(required = false) String matchId, HttpServletRequest request, Model model) {
        return screens.play(request.getSession(false), matchId, model);
    }
    @PostMapping("/play")
    public String submitHand(@ModelAttribute HandSelectionForm form, HttpServletRequest request) {
        return "redirect:/play?matchId=" + matches.submitHand(request.getSession(false), form);
    }
}
