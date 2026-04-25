package com.example.chat.controller;

import com.example.chat.dto.AuthRequest;
import com.example.chat.dto.AuthResponse;
import com.example.chat.service.AuthService;
import com.example.chat.service.PhoneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    private AuthService authService;

    @Autowired
    private PhoneService phoneService;

    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        return authService.login(request);
    }

    @GetMapping("/exists")
    public ResponseEntity<Map<String, Boolean>> exists(@RequestParam("phone") String phone) {
        String normalized = phoneService.normalize(phone);
        return ResponseEntity.ok(Map.of("exists", authService.userExists(normalized)));
    }
}
