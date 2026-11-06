package org.elibanking.walletservice.enquiry;

import lombok.extern.slf4j.Slf4j;
import org.elibanking.walletservice.client.PaymentServiceClient;
import org.elibanking.walletservice.client.PaymentServiceClient.Bank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The bank list (wallet-plan 8.4): the provider's banks, plus our own, which the provider does not list. The provider's list is cached
 * in Redis for BANK_LIST_CACHE_TTL. A Redis that cannot be read is not an error: the list is read straight from the provider (3.10, a
 * read endpoint fails open).
 */
@Slf4j
@Service
public class BankListService {

    static final String CACHE_KEY = "banks:provider-list";

    private final PaymentServiceClient payments;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String ownBankName;
    private final String ownBankCode;
    private final Duration cacheTtl;

    public BankListService(PaymentServiceClient payments, StringRedisTemplate redis, ObjectMapper mapper,
                           @Value("${wallet.own-bank.name}") String ownBankName,
                           @Value("${wallet.own-bank.code}") String ownBankCode,
                           @Value("${wallet.banks.cache-ttl}") Duration cacheTtl) {
        this.payments = payments;
        this.redis = redis;
        this.mapper = mapper;
        this.ownBankName = ownBankName;
        this.ownBankCode = ownBankCode;
        this.cacheTtl = cacheTtl;
    }

    public List<Bank> list() {
        List<Bank> banks = new ArrayList<>(providerBanks());
        banks.add(new Bank(ownBankName, ownBankCode));
        return banks;
    }

    private List<Bank> providerBanks() {
        String cached = readCache();
        if (cached != null) {
            try {
                return parse(cached);
            } catch (RuntimeException unreadable) {
                // A corrupt entry is a miss: it is replaced below, and it expires anyway.
                log.warn("The cached bank list is unreadable; reading the provider");
            }
        }
        List<Bank> fetched = payments.listBanks();
        writeCache(fetched);
        return fetched;
    }

    private String readCache() {
        try {
            return redis.opsForValue().get(CACHE_KEY);
        } catch (DataAccessException unreachable) {
            log.warn("The bank list cache could not be read; reading the provider ({})", unreachable.getClass().getSimpleName());
            return null;
        }
    }

    private void writeCache(List<Bank> banks) {
        try {
            redis.opsForValue().set(CACHE_KEY, mapper.writeValueAsString(banks), cacheTtl);
        } catch (DataAccessException unreachable) {
            log.warn("The bank list could not be cached ({})", unreachable.getClass().getSimpleName());
        }
    }

    private List<Bank> parse(String json) {
        List<Bank> banks = new ArrayList<>();
        for (JsonNode bank : mapper.readTree(json)) {
            banks.add(new Bank(bank.path("name").asText(), bank.path("code").asText()));
        }
        return banks;
    }
}
