package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.model.AppUser;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.DeliveryAssignment;
import com.example.orders.model.DeliveryStatus;
import com.example.orders.model.ReturnPickup;
import com.example.orders.model.ReturnRequest;
import com.example.orders.model.Role;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.DeliveryAssignmentRepository;
import com.example.orders.repository.ReturnPickupRepository;
import com.example.orders.repository.ReturnRequestRepository;
import com.example.orders.security.CurrentUser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * Admins turn customers and drivers off and on. An inactive user can still sign in (Keycloak does
 * that), but every API call except /api/me is refused (InactiveAccountFilter), so the page can
 * tell them to contact the administrator.
 *
 * Turning a driver off gives back the jobs they have not started, so those orders and returns go
 * back on the list for other drivers. It is refused while they carry a parcel or are on the way
 * to a pickup: those must be finished or marked failed first, or the goods would be stranded.
 */
@Service
public class AccountStatusService {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusService.class);

    public record Deactivate(String reason) {}

    /** What changed: the user's new state and how many open jobs were handed back. */
    public record Result(String userId, boolean active, int deliveriesReleased, int pickupsReleased) {}

    private final AppUserRepository users;
    private final DeliveryAssignmentRepository assignments;
    private final ReturnPickupRepository pickups;
    private final ReturnRequestRepository returns;
    private final OrderStateService orderStateService;
    private final CustomerNotificationService notifications;
    private final CurrentUser currentUser;

    // Checked on every request; a short cache keeps that off the database. A change made here is
    // seen at once on this pod, and within the expiry on the others.
    private final Cache<String, Boolean> activeCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public AccountStatusService(AppUserRepository users, DeliveryAssignmentRepository assignments,
                                ReturnPickupRepository pickups, ReturnRequestRepository returns,
                                OrderStateService orderStateService, CustomerNotificationService notifications,
                                CurrentUser currentUser) {
        this.users = users;
        this.assignments = assignments;
        this.pickups = pickups;
        this.returns = returns;
        this.orderStateService = orderStateService;
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    /** False when an admin turned the user off. Unknown users count as active (they are new). */
    public boolean isActive(String userId) {
        return activeCache.get(userId, id -> users.findById(id).map(AppUser::isActive).orElse(true));
    }

    @Transactional
    public Result deactivate(String userId, Deactivate request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (reason.length() < 3) {
            throw new InvalidOrderException("Give a reason (at least 3 characters); the next admin will see it");
        }
        if (reason.length() > 500) {
            throw new InvalidOrderException("The reason can be at most 500 characters");
        }
        String admin = currentUser.require().userId();
        AppUser user = target(userId, admin);
        if (!user.isActive()) {
            throw new InvalidOrderStateException(user.getFullName() + " is already inactive");
        }

        int deliveries = 0;
        int pickupsReleased = 0;
        if (user.getRoles().contains(Role.DELIVERY)) {
            deliveries = releaseDeliveries(user);
            pickupsReleased = releasePickups(user);
        }
        user.deactivate(admin, reason);
        users.save(user);
        forgetAfterCommit(userId);
        if (user.getEmail() != null) {
            notifications.queue(new CustomerNotification(null, user.getUserId(), "Your account has been paused",
                    "Hello " + user.getFullName() + ",\n\nYour account has been paused by our team, so you cannot "
                            + "use the app for now. Orders already on their way are not affected. Please contact the "
                            + "administrator if you think this is a mistake."));
        }
        log.warn("[USERS] {} deactivated {} ({}): {}. Released {} deliveries and {} pickups", admin, userId,
                user.getRoles(), reason, deliveries, pickupsReleased);
        return new Result(userId, false, deliveries, pickupsReleased);
    }

    @Transactional
    public Result activate(String userId) {
        String admin = currentUser.require().userId();
        AppUser user = target(userId, admin);
        if (user.isActive()) {
            throw new InvalidOrderStateException(user.getFullName() + " is already active");
        }
        user.reactivate();
        users.save(user);
        forgetAfterCommit(userId);
        if (user.getEmail() != null) {
            notifications.queue(new CustomerNotification(null, user.getUserId(), "Your account is active again",
                    "Hello " + user.getFullName() + ",\n\nYour account is active again. Welcome back."));
        }
        log.info("[USERS] {} reactivated {}", admin, userId);
        return new Result(userId, true, 0, 0);
    }

    /** Customers and drivers only: admins are managed in Keycloak, and nobody turns themselves off. */
    private AppUser target(String userId, String admin) {
        if (userId.equals(admin)) {
            throw new InvalidOrderStateException("You cannot change your own account");
        }
        AppUser user = users.findForUpdate(userId).orElseThrow(() -> new NotFoundException("No user " + userId));
        if (user.getRoles().contains(Role.ADMIN) || user.getRoles().contains(Role.SERVICE)) {
            throw new InvalidOrderStateException("Admin and service accounts are managed in Keycloak, not here");
        }
        return user;
    }

    /** Deliveries not picked up go back on the list. Each order is locked, as a pickup would lock it. */
    private int releaseDeliveries(AppUser driver) {
        int released = 0;
        for (DeliveryAssignment live : assignments.findLiveFor(driver.getUserId())) {
            orderStateService.lock(live.getOrderId());
            DeliveryAssignment current = assignments.findById(live.getId()).orElseThrow();
            if (current.getStatus() == DeliveryStatus.PICKED_UP) {
                throw new InvalidOrderStateException(driver.getFullName() + " is carrying order " + current.getOrderId()
                        + ". Wait until it is delivered or marked failed, then try again.");
            }
            if (current.getStatus() == DeliveryStatus.ASSIGNED) {
                current.setStatus(DeliveryStatus.REASSIGNED);
                assignments.save(current);
                released++;
            }
        }
        return released;
    }

    /** Return pickups the driver has not set out for go back on the list. */
    private int releasePickups(AppUser driver) {
        int released = 0;
        for (ReturnPickup live : pickups.findLiveFor(driver.getUserId())) {
            ReturnRequest ret = returns.findById(live.getReturnId()).orElseThrow();
            orderStateService.lock(ret.getOrderId());
            ReturnPickup current = pickups.findById(live.getId()).orElseThrow();
            if (current.getStatus() == ReturnPickup.Status.ON_THE_WAY) {
                throw new InvalidOrderStateException(driver.getFullName() + " is on the way to collect return "
                        + current.getReturnId() + ". Wait until it is collected or marked failed, then try again.");
            }
            if (current.getStatus() == ReturnPickup.Status.ASSIGNED) {
                current.setStatus(ReturnPickup.Status.REASSIGNED);
                pickups.save(current);
                released++;
            }
        }
        return released;
    }

    private void forgetAfterCommit(String userId) {
        activeCache.invalidate(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                activeCache.invalidate(userId);
            }
        });
    }
}
