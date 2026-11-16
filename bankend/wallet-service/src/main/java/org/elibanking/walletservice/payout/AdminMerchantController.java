package org.elibanking.walletservice.payout;

import org.elibanking.walletservice.client.PaymentServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only Flutterwave merchant directory for operations administrators. */
@RestController
@RequestMapping("/api/v1/admin/merchants")
public class AdminMerchantController {

    private final PaymentServiceClient payments;

    public AdminMerchantController(PaymentServiceClient payments) {
        this.payments = payments;
    }

    @GetMapping
    public List<PaymentServiceClient.Merchant> list() {
        return payments.listMerchants();
    }
}
