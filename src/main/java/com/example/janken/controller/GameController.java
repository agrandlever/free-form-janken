package com.example.janken.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.example.janken.form.HandSelectionForm;
import com.example.janken.service.MatchService;
import com.example.janken.service.MatchResultService;
import com.example.janken.form.MatchResultReturnForm;
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
    private final MatchResultService results;
    public GameController(ScreenRenderer screens, MatchService matches, MatchResultService results) {
        this.screens = screens; this.matches = matches; this.results = results;
    }
    @GetMapping("/match-result")
    public String matchResult(@RequestParam(required = false) String matchId, HttpServletRequest request, Model model) {
        var screen = results.page(request.getSession(false), matchId);
        if (!screen.template().equals("match-result")) { return "redirect:" + screen.path(); }
        model.addAllAttributes(screen.model());
        return screen.template();
    }
    @PostMapping("/match-result/return")
    public String returnToRoom(@ModelAttribute MatchResultReturnForm form, HttpServletRequest request) {
        return "redirect:" + results.returnToRoom(request.getSession(false), form);
    }
    @GetMapping("/play")
    public String play(@RequestParam(required = false) String matchId, HttpServletRequest request, Model model) {
        return screens.play(request.getSession(false), matchId, model);
    }
    @GetMapping("/round-result")
    public String roundResult(@RequestParam(required = false) String matchId, HttpServletRequest request, Model model) {
        return screens.roundResult(request.getSession(false), matchId, model);
    }
    @PostMapping("/play")
    public String submitHand(@ModelAttribute HandSelectionForm form, HttpServletRequest request) {
        return "redirect:/play?matchId=" + matches.submitHand(request.getSession(false), form);
    }
}
