package com.example.wallet.mapper;

import com.example.wallet.entity.TransferEntity;
import com.example.wallet.model.TransferResponse;

public final class TransferMapper {
    private TransferMapper() {}
    public static TransferResponse toResponse(TransferEntity entity) {
        return new TransferResponse(entity.id(), entity.fromWallet(), entity.toWallet(),
                entity.amountPaise(), entity.idempotencyKey(), entity.status(), entity.reason(), entity.reversalOf());
    }
}
