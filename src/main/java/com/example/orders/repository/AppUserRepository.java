package com.example.orders.repository;

import com.example.orders.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, String> {

    Optional<AppUser> findByKeycloakSubject(String keycloakSubject);

    Optional<AppUser> findByEmailIgnoreCase(String email);

    List<AppUser> findAllByOrderByCreatedAtDesc();

    /** Serialises changes to one user's profile, such as two photo uploads at once. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from AppUser u where u.userId = :userId")
    java.util.Optional<AppUser> findForUpdate(String userId);
}
