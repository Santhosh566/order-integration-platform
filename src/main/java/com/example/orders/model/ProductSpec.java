package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** One line of a product's specification table, e.g. "RAM" = "8 GB". */
@Entity
@Table(name = "product_specs")
@IdClass(ProductSpec.Key.class)
public class ProductSpec {

    @Id
    @Column(nullable = false, length = 64)
    private String sku;

    @Id
    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 200)
    private String value;

    @Column(nullable = false)
    private int position;

    protected ProductSpec() {}

    public ProductSpec(String sku, String name, String value, int position) {
        this.sku = sku;
        this.name = name;
        this.value = value;
        this.position = position;
    }

    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getValue() { return value; }
    public int getPosition() { return position; }

    public static class Key implements Serializable {
        private String sku;
        private String name;

        public Key() {}

        public Key(String sku, String name) {
            this.sku = sku;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(sku, k.sku) && Objects.equals(name, k.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sku, name);
        }
    }
}
