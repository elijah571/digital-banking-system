package org.elibanking.paymentservice.payout;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.client.HttpStatusCodeException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Routes payouts to Flutterwave first and only crosses to Monnify when the first rail is known not to have sent. */
public final class ResilientPayoutRouter implements PayoutProvider {

    private static final Comparator<Bank> BANK_ORDER = Comparator.comparing(Bank::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Bank::name).thenComparing(Bank::code);

    private final PayoutProvider primary;
    private final PayoutProvider secondary;
    private final CircuitBreaker primaryBreaker;
    private final CircuitBreaker secondaryBreaker;

    public ResilientPayoutRouter(PayoutProvider primary, PayoutProvider secondary) {
        this(primary, secondary, newRegistry());
    }

    ResilientPayoutRouter(PayoutProvider primary, PayoutProvider secondary, CircuitBreakerRegistry registry) {
        if (!"flutterwave".equals(primary.getProviderName()) || !"monnify".equals(secondary.getProviderName())) {
            throw new IllegalArgumentException("payout routing requires Flutterwave primary and Monnify secondary");
        }
        this.primary = primary;
        this.secondary = secondary;
        this.primaryBreaker = registry.circuitBreaker(primary.getProviderName());
        this.secondaryBreaker = registry.circuitBreaker(secondary.getProviderName());
    }

    static CircuitBreakerRegistry newRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Override
    public String getProviderName() {
        return "resilient";
    }

    @Override
    public List<Bank> listBanks() {
        Map<String, Bank> byCode = new LinkedHashMap<>();
        RuntimeException firstFailure = null;
        try {
            // Keep primary names/codes when both rails list a bank with the same code.
            primary.listBanks().forEach(bank -> byCode.putIfAbsent(bank.code(), bank));
        } catch (RuntimeException unavailable) {
            firstFailure = unavailable;
        }
        try {
            secondary.listBanks().forEach(bank -> byCode.putIfAbsent(bank.code(), bank));
        } catch (RuntimeException unavailable) {
            if (firstFailure != null) {
                unavailable.addSuppressed(firstFailure);
                throw unavailable;
            }
        }
        if (byCode.isEmpty() && firstFailure != null) throw firstFailure;
        List<Bank> banks = new ArrayList<>(byCode.values());
        banks.sort(BANK_ORDER);
        return List.copyOf(banks);
    }

    @Override
    public Resolution resolveAccount(String bankCode, String accountNumber) {
        try {
            Resolution result = primaryBreaker.executeSupplier(() -> primary.validateAccount(bankCode, accountNumber));
            if (result.outcome() != Outcome.UNAVAILABLE) {
                return result;
            }
        } catch (RuntimeException degraded) {
            // Name enquiry has no money side effect, so a secondary lookup is safe on any primary failure.
        }
        return secondaryBreaker.executeSupplier(() -> secondary.validateAccount(bankCode, accountNumber));
    }

    @Override
    public SendResult send(String key, BigDecimal amount, String bankCode, String accountNumber, String accountName,
                           String narration) {
        return route(new PayoutRequest(key, amount, bankCode, accountNumber, accountName, narration), ignored -> { });
    }

    /** The callback durably records the selected rail before any provider request is made. */
    public SendResult route(PayoutRequest request, Consumer<String> selectedProvider) {
        return route(request, primary.getProviderName(), selectedProvider);
    }

    /** Used only after an operator's durable reroute selection or safe same-rail reconciliation. */
    public SendResult route(PayoutRequest request, String preferredProvider, Consumer<String> selectedProvider) {
        if (secondary.getProviderName().equalsIgnoreCase(preferredProvider)) {
            return sendSecondary(request, selectedProvider);
        }
        if (!primary.getProviderName().equalsIgnoreCase(preferredProvider)) {
            throw new IllegalArgumentException("unknown payout provider");
        }
        selectedProvider.accept(primary.getProviderName());
        try {
            return named(primary, primaryBreaker.executeSupplier(() -> primary.initiatePayout(request)));
        } catch (CallNotPermittedException open) {
            return confirmPrimaryAbsentThenFailover(request, selectedProvider);
        } catch (RuntimeException primaryFailure) {
            if (primaryFailure instanceof HttpStatusCodeException http && http.getStatusCode().is4xxClientError()) {
                throw primaryFailure;
            }
            return confirmPrimaryAbsentThenFailover(request, selectedProvider);
        }
    }

