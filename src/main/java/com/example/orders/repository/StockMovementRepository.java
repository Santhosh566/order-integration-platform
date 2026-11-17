package com.example.orders.repository;

import com.example.orders.model.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** Newest first. */
    Page<StockMovement> findBySkuOrderByIdDesc(String sku, Pageable pageable);

    List<StockMovement> findByOrderIdOrderById(String orderId);

    List<StockMovement> findBySkuOrderById(String sku);
}
