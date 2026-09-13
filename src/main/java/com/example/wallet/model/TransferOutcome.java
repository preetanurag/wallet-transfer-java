package com.example.wallet.model;

/** Service result; replay metadata stays outside the public transfer response. */
public record TransferOutcome(TransferResponse transfer, boolean replay) {}
