package com.example.subscription.service;

import com.example.subscription.model.ManualPayment;
import com.example.subscription.model.ManualPaymentStatus;
import com.example.subscription.model.PaymentTransaction;
import com.example.subscription.repository.InMemoryManualPaymentRepository;
import com.example.subscription.repository.InMemoryPaymentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregates revenue across BOTH payment rails: Paystack (PaymentTransaction)
 * and manual mobile-money/bank-transfer submissions that an admin approved
 * (ManualPayment). A deposit only counts once it's confirmed - "verified"
 * for Paystack, "approved" for manual - never while still pending.
 */
@Service
public class StatsService {

    private final InMemoryPaymentRepository paymentRepository;
    private final InMemoryManualPaymentRepository manualPaymentRepository;

    public StatsService(InMemoryPaymentRepository paymentRepository,
                         InMemoryManualPaymentRepository manualPaymentRepository) {
        this.paymentRepository = paymentRepository;
        this.manualPaymentRepository = manualPaymentRepository;
    }

    private List<PaymentTransaction> successfulTransactions() {
        return paymentRepository.findAll().stream()
                .filter(tx -> tx.getStatus() == PaymentTransaction.Status.SUCCESS && tx.getVerifiedAt() != null)
                .collect(Collectors.toList());
    }

    private List<ManualPayment> approvedManualPayments() {
        return manualPaymentRepository.findByStatus(ManualPaymentStatus.APPROVED).stream()
                .filter(p -> p.getReviewedAt() != null)
                .collect(Collectors.toList());
    }

    /** Total cedis deposited (Paystack success + manual approved) on a given calendar date. */
    public int depositsOn(LocalDate date) {
        int paystack = successfulTransactions().stream()
                .filter(tx -> tx.getVerifiedAt().toLocalDate().equals(date))
                .mapToInt(tx -> tx.getPlan().getAmountCedis())
                .sum();
        int manual = approvedManualPayments().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        return paystack + manual;
    }

    /** Total cedis deposited since a given timestamp (e.g. now.minusDays(7) for "this week"). */
    public int depositsSince(LocalDateTime since) {
        int paystack = successfulTransactions().stream()
                .filter(tx -> !tx.getVerifiedAt().isBefore(since))
                .mapToInt(tx -> tx.getPlan().getAmountCedis())
                .sum();
        int manual = approvedManualPayments().stream()
                .filter(p -> !p.getReviewedAt().isBefore(since))
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        return paystack + manual;
    }

    public int totalDepositsAllTime() {
        int paystack = successfulTransactions().stream()
                .mapToInt(tx -> tx.getPlan().getAmountCedis())
                .sum();
        int manual = approvedManualPayments().stream()
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        return paystack + manual;
    }

    public long successfulPaymentCount() {
        return successfulTransactions().size() + approvedManualPayments().size();
    }

    public long successfulPaymentCountOn(LocalDate date) {
        long paystack = successfulTransactions().stream()
                .filter(tx -> tx.getVerifiedAt().toLocalDate().equals(date))
                .count();
        long manual = approvedManualPayments().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .count();
        return paystack + manual;
    }
}
