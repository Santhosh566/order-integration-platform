package com.example.orders.service;

import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.Order;
import com.example.orders.repository.OrderRepository;
import com.example.orders.security.CurrentUser;
import com.example.orders.security.Viewer;
import org.apache.camel.Body;
import org.springframework.stereotype.Service;

/**
 * Which orders the signed-in user may see and act on. An admin may see every order; a customer
 * only their own. Someone else's order answers exactly like a missing one, so its existence leaks nothing.
 * Called only from HTTP routes: the pipeline itself has no signed-in user.
 */
@Service("orderAccess")
public class OrderAccessService {

    private final CurrentUser currentUser;
    private final OrderRepository orderRepository;

    public OrderAccessService(CurrentUser currentUser, OrderRepository orderRepository) {
        this.currentUser = currentUser;
        this.orderRepository = orderRepository;
    }

    /**
     * A customer always orders for themselves, whatever customerId the request names.
     * An admin may place an order on a customer's behalf.
     */
    public void assignCustomer(@Body Order order) {
        Viewer viewer = currentUser.require();
        if (!viewer.isAdmin()) {
            order.setCustomerId(viewer.userId());
        }
    }

    /**
     * @return the order, or null when it does not exist or belongs to someone else
     */
    public Order findVisible(String orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        return order != null && canSee(order) ? order : null;
    }

    public void requireOwnerOrAdmin(String orderId) {
        if (findVisible(orderId) == null) {
            throw new OrderNotFoundException(orderId);
        }
    }

    /**
     * @return null for an admin, who sees every order, otherwise the customer whose orders to list
     */
    public String customerFilter() {
        Viewer viewer = currentUser.require();
        return viewer.isAdmin() ? null : viewer.userId();
    }

    private boolean canSee(Order order) {
        Viewer viewer = currentUser.require();
        return viewer.isAdmin() || viewer.userId().equals(order.getCustomerId());
    }
}
