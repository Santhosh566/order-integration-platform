package com.example.orders.security;

import com.example.orders.model.AppUser;
import com.example.orders.model.Role;
import com.example.orders.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

/**
 * Links Keycloak users to users of this app. Someone who signed up in Keycloak gets a user
 * here on their first request; their name and email come from the token.
 */
@Service
public class UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);

    private final AppUserRepository users;
    private final TransactionTemplate newTransaction;

    public UserDirectory(AppUserRepository users, PlatformTransactionManager transactionManager) {
        this.users = users;
        // Its own transaction: the request may be inside one that must not see a failed insert
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String userIdFor(Jwt jwt, Set<Role> roles) {
        try {
            return newTransaction.execute(status -> findOrCreate(jwt, roles));
        } catch (DataIntegrityViolationException e) {
            // Two first requests raced; the other one created the user
            return users.findByKeycloakSubject(jwt.getSubject()).orElseThrow().getUserId();
        }
    }

    private String findOrCreate(Jwt jwt, Set<Role> roles) {
        AppUser user = users.findByKeycloakSubject(jwt.getSubject()).orElseGet(() -> link(jwt, roles));
        // Keycloak is the source of truth for roles
        if (!user.getRoles().equals(roles)) {
            user.getRoles().clear();
            user.getRoles().addAll(roles);
            users.save(user);
        }
        return user.getUserId();
    }

    /**
     * A user made before Keycloak, with the same verified email, is linked rather than duplicated.
     * An unverified email is never trusted for that: anyone could claim it at sign-up.
     */
    private AppUser link(Jwt jwt, Set<Role> roles) {
        String email = jwt.getClaimAsString("email");
        boolean verified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
        if (email != null && verified) {
            AppUser existing = users.findByEmailIgnoreCase(email).orElse(null);
            if (existing != null && existing.getKeycloakSubject() == null) {
                existing.setKeycloakSubject(jwt.getSubject());
                log.info("[USERS] Linked Keycloak user {} to existing user {}", jwt.getSubject(), existing.getUserId());
                return users.save(existing);
            }
        }

        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) {
            name = jwt.getClaimAsString("preferred_username");
        }
        Role primary = roles.contains(Role.ADMIN) ? Role.ADMIN
                : roles.contains(Role.DELIVERY) ? Role.DELIVERY
                : roles.contains(Role.SERVICE) ? Role.SERVICE
                : Role.CUSTOMER;
        AppUser user = new AppUser(prefix(primary) + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                name != null ? name : "New user", primary);
        user.getRoles().addAll(roles);
        user.setKeycloakSubject(jwt.getSubject());
        // Taken already by a user who is not linked: leave it empty rather than steal it
        if (email != null && users.findByEmailIgnoreCase(email).isEmpty()) {
            user.setEmail(email);
        }
        log.info("[USERS] New user {} for Keycloak user {} with roles {}", user.getUserId(), jwt.getSubject(), roles);
        return users.saveAndFlush(user);
    }

    private static String prefix(Role role) {
        return switch (role) {
            case ADMIN -> "ADM-";
            case DELIVERY -> "DEL-";
            case SERVICE -> "SVC-";
            case CUSTOMER -> "CUST-";
        };
    }
}
