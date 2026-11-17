package com.example.orders.model.xml;

import jakarta.xml.bind.annotation.XmlRegistry;

/**
 * ObjectFactory required by JAXB context scanning.
 */
@XmlRegistry
public class ObjectFactory {

    public ObjectFactory() {}

    public XmlPurchaseOrder createXmlPurchaseOrder() {
        return new XmlPurchaseOrder();
    }

    public XmlPurchaseOrderItem createXmlPurchaseOrderItem() {
        return new XmlPurchaseOrderItem();
    }
}
