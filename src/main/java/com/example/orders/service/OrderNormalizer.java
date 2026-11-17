package com.example.orders.service;

import com.example.orders.money.Money;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.csv.CsvOrderItemRecord;
import com.example.orders.model.xml.XmlPurchaseOrder;
import com.example.orders.model.xml.XmlPurchaseOrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.3: Normalizer EIP
 * Translates heterogeneous incoming message formats (B2B CSV files, Supplier XML documents)
 * into standard Canonical Order entities so the downstream fulfillment engine is completely format-agnostic.
 */
@Service("orderNormalizer")
public class OrderNormalizer {

    private static final Logger log = LoggerFactory.getLogger(OrderNormalizer.class);

    /**
     * Normalizes a list of CSV records (from dropship file uploads) into canonical Order entities.
     * Groups multiple lines by orderId into single composite orders with items.
     */
    public List<Order> normalizeFromCsv(List<CsvOrderItemRecord> csvRecords) {
        log.info("[NORMALIZER EIP: CSV] Normalizing {} CSV record(s) into canonical Order model...", csvRecords.size());

        Map<String, Order> orderMap = new LinkedHashMap<>();

        for (CsvOrderItemRecord record : csvRecords) {
            Order order = orderMap.computeIfAbsent(record.getOrderId(), id -> {
                // The partner's order number is kept as the reference; the server issues the id
                Order o = new Order();
                o.setOrderId(Order.newId());
                o.setExternalReference(record.getOrderId());
                o.setCustomerId(record.getCustomerId());
                o.setItems(new ArrayList<>());
                return o;
            });

            OrderItem item = new OrderItem(record.getSku(), record.getQuantity(), Money.ZERO);
            order.getItems().add(item);
        }

        List<Order> normalizedOrders = new ArrayList<>(orderMap.values());
        for (Order order : normalizedOrders) {
            log.info("[NORMALIZER EIP: CSV] Created Canonical Order: {} (Customer: {}, Items: {})",
                    order.getOrderId(), order.getCustomerId(), order.getItems().size());
        }

        return normalizedOrders;
    }

    /**
     * Normalizes a JAXB XML purchase order into a canonical Order entity.
     */
    public Order normalizeFromXml(XmlPurchaseOrder xmlOrder) {
        log.info("[NORMALIZER EIP: XML] Normalizing XML Purchase Order: {} (Customer: {}) into canonical model...",
                xmlOrder.getOrderId(), xmlOrder.getCustomerId());

        Order order = new Order();
        order.setOrderId(Order.newId());
        order.setExternalReference(xmlOrder.getOrderId());
        order.setCustomerId(xmlOrder.getCustomerId());
        order.setItems(new ArrayList<>());

        if (xmlOrder.getItems() != null) {
            for (XmlPurchaseOrderItem xmlItem : xmlOrder.getItems()) {
                OrderItem item = new OrderItem(xmlItem.getSku(), xmlItem.getQuantity(), Money.ZERO);
                order.getItems().add(item);
            }
        }

        log.info("[NORMALIZER EIP: XML] Created Canonical Order: {} (Customer: {}, Items: {})",
                order.getOrderId(), order.getCustomerId(), order.getItems().size());

        return order;
    }
}
