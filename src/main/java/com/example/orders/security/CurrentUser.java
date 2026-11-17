package com.example.orders.security;

import com.example.orders.model.Role;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the signed-in user of the current HTTP request. Camel's REST routes run on the
 * request thread, so this works inside them too. Kafka consumers and timers have no user.
 */
@Component
public class CurrentUser {

    private final UserDirectory userDirectory;

    // Keycloak user id and roles -> this app's user id, so a request does not need a database lookup
    private final Cache<String, String> userIds = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public CurrentUser(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    public Optional<Viewer> get() {
        return from(SecurityContextHolder.getContext().getAuthentication());
    }

    public Viewer require() {
        return get().orElseThrow(() -> new IllegalStateException("No signed-in user on this request"));
    }

    public Optional<Viewer> from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        Set<Role> roles = KeycloakRoles.of(jwt);
        // Keyed by roles too, so a role change in Keycloak is stored on the next request
        String userId = userIds.get(jwt.getSubject() + "|" + roles, key -> userDirectory.userIdFor(jwt, roles));
        return Optional.of(new Viewer(userId, roles));
    }
}
