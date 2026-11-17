package com.example.orders.repository;

import com.example.orders.model.ReturnPickup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReturnPickupRepository extends JpaRepository<ReturnPickup, Long> {

    Collection<ReturnPickup.Status> LIVE = EnumSet.of(ReturnPickup.Status.ASSIGNED, ReturnPickup.Status.ON_THE_WAY);

    Optional<ReturnPickup> findFirstByReturnIdAndStatusIn(String returnId, Collection<ReturnPickup.Status> statuses);

    List<ReturnPickup> findByDeliveryUserIdAndStatusInOrderByAssignedAtAsc(String deliveryUserId,
                                                                          Collection<ReturnPickup.Status> statuses);

    List<ReturnPickup> findByStatusInOrderByAssignedAtAsc(Collection<ReturnPickup.Status> statuses);

    List<ReturnPickup> findByReturnIdOrderByAttemptNumberAsc(String returnId);

    long countByReturnId(String returnId);

    /** The attempt holding the return now, if any. */
    default Optional<ReturnPickup> findLive(String returnId) {
        return findFirstByReturnIdAndStatusIn(returnId, LIVE);
    }

    default List<ReturnPickup> findLiveFor(String deliveryUserId) {
        return findByDeliveryUserIdAndStatusInOrderByAssignedAtAsc(deliveryUserId, LIVE);
    }

    default List<ReturnPickup> findAllLive() {
        return findByStatusInOrderByAssignedAtAsc(LIVE);
    }
}
