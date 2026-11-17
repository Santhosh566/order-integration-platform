package com.example.orders.controller;

import com.example.orders.service.ProfileService;
import com.example.orders.storage.ImageStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * Every signed-in user's own profile and photo. Photos are personal data: never public, and only
 * the user and admins can load one.
 */
@RestController
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/me/profile")
    public ProfileService.Profile mine() {
        return profiles.mine();
    }

    @PutMapping("/api/me/profile")
    public ProfileService.Profile update(@RequestBody ProfileService.ProfileUpdate update) {
        return profiles.update(update);
    }

    /** multipart/form-data with "file": a JPEG, PNG or WebP; the middle square is kept. */
    @PostMapping(value = "/api/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileService.Profile uploadPhoto(@RequestParam("file") MultipartFile file) throws IOException {
        return profiles.uploadPhoto(file.getBytes());
    }

    @DeleteMapping("/api/me/photo")
    public ProfileService.Profile removePhoto() {
        return profiles.removePhoto();
    }

    /** The page adds ?v=<photoUpdatedAt>, so a new photo is a new address and the old one may be kept a day. */
    @GetMapping("/api/users/{userId}/photo")
    public ResponseEntity<InputStreamResource> photo(@PathVariable String userId) {
        ImageStorage.StoredImage image = profiles.photo(userId);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff");
        if (image.size() >= 0) {
            response.contentLength(image.size());
        }
        return response.body(new InputStreamResource(image.stream()));
    }
}
