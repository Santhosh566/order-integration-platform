package com.example.orders.security;

import com.example.orders.model.Role;

import java.util.Set;

/**
 * Who is making the current request: a user id and their roles, taken from the login token.
 */
public record Viewer(String userId, Set<Role> roles) {

    public boolean is(Role role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return is(Role.ADMIN);
    }
}
