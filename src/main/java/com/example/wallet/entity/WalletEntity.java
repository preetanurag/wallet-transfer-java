package com.example.wallet.entity;

import java.util.UUID;

/** PostgreSQL wallet row; persistence data is separate from the API response. */
public record WalletEntity(UUID id, String userId, long balancePaise) {}
