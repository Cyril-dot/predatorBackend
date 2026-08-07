package com.example.subscription.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public class AdminLoginRequest {

    // Accepts either {"username": "..."} or {"email": "..."} in the JSON body,
    // since admin usernames are always email addresses and frontends commonly
    // label/name this field "email" instead of "username".
    @NotBlank
    @JsonAlias("email")
    private String username;

    @NotBlank
    private String password;

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
