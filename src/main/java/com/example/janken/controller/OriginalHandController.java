package com.example.janken.controller;

import com.example.janken.domain.enums.HandRelation;
import com.example.janken.form.OriginalHandForm;
import com.example.janken.form.OriginalHandDeleteForm;
import com.example.janken.service.GameOperationException;
import com.example.janken.service.OriginalHandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.beans.PropertyEditorSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class OriginalHandController {
    private final OriginalHandService hands;
    private final ScreenRenderer screens;
    public OriginalHandController(OriginalHandService hands, ScreenRenderer screens) {
        this.hands = hands;
        this.screens = screens;
    }

    @InitBinder
    void bind(WebDataBinder binder) {
        // 許可値だけを既存Enumへ変換する。不正値はBindingResultで入力値とともに保持する。
        binder.setAllowedFields("name", "vsRock", "vsScissors", "vsPaper", "vsOriginal",
                "returnPage", "roomId", "resultMatchId");
        binder.registerCustomEditor(HandRelation.class, new PropertyEditorSupport() {
            @Override public void setAsText(String text) {
                setValue(text == null || text.isEmpty() ? null : HandRelation.valueOf(text));
            }
        });
    }

    @PostMapping("/original-hand/save")
    public String save(@ModelAttribute("originalHandForm") OriginalHandForm form, BindingResult binding,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        Map<String, List<String>> inputErrors = new LinkedHashMap<>();
        binding.getFieldErrors().forEach(error -> inputErrors.put(error.getField(),
                List.of("相性は勝ち・負け・引き分けから選択してください。")));
        try {
            return "redirect:" + hands.save(request.getSession(false), form, inputErrors);
        } catch (GameOperationException error) {
            response.setStatus(error.getStatus());
            String view = screens.error(request.getSession(false), form, error, model);
            if (Boolean.TRUE.equals(model.getAttribute("originalHandFormOpen"))) {
                // 型変換できなかった値も、同じフォームの選択肢へ戻せるようにする。
                BeanPropertyBindingResult displayBinding = new BeanPropertyBindingResult(
                        model.getAttribute("originalHandForm"), "originalHandForm");
                binding.getAllErrors().forEach(displayBinding::addError);
                model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "originalHandForm", displayBinding);
                Map<String, Object> rejected = new LinkedHashMap<>();
                binding.getFieldErrors().forEach(field -> rejected.put(field.getField(), field.getRejectedValue()));
                model.addAttribute("originalHandRejectedValues", rejected);
            } else {
                model.asMap().remove(BindingResult.MODEL_KEY_PREFIX + "originalHandForm");
            }
            return view;
        }
    }

    @PostMapping("/original-hand/delete")
    public String delete(@ModelAttribute("originalHandDeleteForm") OriginalHandDeleteForm form,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        try {
            return "redirect:" + hands.delete(request.getSession(false), form);
        } catch (GameOperationException error) {
            response.setStatus(error.getStatus());
            return screens.error(request.getSession(false), form, error, model);
        }
    }
}
