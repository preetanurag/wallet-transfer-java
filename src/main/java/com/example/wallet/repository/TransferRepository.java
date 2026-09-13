package com.example.wallet.repository;

import com.example.wallet.entity.TransferEntity;
import com.example.wallet.exception.ApiException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<TransferEntity> TRANSFER = (rs, n) -> new TransferEntity(
            rs.getObject("id", UUID.class), rs.getString("user_id"),
            rs.getObject("from_wallet", UUID.class), rs.getObject("to_wallet", UUID.class),
            rs.getLong("amount_paise"), rs.getString("idempotency_key"), rs.getString("status"),
            rs.getString("reason"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("reversal_of", UUID.class));

    public TransferRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockKey(String user, String key) {
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        // A length-prefixed user avoids ambiguous concatenations. Hash collisions only serialize extra work.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (rs, row) -> 0, user.length() + ":" + user + key);
    }

    public Optional<TransferEntity> byKey(String user, String key) {
        return jdbc.query("SELECT * FROM transfers WHERE user_id=? AND idempotency_key=?", TRANSFER, user, key)
                .stream().findFirst();
    }

    public TransferEntity lockOriginal(UUID id) {
        return jdbc.query("SELECT * FROM transfers WHERE id=? FOR UPDATE", TRANSFER, id)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "transfer_not_found"));
    }

    public boolean hasSuccessfulReversal(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM transfers WHERE reversal_of=? AND status='succeeded')",
                Boolean.class, id));
    }

    public TransferEntity insert(TransferEntity entity) {
        return jdbc.queryForObject("""
                INSERT INTO transfers(id,user_id,idempotency_key,from_wallet,to_wallet,amount_paise,status,reason,reversal_of)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING *
                """, TRANSFER, entity.id(), entity.userId(), entity.idempotencyKey(), entity.fromWallet(),
                entity.toWallet(), entity.amountPaise(), entity.status(), entity.reason(), entity.reversalOf());
    }

    public Optional<TransferEntity> visibleTransfer(UUID id, String user) {
        return jdbc.query("""
                SELECT t.* FROM transfers t JOIN wallets w ON w.id=t.to_wallet
                WHERE t.id=? AND (t.user_id=? OR w.user_id=?)
                """, TRANSFER, id, user, user).stream().findFirst();
    }
}
