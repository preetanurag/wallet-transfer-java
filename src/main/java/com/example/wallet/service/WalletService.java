package com.example.wallet.service;

import com.example.wallet.exception.ApiException;
import com.example.wallet.mapper.WalletMapper;
import com.example.wallet.model.WalletResponse;
import com.example.wallet.repository.WalletRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {
    private final WalletRepository repository;
    public WalletService(WalletRepository repository) { this.repository = repository; }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public WalletResponse getOrCreate(String user) {
        return WalletMapper.toResponse(repository.getOrCreate(user));
    }

    public WalletResponse getWallet(String user, UUID id) {
        return repository.owned(id, user).map(WalletMapper::toResponse)
                .orElseThrow(() -> new ApiException(404, "wallet_not_found"));
    }
}
