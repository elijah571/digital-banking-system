package org.elibanking.transactionservice.client;

/** Subset of account-service's AccountResponse needed for ownership checks. */
public record AccountInfo(String accountNumber, String ownerId, String status) {
}
