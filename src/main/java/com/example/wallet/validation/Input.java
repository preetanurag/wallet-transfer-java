package com.example.wallet.validation;

import com.example.wallet.exception.ApiException;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import com.example.wallet.model.*;
import static com.example.wallet.config.MoneyLimits.MAX_MONEY;

public final class Input {
    private Input() {}
    public static UUID id(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ApiException(400, "invalid_wallet_id");
        }
        return UUID.fromString(value);
    }
    private static String text(JsonNode body, String key) {
        JsonNode node = body.get(key);
        if (node == null || !node.isString()) throw new ApiException(400, "invalid_" + key);
        return node.stringValue();
    }
    public static TransferCommand transfer(JsonNode body) {
        if (!body.isObject() || body.size() != 4 || !body.has("from") || !body.has("to") ||
                !body.has("amount_paise") || !body.has("idempotency_key")) {
            throw new ApiException(400, "invalid_transfer_fields");
        }
        UUID from = id(text(body, "from"));
        UUID to = id(text(body, "to"));
        if (from.equals(to)) throw new ApiException(400, "self_transfer");
        JsonNode amount = body.get("amount_paise");
        // A JSON integer node excludes fractions and exponent notation without floating-point conversion.
        if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() < 1 ||
                amount.longValue() > MAX_MONEY) {
            throw new ApiException(400, "amount_must_be_positive_safe_integer_paise");
        }
        String key = text(body, "idempotency_key");
        if (!key.matches("[\\x21-\\x7e]{1,128}")) throw new ApiException(400, "invalid_idempotency_key");
        return new TransferCommand(from, to, amount.longValue(), key);
    }
    public static ReversalCommand reversal(JsonNode body) {
        if (!body.isObject() || body.size() != 1 || !body.has("idempotency_key")) {
            throw new ApiException(400, "invalid_reversal_fields");
        }
        String key = text(body, "idempotency_key");
        if (!key.matches("[\\x21-\\x7e]{1,128}")) throw new ApiException(400, "invalid_idempotency_key");
        return new ReversalCommand(key);
    }

    public static void wallet(JsonNode body, String user) {
        if (!body.isObject() || body.size() > 1 ||
                (body.size() == 1 && (!body.has("user_id") || !user.equals(text(body, "user_id"))))) {
            throw new ApiException(400, "wallet_user_must_match_caller");
        }
    }
}
