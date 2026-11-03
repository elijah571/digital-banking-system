package org.elibanking.paymentservice.service;

import com.stripe.Stripe;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elibanking.paymentservice.client.AccountClient;
import org.elibanking.paymentservice.dto.CreatePaymentRequest;
import org.elibanking.paymentservice.dto.PaymentOrderResponse;
import org.elibanking.paymentservice.entity.Payment;
import org.elibanking.paymentservice.entity.PaymentStatus;
import org.elibanking.paymentservice.exception.PaymentAccessDeniedException;
import org.elibanking.paymentservice.repository.PaymentRepository;
import org.elibanking.paymentservice.security.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private static final String PAYMENT_COMPLETED_TOPIC = "payment.completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment.failed";

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AccountClient accountClient;

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.publishable-key}")
    private String publishableKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    public PaymentOrderResponse createPaymentOrder(CreatePaymentRequest request, Caller caller) throws StripeException {
        // Users can only pay into their own accounts.
        if (!caller.isInternal()) {
            AccountClient.AccountInfo account = accountClient.getAccount(request.getAccountNumber());
            if (!caller.userId().equals(account.ownerId())) {
                throw new PaymentAccessDeniedException("You can only make payments to your own accounts");
            }
        }
        log.info("Creating payment intent for account: {} amount: {}", request.getAccountNumber(), request.getAmount());

        // Convert amount to smallest currency unit (e.g., cents)
        long convertedAmount = request.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValueExact();

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(convertedAmount)
                .setCurrency("usd")
                .setDescription(request.getDescription())
                .putMetadata("accountNumber", request.getAccountNumber())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                .build();

        PaymentIntent paymentIntent = PaymentIntent.create(params);
        log.info("Stripe payment intent created: {}", paymentIntent.getId());

        // Save payment record
        Payment payment = new Payment();
        payment.setStripePaymentIntentId(paymentIntent.getId());
        payment.setAccountNumber(request.getAccountNumber());
        payment.setAmount(request.getAmount());
        payment.setCurrency("USD");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setDescription(request.getDescription());

        Payment savedPayment = paymentRepository.save(payment);

        return new PaymentOrderResponse(
                savedPayment.getId(),
                paymentIntent.getId(),
                paymentIntent.getClientSecret(),
                request.getAmount(),
                "USD",
                "CREATED",
                publishableKey
        );
    }

    public void handleWebhook(String payload, String signatureHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.error("Stripe webhook signature verification failed: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Stripe webhook signature", e);
        }

        log.info("Received Stripe webhook: {}", event.getType());

        switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentSuccessful(event);
            case "payment_intent.payment_failed" -> handlePaymentFailure(event);
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
        }
    }

    private void handlePaymentSuccessful(Event event) {
        try {
            PaymentIntent paymentIntent = extractPaymentIntent(event);
            if (paymentIntent == null) {
                return;
            }

            Payment payment = paymentRepository.findByStripePaymentIntentId(paymentIntent.getId())
                    .orElseThrow(() -> new RuntimeException("Payment not found for intent: " + paymentIntent.getId()));

            payment.setStripeChargeId(paymentIntent.getLatestCharge());
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            Map<String, Object> message = new HashMap<>();
            message.put("paymentId", payment.getId());
            message.put("accountNumber", payment.getAccountNumber());
            message.put("amount", payment.getAmount());
            message.put("stripePaymentId", paymentIntent.getId());

            kafkaTemplate.send(PAYMENT_COMPLETED_TOPIC, payment.getId(), message);
            log.info("Payment completed: {}", payment.getId());

        } catch (Exception e) {
            log.error("Error handling payment success: {}", e.getMessage(), e);
        }
    }

    private void handlePaymentFailure(Event event) {
        try {
            PaymentIntent paymentIntent = extractPaymentIntent(event);
            if (paymentIntent == null) {
                return;
            }

            Payment payment = paymentRepository.findByStripePaymentIntentId(paymentIntent.getId())
                    .orElseThrow(() -> new RuntimeException("Payment not found for intent: " + paymentIntent.getId()));

            String reason = paymentIntent.getLastPaymentError() != null
                    ? paymentIntent.getLastPaymentError().getMessage()
                    : "Payment failed via Stripe";

            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(reason);
            paymentRepository.save(payment);

            Map<String, Object> message = new HashMap<>();
            message.put("paymentId", payment.getId());
            message.put("accountNumber", payment.getAccountNumber());
            message.put("amount", payment.getAmount());
            message.put("reason", reason);

            kafkaTemplate.send(PAYMENT_FAILED_TOPIC, payment.getId(), message);
            log.warn("Payment failed: {}", payment.getId());

        } catch (Exception e) {
            log.error("Error handling payment failure: {}", e.getMessage(), e);
        }
    }

    private PaymentIntent extractPaymentIntent(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = deserializer.getObject().orElse(null);

        if (stripeObject == null) {
            // API version mismatch between the account and the pinned SDK version
            try {
                stripeObject = deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                log.error("Unable to deserialize Stripe event data object: {}", e.getMessage());
                return null;
            }
        }

        if (stripeObject instanceof PaymentIntent paymentIntent) {
            return paymentIntent;
        }

        log.warn("Webhook data object is not a PaymentIntent for event type: {}", event.getType());
        return null;
    }
}
