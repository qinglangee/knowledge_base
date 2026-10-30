package com.kb.controller;

import com.kb.common.ApiResponse;
import com.kb.dto.Dtos.SettingsDTO;
import com.kb.dto.Dtos.SettingsUpdateRequest;
import com.kb.service.SettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<SettingsDTO> get() {
        return ApiResponse.ok(service.asDTO());
    }

    @PutMapping
    public ApiResponse<SettingsDTO> update(@RequestBody SettingsUpdateRequest req) {
        service.update(req);
        return ApiResponse.ok(service.asDTO());
    }
}
