package org.elibanking.walletservice.enquiry;

import org.elibanking.walletservice.client.PaymentServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The banks a transfer can go to (wallet-plan 8.4). A user token is required for every /api route. */
@RestController
@RequestMapping("/api/v1/banks")
public class BankController {

    private final BankListService banks;

    public BankController(BankListService banks) {
        this.banks = banks;
    }

    @GetMapping
    public List<PaymentServiceClient.Bank> list() {
        return banks.list();
    }
}
