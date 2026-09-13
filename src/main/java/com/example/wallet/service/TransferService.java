package com.example.wallet.service;

import com.example.wallet.entity.TransferEntity;
import com.example.wallet.entity.WalletEntity;
import com.example.wallet.exception.ApiException;
import com.example.wallet.mapper.TransferMapper;
import com.example.wallet.model.ReversalCommand;
import com.example.wallet.model.TransferCommand;
import com.example.wallet.model.TransferOutcome;
import com.example.wallet.model.TransferResponse;
import com.example.wallet.repository.TransferRepository;
import com.example.wallet.repository.WalletRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.example.wallet.config.MoneyLimits.MAX_MONEY;

@Service
public class TransferService {
    private final WalletRepository wallets;
    private final TransferRepository transfers;

    public TransferService(WalletRepository wallets, TransferRepository transfers) {
        this.wallets = wallets;
        this.transfers = transfers;
    }

    public TransferResponse getTransfer(String user, UUID id) {
        return transfers.visibleTransfer(id, user).map(TransferMapper::toResponse)
                .orElseThrow(() -> new ApiException(404, "transfer_not_found"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public TransferOutcome transfer(String user, TransferCommand command) {
        transfers.lockKey(user, command.key());
        var existing = transfers.byKey(user, command.key());
        if (existing.isPresent()) {
            TransferEntity t = existing.get();
            // Forward transfers and reversals share the caller/key namespace.
            if (t.reversalOf() != null || !t.fromWallet().equals(command.from()) ||
                    !t.toWallet().equals(command.to()) || t.amountPaise() != command.amount()) {
                throw new ApiException(409, "idempotency_key_conflict");
            }
            return replay(t);
        }
        return persist(user, command, moveFunds(user, command), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public TransferOutcome reverse(String user, UUID originalId, ReversalCommand reversal) {
        transfers.lockKey(user, reversal.key());
        var existing = transfers.byKey(user, reversal.key());
        if (existing.isPresent()) {
            // The original transfer ID is the body identity for a reversal.
            // Check replay before already-reversed state so a successful retry stays successful.
            if (!originalId.equals(existing.get().reversalOf())) {
                throw new ApiException(409, "idempotency_key_conflict");
            }
            return replay(existing.get());
        }
        // Lock order: caller/key, original transfer, sorted wallet rows. Forward
        // transfers never wait on original transfer rows, avoiding a lock cycle.
        TransferEntity original = transfers.lockOriginal(originalId);
        if (wallets.owned(original.toWallet(), user).isEmpty()) {
            throw new ApiException(403, "reversal_requires_recipient");
        }
        if (original.reversalOf() != null) throw new ApiException(409, "cannot_reverse_reversal");
        if (!original.status().equals("succeeded")) throw new ApiException(409, "transfer_not_succeeded");
        if (transfers.hasSuccessfulReversal(originalId)) throw new ApiException(409, "transfer_already_reversed");

        TransferCommand command = new TransferCommand(original.toWallet(), original.fromWallet(),
                original.amountPaise(), reversal.key());
        return persist(user, command, moveFunds(user, command), originalId);
    }

    private TransferOutcome replay(TransferEntity entity) {
        return new TransferOutcome(TransferMapper.toResponse(entity), true);
    }

    private TransferOutcome persist(String user, TransferCommand command, String reason, UUID reversalOf) {
        TransferEntity entity = new TransferEntity(UUID.randomUUID(), user, command.from(), command.to(),
                command.amount(), command.key(), reason == null ? "succeeded" : "declined", reason, null, reversalOf);
        return new TransferOutcome(TransferMapper.toResponse(transfers.insert(entity)), false);
    }

    /** Shared money-movement primitive, always called inside the surrounding transaction. */
    private String moveFunds(String user, TransferCommand command) {
        var locked = new HashMap<UUID, WalletEntity>();
        List.of(command.from(), command.to()).stream().sorted(Comparator.comparing(UUID::toString))
                .forEach(id -> locked.put(id, wallets.lockWallet(id)));
        WalletEntity from = locked.get(command.from()), to = locked.get(command.to());
        if (!from.userId().equals(user)) throw new ApiException(403, "source_wallet_not_owned");
        if (from.balancePaise() < command.amount()) return "insufficient_funds";
        if (to.balancePaise() > MAX_MONEY - command.amount()) return "recipient_balance_limit";
        wallets.debit(from.id(), command.amount());
        wallets.credit(to.id(), command.amount());
        return null;
    }
}
