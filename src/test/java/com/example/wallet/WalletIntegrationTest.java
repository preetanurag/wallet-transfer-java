package com.example.wallet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.wallet.config.MoneyLimits.MAX_MONEY;
import com.example.wallet.service.WalletService;
import com.example.wallet.repository.WalletRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString();
    private static final String A = "alice-" + RUN, B = "bob-" + RUN, C = "carol-" + RUN, F = "fresh-" + RUN;
    private static final Map<String, String> TOKENS = Map.of(A, "alice-test-token-0001", B, "bob-test-token-000001",
            C, "carol-test-token-0001", F, "fresh-test-token-0001");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("TEST_DB_URL", "jdbc:postgresql://localhost:5432/wallet_test"));
        registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "wallet"));
        registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "wallet-local-password"));
        registry.add("wallet.auth-tokens", () -> new ObjectMapper().writeValueAsString(TOKENS));
    }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired WalletService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean WalletRepository repository;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID a, b, c;
    private record Reply(int status, JsonNode body, String correlation) {}
    @BeforeAll void setup() {
        a = service.getOrCreate(A).id(); b = service.getOrCreate(B).id(); c = service.getOrCreate(C).id();
    }
    @BeforeEach void resetBalances() { fund(a, 10000); fund(b, 10000); fund(c, 10000); }
    @AfterEach void resetSpy() { reset(repository); }
    @AfterAll void cleanup() {
        jdbc.update("DELETE FROM transfers WHERE user_id IN (?,?,?,?)", A, B, C, F);
        jdbc.update("DELETE FROM wallets WHERE user_id IN (?,?,?,?)", A, B, C, F);
    }
    private void fund(UUID id, long amount) { jdbc.update("UPDATE wallets SET balance_paise=? WHERE id=?", amount, id); }
    private long balance(UUID id) { return jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id=?", Long.class, id); }
    private Map<String, Object> body(UUID from, UUID to, long amount, String key) {
        return Map.of("from", from, "to", to, "amount_paise", amount, "idempotency_key", key);
    }
    private Reply request(String user, String path, Object body) throws Exception {
        return raw(user, path, body == null ? null : mapper.writeValueAsString(body));
    }
    private Reply raw(String user, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKENS.getOrDefault(user, "bad-token"))
                .header("Content-Type", "application/json").header("X-Correlation-ID", "integration-test");
        if (body == null) builder.GET(); else builder.POST(HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), mapper.readTree(response.body()), response.headers().firstValue("X-Correlation-ID").orElse(""));
    }
    private <T> List<T> concurrent(int count, java.util.function.IntFunction<Callable<T>> call) throws Exception {
        var executor = Executors.newFixedThreadPool(Math.min(count, 50));
        try {
            var futures = executor.invokeAll(IntStream.range(0, count).mapToObj(call).toList());
            var values = new ArrayList<T>();
            for (var f : futures) values.add(f.get());
            return values;
        } finally { executor.shutdownNow(); }
    }
    @Test void concurrentGetOrCreateHasExactlyOneWallet() throws Exception {
        var replies = concurrent(50, i -> () -> request(F, "/wallets", Map.of()));
        assertTrue(replies.stream().allMatch(r -> r.status == 200));
        assertEquals(1, replies.stream().map(r -> r.body.get("id").stringValue()).distinct().count());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id=?", Long.class, F));
    }
    @Test void retryStormAppliesOnceAndChangedBodyConflicts() throws Exception {
        String key = UUID.randomUUID().toString(); var command = body(a, b, 100, key);
        var replies = concurrent(30, i -> () -> request(A, "/transfers", command));
        for (var reply : replies) { assertEquals(200, reply.status); assertEquals(replies.get(0).body, reply.body); }
        assertEquals(9900, balance(a)); assertEquals(10100, balance(b));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM transfers WHERE user_id=? AND idempotency_key=?", Long.class, A, key));
        assertEquals(409, request(A, "/transfers", body(a,b,101,key)).status);
        assertEquals(409, request(A, "/transfers", body(a,c,100,key)).status);
        String id = replies.get(0).body.get("id").stringValue();
        assertEquals(replies.get(0).body, request(B,"/transfers/"+id,null).body);
        assertEquals(404, request(C,"/transfers/"+id,null).status);
    }
    @Test void differentBodiesRaceOnSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        var replies = concurrent(2, i -> () -> request(A,"/transfers",body(a,b,100+i,key)));
        assertEquals(List.of(200,409), replies.stream().map(Reply::status).sorted().toList());
        assertEquals(30000, balance(a)+balance(b)+balance(c));
    }
    @Test void contentionConservesMoneyAndPreventsOverdraft() throws Exception {
        List<UUID> ids = List.of(a,b,c); List<String> users = List.of(A,B,C);
        var replies = concurrent(360, i -> () -> {
            int from=i%3, to=(from+(i%2==0?1:2))%3;
            return request(users.get(from),"/transfers",body(ids.get(from),ids.get(to),i%9==0?MAX_MONEY:17,UUID.randomUUID().toString()));
        });
        assertTrue(replies.stream().allMatch(r -> r.status==200), replies.toString());
        assertTrue(replies.stream().anyMatch(r -> r.body.get("status").stringValue().equals("declined")));
        assertEquals(30000, balance(a)+balance(b)+balance(c));
        assertTrue(balance(a)>=0 && balance(b)>=0 && balance(c)>=0);
    }
    @Test void declinedResultSurvivesFundingAndRetry() throws Exception {
        fund(a,0); var command = body(a,b,1,UUID.randomUUID().toString());
        var first=request(A,"/transfers",command);
        assertEquals("insufficient_funds",first.body.get("reason").stringValue());
        fund(a,10000); assertEquals(first.body,request(A,"/transfers",command).body); assertEquals(10000,balance(a));
    }
    @Test void ownershipAndInputValidation() throws Exception {
        assertEquals(401,request("unknown","/wallets",Map.of()).status);
        assertEquals(404,request(B,"/wallets/"+a,null).status);
        assertEquals(403,request(B,"/transfers",body(a,b,1,UUID.randomUUID().toString())).status);
        assertEquals(400,request(A,"/wallets",Map.of("user_id",B)).status);
        assertEquals(400,request(A,"/transfers",body(a,a,1,UUID.randomUUID().toString())).status);
        assertEquals(404,request(A,"/transfers",body(a,UUID.randomUUID(),1,UUID.randomUUID().toString())).status);
        for (String amount : List.of("0","-1","1.2","\"12\"","true","null","9007199254740992","9007199254740990.1","1e2","1.00000000000000001")) {
            String json="{\"from\":\""+a+"\",\"to\":\""+b+"\",\"amount_paise\":"+amount+",\"idempotency_key\":\"invalid\"}";
            assertEquals(400,raw(A,"/transfers",json).status,amount);
        }
        assertEquals(400,raw(A,"/transfers","{broken").status);
        assertEquals(400,raw(A,"/transfers","null").status);
    }
    @Test void upperBalanceLimitAndMaximumAmountAreExact() throws Exception {
        fund(b,MAX_MONEY);
        var declined=request(A,"/transfers",body(a,b,1,UUID.randomUUID().toString()));
        assertEquals("recipient_balance_limit",declined.body.get("reason").stringValue());
        assertEquals(10000,balance(a)); assertEquals(MAX_MONEY,balance(b));
        fund(a,MAX_MONEY); fund(b,0);
        assertEquals("succeeded",request(A,"/transfers",body(a,b,MAX_MONEY,UUID.randomUUID().toString())).body.get("status").stringValue());
        assertEquals(0,balance(a)); assertEquals(MAX_MONEY,balance(b));
    }
    @Test void creditFailureRollsBackDebitAndKeyThenRetrySucceeds() throws Exception {
        String key=UUID.randomUUID().toString();
        doThrow(new DataAccessResourceFailureException("injected credit failure")).when(repository).credit(b,100);
        assertEquals(503,request(A,"/transfers",body(a,b,100,key)).status);
        assertEquals(10000,balance(a)); assertEquals(10000,balance(b));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM transfers WHERE user_id=? AND idempotency_key=?",Long.class,A,key));
        reset(repository);
        assertEquals(200,request(A,"/transfers",body(a,b,100,key)).status);
        assertEquals(9900,balance(a));
    }
    @Test void keysAreScopedPerCallerAndNegativeBalancesAreRejected() throws Exception {
        String key=UUID.randomUUID().toString();
        var first=request(A,"/transfers",body(a,b,1,key));
        var second=request(B,"/transfers",body(b,a,1,key));
        assertEquals(200,first.status); assertEquals(200,second.status);
        assertNotEquals(first.body.get("id"),second.body.get("id"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->fund(a,-1));
    }
    @Test void metricsAndCorrelationAreExposed() throws Exception {
        var result=request(A,"/transfers",body(a,b,1,UUID.randomUUID().toString()));
        assertEquals("integration-test",result.correlation);
        var metrics=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/metrics")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,metrics.statusCode());
        assertTrue(metrics.body().contains("wallet_events_total"));
        assertTrue(metrics.body().contains("http_server_requests_seconds_bucket"));
        assertEquals(200,request("unknown","/healthz",null).status);
    }

    private UUID original(long amount) throws Exception {
        var result = request(A, "/transfers", body(a,b,amount,UUID.randomUUID().toString()));
        assertEquals(200, result.status);
        assertEquals("succeeded", result.body.get("status").stringValue());
        return UUID.fromString(result.body.get("id").stringValue());
    }
    private Reply reverse(String user, UUID id, String key) throws Exception {
        return request(user, "/transfers/"+id+"/reverse", Map.of("idempotency_key",key));
    }

    @Test void reversalRetryStormRefundsExactlyOnceAndRemainsReadable() throws Exception {
        UUID original = original(100);
        String key = UUID.randomUUID().toString();
        var replies = concurrent(30, i -> () -> reverse(B,original,key));
        for (var reply : replies) {
            assertEquals(200,reply.status);
            assertEquals(replies.get(0).body,reply.body);
        }
        JsonNode refund = replies.get(0).body;
        assertEquals("succeeded",refund.get("status").stringValue());
        assertEquals(original.toString(),refund.get("reversal_of").stringValue());
        assertEquals(b.toString(),refund.get("from").stringValue());
        assertEquals(a.toString(),refund.get("to").stringValue());
        assertEquals(100,refund.get("amount_paise").longValue());
        assertEquals(10000,balance(a)); assertEquals(10000,balance(b));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM transfers WHERE reversal_of=?",Long.class,original));
        assertEquals(refund, request(A,"/transfers/"+refund.get("id").stringValue(),null).body);
        assertEquals(refund, request(B,"/transfers/"+refund.get("id").stringValue(),null).body);
        assertEquals(404,request(C,"/transfers/"+refund.get("id").stringValue(),null).status);
        assertEquals(409,reverse(B,original,UUID.randomUUID().toString()).status);
        // An existing successful key must continue to replay after another key is rejected.
        assertEquals(refund,reverse(B,original,key).body);
    }

    @Test void differentReversalKeysCannotDoubleRefund() throws Exception {
        UUID original = original(100);
        var replies = concurrent(20,i -> () -> reverse(B,original,UUID.randomUUID().toString()));
        assertEquals(1,replies.stream().filter(r -> r.status==200).count());
        assertEquals(19,replies.stream().filter(r -> r.status==409 && r.body.get("error").stringValue().equals("transfer_already_reversed")).count());
        assertEquals(10000,balance(a)); assertEquals(10000,balance(b));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM transfers WHERE reversal_of=? AND status='succeeded'",Long.class,original));
    }

    @Test void reversalDeclineIsPersistentButNewKeyCanRetryAfterFunding() throws Exception {
        UUID original = original(100);
        // Spend the recipient's entire available balance through the real transfer API.
        assertEquals(200,request(B,"/transfers",body(b,c,10100,UUID.randomUUID().toString())).status);
        long total=balance(a)+balance(b)+balance(c);
        String key=UUID.randomUUID().toString();
        var declined=reverse(B,original,key);
        assertEquals(200,declined.status);
        assertEquals("insufficient_funds",declined.body.get("reason").stringValue());
        assertEquals(0,balance(b)); assertEquals(9900,balance(a));
        assertEquals(total,balance(a)+balance(b)+balance(c));
        request(C,"/transfers",body(c,b,100,UUID.randomUUID().toString()));
        assertEquals(declined.body,reverse(B,original,key).body);
        var success=reverse(B,original,UUID.randomUUID().toString());
        assertEquals("succeeded",success.body.get("status").stringValue());
        assertEquals(total,balance(a)+balance(b)+balance(c));
        // Even after a later successful refund, replaying the declined key stays declined.
        assertEquals(declined.body,reverse(B,original,key).body);
    }

    @Test void reversalAuthorizationAndInvalidOriginals() throws Exception {
        UUID original=original(100);
        assertEquals(401,reverse("unknown",original,UUID.randomUUID().toString()).status);
        assertEquals(403,reverse(A,original,UUID.randomUUID().toString()).status);
        assertEquals(403,reverse(C,original,UUID.randomUUID().toString()).status);
        assertEquals(404,reverse(B,UUID.randomUUID(),UUID.randomUUID().toString()).status);
        var declined=request(A,"/transfers",body(a,b,MAX_MONEY,UUID.randomUUID().toString()));
        UUID declinedId=UUID.fromString(declined.body.get("id").stringValue());
        assertEquals("transfer_not_succeeded",reverse(B,declinedId,UUID.randomUUID().toString()).body.get("error").stringValue());
        var refund=reverse(B,original,UUID.randomUUID().toString());
        UUID refundId=UUID.fromString(refund.body.get("id").stringValue());
        assertEquals("cannot_reverse_reversal",reverse(A,refundId,UUID.randomUUID().toString()).body.get("error").stringValue());
    }

    @Test void reversalCannotOverrideAmountOrWallets() throws Exception {
        UUID original=original(100);
        String path="/transfers/"+original+"/reverse";
        for (Object invalid : List.of(Map.of(),Map.of("idempotency_key",""),Map.of("idempotency_key","has space"),
                Map.of("idempotency_key",123),Map.of("idempotency_key","x","amount_paise",1),
                Map.of("idempotency_key","x","from",a),Map.of("idempotency_key","x","to",c),
                Map.of("idempotency_key","x".repeat(129)))) {
            assertEquals(400,request(B,path,invalid).status);
        }
        assertEquals(400,raw(B,path,"null").status);
        assertEquals(400,raw(B,path,"[]").status);
        assertEquals(9900,balance(a)); assertEquals(10100,balance(b));
    }

    @Test void reversalKeyBindsOriginalAndOperationType() throws Exception {
        UUID first=original(100), second=original(100);
        String refundKey=UUID.randomUUID().toString();
        var refund=reverse(B,first,refundKey);
        assertEquals(200,refund.status);
        assertEquals("idempotency_key_conflict",reverse(B,second,refundKey).body.get("error").stringValue());
        assertEquals(409,request(B,"/transfers",body(b,a,100,refundKey)).status);
        String normalKey=UUID.randomUUID().toString();
        assertEquals(200,request(B,"/transfers",body(b,a,100,normalKey)).status);
        assertEquals("idempotency_key_conflict",reverse(B,second,normalKey).body.get("error").stringValue());
    }

    @Test void reversalCreditFailureRollsBackAndKeepsRefundAvailable() throws Exception {
        UUID original=original(100);
        String key=UUID.randomUUID().toString();
        doThrow(new DataAccessResourceFailureException("injected reversal credit failure")).when(repository).credit(a,100);
        assertEquals(503,reverse(B,original,key).status);
        assertEquals(9900,balance(a)); assertEquals(10100,balance(b));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM transfers WHERE reversal_of=?",Long.class,original));
        reset(repository);
        assertEquals("succeeded",reverse(B,original,key).body.get("status").stringValue());
        assertEquals(10000,balance(a)); assertEquals(10000,balance(b));
    }

    @Test void reversalRecipientLimitDeclinesWithoutDebit() throws Exception {
        UUID original=original(100);
        fund(a,MAX_MONEY);
        var result=reverse(B,original,UUID.randomUUID().toString());
        assertEquals("recipient_balance_limit",result.body.get("reason").stringValue());
        assertEquals(MAX_MONEY,balance(a)); assertEquals(10100,balance(b));
    }

    @Test void refundAndConcurrentSpendingCannotOverdraw() throws Exception {
        fund(b,0);
        UUID original=original(100);
        var results=concurrent(2,i -> () -> i==0 ? reverse(B,original,UUID.randomUUID().toString())
                : request(B,"/transfers",body(b,c,100,UUID.randomUUID().toString())));
        assertTrue(results.stream().allMatch(r -> r.status==200));
        assertEquals(1,results.stream().filter(r -> r.body.get("status").stringValue().equals("succeeded")).count());
        assertEquals(1,results.stream().filter(r -> r.body.get("status").stringValue().equals("declined")).count());
        assertEquals(20000,balance(a)+balance(b)+balance(c));
        assertEquals(0,balance(b));
    }

    @Test void databaseRejectsDuplicateSuccessfulRefundEvenWithDifferentKey() throws Exception {
        UUID original=original(100);
        var refund=reverse(B,original,UUID.randomUUID().toString());
        UUID refundId=UUID.fromString(refund.body.get("id").stringValue());
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO transfers(id,user_id,idempotency_key,from_wallet,to_wallet,amount_paise,status,reason,reversal_of)
                SELECT ?, user_id, ?, from_wallet, to_wallet, amount_paise, status, reason, reversal_of
                FROM transfers WHERE id=?
                """, UUID.randomUUID(), UUID.randomUUID().toString(), refundId));
    }
}
