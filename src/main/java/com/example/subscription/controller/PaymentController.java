package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.InitializePaymentRequest;
import com.example.subscription.exception.ApiException;
import com.example.subscription.model.PaymentTransaction;
import com.example.subscription.model.Plan;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemoryPaymentRepository;
import com.example.subscription.service.AccountService;
import com.example.subscription.service.CommissionService;
import com.example.subscription.service.PaystackService;
import com.example.subscription.util.CodeGenerator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final PaystackService paystackService;
    private final InMemoryPaymentRepository paymentRepository;
    private final AccountService accountService;
    private final CommissionService commissionService;

    public PaymentController(PaystackService paystackService,
                              InMemoryPaymentRepository paymentRepository,
                              AccountService accountService,
                              CommissionService commissionService) {
        this.paystackService = paystackService;
        this.paymentRepository = paymentRepository;
        this.accountService = accountService;
        this.commissionService = commissionService;
    }

    /** Lists the 3 available plans - handy for the frontend to render pricing. */
    @GetMapping("/plans")
    public ApiResponse<Object> plans() {
        var list = new java.util.ArrayList<Map<String, Object>>();
        for (Plan p : Plan.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plan", p.name());
            m.put("hours", p.getHours());
            m.put("amountCedis", p.getAmountCedis());
            list.add(m);
        }
        return ApiResponse.ok("Available plans", list);
    }

    /**
     * Step 2 of the flow (after /api/auth/register): Frontend calls this
     * with the user's email + chosen plan. Requires the email to already be
     * registered and to have no currently active/unused subscription - one
     * subscription at a time, no topping up. We create a pending transaction
     * and ask Paystack for an authorization URL to redirect the user to.
     */
    @PostMapping("/initialize")
    public ApiResponse<Object> initialize(@Valid @RequestBody InitializePaymentRequest req) {
        if (!accountService.canPurchase(req.getEmail())) {
            throw new ApiException(
                    "Cannot start payment: either " + req.getEmail() + " is not registered yet, " +
                    "or it already has an active subscription (one at a time).",
                    HttpStatus.CONFLICT);
        }

        Plan plan = Plan.fromCode(req.getPlan());
        String reference = CodeGenerator.generateReference();

        PaymentTransaction tx = new PaymentTransaction(reference, req.getEmail(), plan);
        paymentRepository.save(tx);

        Map<String, Object> paystackData = paystackService.initializeTransaction(req.getEmail(), plan, reference);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorizationUrl", paystackData.get("authorization_url"));
        result.put("reference", reference);
        result.put("plan", plan.name());
        result.put("amountCedis", plan.getAmountCedis());

        return ApiResponse.ok("Payment initialized. Redirect user to authorizationUrl.", result);
    }

    /**
     * Step 3: After Paystack redirects the user back (or your frontend polls
     * this), call this to verify the transaction actually succeeded. If it
     * did, we generate the login password and return it ONCE.
     */
    @GetMapping("/verify/{reference}")
    public ApiResponse<Object> verify(@PathVariable String reference) {
        PaymentTransaction tx = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new ApiException("Unknown transaction reference", HttpStatus.NOT_FOUND));

        if (tx.getStatus() == PaymentTransaction.Status.SUCCESS) {
            throw new ApiException("This transaction has already been verified and used.", HttpStatus.CONFLICT);
        }

        Map<String, Object> data = paystackService.verifyTransaction(reference);
        String status = String.valueOf(data.get("status"));

        if (!"success".equalsIgnoreCase(status)) {
            tx.setStatus(PaymentTransaction.Status.FAILED);
            paymentRepository.save(tx);
            throw new ApiException("Payment was not successful (status: " + status + ")", HttpStatus.PAYMENT_REQUIRED);
        }

        tx.setStatus(PaymentTransaction.Status.SUCCESS);
        tx.setVerifiedAt(java.time.LocalDateTime.now());
        paymentRepository.save(tx);

        UserAccount account = accountService.assignSubscription(tx.getEmail(), tx.getPlan());

        commissionService.recordIfReferred(
                tx.getReference(), tx.getEmail(), tx.getPlan(),
                tx.getPlan().getAmountCedis(), account.getReferredByAdminCode());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", account.getUsername());
        result.put("password", account.getPassword()); // shown ONCE
        result.put("plan", account.getPlan().name());
        result.put("hours", account.getPlan().getHours());
        result.put("message", "Save this password now - it will not be shown again. Use it to log in.");

        return ApiResponse.ok("Payment verified. Account ready.", result);
    }
}
