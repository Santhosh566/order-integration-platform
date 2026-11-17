package com.example.orders.service;

import com.example.orders.model.AppUser;
import com.example.orders.model.CustomerAddress;
import com.example.orders.model.CustomerProfile;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.CustomerAddressRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Phase 3.5: Service providing customer profile data for Content Enricher EIP,
 * read from the users table through a high-speed in-memory Caffeine Cache.
 */
@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final AppUserRepository userRepository;
    private final CustomerAddressRepository addressRepository;

    // Phase 3.5: In-Memory Caffeine Cache with 10-minute TTL and max 10,000 entries
    private final Cache<String, CustomerProfile> profileCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build();

    public CustomerService(AppUserRepository userRepository, CustomerAddressRepository addressRepository) {
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
    }

    /**
     * Returns the customer's profile, or null when there is no such user.
     */
    public CustomerProfile getProfile(String customerId) {
        // Check Caffeine cache
        CustomerProfile cached = profileCache.getIfPresent(customerId);
        if (cached != null) {
            log.info("[CAFFEINE CACHE HIT] Fast in-memory lookup for Customer ID: {}", customerId);
            return cached;
        }

        // Cache miss: retrieve from the database and store in Caffeine
        log.info("[CAFFEINE CACHE MISS] Loading Customer ID: {} from database...", customerId);
        AppUser user = userRepository.findById(customerId).orElse(null);
        if (user == null) {
            return null;
        }
        CustomerProfile profile = new CustomerProfile(user.getUserId(), user.getFullName(),
                user.getEmail(), user.getPhone(), user.getTier());
        profileCache.put(customerId, profile);
        return profile;
    }

    public boolean isActiveCustomer(String customerId) {
        return userRepository.findById(customerId).filter(AppUser::isActive).isPresent();
    }

    public Optional<ShippingAddress> defaultAddress(String customerId) {
        return addressRepository.findByUserIdAndDefaultAddressTrue(customerId).map(CustomerAddress::getAddress);
    }
}
