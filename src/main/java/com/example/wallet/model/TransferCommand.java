package com.example.wallet.model;

import java.util.UUID;

/** Validated input passed from the HTTP boundary to the service. */
public record TransferCommand(UUID from, UUID to, long amount, String key) {}
