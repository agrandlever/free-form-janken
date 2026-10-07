package com.example.janken.controller;

import com.example.janken.form.LoginForm;
import com.example.janken.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AuthController {
    private final AuthService auth;
    private final ScreenRenderer screens;
    public AuthController(AuthService auth, ScreenRenderer screens) { this.auth = auth; this.screens = screens; }

    @GetMapping("/")
    public String index(HttpServletRequest request, Model model) {
        return screens.get(request.getSession(false), "/", model);
    }

    @PostMapping("/login")
    public String login(@ModelAttribute LoginForm form, HttpServletRequest request) {
        auth.login(request, form.getUsername());
        return "redirect:/rooms";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        auth.logout(request.getSession(false));
        return "redirect:/";
    }
}
