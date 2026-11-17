package com.example.orders.controller;

import com.example.orders.model.AppUser;
import com.example.orders.model.CustomerAddress;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.CustomerAddressRepository;
import com.example.orders.security.CurrentUser;
import com.example.orders.security.Viewer;
import com.example.orders.service.OrderValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The signed-in user's own profile, and the Keycloak settings the web page needs to sign in.
 * Sign-up, login and passwords are Keycloak's job.
 */
@RestController
@RequestMapping("/api")
public class AccountController {

    private final CurrentUser currentUser;
    private final AppUserRepository users;
    private final CustomerAddressRepository addresses;
    private final com.example.orders.repository.UserProfileRepository profiles;
    private final String issuerUri;
    private final String webClientId;

    public AccountController(CurrentUser currentUser,
                             AppUserRepository users,
                             CustomerAddressRepository addresses,
                             com.example.orders.repository.UserProfileRepository profiles,
                             @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
                             @Value("${orders.security.web-client-id}") String webClientId) {
        this.currentUser = currentUser;
        this.users = users;
        this.addresses = addresses;
        this.profiles = profiles;
        this.issuerUri = issuerUri;
        this.webClientId = webClientId;
    }

    @GetMapping("/public/auth-config")
    public Map<String, String> authConfig() {
        return Map.of("issuer", issuerUri, "clientId", webClientId);
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        Viewer viewer = currentUser.require();
        AppUser user = users.findById(viewer.userId()).orElseThrow();
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("userId", user.getUserId());
        me.put("fullName", user.getFullName());
        me.put("email", user.getEmail());
        me.put("phone", user.getPhone());
        // False when an admin turned the account off: the page then shows "contact the administrator"
        me.put("active", user.isActive());
        // The header shows the photo; the time makes a new photo a new address, past any cache
        me.put("photoUpdatedAt", profiles.findById(user.getUserId())
                .filter(p -> p.getPhotoKey() != null).map(p -> p.getPhotoUpdatedAt()).orElse(null));
        me.put("roles", viewer.roles());
        me.put("defaultAddress", addresses.findByUserIdAndDefaultAddressTrue(user.getUserId())
                .map(CustomerAddress::getAddress).orElse(null));
        return me;
    }

    /**
     * Saves the address new orders are delivered to when they do not name one.
     */
    @PutMapping("/me/address")
    @Transactional
    public ShippingAddress setDefaultAddress(@RequestBody ShippingAddress address) {
        OrderValidator.validateAddress(address);
        String userId = currentUser.require().userId();
        addresses.findByUserIdAndDefaultAddressTrue(userId).ifPresent(previous -> {
            previous.setDefaultAddress(false);
            // The old default must be cleared first: the table allows only one per customer
            addresses.saveAndFlush(previous);
        });
        addresses.save(new CustomerAddress(userId, "Default", address, true));
        return address;
    }
}
