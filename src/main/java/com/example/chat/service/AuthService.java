package com.example.chat.service;

import com.example.chat.dto.AuthRequest;
import com.example.chat.dto.AuthResponse;
import com.example.chat.entity.UserEntity;
import com.example.chat.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_SECONDS = 300;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PhoneService phoneService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockUntil = new ConcurrentHashMap<>();

    public AuthResponse register(AuthRequest request) {
        String phone = phoneService.normalize(request.getPhone());
        validatePassword(request.getPassword());
        if (userRepository.existsByPhone(phone)) {
            throw new IllegalArgumentException("Phone already registered");
        }
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
        return new AuthResponse(jwtService.issueToken(phone), phone);
    }

    public AuthResponse login(AuthRequest request) {
        String phone = phoneService.normalize(request.getPhone());
        checkLocked(phone);
        UserEntity user = userRepository.findByPhone(phone)
                .orElseThrow(() -> recordFailure(phone, "Invalid phone or password"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw recordFailure(phone, "Invalid phone or password");
        }
        failedAttempts.remove(phone);
        lockUntil.remove(phone);
        return new AuthResponse(jwtService.issueToken(phone), phone);
    }

    public boolean userExists(String phone) {
        return userRepository.existsByPhone(phoneService.normalize(phone));
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
    }

    private RuntimeException recordFailure(String phone, String message) {
        int attempts = failedAttempts.merge(phone, 1, Integer::sum);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            lockUntil.put(phone, Instant.now().plusSeconds(LOCK_SECONDS));
            failedAttempts.remove(phone);
            return new IllegalStateException("Account temporarily locked, please retry later");
        }
        return new IllegalArgumentException(message);
    }

    private void checkLocked(String phone) {
        Instant until = lockUntil.get(phone);
        if (until != null && Instant.now().isBefore(until)) {
            throw new IllegalStateException("Account temporarily locked, please retry later");
        }
        if (until != null) {
            lockUntil.remove(phone);
        }
    }
}
