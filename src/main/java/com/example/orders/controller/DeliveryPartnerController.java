package com.example.orders.controller;

import com.example.orders.delivery.DeliveryPartnerService;
import com.example.orders.storage.ImageStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** The driver bringing an order, for its customer (and admins). */
@RestController
public class DeliveryPartnerController {

    private final DeliveryPartnerService partners;

    public DeliveryPartnerController(DeliveryPartnerService partners) {
        this.partners = partners;
    }

    @GetMapping("/api/orders/{orderId}/delivery-partner")
    public DeliveryPartnerService.View partner(@PathVariable String orderId) {
        return partners.partner(orderId);
    }

    @GetMapping("/api/orders/{orderId}/delivery-partner/photo")
    public ResponseEntity<InputStreamResource> photo(@PathVariable String orderId) {
        ImageStorage.StoredImage image = partners.photo(orderId);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff");
        if (image.size() >= 0) {
            response.contentLength(image.size());
        }
        return response.body(new InputStreamResource(image.stream()));
    }
}
