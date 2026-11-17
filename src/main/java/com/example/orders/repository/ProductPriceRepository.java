package com.example.orders.repository;

import com.example.orders.model.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ProductPriceRepository extends JpaRepository<ProductPrice, Long> {

    /** Newest first. */
    List<ProductPrice> findBySkuOrderByEffectiveFromDescIdDesc(String sku);

    /**
     * Scheduled prices that are due, oldest first. SKIP LOCKED lets several pods run the
     * scheduler without applying the same price twice.
     */
    @Query(value = "SELECT * FROM product_prices WHERE applied_at IS NULL AND cancelled_at IS NULL "
                 + "AND effective_from <= :now ORDER BY effective_from, id LIMIT 100 FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<ProductPrice> lockDue(LocalDateTime now);
}
