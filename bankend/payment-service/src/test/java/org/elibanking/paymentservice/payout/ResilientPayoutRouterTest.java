package org.elibanking.paymentservice.payout;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResilientPayoutRouterTest {

    private static final PayoutProvider.PayoutRequest REQUEST = new PayoutProvider.PayoutRequest("out-7",
            new BigDecimal("100.00"), "058", "0123456789", "Ada Okafor", "Rent");

    @Test
    void fallsBackOnlyAfterPrimaryStatusConfirmsNotFoundAndPersistsRailBeforeSend() {
        StubProvider primary = new StubProvider("flutterwave");
        primary.sendFailure = new IllegalStateException("503");
        StubProvider secondary = new StubProvider("monnify");
        List<String> selections = new ArrayList<>();
        ResilientPayoutRouter router = new ResilientPayoutRouter(primary, secondary);

        PayoutProvider.SendResult result = router.route(REQUEST, selections::add);

        assertEquals(List.of("flutterwave", "monnify"), selections);
        assertEquals(1, primary.statusCalls);
        assertEquals(1, secondary.sendCalls);
        assertEquals("monnify", result.providerName());
    }

    @Test
    void ambiguousPrimaryStatusNeverSendsOnSecondaryRail() {
        StubProvider primary = new StubProvider("flutterwave");
        primary.sendFailure = new IllegalStateException("read timeout");
        primary.status = PayoutProvider.PayoutStatus.PENDING;
        StubProvider secondary = new StubProvider("monnify");
        ResilientPayoutRouter router = new ResilientPayoutRouter(primary, secondary);

        PayoutProvider.SendResult result = router.route(REQUEST, ignored -> { });

        assertEquals(PayoutProvider.SendStatus.PENDING, result.status());
        assertEquals("flutterwave", result.providerName());
        assertEquals(0, secondary.sendCalls);
    }

    @Test
    void circuitOpenSkipsPrimaryAndRoutesToSecondary() {
        CircuitBreakerRegistry registry = ResilientPayoutRouter.newRegistry();
        var primaryBreaker = registry.circuitBreaker("flutterwave");
        for (int i = 0; i < 10; i++) {
            primaryBreaker.onError(1, TimeUnit.MILLISECONDS, new IllegalStateException("provider unavailable"));
        }
        StubProvider primary = new StubProvider("flutterwave");
        StubProvider secondary = new StubProvider("monnify");
        ResilientPayoutRouter router = new ResilientPayoutRouter(primary, secondary, registry);

        PayoutProvider.SendResult result = router.route(REQUEST, ignored -> { });

        assertEquals("monnify", result.providerName());
        assertEquals(0, primary.sendCalls);
        assertEquals(1, primary.statusCalls);
        assertEquals(1, secondary.sendCalls);
    }

    @Test
    void openPrimaryCircuitStillChecksAnEarlierAmbiguousReferenceBeforeFailingOver() {
        CircuitBreakerRegistry registry = ResilientPayoutRouter.newRegistry();
        var breaker = registry.circuitBreaker("flutterwave");
        for (int i = 0; i < 10; i++) breaker.onError(1, TimeUnit.MILLISECONDS, new IllegalStateException("down"));
        StubProvider primary = new StubProvider("flutterwave");
        primary.status = PayoutProvider.PayoutStatus.PENDING;
        StubProvider secondary = new StubProvider("monnify");

        PayoutProvider.SendResult result = new ResilientPayoutRouter(primary, secondary, registry).route(REQUEST, ignored -> { });

        assertEquals(PayoutProvider.SendStatus.PENDING, result.status());
        assertEquals(1, primary.statusCalls);
        assertEquals(0, secondary.sendCalls);
    }

    @Test
    void statusLookupFailureAfterPrimarySendErrorIsAmbiguousAndDoesNotFailOver() {
        StubProvider primary = new StubProvider("flutterwave");
        primary.sendFailure = new IllegalStateException("gateway timeout");
        primary.statusFailure = new IllegalStateException("status timeout");
        StubProvider secondary = new StubProvider("monnify");
        ResilientPayoutRouter router = new ResilientPayoutRouter(primary, secondary);

        assertThrows(ResilientPayoutRouter.AmbiguousPayoutException.class,
                () -> router.route(REQUEST, ignored -> { }));
        assertEquals(0, secondary.sendCalls);
    }

    @Test
    void secondaryTimeoutIsNotRetriedWhenItsStatusIsStillPending() {
        StubProvider primary = new StubProvider("flutterwave");
        StubProvider secondary = new StubProvider("monnify");
        secondary.sendFailure = new IllegalStateException("read timeout");
        secondary.status = PayoutProvider.PayoutStatus.PENDING;
        ResilientPayoutRouter router = new ResilientPayoutRouter(primary, secondary);

        PayoutProvider.SendResult result = router.route(REQUEST, "monnify", ignored -> { });

        assertEquals(PayoutProvider.SendStatus.PENDING, result.status());
        assertEquals(1, secondary.sendCalls);
        assertEquals(0, primary.sendCalls);
    }

    @Test
    void operatorRerouteCanChooseMonnifyWithoutCallingFlutterwave() {
        StubProvider primary = new StubProvider("flutterwave");
        StubProvider secondary = new StubProvider("monnify");
        List<String> selections = new ArrayList<>();

        PayoutProvider.SendResult result = new ResilientPayoutRouter(primary, secondary)
                .route(REQUEST, "monnify", selections::add);

        assertEquals(List.of("monnify"), selections);
        assertEquals(0, primary.sendCalls);
        assertEquals("monnify", result.providerName());
    }

    @Test
    void combinesProviderBanksInNameOrderAndKeepsPrimaryCodeMapping() {
        StubProvider primary = new StubProvider("flutterwave");
        primary.banks = List.of(new PayoutProvider.Bank("Zenith Bank", "057"),
                new PayoutProvider.Bank("Primary GT", "058"));
        StubProvider secondary = new StubProvider("monnify");
        secondary.banks = List.of(new PayoutProvider.Bank("Access Bank", "044"),
                new PayoutProvider.Bank("Secondary GT", "058"));

        assertEquals(List.of(new PayoutProvider.Bank("Access Bank", "044"),
                new PayoutProvider.Bank("Primary GT", "058"), new PayoutProvider.Bank("Zenith Bank", "057")),
                new ResilientPayoutRouter(primary, secondary).listBanks());
    }

    @Test
    void stillListsBanksWhenThePrimaryBankFeedIsUnavailable() {
        StubProvider primary = new StubProvider("flutterwave");
        primary.bankFailure = new IllegalStateException("primary bank feed down");
        StubProvider secondary = new StubProvider("monnify");
        secondary.banks = List.of(new PayoutProvider.Bank("Access Bank", "044"));
        assertEquals(secondary.banks, new ResilientPayoutRouter(primary, secondary).listBanks());
    }

    private static final class StubProvider implements PayoutProvider {
        final String name;
        int sendCalls;
        int statusCalls;
        RuntimeException sendFailure;
        RuntimeException statusFailure;
        RuntimeException bankFailure;
        PayoutStatus status = PayoutStatus.NOT_FOUND;
        List<Bank> banks = List.of();

        StubProvider(String name) { this.name = name; }

        @Override public String getProviderName() { return name; }
        @Override public List<Bank> listBanks() {
            if (bankFailure != null) throw bankFailure;
            return banks;
        }
        @Override public Resolution resolveAccount(String bankCode, String accountNumber) {
            return new Resolution(Outcome.FOUND, "Ada Okafor");
        }
        @Override public SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber,
                                         String accountName, String narration) {
            sendCalls++;
            if (sendFailure != null) throw sendFailure;
            return new SendResult(name + "-ref", SendStatus.ACCEPTED);
        }
        @Override public PayoutStatus getStatus(String key) {
            statusCalls++;
            if (statusFailure != null) throw statusFailure;
            return status;
        }
        @Override public StatusResult getStatusDetails(String key) {
            statusCalls++;
            if (statusFailure != null) throw statusFailure;
            return new StatusResult(status, key, new BigDecimal("100.00"), "NGN");
        }
    }
}
