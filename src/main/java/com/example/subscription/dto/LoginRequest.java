package com.example.subscription.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {

    // Accepts either {"username": "..."} or {"email": "..."} in the JSON body.
    @NotBlank
    @JsonAlias("email")
    private String username; // email used when paying

    @NotBlank
    private String password; // the generated password

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
