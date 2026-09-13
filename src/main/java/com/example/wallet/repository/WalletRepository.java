package com.example.wallet.repository;

import com.example.wallet.entity.WalletEntity;
import com.example.wallet.exception.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<WalletEntity> WALLET = (rs, n) ->
            new WalletEntity(rs.getObject("id", UUID.class), rs.getString("user_id"), rs.getLong("balance_paise"));

    public WalletRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public WalletEntity getOrCreate(String user) {
        jdbc.update("INSERT INTO wallets(id,user_id) VALUES (?,?) ON CONFLICT(user_id) DO NOTHING", UUID.randomUUID(), user);
        return jdbc.query("SELECT * FROM wallets WHERE user_id=?", WALLET, user).get(0);
    }

    public Optional<WalletEntity> owned(UUID id, String user) {
        return jdbc.query("SELECT * FROM wallets WHERE id=? AND user_id=?", WALLET, id, user).stream().findFirst();
    }

    public WalletEntity lockWallet(UUID id) {
        return jdbc.query("SELECT * FROM wallets WHERE id=? FOR UPDATE", WALLET, id).stream()
                .findFirst().orElseThrow(() -> new ApiException(404, "wallet_not_found"));
    }

    public void debit(UUID id, long amount) {
        jdbc.update("UPDATE wallets SET balance_paise=balance_paise-? WHERE id=?", amount, id);
    }

    public void credit(UUID id, long amount) {
        jdbc.update("UPDATE wallets SET balance_paise=balance_paise+? WHERE id=?", amount, id);
    }
}
