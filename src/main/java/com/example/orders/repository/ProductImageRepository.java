package com.example.orders.repository;

import com.example.orders.model.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findBySkuOrderByPositionAscIdAsc(String sku);

    /** The photos of many products at once, for the shop's product list. */
    List<ProductImage> findBySkuInOrderBySkuAscPositionAscIdAsc(Collection<String> skus);

    long countBySku(String sku);

    /** True for keys this database knows, so the image endpoint never serves anything else. */
    boolean existsByLargeKeyOrThumbKey(String largeKey, String thumbKey);

    Optional<ProductImage> findByIdAndSku(Long id, String sku);
}
