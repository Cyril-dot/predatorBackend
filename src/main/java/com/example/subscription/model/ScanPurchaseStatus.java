package com.example.subscription.model;

public enum ScanPurchaseStatus {
    PENDING,   // submitted, awaiting admin review
    APPROVED,  // admin confirmed payment - one scan is now available to use
    REJECTED,  // admin could not verify it
    USED       // the single scan this purchase grants has already been consumed
}
