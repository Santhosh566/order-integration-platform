package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.exception.ProductNotFoundException;
import com.example.orders.model.ProductImage;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.ProductImageRepository;
import com.example.orders.storage.ImageProcessor;
import com.example.orders.storage.ImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Product photos: upload, order, alt text, delete. The files go to MinIO, their keys to the database.
 * Every upload gets new keys, so a cached picture can be kept forever and is never out of date.
 */
@Service
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    public static final int MAX_PHOTOS = 8;

    private final ProductImageRepository images;
    private final InventoryRepository inventory;
    private final ImageStorage storage;
    private final ImageProcessor processor;
    private final StockLedger stockLedger;
    private final long maxUploadBytes;

    public ProductImageService(ProductImageRepository images, InventoryRepository inventory, ImageStorage storage,
                               ImageProcessor processor, StockLedger stockLedger,
                               @Value("${orders.storage.max-upload-bytes}") long maxUploadBytes) {
        this.images = images;
        this.inventory = inventory;
        this.storage = storage;
        this.processor = processor;
        this.stockLedger = stockLedger;
        this.maxUploadBytes = maxUploadBytes;
    }

    @Transactional
    public ProductImage upload(String sku, byte[] upload, String altText) {
        // Locks the product, so two uploads at once cannot both take the eighth place
        inventory.findForUpdate(sku).orElseThrow(() -> new ProductNotFoundException(sku));
        if (upload == null || upload.length == 0) {
            throw new InvalidOrderException("Choose a picture to upload");
        }
        if (upload.length > maxUploadBytes) {
            throw new InvalidOrderException("The picture is " + upload.length / 1024 / 1024 + " MB; at most "
                    + maxUploadBytes / 1024 / 1024 + " MB");
        }
        long count = images.countBySku(sku);
        if (count >= MAX_PHOTOS) {
            throw new InvalidOrderStateException("A product has at most " + MAX_PHOTOS + " photos; delete one first");
        }
        String alt = checkAlt(altText);
        ImageProcessor.Processed photo = processor.process(upload);

        String base = "products/" + sku + "/" + UUID.randomUUID();
        String largeKey = base + "-" + ImageProcessor.LARGE + ".jpg";
        String thumbKey = base + "-" + ImageProcessor.THUMB + ".jpg";
        storage.put(largeKey, photo.large(), "image/jpeg");
        try {
            storage.put(thumbKey, photo.thumb(), "image/jpeg");
        } catch (RuntimeException e) {
            removeQuietly(largeKey);
            throw e;
        }
        // If the database refuses the row, the files must not stay behind without it
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    removeQuietly(largeKey);
                    removeQuietly(thumbKey);
                }
            }
        });
        ProductImage image = images.save(new ProductImage(sku, largeKey, thumbKey, (int) count, alt,
                photo.width(), photo.height(), photo.large().length, stockLedger.actor()));
        log.info("[PHOTOS] {} added photo {} to {} ({} × {}, {} KB)", image.getUploadedBy(), image.getId(), sku,
                photo.width(), photo.height(), photo.large().length / 1024);
        return image;
    }

    /** Removes the row now and the files once that is committed; an orphaned file is harmless. */
    @Transactional
    public void delete(String sku, Long imageId) {
        ProductImage image = find(sku, imageId);
        images.delete(image);
        List<ProductImage> rest = new ArrayList<>(images.findBySkuOrderByPositionAscIdAsc(sku));
        rest.remove(image);
        renumber(rest);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                removeQuietly(image.getLargeKey());
                removeQuietly(image.getThumbKey());
            }
        });
        log.info("[PHOTOS] {} deleted photo {} of {}", stockLedger.actor(), imageId, sku);
    }

    /** The new order of every photo of the product; the first becomes the main photo. */
    @Transactional
    public List<ProductImage> reorder(String sku, List<Long> imageIds) {
        List<ProductImage> current = images.findBySkuOrderByPositionAscIdAsc(sku);
        if (imageIds == null || imageIds.size() != current.size()
                || !new HashSet<>(imageIds).equals(new HashSet<>(current.stream().map(ProductImage::getId).toList()))) {
            throw new InvalidOrderException("Send every photo of " + sku + " once, in the new order");
        }
        List<ProductImage> ordered = imageIds.stream()
                .map(id -> current.stream().filter(i -> i.getId().equals(id)).findFirst().orElseThrow())
                .toList();
        renumber(ordered);
        return ordered;
    }

    @Transactional
    public ProductImage describe(String sku, Long imageId, String altText) {
        ProductImage image = find(sku, imageId);
        image.setAltText(checkAlt(altText));
        return image;
    }

    /** Only keys this database knows are served, so the endpoint cannot be used to read anything else. */
    @Transactional(readOnly = true)
    public boolean isKnownKey(String key) {
        return images.existsByLargeKeyOrThumbKey(key, key);
    }

    private ProductImage find(String sku, Long imageId) {
        return images.findByIdAndSku(imageId, sku)
                .orElseThrow(() -> new NotFoundException("Photo " + imageId + " of " + sku + " not found"));
    }

    private static void renumber(List<ProductImage> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
    }

    private static String checkAlt(String altText) {
        if (altText == null || altText.isBlank()) {
            return null;
        }
        String alt = altText.strip();
        if (alt.length() > 200) {
            throw new InvalidOrderException("The photo description may have at most 200 characters");
        }
        return alt;
    }

    private void removeQuietly(String key) {
        try {
            storage.remove(key);
        } catch (RuntimeException e) {
            log.warn("[PHOTOS] Left {} in storage: {}", key, e.getMessage());
        }
    }
}
