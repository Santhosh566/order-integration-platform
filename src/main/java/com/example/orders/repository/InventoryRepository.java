package com.example.orders.repository;

import com.example.orders.model.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<InventoryItem, String> {

    /**
     * Loads a stock row and locks it, so two orders cannot reserve the same units.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.sku = :sku")
    Optional<InventoryItem> findForUpdate(String sku);

    /** Admin list: matches the SKU or the name, case-insensitive; pattern is "%text%". */
    @Query("select i from InventoryItem i where (:includeInactive = true or i.active = true) "
         + "and (lower(i.sku) like :pattern or lower(coalesce(i.productName, '')) like :pattern)")
    Page<InventoryItem> search(String pattern, boolean includeInactive, Pageable pageable);

    /** What the shop shows: products on sale with a price. */
    List<InventoryItem> findByActiveTrueAndUnitPriceIsNotNullOrderBySku();
}
