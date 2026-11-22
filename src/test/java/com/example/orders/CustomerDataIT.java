package com.example.orders;

import com.example.orders.model.AppUser;
import com.example.orders.model.CustomerAddress;
import com.example.orders.model.DeliveryAssignment;
import com.example.orders.model.DeliveryStatus;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.Role;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.CustomerAddressRepository;
import com.example.orders.repository.DeliveryAssignmentRepository;
import com.example.orders.service.WarehouseRoutingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Customers and addresses come from the database, and the constraints on the new tables hold.
 */
class CustomerDataIT extends IntegrationTestBase {

    @Autowired
    AppUserRepository users;

    @Autowired
    CustomerAddressRepository addresses;

    @Autowired
    DeliveryAssignmentRepository deliveries;

    @Autowired
    WarehouseRoutingService routing;

    // ── Orders and customers ────────────────────────────────────────────────

    @Test
    void demoCustomer_isLoadedFromTheDatabase_andOrderUsesTheDefaultAddress() {
        String orderId = placeOrder(newSku(5), 1, 10.00);

        ShippingAddress address = order(orderId).getShippingAddress();
        assertThat(address.getRecipientName()).isEqualTo("Alice Smith");
        assertThat(address.getCity()).isEqualTo("Bengaluru");
        assertThat(users.findById("CUST-100").orElseThrow().getRoles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    void changingASavedAddress_doesNotChangeAnExistingOrder() {
        AppUser customer = newCustomer();
        CustomerAddress saved = addresses.save(new CustomerAddress(
                customer.getUserId(), "Home", address("Hyderabad", "Telangana"), true));
        String orderId = submitOrder(orderFor(customer.getUserId(), newSku(5)))
                .statusCode(202).extract().path("orderId");

        saved.getAddress().setCity("Pune");
        addresses.save(saved);

        assertThat(order(orderId).getShippingAddress().getCity()).isEqualTo("Hyderabad");
    }

    @Test
    void addressInTheRequest_isStoredOnTheOrder() {
        Map<String, Object> request = orderFor("CUST-100", newSku(5));
        request.put("shippingAddress", addressJson());

        String orderId = submitOrder(request).statusCode(202).extract().path("orderId");

        assertThat(order(orderId).getShippingAddress().getCity()).isEqualTo("Pune");
    }

    @Test
    void unknownCustomer_isRefused() {
        submitOrder(orderFor("CUST-DOES-NOT-EXIST", newSku(5))).statusCode(400);
    }

    @Test
    void disabledCustomer_isRefused() {
        AppUser customer = newCustomer();
        addresses.save(new CustomerAddress(customer.getUserId(), "Home", address("Hyderabad", "Telangana"), true));
        customer.setActive(false);
        users.save(customer);

        submitOrder(orderFor(customer.getUserId(), newSku(5))).statusCode(400);
    }

    @Test
    void customerWithNoAddress_mustSendOne() {
        AppUser customer = newCustomer();
        String sku = newSku(5);

        submitOrder(orderFor(customer.getUserId(), sku)).statusCode(400);

        Map<String, Object> withAddress = orderFor(customer.getUserId(), sku);
        withAddress.put("shippingAddress", addressJson());
        submitOrder(withAddress).statusCode(202);
    }

    @Test
    void incompleteAddress_isRefused() {
        Map<String, Object> missingCity = addressJson();
        missingCity.remove("city");
        Map<String, Object> badPhone = addressJson();
        badPhone.put("recipientPhone", "call me");

        for (Map<String, Object> bad : List.of(missingCity, badPhone)) {
            Map<String, Object> request = orderFor("CUST-100", newSku(5));
            request.put("shippingAddress", bad);
            submitOrder(request).statusCode(400);
        }
    }

    @Test
    void customerWithoutContactDetails_isStillDelivered() {
        AppUser customer = newCustomer();
        Map<String, Object> request = orderFor(customer.getUserId(), newSku(5));
        request.put("shippingAddress", addressJson());

        String orderId = submitOrder(request).statusCode(202).extract().path("orderId");

        awaitStatus(orderId, OrderStatus.DELIVERED);
    }

    @Test
    void invoice_isAPdf_evenWhenTheNameLooksLikeMarkup() {
        Map<String, Object> shippingAddress = addressJson();
        shippingAddress.put("recipientName", "<script>alert(1)</script>");
        Map<String, Object> request = orderFor("CUST-100", newSku(5));
        request.put("shippingAddress", shippingAddress);
        String orderId = submitOrder(request).statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.DELIVERED);

        // The PDF is the only invoice; there is no HTML version a name could inject a script into
        byte[] pdf = given().get("/api/invoices/" + orderId).then()
                .statusCode(200).contentType("application/pdf").extract().asByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    // ── Fulfillment hubs ────────────────────────────────────────────────────

    @Test
    void thePickListGoesToTheWarehouseTheOrderShipsFrom_whereverTheCustomerIs() {
        // Every order: its warehouse and head office, whatever the delivery state
        assertThat(hubsFor("CUST-100", address("Lucknow", "Uttar Pradesh"))).startsWith("direct:warehouseHub,direct:hubCentral");
        assertThat(hubsFor("CUST-100", address("Bengaluru", "Karnataka"))).startsWith("direct:warehouseHub,direct:hubCentral");
        assertThat(routing.resolveFulfillmentRecipients(null)).isEqualTo("direct:hubCentral");

        stockJdbc.update("insert into warehouses (code, name, address, pin, state_code, gstin, location, invoice_prefix, credit_note_prefix) "
                + "values ('GGN', 'Gurugram fulfilment centre', 'Sector 37, Gurugram, Haryana', '122001', '06', '06AABCO1234F1Z9', "
                + "ST_SetSRID(ST_MakePoint(77.0266, 28.4595), 4326)::geography, 'GGI', 'GGC') on conflict (code) do nothing");
        Long ggn = stockJdbc.queryForObject("select warehouse_id from warehouses where code = 'GGN'", Long.class);
        Order fromGurugram = new Order();
        fromGurugram.setOrderId("ORD-" + shortId());
        fromGurugram.setShippingAddress(address("Bengaluru", "Karnataka"));
        fromGurugram.setWarehouseId(ggn);
        // Delivered in Karnataka, but packed in Gurugram: Gurugram gets the pick list
        assertThat(routing.pickListWarehouse(fromGurugram).code()).isEqualTo("GGN");
        // Orders from before warehouses were recorded: the default one
        Order old = new Order();
        old.setOrderId("ORD-" + shortId());
        assertThat(routing.pickListWarehouse(old).code()).isEqualTo("BLR");
    }

    @Test
    void priorityHub_followsTheStoredTier_notTheIdText() {
        AppUser vip = newCustomer();
        vip.setTier("VIP");
        users.save(vip);
        AppUser standard = users.save(new AppUser("CUST-VIP-LOOKALIKE-" + shortId(), "Standard", Role.CUSTOMER));

        assertThat(hubsFor(vip.getUserId(), address("Bengaluru", "KA"))).contains("hubPriorityWhiteGlove");
        assertThat(hubsFor(standard.getUserId(), address("Bengaluru", "KA"))).doesNotContain("hubPriorityWhiteGlove");
    }

    // ── Table constraints ───────────────────────────────────────────────────

    @Test
    void anEmail_belongsToOneUser_ignoringCase() {
        String email = shortId() + "@example.com";
        AppUser first = newCustomer();
        first.setEmail(email);
        users.save(first);

        AppUser second = newCustomer();
        second.setEmail(email.toUpperCase());

        assertThatThrownBy(() -> users.save(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCustomer_hasOneDefaultAddress() {
        AppUser customer = newCustomer();
        addresses.save(new CustomerAddress(customer.getUserId(), "Home", address("Hyderabad", "Telangana"), true));

        assertThatThrownBy(() -> addresses.save(new CustomerAddress(
                customer.getUserId(), "Office", address("Kochi", "Kerala"), true)))
            .isInstanceOf(DataIntegrityViolationException.class);

        addresses.save(new CustomerAddress(customer.getUserId(), "Office", address("Kochi", "Kerala"), false));
    }

    @Test
    void anOrder_hasOneLiveDeliveryAttempt() {
        String orderId = placeOrder(newSku(5), 1, 10.00);
        String firstDriver = newDeliveryUser().getUserId();
        String secondDriver = newDeliveryUser().getUserId();
        DeliveryAssignment first = deliveries.save(new DeliveryAssignment(orderId, firstDriver, 1));

        assertThatThrownBy(() -> deliveries.save(new DeliveryAssignment(orderId, secondDriver, 2)))
            .isInstanceOf(DataIntegrityViolationException.class);

        first.setStatus(DeliveryStatus.FAILED);
        deliveries.save(first);
        deliveries.save(new DeliveryAssignment(orderId, secondDriver, 2));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String hubsFor(String customerId, ShippingAddress destination) {
        Order order = new Order();
        order.setOrderId("ORD-" + shortId());
        order.setCustomerId(customerId);
        order.setShippingAddress(destination);
        return routing.resolveFulfillmentRecipients(order);
    }

    private AppUser newCustomer() {
        return users.save(new AppUser("CUST-" + shortId(), "Test Customer", Role.CUSTOMER));
    }

    private AppUser newDeliveryUser() {
        return users.save(new AppUser("DEL-" + shortId(), "Test Driver", Role.DELIVERY));
    }

    private static Map<String, Object> orderFor(String customerId, String sku) {
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", customerId);
        order.put("items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.00)));
        return order;
    }

    private static Map<String, Object> addressJson() {
        Map<String, Object> address = new HashMap<>();
        address.put("recipientName", "Dana Reed");
        address.put("recipientPhone", "+91-98450-00150");
        address.put("line1", "1 Pine Road");
        address.put("city", "Pune");
        address.put("state", "Maharashtra");
        address.put("postalCode", "411001");
        address.put("country", "IN");
        return address;
    }

    private static ShippingAddress address(String city, String state) {
        ShippingAddress address = new ShippingAddress();
        address.setRecipientName("Dana Reed");
        address.setRecipientPhone("+91-98450-00150");
        address.setLine1("1 Pine Road");
        address.setCity(city);
        address.setState(state);
        address.setPostalCode("560001");
        address.setCountry("IN");
        return address;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
