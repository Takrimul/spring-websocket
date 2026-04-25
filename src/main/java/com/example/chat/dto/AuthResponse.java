package com.example.chat.dto;

public class AuthResponse {
    private String token;
    private String phone;

    public AuthResponse() {}

    public AuthResponse(String token, String phone) {
        this.token = token;
        this.phone = phone;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
