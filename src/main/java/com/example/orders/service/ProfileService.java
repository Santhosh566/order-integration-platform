package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.AppUser;
import com.example.orders.model.Role;
import com.example.orders.model.UserProfile;
import com.example.orders.model.UserProfile.Gender;
import com.example.orders.model.UserProfile.VehicleType;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.UserProfileRepository;
import com.example.orders.security.CurrentUser;
import com.example.orders.security.Viewer;
import com.example.orders.storage.ImageProcessor;
import com.example.orders.storage.ImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The signed-in user's own profile: name and phone for everyone, a photo, and the part that goes
 * with each role (customer, delivery user, staff). Email and password belong to Keycloak.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    // An Indian mobile number: 10 digits starting 6–9, with or without +91 / 0 in front
    private static final Pattern MOBILE = Pattern.compile("(?:\\+?91|0)?([6-9]\\d{9})");
    // State code, RTO number, series, number (KA01AB1234, DL3CAB1234), or the BH series (22BH1234AA)
    private static final Pattern VEHICLE = Pattern.compile("[A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{4}|\\d{2}BH\\d{4}[A-Z]{1,2}");
    // State code, RTO (2 digits), year of issue, 7-digit number: KA0120190001234
    private static final Pattern LICENCE = Pattern.compile("[A-Z]{2}\\d{13}");
    private static final Pattern UPI = Pattern.compile("[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,63}");
    private static final int MIN_AGE = 18;

    /** @param cartReminders emails about a cart left without ordering; null on an update keeps it */
    public record CustomerPart(LocalDate dateOfBirth, Gender gender, Boolean cartReminders) {}

    public record DriverPart(VehicleType vehicleType, String vehicleNumber, String licenceNumber,
                             String emergencyContactName, String emergencyContactPhone, String upiId) {}

    public record StaffPart(String jobTitle, String department) {}

    /** What the profile page shows. A part is null when the user does not have that role. */
    public record Profile(String userId, String email, Set<Role> roles, String fullName, String phone,
                          boolean hasPhoto, LocalDateTime photoUpdatedAt, LocalDateTime memberSince,
                          CustomerPart customer, DriverPart driver, StaffPart staff) {}

    /** What the page sends. Parts for roles the user does not have are ignored. */
    public record ProfileUpdate(String fullName, String phone, CustomerPart customer, DriverPart driver, StaffPart staff) {}

    private final CurrentUser currentUser;
    private final AppUserRepository users;
    private final UserProfileRepository profiles;
    private final ImageProcessor processor;
    private final ImageStorage storage;
    private final long maxUploadBytes;

    public ProfileService(CurrentUser currentUser, AppUserRepository users, UserProfileRepository profiles,
                          ImageProcessor processor, ImageStorage storage,
                          @Value("${orders.storage.max-upload-bytes}") long maxUploadBytes) {
        this.currentUser = currentUser;
        this.users = users;
        this.profiles = profiles;
        this.processor = processor;
        this.storage = storage;
        this.maxUploadBytes = maxUploadBytes;
    }

    @Transactional(readOnly = true)
    public Profile mine() {
        Viewer viewer = currentUser.require();
        AppUser user = users.findById(viewer.userId()).orElseThrow();
        return view(user, viewer.roles(), profiles.findById(user.getUserId()).orElse(null));
    }

    @Transactional
    public Profile update(ProfileUpdate update) {
        if (update == null) {
            throw new InvalidOrderException("Send the profile to save");
        }
        Viewer viewer = currentUser.require();
        AppUser user = users.findForUpdate(viewer.userId()).orElseThrow();
        UserProfile profile = profiles.findById(user.getUserId()).orElseGet(() -> new UserProfile(user.getUserId()));
        Set<Role> roles = viewer.roles();

        String name = text(update.fullName(), "Full name", 100);
        if (name == null || name.length() < 2) {
            throw new InvalidOrderException("Full name must be at least 2 characters");
        }
        user.setFullName(name);
        String phone = mobile(update.phone(), "Mobile number");
        if (phone == null && roles.contains(Role.DELIVERY)) {
            throw new InvalidOrderException("Mobile number is required: customers call it about their delivery");
        }
        user.setPhone(phone);

        if (roles.contains(Role.CUSTOMER) && update.customer() != null) {
            CustomerPart part = update.customer();
            profile.setDateOfBirth(birthday(part.dateOfBirth()));
            profile.setGender(part.gender());
            if (part.cartReminders() != null) {
                profile.setCartReminders(part.cartReminders());
            }
        }
        if (roles.contains(Role.DELIVERY) && update.driver() != null) {
            applyDriver(profile, update.driver(), phone);
        }
        if (roles.contains(Role.ADMIN) && update.staff() != null) {
            profile.setJobTitle(text(update.staff().jobTitle(), "Job title", 100));
            profile.setDepartment(text(update.staff().department(), "Department", 100));
        }
        profile.touch();
        profiles.save(profile);
        log.info("[PROFILE] {} updated their profile", user.getUserId());
        return view(user, roles, profile);
    }

    /** Replaces the photo. The old file is removed once the new one is committed. */
    @Transactional
    public Profile uploadPhoto(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw new InvalidOrderException("Choose a picture to upload");
        }
        if (upload.length > maxUploadBytes) {
            throw new InvalidOrderException("The picture is " + upload.length / 1024 / 1024 + " MB; at most "
                    + maxUploadBytes / 1024 / 1024 + " MB");
        }
        Viewer viewer = currentUser.require();
        AppUser user = users.findForUpdate(viewer.userId()).orElseThrow();
        byte[] square = processor.avatar(upload);

        UserProfile profile = profiles.findById(user.getUserId()).orElseGet(() -> new UserProfile(user.getUserId()));
        String old = profile.getPhotoKey();
        String key = "profiles/" + user.getUserId() + "/" + UUID.randomUUID() + "-" + ImageProcessor.AVATAR + ".jpg";
        storage.put(key, square, "image/jpeg");
        afterCommitOrRollback(old, key);
        profile.setPhoto(key);
        profile.touch();
        profiles.save(profile);
        log.info("[PROFILE] {} uploaded a profile photo ({} KB)", user.getUserId(), square.length / 1024);
        return view(user, viewer.roles(), profile);
    }

    @Transactional
    public Profile removePhoto() {
        Viewer viewer = currentUser.require();
        AppUser user = users.findForUpdate(viewer.userId()).orElseThrow();
        UserProfile profile = profiles.findById(user.getUserId()).orElse(null);
        if (profile != null && profile.getPhotoKey() != null) {
            afterCommitOrRollback(profile.getPhotoKey(), null);
            profile.setPhoto(null);
            profile.touch();
        }
        return view(user, viewer.roles(), profile);
    }

    /**
     * The photo of a user, for the user themselves or an admin; null when there is none or the
     * viewer may not see it (the caller answers 404 for both, so nobody learns who has one).
     */
    @Transactional(readOnly = true)
    public ImageStorage.StoredImage photo(String userId) {
        Viewer viewer = currentUser.require();
        if (!viewer.userId().equals(userId) && !viewer.isAdmin()) {
            return null;
        }
        return storedPhoto(userId);
    }

    /** The photo without an access check: the caller has decided the viewer may see it. */
    @Transactional(readOnly = true)
    public ImageStorage.StoredImage storedPhoto(String userId) {
        return profiles.findById(userId)
                .map(UserProfile::getPhotoKey)
                .map(storage::get)
                .orElse(null);
    }

    /** Any user's profile, for an admin looking them up; their stored roles decide the parts. */
    @Transactional(readOnly = true)
    public Profile profileOf(String userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> new com.example.orders.exception.NotFoundException("No user " + userId));
        return view(user, user.getRoles(), profiles.findById(userId).orElse(null));
    }

    // ── Parts ──────────────────────────────────────────────────────────────────

    private void applyDriver(UserProfile profile, DriverPart part, String ownPhone) {
        VehicleType vehicle = part.vehicleType();
        if (vehicle == null) {
            throw new InvalidOrderException("Choose how you deliver (vehicle type)");
        }
        String number = upper(part.vehicleNumber());
        String licence = upper(part.licenceNumber());
        if (vehicle.isMotorised()) {
            if (number == null || !VEHICLE.matcher(number).matches()) {
                throw new InvalidOrderException("Vehicle number must look like KA01AB1234 (or 22BH1234AA)");
            }
            if (licence == null || !LICENCE.matcher(licence).matches()) {
                throw new InvalidOrderException("Driving licence number must be 15 characters, like KA0120190001234");
            }
        } else {
            // A cycle or walking needs neither; anything sent is dropped rather than kept unchecked
            number = null;
            licence = null;
        }
        String contactName = text(part.emergencyContactName(), "Emergency contact name", 100);
        String contactPhone = mobile(part.emergencyContactPhone(), "Emergency contact number");
        if ((contactName == null) != (contactPhone == null)) {
            throw new InvalidOrderException("Give both the emergency contact's name and number, or neither");
        }
        if (contactPhone != null && contactPhone.equals(ownPhone)) {
            throw new InvalidOrderException("The emergency contact needs a number other than your own");
        }
        String upi = part.upiId() == null || part.upiId().isBlank() ? null : part.upiId().trim();
        if (upi != null && !UPI.matcher(upi).matches()) {
            throw new InvalidOrderException("UPI ID must look like name@bank");
        }
        profile.setVehicleType(vehicle);
        profile.setVehicleNumber(number);
        profile.setLicenceNumber(licence);
        profile.setEmergencyContactName(contactName);
        profile.setEmergencyContactPhone(contactPhone);
        profile.setUpiId(upi == null ? null : upi.toLowerCase(Locale.ROOT));
    }

    private static Profile view(AppUser user, Set<Role> roles, UserProfile profile) {
        UserProfile p = profile != null ? profile : new UserProfile(user.getUserId());
        return new Profile(user.getUserId(), user.getEmail(), roles, user.getFullName(), user.getPhone(),
                p.getPhotoKey() != null, p.getPhotoUpdatedAt(), user.getCreatedAt(),
                roles.contains(Role.CUSTOMER) ? new CustomerPart(p.getDateOfBirth(), p.getGender(), p.isCartReminders()) : null,
                roles.contains(Role.DELIVERY) ? new DriverPart(p.getVehicleType(), p.getVehicleNumber(),
                        p.getLicenceNumber(), p.getEmergencyContactName(), p.getEmergencyContactPhone(), p.getUpiId()) : null,
                roles.contains(Role.ADMIN) ? new StaffPart(p.getJobTitle(), p.getDepartment()) : null);
    }

    // ── Checks ─────────────────────────────────────────────────────────────────

    /** Stored as +91-98450-00101, the way addresses already write it; null when left empty. */
    static String mobile(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var match = MOBILE.matcher(value.replaceAll("[\\s()-]", ""));
        if (!match.matches()) {
            throw new InvalidOrderException(field + " must be a 10-digit Indian mobile number");
        }
        String digits = match.group(1);
        return "+91-" + digits.substring(0, 5) + "-" + digits.substring(5);
    }

    private static LocalDate birthday(LocalDate date) {
        if (date == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        int age = Period.between(date, today).getYears();
        if (date.isAfter(today) || age < MIN_AGE) {
            throw new InvalidOrderException("You must be at least " + MIN_AGE + " to shop here");
        }
        if (age > 120) {
            throw new InvalidOrderException("Check the date of birth");
        }
        return date;
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() > max) {
            throw new InvalidOrderException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static String upper(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** On commit the old file goes; on rollback the new one does. Either way nothing is left behind. */
    private void afterCommitOrRollback(String oldKey, String newKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String gone = status == STATUS_COMMITTED ? oldKey : newKey;
                if (gone != null) {
                    try {
                        storage.remove(gone);
                    } catch (RuntimeException e) {
                        log.warn("[PROFILE] Could not remove photo {}: {}", gone, e.getMessage());
                    }
                }
            }
        });
    }
}
