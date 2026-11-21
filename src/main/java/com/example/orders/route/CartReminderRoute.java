package com.example.orders.route;

import com.example.orders.service.CartReminderService;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Polling Consumer EIP: reminds customers about carts they left, and closes carts left for a month.
 * See CartReminderService for the rules. Can be paused from Operations like the other timers.
 */
@Component
public class CartReminderRoute extends RouteBuilder {

    private final CartReminderService reminders;

    public CartReminderRoute(CartReminderService reminders) {
        this.reminders = reminders;
    }

    @Override
    public void configure() {
        from("timer:cartReminders?period={{orders.cart.reminder-poll-ms}}&delay={{orders.cart.reminder-poll-ms}}")
            .routeId("cart-reminders")
            .bean(reminders, "remindDue")
            .bean(reminders, "expire");
    }
}
