package com.example.orders.service;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/**
 * A name for this running instance: the host (the pod name on Kubernetes) plus a random part, so
 * two instances on one machine are told apart too. Each instance reads live updates with its own
 * Kafka consumer group, named after it.
 */
@Component
public class LivePodId {

    private final String value;

    public LivePodId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "pod";
            }
        }
        this.value = host.replaceAll("[^A-Za-z0-9._-]", "-") + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String value() {
        return value;
    }
}
