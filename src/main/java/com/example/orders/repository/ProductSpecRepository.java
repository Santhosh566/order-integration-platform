package com.example.orders.repository;

import com.example.orders.model.ProductSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductSpecRepository extends JpaRepository<ProductSpec, ProductSpec.Key> {

    List<ProductSpec> findBySkuOrderByPositionAsc(String sku);

    List<ProductSpec> findBySkuInOrderBySkuAscPositionAsc(Collection<String> skus);

    @Modifying
    @Query("delete from ProductSpec s where s.sku = :sku")
    void deleteBySku(String sku);
}
