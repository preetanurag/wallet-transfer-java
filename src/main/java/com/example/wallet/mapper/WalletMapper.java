package com.example.wallet.mapper;

import com.example.wallet.entity.WalletEntity;
import com.example.wallet.model.WalletResponse;

public final class WalletMapper {
    private WalletMapper() {}
    public static WalletResponse toResponse(WalletEntity entity) {
        return new WalletResponse(entity.id(), entity.balancePaise());
    }
}
