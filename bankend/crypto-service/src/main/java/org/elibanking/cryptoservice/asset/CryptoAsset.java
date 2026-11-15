package org.elibanking.cryptoservice.asset;

public record CryptoAsset(String assetKey, String fireblocksAssetId, String networkCode, String displaySymbol,
                          String displayName, String contractAddress, String addressMode, String feeAssetKey, int decimals, String addressPattern,
                          int confirmationThreshold, String dailyWithdrawalLimitBaseUnits, boolean manualDepositReview, boolean enabled) {}
