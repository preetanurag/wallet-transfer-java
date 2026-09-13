package com.example.wallet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record WalletResponse(UUID id, @JsonProperty("balance_paise") long balancePaise) {}
