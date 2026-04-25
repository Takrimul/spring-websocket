package com.example.chat.service;

import org.springframework.stereotype.Service;

@Service
public class PhoneService {
    private static final String PHONE_REGEX = "^\\+?[1-9][0-9]{7,14}$";

    public String normalize(String phone) {
        if (phone == null) {
            throw new IllegalArgumentException("Phone is required");
        }
        String normalized = phone.replaceAll("[\\s\\-()]", "");
        if (!normalized.startsWith("+")) {
            normalized = "+" + normalized;
        }
        if (!normalized.matches(PHONE_REGEX)) {
            throw new IllegalArgumentException("Phone must be valid E.164-like format");
        }
        return normalized;
    }
}
