package com.example.orders.security;

import com.example.orders.model.Role;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads this app's roles from a Keycloak token's realm roles ("realm_access.roles").
 * Keycloak's own roles, such as offline_access, are ignored.
 */
public final class KeycloakRoles {

    private KeycloakRoles() {}

    public static Set<Role> of(Jwt jwt) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> names) {
            for (Object name : names) {
                for (Role role : Role.values()) {
                    if (role.name().equals(name)) {
                        roles.add(role);
                    }
                }
            }
        }
        return roles;
    }
}
