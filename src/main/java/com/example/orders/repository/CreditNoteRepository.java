package com.example.orders.repository;

import com.example.orders.model.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditNoteRepository extends JpaRepository<CreditNote, String> {

    List<CreditNote> findByOrderIdOrderByIssuedAt(String orderId);
}
