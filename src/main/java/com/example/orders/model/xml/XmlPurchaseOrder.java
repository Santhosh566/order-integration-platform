package com.example.orders.model.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * JAXB-annotated model for legacy B2B supplier XML purchase orders.
 */
@XmlRootElement(name = "purchaseOrder")
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlPurchaseOrder {

    @XmlElement(name = "orderId", required = true)
    private String orderId;

    @XmlElement(name = "customerId", required = true)
    private String customerId;

    @XmlElementWrapper(name = "items")
    @XmlElement(name = "item")
    private List<XmlPurchaseOrderItem> items = new ArrayList<>();

    public XmlPurchaseOrder() {}

    public XmlPurchaseOrder(String orderId, String customerId, List<XmlPurchaseOrderItem> items) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.items = items;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public List<XmlPurchaseOrderItem> getItems() {
        return items;
    }

    public void setItems(List<XmlPurchaseOrderItem> items) {
        this.items = items;
    }
}
