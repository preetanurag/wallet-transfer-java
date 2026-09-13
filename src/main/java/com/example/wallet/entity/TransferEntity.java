package com.example.wallet.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** PostgreSQL transfer row, including internal ownership and audit fields. */
public record TransferEntity(UUID id, String userId, UUID fromWallet, UUID toWallet,
        long amountPaise, String idempotencyKey, String status, String reason,
        OffsetDateTime createdAt, UUID reversalOf) {}
