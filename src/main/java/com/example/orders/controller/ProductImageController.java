package com.example.orders.controller;

import com.example.orders.model.ProductImage;
import com.example.orders.service.ProductImageService;
import com.example.orders.storage.ImageStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Product photos: admins upload and arrange them; anyone can view them (they are on the shop window).
 * Promotion banners (PromotionController) are served from here too, under promotions/.
 */
@RestController
public class ProductImageController {

    private static final String IMAGES = "/api/public/images/";

    private final ProductImageService photos;
    private final ImageStorage storage;
    private final com.example.orders.service.PromotionService promotions;

    public ProductImageController(ProductImageService photos, ImageStorage storage,
                                  com.example.orders.service.PromotionService promotions) {
        this.photos = photos;
        this.storage = storage;
        this.promotions = promotions;
    }

    /** multipart/form-data: "file", and optionally "altText" describing the photo for screen readers. */
    @PostMapping(value = "/api/admin/catalog/{sku}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProductImage upload(@PathVariable String sku, @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "altText", required = false) String altText) throws IOException {
        return photos.upload(sku, file.getBytes(), altText);
    }

    @DeleteMapping("/api/admin/catalog/{sku}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String sku, @PathVariable Long imageId) {
        photos.delete(sku, imageId);
    }

    /** Body: {"imageIds": [3, 1, 2]}; the first is the main photo. */
    @PutMapping("/api/admin/catalog/{sku}/images/order")
    public List<ProductImage> reorder(@PathVariable String sku, @RequestBody Map<String, List<Long>> body) {
        return photos.reorder(sku, body.get("imageIds"));
    }

    @PatchMapping("/api/admin/catalog/{sku}/images/{imageId}")
    public ProductImage describe(@PathVariable String sku, @PathVariable Long imageId,
                                 @RequestBody Map<String, String> body) {
        return photos.describe(sku, imageId, body.get("altText"));
    }

    /**
     * A photo, e.g. /api/public/images/products/PEN-01/9f…-400.jpg. Keys never change, so browsers
     * may keep it for a year without asking again.
     */
    @GetMapping(IMAGES + "**")
    public ResponseEntity<InputStreamResource> image(HttpServletRequest request) {
        String key = request.getRequestURI().substring(request.getContextPath().length() + IMAGES.length());
        boolean known = key.startsWith("promotions/") ? promotions.isKnownImage(key) : photos.isKnownKey(key);
        if (key.contains("..") || !known) {
            return ResponseEntity.notFound().build();
        }
        ImageStorage.StoredImage image = storage.get(key);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag("\"" + Integer.toHexString(key.hashCode()) + "\"")
                .header("X-Content-Type-Options", "nosniff");
        if (image.size() >= 0) {
            response.contentLength(image.size());
        }
        return response.body(new InputStreamResource(image.stream()));
    }
}
