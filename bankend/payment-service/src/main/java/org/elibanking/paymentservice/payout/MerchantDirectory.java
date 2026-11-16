package org.elibanking.paymentservice.payout;

import java.util.List;

/** Read-only collection merchant directory backed by Flutterwave subaccounts. */
public interface MerchantDirectory {

    record Merchant(String id, String businessName) {
    }

    List<Merchant> list();
}
