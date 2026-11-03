package org.elibanking.paymentservice.controller;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.elibanking.paymentservice.dto.CreatePaymentRequest;
import org.elibanking.paymentservice.dto.PaymentOrderResponse;
import org.elibanking.paymentservice.security.Caller;
import org.elibanking.paymentservice.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Card payments through Stripe")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/create-order")
    @Operation(summary = "Create a Stripe payment order",
            description = "Creates a Stripe PaymentIntent in USD for one of **your own** accounts. Use the returned `clientSecret` and "
                    + "`stripePublishableKey` with Stripe.js / Stripe Elements on the frontend to collect the card. "
                    + "The final result arrives through the webhook. Amount must have at most 2 decimals.")
    @ApiResponse(responseCode = "201", description = "Payment intent created, status CREATED")
    @ApiResponse(responseCode = "400", description = "Validation failed, or amount has more than 2 decimals",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account belongs to another user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Stripe rejected the request or is unreachable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PaymentOrderResponse> createPaymentOrder(@Valid @RequestBody CreatePaymentRequest request, Caller caller) throws StripeException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createPaymentOrder(request, caller));
    }

    @PostMapping("/webhook")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @Operation(summary = "Stripe webhook (called by Stripe, public)",
            description = "Receives `payment_intent.succeeded` / `payment_intent.payment_failed`, updates the payment "
                    + "and publishes `payment.completed` / `payment.failed`. Not meant to be called manually — the "
                    + "`Stripe-Signature` header is verified with STRIPE_WEBHOOK_SECRET. To test locally: "
                    + "`stripe listen --forward-to localhost:8080/api/v1/payments/webhook`.")
    @ApiResponse(responseCode = "200", description = "Event processed")
    @ApiResponse(responseCode = "400", description = "Missing or invalid Stripe signature",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<String> handleWebhook(@RequestBody String payload,
                                                @Parameter(description = "Signature header set by Stripe")
                                                @RequestHeader("Stripe-Signature") String signatureHeader) {
        paymentService.handleWebhook(payload, signatureHeader);
        return ResponseEntity.ok("Webhook processed");
    }
}
