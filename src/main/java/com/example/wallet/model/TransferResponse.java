package com.example.wallet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record TransferResponse(UUID id, UUID from, UUID to,
        @JsonProperty("amount_paise") long amountPaise,
        @JsonProperty("idempotency_key") String idempotencyKey,
        String status, String reason,
        @JsonProperty("reversal_of") UUID reversalOf) {}
