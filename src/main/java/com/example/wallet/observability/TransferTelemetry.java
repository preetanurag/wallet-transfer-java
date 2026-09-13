package com.example.wallet.observability;



import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.example.wallet.model.*;
import static com.example.wallet.config.MoneyLimits.MAX_MONEY;

@Component
public class TransferTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(TransferTelemetry.class);
    private final MeterRegistry meters;
    public TransferTelemetry(MeterRegistry meters) {
        this.meters = meters;
        for (String event : new String[]{"created", "idempotent_replay", "declined_insufficient_funds", "declined_recipient_balance_limit", "reversal_created", "reversal_succeeded", "reversal_declined"}) {
            meters.counter("wallet.events", "event", event);
        }
    }
    // Called only after the transactional proxy has successfully committed.
    public void committed(TransferOutcome result) {
        TransferResponse t = result.transfer();
        String event = result.replay() ? "idempotent_replay" : "created";
        meters.counter("wallet.events", "event", event).increment();
        LOG.atInfo().addKeyValue("event", result.replay() ? event : "transfer_created")
                .addKeyValue("transfer_id", t.id()).log("Transfer result");
        if (result.replay()) return;
        if (t.reversalOf() != null) {
            meters.counter("wallet.events", "event", "reversal_created").increment();
            meters.counter("wallet.events", "event", "reversal_" + t.status()).increment();
            LOG.atInfo().addKeyValue("event", "reversal_" + t.status())
                    .addKeyValue("transfer_id", t.id()).addKeyValue("reversal_of", t.reversalOf())
                    .addKeyValue("reason", t.reason()).log("Reversal result");
        }
        if (t.status().equals("declined")) {
            meters.counter("wallet.events", "event", "declined_" + t.reason()).increment();
            LOG.atInfo().addKeyValue("event", "transfer_declined").addKeyValue("transfer_id", t.id())
                    .addKeyValue("reason", t.reason()).log("Transfer declined");
        } else {
            LOG.atInfo().addKeyValue("event", "wallet_debited").addKeyValue("transfer_id", t.id())
                    .addKeyValue("wallet_id", t.from()).addKeyValue("amount_paise", t.amountPaise()).log("Wallet debited");
            LOG.atInfo().addKeyValue("event", "wallet_credited").addKeyValue("transfer_id", t.id())
                    .addKeyValue("wallet_id", t.to()).addKeyValue("amount_paise", t.amountPaise()).log("Wallet credited");
        }
    }
}
