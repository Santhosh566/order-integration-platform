package com.example.orders.repository;

import com.example.orders.model.ReturnRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, String> {

    List<ReturnRequest> findByOrderIdOrderByRequestedAt(String orderId);

    List<ReturnRequest> findByCustomerIdOrderByRequestedAtDesc(String customerId);

    List<ReturnRequest> findByStatusOrderByRequestedAt(ReturnRequest.Status status);

    List<ReturnRequest> findAllByOrderByRequestedAtDesc();
}
