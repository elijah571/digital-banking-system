package org.elibanking.cryptoservice.asset;

import org.elibanking.cryptoservice.client.AccountServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class CryptoAssetService {
    public record Upsert(String assetKey, String providerAssetId, String fireblocksAssetId, String networkCode, String displaySymbol,
                         String displayName, String contractAddress, String addressMode, String feeAssetKey, int decimals, String addressPattern,
                         int confirmationThreshold, String dailyWithdrawalLimitBaseUnits, boolean manualDepositReview, boolean enabled) {}
    private final JdbcTemplate jdbc;
    private final AccountServiceClient accounts;
    private final boolean privyEnabled;
    public CryptoAssetService(JdbcTemplate jdbc, AccountServiceClient accounts,
            @Value("${privy.enabled:false}") boolean privyEnabled) {
        this.jdbc=jdbc; this.accounts=accounts; this.privyEnabled=privyEnabled;
    }
    @Transactional
    public CryptoAsset upsert(Upsert value) {
        String providerAssetId=value.providerAssetId()==null||value.providerAssetId().isBlank()?value.fireblocksAssetId():value.providerAssetId();
        if (value.assetKey()==null || !value.assetKey().matches("[A-Z0-9][A-Z0-9_:-]{0,99}")) throw new IllegalArgumentException("assetKey must be a normalized stable identifier");
        if (providerAssetId==null || !providerAssetId.matches("[A-Za-z0-9_:-]{1,100}")) throw new IllegalArgumentException("Invalid provider asset id");
        if (value.networkCode()==null || !value.networkCode().matches("[A-Z0-9_:-]{1,100}")) throw new IllegalArgumentException("Invalid network code");
        if (value.decimals()<0 || value.decimals()>36 || value.confirmationThreshold()<1 || value.confirmationThreshold()>100000 || !List.of("ACCOUNT","DERIVED").contains(value.addressMode())) throw new IllegalArgumentException("Invalid asset precision, address mode or confirmation threshold");
        BigInteger dailyLimit;
        try { dailyLimit=new BigInteger(value.dailyWithdrawalLimitBaseUnits()); } catch(Exception e) { throw new IllegalArgumentException("Daily withdrawal limit must be integer base units"); }
        if (dailyLimit.signum()<=0 || dailyLimit.toString().length()>65) throw new IllegalArgumentException("Daily withdrawal limit must be positive and fit the ledger");
        if (value.enabled() && !privyEnabled) throw new IllegalStateException("Enable Privy configuration before enabling a crypto asset");
        if (value.enabled() && (value.feeAssetKey()==null || value.feeAssetKey().isBlank())) throw new IllegalArgumentException("An enabled asset must declare the asset used to account for network fees");
        if (value.addressPattern()!=null && !value.addressPattern().isBlank()) Pattern.compile(value.addressPattern());
        jdbc.update("""
                INSERT INTO crypto_asset(asset_key,fireblocks_asset_id,provider_asset_id,network_code,display_symbol,display_name,contract_address,
                  address_mode,fee_asset_key,decimals,address_pattern,confirmation_threshold,daily_withdrawal_limit_base_units,manual_deposit_review,enabled)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE fireblocks_asset_id=VALUES(fireblocks_asset_id),provider_asset_id=VALUES(provider_asset_id),
                  network_code=VALUES(network_code),display_symbol=VALUES(display_symbol),display_name=VALUES(display_name),
                  contract_address=VALUES(contract_address),address_mode=VALUES(address_mode),fee_asset_key=VALUES(fee_asset_key),decimals=VALUES(decimals),address_pattern=VALUES(address_pattern),
                  confirmation_threshold=VALUES(confirmation_threshold),daily_withdrawal_limit_base_units=VALUES(daily_withdrawal_limit_base_units),manual_deposit_review=VALUES(manual_deposit_review),
                  enabled=VALUES(enabled)
                """, value.assetKey(),providerAssetId,providerAssetId,value.networkCode(),value.displaySymbol(),value.displayName(),
                value.contractAddress(),value.addressMode(),blankToNull(value.feeAssetKey()),value.decimals(),blankToNull(value.addressPattern()),value.confirmationThreshold(),dailyLimit,value.manualDepositReview(),value.enabled());
        accounts.registerAsset(value.assetKey());
        if(value.feeAssetKey()!=null&&!value.feeAssetKey().isBlank())accounts.registerAsset(value.feeAssetKey());
        return get(value.assetKey(), false);
    }
    public CryptoAsset requireEnabled(String key) { return get(key, true); }
    public CryptoAsset requireConfigured(String key) { return get(key, false); }
    public List<CryptoAsset> enabledAssets() { return list(true); }
    public List<CryptoAsset> allAssets() { return list(false); }
    private CryptoAsset get(String key, boolean enabledOnly) {
        List<CryptoAsset> list=jdbc.query("SELECT * FROM crypto_asset WHERE asset_key=?"+(enabledOnly?" AND enabled=TRUE":""), this::map, key);
        if(list.isEmpty()) throw new IllegalArgumentException(enabledOnly?"Crypto asset or network is not enabled":"Crypto asset not found"); return list.getFirst();
    }
    private List<CryptoAsset> list(boolean enabledOnly) { return jdbc.query("SELECT * FROM crypto_asset"+(enabledOnly?" WHERE enabled=TRUE":"")+" ORDER BY display_symbol,network_code",this::map); }
    private CryptoAsset map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new CryptoAsset(rs.getString("asset_key"),rs.getString("provider_asset_id"),rs.getString("network_code"),rs.getString("display_symbol"),
                rs.getString("display_name"),rs.getString("contract_address"),rs.getString("address_mode"),rs.getString("fee_asset_key"),rs.getInt("decimals"),rs.getString("address_pattern"),rs.getInt("confirmation_threshold"),
                rs.getBigDecimal("daily_withdrawal_limit_base_units").toBigIntegerExact().toString(),rs.getBoolean("manual_deposit_review"),rs.getBoolean("enabled"));
    }
    private static String blankToNull(String value) { return value==null||value.isBlank()?null:value; }
}
