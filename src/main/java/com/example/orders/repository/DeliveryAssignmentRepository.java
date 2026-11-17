package com.example.orders.repository;

import com.example.orders.model.DeliveryAssignment;
import com.example.orders.model.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryAssignmentRepository extends JpaRepository<DeliveryAssignment, Long> {

    Collection<DeliveryStatus> LIVE = EnumSet.of(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP);

    Optional<DeliveryAssignment> findFirstByOrderIdAndStatusIn(String orderId, Collection<DeliveryStatus> statuses);

    List<DeliveryAssignment> findByDeliveryUserIdAndStatusInOrderByAssignedAtAsc(String deliveryUserId,
                                                                                 Collection<DeliveryStatus> statuses);

    List<DeliveryAssignment> findByOrderIdOrderByAttemptNumberAsc(String orderId);

    long countByOrderId(String orderId);

    List<DeliveryAssignment> findByStatusInOrderByAssignedAtAsc(Collection<DeliveryStatus> statuses);

    /** Every delivery someone is working on now, oldest first. */
    default List<DeliveryAssignment> findAllLive() {
        return findByStatusInOrderByAssignedAtAsc(LIVE);
    }

    /** The attempt currently holding the order, if any. */
    default Optional<DeliveryAssignment> findLive(String orderId) {
        return findFirstByOrderIdAndStatusIn(orderId, LIVE);
    }

    /** The deliveries this user is working on now. */
    default List<DeliveryAssignment> findLiveFor(String deliveryUserId) {
        return findByDeliveryUserIdAndStatusInOrderByAssignedAtAsc(deliveryUserId, LIVE);
    }
}
