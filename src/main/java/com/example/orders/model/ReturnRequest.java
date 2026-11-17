package com.example.orders.model;

import com.example.orders.exception.InvalidOrderStateException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A customer's request to send back some or all of a delivered order.
 *
 * REQUESTED → APPROVED → REFUNDED once the goods arrive and are inspected, or REQUESTED → REJECTED.
 */
@Entity
@Table(name = "return_requests")
public class ReturnRequest {

    public enum Status { REQUESTED, APPROVED, PICKED_UP, REJECTED, REFUNDED }

    @Id
    @Column(name = "return_id")
    private String returnId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String reason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decision_note")
    private String decisionNote;

    // A delivery user collected the goods from the customer; they are on the way to the hub
    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "picked_up_by")
    private String pickedUpBy;

    // The live pickup attempt, if any: ASSIGNED (a driver has it) or ON_THE_WAY. Read-only.
    @org.hibernate.annotations.Formula("(select p.status from return_pickups p where p.return_id = return_id "
            + "and p.status in ('ASSIGNED', 'ON_THE_WAY'))")
    private String pickupStatus;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "received_by")
    private String receivedBy;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "return_id", nullable = false)
    private List<ReturnItem> items = new ArrayList<>();

    protected ReturnRequest() {}

    public ReturnRequest(String orderId, String customerId, String reason, List<ReturnItem> items) {
        this.returnId = "RET-" + UUID.randomUUID();
        this.orderId = orderId;
        this.customerId = customerId;
        this.reason = reason;
        this.items = new ArrayList<>(items);
        this.status = Status.REQUESTED;
        this.requestedAt = LocalDateTime.now();
    }

    public void approve(String by, String note) {
        decide(Status.APPROVED, by, note);
    }

    public void reject(String by, String note) {
        decide(Status.REJECTED, by, note);
    }

    private void decide(Status next, String by, String note) {
        if (status != Status.REQUESTED) {
            throw new InvalidOrderStateException("Return " + returnId + " is " + status + ", not waiting for a decision");
        }
        status = next;
        decidedAt = LocalDateTime.now();
        decidedBy = by;
        decisionNote = note;
    }

    /** A delivery user collected the goods at the customer's door. */
    public void pickedUp(String by) {
        if (status != Status.APPROVED) {
            throw new InvalidOrderStateException("Return " + returnId + " is " + status + "; only an approved return can be picked up");
        }
        status = Status.PICKED_UP;
        pickedUpAt = LocalDateTime.now();
        pickedUpBy = by;
    }

    /**
     * The goods reached the hub and were inspected. Normally after a pickup; an approved return
     * can also arrive another way, such as the customer sending it by post.
     */
    public void received(String by) {
        if (status != Status.APPROVED && status != Status.PICKED_UP) {
            throw new InvalidOrderStateException("Return " + returnId + " is " + status
                    + "; only an approved or picked-up return can be received");
        }
        status = Status.REFUNDED;
        receivedAt = LocalDateTime.now();
        receivedBy = by;
    }

    /** Still counts against the units that can be returned. */
    public boolean isLive() {
        return status != Status.REJECTED;
    }

    public String getReturnId() { return returnId; }
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public Status getStatus() { return status; }
    public String getReason() { return reason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionNote() { return decisionNote; }
    public LocalDateTime getPickedUpAt() { return pickedUpAt; }
    public String getPickedUpBy() { return pickedUpBy; }
    public String getPickupStatus() { return pickupStatus; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public String getReceivedBy() { return receivedBy; }
    public List<ReturnItem> getItems() { return items; }
}