    private SendResult confirmPrimaryAbsentThenFailover(PayoutRequest request, Consumer<String> selectedProvider) {
        final StatusResult status;
        try {
            // This read is intentionally outside the circuit breaker. Even when the breaker is open, confirm no prior request
            // reached the primary before allowing another rail to send this idempotency key.
            status = primary.checkStatus(request.key());
        } catch (RuntimeException statusFailure) {
            throw new AmbiguousPayoutException(primary.getProviderName(), statusFailure);
        }
        if (status == null || status.status() == null) {
            throw new AmbiguousPayoutException(primary.getProviderName(), new IllegalStateException("provider returned no payout status"));
        }
        return switch (status.status()) {
            case NOT_FOUND -> sendSecondary(request, selectedProvider);
            case SUCCESS, PENDING, FAILED, REVERSED -> fromStatus(primary, status);
        };
    }

    private SendResult sendSecondary(PayoutRequest request, Consumer<String> selectedProvider) {
        selectedProvider.accept(secondary.getProviderName());
        try {
            SendResult result = secondaryBreaker.executeSupplier(() -> secondary.initiatePayout(request));
            return named(secondary, result);
        } catch (RuntimeException sendFailure) {
            final StatusResult status;
            try {
                status = secondary.checkStatus(request.key());
            } catch (RuntimeException statusFailure) {
                throw new AmbiguousPayoutException(secondary.getProviderName(), statusFailure);
            }
            if (status == null || status.status() == null) {
                throw new AmbiguousPayoutException(secondary.getProviderName(), new IllegalStateException("provider returned no payout status"));
            }
            return switch (status.status()) {
                case NOT_FOUND -> throw sendFailure;
                case SUCCESS, PENDING, FAILED, REVERSED -> fromStatus(secondary, status);
            };
        }
    }

    @Override
    public PayoutStatus getStatus(String key) {
        return getStatusDetails(key).status();
    }

    @Override
    public StatusResult getStatusDetails(String key) {
        return primary.checkStatus(key);
    }

    /** Query the persisted active rail selected by the wallet. */
    @Override
    public StatusResult checkStatus(String key, String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return getStatusDetails(key);
        }
        PayoutProvider provider = switch (providerName.toLowerCase(Locale.ROOT)) {
            case "flutterwave" -> primary;
            case "monnify" -> secondary;
            default -> throw new IllegalArgumentException("unknown payout provider");
        };
        return provider.checkStatus(key);
    }

    private static SendResult named(PayoutProvider provider, SendResult result) {
        return new SendResult(result.providerRef(), result.status(), provider.getProviderName());
    }

    private static SendResult fromStatus(PayoutProvider provider, StatusResult status) {
        SendStatus sendStatus = switch (status.status()) {
            case FAILED, REVERSED, NOT_FOUND -> SendStatus.FAILED;
            case SUCCESS -> SendStatus.ACCEPTED;
            case PENDING -> SendStatus.PENDING;
        };
        return new SendResult(PayoutReference.fromPayoutKey(status.reference()), sendStatus, provider.getProviderName());
    }

    public static final class AmbiguousPayoutException extends RuntimeException {
        private final String providerName;

        public AmbiguousPayoutException(String providerName, Throwable cause) {
            super("payout outcome could not be confirmed from " + providerName, cause);
            this.providerName = providerName;
        }

        public String providerName() {
            return providerName;
        }
    }
}
