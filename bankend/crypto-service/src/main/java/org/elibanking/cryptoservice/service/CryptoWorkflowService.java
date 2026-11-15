package org.elibanking.cryptoservice.service;

import org.elibanking.cryptoservice.asset.CryptoAsset;
import org.elibanking.cryptoservice.asset.CryptoAssetService;
import org.elibanking.cryptoservice.client.AccountServiceClient;
import org.elibanking.cryptoservice.client.CryptoFraudClient;
import org.elibanking.cryptoservice.client.WalletPinClient;
import org.elibanking.cryptoservice.event.CryptoOutbox;
import org.elibanking.cryptoservice.provider.FireblocksClient;
import org.elibanking.cryptoservice.provider.FireblocksWebhookVerifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CryptoWorkflowService {
    public record DepositAddress(String assetKey,String networkCode,String symbol,String address,String tag) {}
    public record TransactionView(String id,String userId,String direction,String assetKey,String symbol,String networkCode,String amount,
                                  String status,String providerStatus,String txHash,String destinationAddress,String destinationTag,
                                  int confirmations,Instant createdAt,Instant updatedAt,String failureReason) {}
    public record WithdrawalRequest(String assetKey,String amount,String destinationAddress,String destinationTag,String pin) {
        @Override public String toString(){return "WithdrawalRequest[assetKey="+assetKey+", amount="+amount+", destinationAddress=redacted, destinationTag=redacted, pin=****]";}
    }
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final CryptoAssetService assets;
    private final FireblocksClient fireblocks; private final FireblocksWebhookVerifier webhookVerifier;
    private final AccountServiceClient accounts; private final WalletPinClient pins; private final CryptoFraudClient fraud;
    private final CryptoOutbox outbox; private final ObjectMapper mapper; private final String workspaceId;

    public CryptoWorkflowService(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager transactionManager,
            CryptoAssetService assets,FireblocksClient fireblocks,FireblocksWebhookVerifier webhookVerifier,
            AccountServiceClient accounts,WalletPinClient pins,CryptoFraudClient fraud,CryptoOutbox outbox,ObjectMapper mapper,
            @Value("${fireblocks.webhook-workspace-id:}") String workspaceId){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(transactionManager);this.assets=assets;this.fireblocks=fireblocks;
        this.webhookVerifier=webhookVerifier;this.accounts=accounts;this.pins=pins;this.fraud=fraud;this.outbox=outbox;this.mapper=mapper;this.workspaceId=workspaceId;
    }

    public DepositAddress depositAddress(String userId,String assetKey){
        CryptoAsset asset=assets.requireEnabled(assetKey);
        return tx.execute(status->{
            jdbc.update("INSERT IGNORE INTO customer_vault(user_id,vault_account_id,provisioning_status) VALUES(?,NULL,'CREATING')",userId);
            Map<String,Object> vault=jdbc.queryForMap("SELECT vault_account_id,provisioning_status FROM customer_vault WHERE user_id=? FOR UPDATE",userId);
            String vaultId=(String)vault.get("vault_account_id");
            if(vaultId==null){JsonNode created=fireblocks.createVault(userId);vaultId=created.path("id").asText("");if(vaultId.isBlank())throw new FireblocksClient.FireblocksUnavailable("Fireblocks did not return a vault account id");
                jdbc.update("UPDATE customer_vault SET vault_account_id=?,provisioning_status='READY' WHERE user_id=?",vaultId,userId);}
            List<DepositAddress> current=jdbc.query("""
                    SELECT a.address,a.address_tag FROM crypto_deposit_address a WHERE a.user_id=? AND a.asset_key=? AND a.provisioning_status='READY'
                    """,(rs,n)->new DepositAddress(asset.assetKey(),asset.networkCode(),asset.displaySymbol(),rs.getString(1),rs.getString(2)),userId,assetKey);
            if(!current.isEmpty())return current.getFirst();
            JsonNode wallet=fireblocks.ensureAssetWallet(vaultId,asset.fireblocksAssetId());
            JsonNode address;
            if("DERIVED".equals(asset.addressMode())) address=fireblocks.createAddress(vaultId,asset.fireblocksAssetId(),"customer-"+sha(userId).substring(0,20),"address-"+sha(userId+"|"+assetKey));
            else address=wallet;
            String value=address.path("address").asText("");String tag=address.path("tag").asText(null);
            if(value.isBlank())throw new FireblocksClient.FireblocksUnavailable("Fireblocks did not return a deposit address");
            jdbc.update("""
                    INSERT INTO crypto_deposit_address(user_id,asset_key,vault_account_id,address,address_tag,provider_address_id,provisioning_status)
                    VALUES(?,?,?,?,?,?,'READY')
                    """,userId,assetKey,vaultId,value,blank(tag),address.path("id").asText(null));
            return new DepositAddress(asset.assetKey(),asset.networkCode(),asset.displaySymbol(),value,blank(tag));
        });
    }

    public List<Map<String,Object>> balances(String userId){
        List<AccountServiceClient.Balance> rows=accounts.balances(userId);List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows){try{CryptoAsset asset=assets.requireEnabled(row.assetKey());Map<String,Object> view=new LinkedHashMap<>();view.put("assetKey",asset.assetKey());view.put("symbol",asset.displaySymbol());view.put("networkCode",asset.networkCode());
            view.put("available",human(new BigInteger(row.availableBaseUnits()),asset.decimals()));view.put("reserved",human(new BigInteger(row.reservedBaseUnits()),asset.decimals()));result.add(view);}catch(IllegalArgumentException ignored){}}
        return result;
    }
    public List<DepositAddress> addresses(String userId){return jdbc.query("SELECT a.asset_key,a.address,a.address_tag,c.network_code,c.display_symbol FROM crypto_deposit_address a JOIN crypto_asset c ON c.asset_key=a.asset_key WHERE a.user_id=? AND a.provisioning_status='READY' ORDER BY a.created_at DESC",
            (rs,n)->new DepositAddress(rs.getString("asset_key"),rs.getString("network_code"),rs.getString("display_symbol"),rs.getString("address"),rs.getString("address_tag")),userId);}

    public TransactionView requestWithdrawal(String userId,String idempotencyKey,WithdrawalRequest request){
        if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>255)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An Idempotency-Key header is required");
        CryptoAsset asset=assets.requireEnabled(request.assetKey());BigInteger amount=baseUnits(request.amount(),asset.decimals());
        String destination=request.destinationAddress()==null?"":request.destinationAddress().trim();
        if(destination.isBlank()||destination.length()>255)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A destination address is required");
        if(asset.addressPattern()!=null&&!Pattern.matches(asset.addressPattern(),destination))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Destination address does not match the selected network");
        String hash=sha(String.join("|",asset.assetKey(),amount.toString(),destination,Objects.toString(request.destinationTag(),"")));
        TransactionView prior=findByKey(userId,idempotencyKey);
        if(prior!=null){String priorHash=jdbc.queryForObject("SELECT request_hash FROM crypto_transaction WHERE id=?",String.class,prior.id());if(!hash.equals(priorHash))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency-Key was used with different withdrawal details");return prior;}
        pins.verify(userId,request.pin());
        List<AccountServiceClient.Balance> balanceRows=accounts.balances(userId);BigInteger available=balanceRows.stream().filter(x->x.assetKey().equals(asset.assetKey())).findFirst().map(x->new BigInteger(x.availableBaseUnits())).orElse(BigInteger.ZERO);
        boolean flagged;
        try{flagged=fraud.flagged(userId,asset.assetKey(),humanDecimal(amount,asset.decimals()),humanDecimal(available,asset.decimals()));}
        catch(RuntimeException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Risk screening is unavailable; withdrawal was not created");}
        String id=UUID.randomUUID().toString();LocalDate day=LocalDate.now(java.time.ZoneOffset.UTC);
        tx.executeWithoutResult(status->{jdbc.update("INSERT IGNORE INTO crypto_daily_usage(user_id,asset_key,usage_day,reserved_base_units,version) VALUES(?,?,?,0,0)",userId,asset.assetKey(),day);
            BigDecimal used=jdbc.queryForObject("SELECT reserved_base_units FROM crypto_daily_usage WHERE user_id=? AND asset_key=? AND usage_day=? FOR UPDATE",BigDecimal.class,userId,asset.assetKey(),day);
            if(used.add(new BigDecimal(amount)).compareTo(new BigDecimal(asset.dailyWithdrawalLimitBaseUnits()))>0)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Daily crypto withdrawal limit would be exceeded");
            jdbc.update("UPDATE crypto_daily_usage SET reserved_base_units=reserved_base_units+?,version=version+1 WHERE user_id=? AND asset_key=? AND usage_day=?",new BigDecimal(amount),userId,asset.assetKey(),day);
            jdbc.update("""
                    INSERT INTO crypto_transaction(id,user_id,direction,asset_key,amount_base_units,destination_address,destination_tag,status,
                       idempotency_key,request_hash,reserved_base_units,usage_day,requires_review)
                    VALUES(?,?,'WITHDRAWAL',?,?,?,?,'RESERVING',?,?,?,?,?)
                    """,id,userId,asset.assetKey(),new BigDecimal(amount),destination,blank(request.destinationTag()),idempotencyKey,hash,new BigDecimal(amount),day,flagged);
        });
        try{
            accounts.apply(new AccountServiceClient.Operation("WITHDRAWAL_RESERVE",userId,asset.assetKey(),amount,null,null,"crypto-"+id+"-reserve",id));
        }catch(AccountServiceClient.InsufficientCryptoBalance refused){failBeforeSubmit(id,"Insufficient crypto balance");throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Insufficient available crypto balance");}
        catch(RuntimeException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Crypto reservation is pending recovery; do not resubmit with a new key");}
        setStatus(id,flagged?"REVIEW_REQUIRED":"RESERVED",null,null,0);
        if(flagged){emitTransactionEvent(id,"crypto.withdrawal.review_required");return getTransaction(userId,id);}
        submitWithdrawal(id);
        return getTransaction(userId,id);
    }

    private void submitWithdrawal(String id){
        Map<String,Object> row=jdbc.queryForMap("SELECT user_id,asset_key,amount_base_units,destination_address,destination_tag,status FROM crypto_transaction WHERE id=?",id);
        if(!List.of("RESERVED","SUBMISSION_UNKNOWN").contains(row.get("status")))return;
        CryptoAsset asset=assets.requireEnabled((String)row.get("asset_key"));String vaultId=ensureVault((String)row.get("user_id"));
        try{
            fireblocks.ensureAssetWallet(vaultId,asset.fireblocksAssetId());
            JsonNode provider=fireblocks.createTransaction(asset.fireblocksAssetId(),vaultId,human(((BigDecimal)row.get("amount_base_units")).toBigIntegerExact(),asset.decimals()),
                    (String)row.get("destination_address"),(String)row.get("destination_tag"),id,"Eli-Pay crypto withdrawal");
            String txId=provider==null?null:provider.path("id").asText(null);String providerStatus=provider==null?null:provider.path("status").asText(null);
            Integer changed=tx.execute(s->jdbc.update("UPDATE crypto_transaction SET provider_tx_id=?,provider_status=?,status=? WHERE id=? AND status IN ('RESERVED','SUBMISSION_UNKNOWN')",txId,providerStatus,txId==null?"SUBMISSION_UNKNOWN":"SUBMITTED",id));
            if(changed!=null&&changed>0)emitTransactionEvent(id,"crypto.withdrawal.submitted");
        }catch(RuntimeException unknown){tx.executeWithoutResult(s->jdbc.update("UPDATE crypto_transaction SET status='SUBMISSION_UNKNOWN' WHERE id=? AND status IN ('RESERVED','SUBMISSION_UNKNOWN')",id));}
    }

    private String ensureVault(String userId){
        return tx.execute(s->{jdbc.update("INSERT IGNORE INTO customer_vault(user_id,vault_account_id,provisioning_status) VALUES(?,NULL,'CREATING')",userId);
            Map<String,Object> row=jdbc.queryForMap("SELECT vault_account_id FROM customer_vault WHERE user_id=? FOR UPDATE",userId);String id=(String)row.get("vault_account_id");
            if(id==null){JsonNode vault=fireblocks.createVault(userId);id=vault.path("id").asText("");if(id.isBlank())throw new FireblocksClient.FireblocksUnavailable("Fireblocks did not return a vault id");jdbc.update("UPDATE customer_vault SET vault_account_id=?,provisioning_status='READY' WHERE user_id=?",id,userId);}return id;});
    }

    public List<TransactionView> transactions(String userId){return jdbc.query("SELECT * FROM crypto_transaction WHERE user_id=? ORDER BY created_at DESC LIMIT 100",(rs,n)->view(rs),userId);}
    public TransactionView getTransaction(String userId,String id){List<TransactionView> found=jdbc.query("SELECT * FROM crypto_transaction WHERE id=? AND user_id=?",(rs,n)->view(rs),id,userId);if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Crypto transaction not found");return found.getFirst();}
    public List<TransactionView> adminReviews(){return jdbc.query("SELECT * FROM crypto_transaction WHERE status='REVIEW_REQUIRED' ORDER BY created_at",(rs,n)->view(rs));}

    public TransactionView decide(String id,String adminId,boolean approve,String reason){
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM crypto_transaction WHERE id=? AND status='REVIEW_REQUIRED'",id);
        String direction=(String)row.get("direction");
        if(approve){
            if("DEPOSIT".equals(direction)){creditDeposit(id);auditAdmin(adminId,"APPROVE_DEPOSIT",id,null);}
            else{tx.executeWithoutResult(s->{jdbc.update("UPDATE crypto_transaction SET status='RESERVED' WHERE id=? AND status='REVIEW_REQUIRED'",id);});auditAdmin(adminId,"APPROVE_WITHDRAWAL",id,null);submitWithdrawal(id);}
        }else{
            if(reason==null||reason.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A rejection reason is required");
            if("WITHDRAWAL".equals(direction)){releaseWithdrawal(row,"REJECTED",reason);}
            else tx.executeWithoutResult(s->{jdbc.update("UPDATE crypto_transaction SET status='REJECTED',failure_reason=? WHERE id=?",reason,id);});
            auditAdmin(adminId,"REJECT_"+direction,id,reason);emitTransactionEvent(id,"crypto."+direction.toLowerCase()+".rejected");
        }
        String userId=(String)row.get("user_id");return getTransaction(userId,id);
    }

    public TransactionView cancel(String userId,String id){
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM crypto_transaction WHERE id=? AND user_id=?",id,userId);
        if(!"WITHDRAWAL".equals(row.get("direction"))||!List.of("RESERVED","REVIEW_REQUIRED").contains(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"This withdrawal can no longer be cancelled");
        releaseWithdrawal(row,"CANCELLED","Cancelled by customer");return getTransaction(userId,id);
    }

    private void releaseWithdrawal(Map<String,Object> row,String terminal,String reason){
        String id=(String)row.get("id"),user=(String)row.get("user_id"),asset=(String)row.get("asset_key");BigInteger amount=((BigDecimal)row.get("reserved_base_units")).toBigIntegerExact();
        accounts.apply(new AccountServiceClient.Operation("WITHDRAWAL_RELEASE",user,asset,amount,null,null,"crypto-"+id+"-release",id));
        Integer changed=tx.execute(s->{int count=jdbc.update("UPDATE crypto_transaction SET status=?,failure_reason=? WHERE id=? AND status IN ('RESERVING','RESERVED','REVIEW_REQUIRED','SUBMITTED','CONFIRMING','SUBMISSION_UNKNOWN')",terminal,reason,id);
            if(count>0)releaseDailyUsage(user,asset,amount,(java.sql.Date)row.get("usage_day"));return count;});
        if(changed!=null&&changed>0)emitTransactionEvent(id,"crypto.withdrawal."+terminal.toLowerCase());
    }

    public boolean acceptWebhook(byte[] rawBody,String signature){
        if(!webhookVerifier.verify(rawBody,signature))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Fireblocks webhook signature");
        final JsonNode event;try{event=mapper.readTree(rawBody);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Fireblocks webhook JSON");}
        String suppliedWorkspace=event.path("workspaceId").asText("");if(!workspaceId.isBlank()&&!workspaceId.equals(suppliedWorkspace))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Fireblocks webhook workspace did not match");
        String eventId=event.path("id").asText("");String eventType=event.path("eventType").asText("");String resource=event.path("resourceId").asText(null);
        if(eventId.isBlank()||eventType.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Fireblocks webhook is missing event identity");
        try{jdbc.update("INSERT INTO crypto_provider_event(event_id,event_type,resource_id,payload) VALUES(?,?,?,?)",eventId,eventType,resource,mapper.writeValueAsString(event));}
        catch(DuplicateKeyException duplicate){return true;}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Webhook could not be durably recorded");}
        return true;
    }

    @Scheduled(fixedDelayString="${crypto.event-processor-delay-ms:5000}")
    public void processPending(){
        List<Map<String,Object>> reserving=jdbc.queryForList("SELECT id,user_id,asset_key,amount_base_units,requires_review FROM crypto_transaction WHERE status='RESERVING' ORDER BY created_at LIMIT 100");
        for(Map<String,Object> row:reserving){String id=(String)row.get("id");try{
            accounts.apply(new AccountServiceClient.Operation("WITHDRAWAL_RESERVE",(String)row.get("user_id"),(String)row.get("asset_key"),((BigDecimal)row.get("amount_base_units")).toBigIntegerExact(),null,null,"crypto-"+id+"-reserve",id));
            boolean review=asBoolean(row.get("requires_review"));setStatus(id,review?"REVIEW_REQUIRED":"RESERVED",null,null,0);
            if(review)emitTransactionEvent(id,"crypto.withdrawal.review_required");else submitWithdrawal(id);
        }catch(AccountServiceClient.InsufficientCryptoBalance refused){failBeforeSubmit(id,"Insufficient crypto balance");}catch(RuntimeException unavailable){/* Retry with the same account-service idempotency key on the next pass. */}}
        List<Map<String,Object>> events=jdbc.queryForList("SELECT event_id,resource_id FROM crypto_provider_event WHERE processed_at IS NULL ORDER BY received_at LIMIT 50");
        for(Map<String,Object> event:events){String eventId=(String)event.get("event_id"),resource=(String)event.get("resource_id");
            try{if(resource!=null&&!resource.isBlank()){JsonNode provider=fireblocks.transaction(resource);handleProviderTransaction(provider);}
                jdbc.update("UPDATE crypto_provider_event SET processed_at=CURRENT_TIMESTAMP(6),attempt_count=attempt_count+1,last_error=NULL WHERE event_id=?",eventId);
            }catch(RuntimeException e){jdbc.update("UPDATE crypto_provider_event SET attempt_count=attempt_count+1,last_error=? WHERE event_id=?",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage().substring(0,Math.min(450,e.getMessage().length())),eventId);}
        }
        List<String> reserved=jdbc.queryForList("SELECT id FROM crypto_transaction WHERE status='RESERVED' ORDER BY updated_at LIMIT 100",String.class);
        for(String id:reserved)submitWithdrawal(id);
        List<String> pending=jdbc.queryForList("SELECT id FROM crypto_transaction WHERE status IN ('SUBMITTED','CONFIRMING','SUBMISSION_UNKNOWN') ORDER BY updated_at LIMIT 100",String.class);
        for(String id:pending){try{Map<String,Object> row=jdbc.queryForMap("SELECT id,provider_tx_id,status FROM crypto_transaction WHERE id=?",id);String providerId=(String)row.get("provider_tx_id");
                JsonNode provider=providerId==null?fireblocks.transactionByExternalId(id):fireblocks.transaction(providerId);
                if(provider!=null&&!provider.isNull()&&provider.hasNonNull("id")){if(providerId==null)jdbc.update("UPDATE crypto_transaction SET provider_tx_id=? WHERE id=?",provider.path("id").asText(),id);handleProviderTransaction(provider);}
            }catch(FireblocksClient.ProviderNotFound ignored){}catch(RuntimeException ignored){}}
    }

    private void handleProviderTransaction(JsonNode provider){
        String providerId=provider.path("id").asText(null);if(providerId==null)return;
        List<Map<String,Object>> local=jdbc.queryForList("SELECT * FROM crypto_transaction WHERE provider_tx_id=?",providerId);
        if(!local.isEmpty()){handleWithdrawalProvider(local.getFirst(),provider);return;}
        handleDepositProvider(provider);
    }

    private void handleWithdrawalProvider(Map<String,Object> local,JsonNode provider){
        String id=(String)local.get("id"),status=provider.path("status").asText("UNKNOWN"),assetKey=(String)local.get("asset_key");CryptoAsset asset=assets.requireConfigured(assetKey);
        String destination=provider.path("destination").path("oneTimeAddress").path("address").asText(provider.path("destination").path("address").asText(""));
        BigInteger amount=((BigDecimal)local.get("amount_base_units")).toBigIntegerExact();
        BigInteger reported=provider.hasNonNull("amount")?baseUnits(provider.path("amount").asText(),asset.decimals()):amount;
        if(!asset.fireblocksAssetId().equals(provider.path("assetId").asText())||reported.compareTo(amount)!=0||!destination.isBlank()&&!destination.equals(local.get("destination_address"))){
            tx.executeWithoutResult(s->jdbc.update("UPDATE crypto_transaction SET status='REVIEW_REQUIRED',failure_reason='Provider transaction details did not match the withdrawal request',provider_status=? WHERE id=?",status,id));return;
        }
        int confirmations=provider.path("numOfConfirmations").asInt(0);jdbc.update("UPDATE crypto_transaction SET provider_status=?,confirmations=? WHERE id=?",status,confirmations,id);
        if("COMPLETED".equals(status)){settleWithdrawal(local,asset,provider);}
        else if(List.of("FAILED","CANCELLED","REJECTED").contains(status)){releaseWithdrawal(local,"FAILED","Fireblocks reported terminal status "+status);}
        else jdbc.update("UPDATE crypto_transaction SET status='CONFIRMING' WHERE id=? AND status IN ('SUBMITTED','CONFIRMING')",id);
    }

    private void settleWithdrawal(Map<String,Object> local,CryptoAsset asset,JsonNode provider){
        String id=(String)local.get("id"),user=(String)local.get("user_id");BigInteger amount=((BigDecimal)local.get("reserved_base_units")).toBigIntegerExact();
        String feeAssetKey=asset.feeAssetKey();BigInteger fee=null;
        String rawFee=provider.path("networkFee").asText("0");if(feeAssetKey!=null&&!feeAssetKey.isBlank()&&!rawFee.isBlank()){BigDecimal feeAmount=new BigDecimal(rawFee);if(feeAmount.signum()<0)throw new IllegalArgumentException("Fireblocks returned a negative network fee");fee=feeAmount.signum()==0?BigInteger.ZERO:baseUnits(rawFee,assets.requireConfigured(feeAssetKey).decimals());}
        accounts.apply(new AccountServiceClient.Operation("WITHDRAWAL_SETTLE",user,asset.assetKey(),amount,feeAssetKey,fee,"crypto-"+id+"-settle",id));
        String txHash=provider.path("txHash").asText(null),providerStatus=provider.path("status").asText();BigInteger settledFee=fee;tx.executeWithoutResult(s->{int changed=jdbc.update("UPDATE crypto_transaction SET status='COMPLETED',provider_status=?,tx_hash=?,fee_base_units=? WHERE id=? AND status IN ('SUBMITTED','CONFIRMING','SUBMISSION_UNKNOWN')",providerStatus,txHash,settledFee==null?null:new BigDecimal(settledFee),id);
            if(changed>0)outbox.append("crypto.withdrawal.completed",id,eventPayload(id));});
    }

    private void handleDepositProvider(JsonNode provider){
        String assetId=provider.path("assetId").asText(""),status=provider.path("status").asText("UNKNOWN");
        String vaultId=provider.path("destination").path("id").asText("");String address=provider.path("destination").path("address").asText("");String tag=provider.path("destination").path("tag").asText("");
        List<Map<String,Object>> mappings=jdbc.queryForList("""
                SELECT a.user_id,a.asset_key,a.address,a.address_tag,c.decimals,c.fireblocks_asset_id,c.confirmation_threshold,c.manual_deposit_review
                FROM crypto_deposit_address a JOIN crypto_asset c ON c.asset_key=a.asset_key
                WHERE a.vault_account_id=? AND c.fireblocks_asset_id=? AND a.provisioning_status='READY'
                """,vaultId,assetId);
        if(mappings.isEmpty())return;
        Map<String,Object> mapping=mappings.stream().filter(m->(address.isBlank()||address.equals(m.get("address")))&&(tag.isBlank()||tag.equals(m.get("address_tag")))).findFirst().orElse(null);
        if(mapping==null)return;
        String providerId=provider.path("id").asText();BigInteger amount=baseUnits(provider.path("amount").asText("0"),((Number)mapping.get("decimals")).intValue());
        List<String> existing=jdbc.queryForList("SELECT id FROM crypto_transaction WHERE provider_tx_id=?",String.class,providerId);String localId;
        if(existing.isEmpty()){
            localId=UUID.randomUUID().toString();boolean review=asBoolean(mapping.get("manual_deposit_review"));
            String current="COMPLETED".equals(status)&&provider.path("numOfConfirmations").asInt(0)>=((Number)mapping.get("confirmation_threshold")).intValue()&&review?"REVIEW_REQUIRED":"CONFIRMING";
            String txHash=provider.path("txHash").asText(null);
            jdbc.update("INSERT INTO crypto_transaction(id,user_id,direction,asset_key,amount_base_units,provider_tx_id,provider_status,tx_hash,status,confirmations) VALUES(?,?,'DEPOSIT',?,?,?,?,?,?,?)",
                    localId,mapping.get("user_id"),mapping.get("asset_key"),new BigDecimal(amount),providerId,status,txHash,current,provider.path("numOfConfirmations").asInt(0));
        }else localId=existing.getFirst();
        Map<String,Object> local=jdbc.queryForMap("SELECT * FROM crypto_transaction WHERE id=?",localId);
        if(!"DEPOSIT".equals(local.get("direction")))return;
        int confirmations=provider.path("numOfConfirmations").asInt(0);jdbc.update("UPDATE crypto_transaction SET provider_status=?,confirmations=? WHERE id=?",status,confirmations,localId);
        if("COMPLETED".equals(status)&&confirmations>=((Number)mapping.get("confirmation_threshold")).intValue()){
            if(asBoolean(mapping.get("manual_deposit_review")))jdbc.update("UPDATE crypto_transaction SET status='REVIEW_REQUIRED' WHERE id=? AND status='CONFIRMING'",localId);
            else creditDeposit(localId);
        }
    }

    private void creditDeposit(String id){
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM crypto_transaction WHERE id=?",id);if(!"DEPOSIT".equals(row.get("direction")))throw new IllegalArgumentException("Not a deposit");
        BigInteger amount=((BigDecimal)row.get("amount_base_units")).toBigIntegerExact();accounts.apply(new AccountServiceClient.Operation("DEPOSIT_CREDIT",(String)row.get("user_id"),(String)row.get("asset_key"),amount,null,null,"crypto-deposit-"+row.get("provider_tx_id"),id));
        tx.executeWithoutResult(s->{int changed=jdbc.update("UPDATE crypto_transaction SET status='COMPLETED',failure_reason=NULL WHERE id=? AND status IN ('CONFIRMING','REVIEW_REQUIRED')",id);if(changed>0)outbox.append("crypto.deposit.credited",id,eventPayload(id));});
    }

    private void failBeforeSubmit(String id,String reason){Map<String,Object> row=jdbc.queryForMap("SELECT user_id,asset_key,reserved_base_units,usage_day FROM crypto_transaction WHERE id=?",id);tx.executeWithoutResult(s->{int changed=jdbc.update("UPDATE crypto_transaction SET status='FAILED',failure_reason=? WHERE id=? AND status='RESERVING'",reason,id);if(changed>0)releaseDailyUsage((String)row.get("user_id"),(String)row.get("asset_key"),((BigDecimal)row.get("reserved_base_units")).toBigIntegerExact(),(java.sql.Date)row.get("usage_day"));});}
    private void releaseDailyUsage(String user,String asset,BigInteger amount,java.sql.Date usageDay){if(usageDay==null)return;LocalDate day=usageDay.toLocalDate();jdbc.update("UPDATE crypto_daily_usage SET reserved_base_units=GREATEST(0,reserved_base_units-?),version=version+1 WHERE user_id=? AND asset_key=? AND usage_day=?",new BigDecimal(amount),user,asset,day);}
    private void setStatus(String id,String status,String reason,String providerStatus,int confirmations){tx.executeWithoutResult(s->jdbc.update("UPDATE crypto_transaction SET status=?,failure_reason=?,provider_status=COALESCE(?,provider_status),confirmations=? WHERE id=?",status,reason,providerStatus,confirmations,id));}
    private void emitTransactionEvent(String id,String event){tx.executeWithoutResult(s->outbox.append(event,id,eventPayload(id)));}
    private Map<String,Object> eventPayload(String id){Map<String,Object> row=jdbc.queryForMap("SELECT id,user_id,direction,asset_key,amount_base_units,provider_tx_id,status FROM crypto_transaction WHERE id=?",id);CryptoAsset a=assets.requireConfigured((String)row.get("asset_key"));Map<String,Object> payload=new LinkedHashMap<>();payload.put("transactionId",id);payload.put("userId",row.get("user_id"));payload.put("direction",row.get("direction"));payload.put("assetKey",row.get("asset_key"));payload.put("symbol",a.displaySymbol());payload.put("networkCode",a.networkCode());payload.put("amount",human(((BigDecimal)row.get("amount_base_units")).toBigIntegerExact(),a.decimals()));payload.put("status",row.get("status"));payload.put("providerTransactionId",row.get("provider_tx_id"));return payload;}
    private void auditAdmin(String admin,String action,String id,String reason){jdbc.update("INSERT INTO crypto_admin_audit(admin_user_id,action,transaction_id,reason) VALUES(?,?,?,?)",admin,action,id,reason);}
    private TransactionView findByKey(String user,String key){List<TransactionView> rows=jdbc.query("SELECT * FROM crypto_transaction WHERE user_id=? AND idempotency_key=?",(rs,n)->view(rs),user,key);return rows.isEmpty()?null:rows.getFirst();}
    private TransactionView view(java.sql.ResultSet rs)throws java.sql.SQLException{CryptoAsset asset=assets.requireConfigured(rs.getString("asset_key"));return new TransactionView(rs.getString("id"),rs.getString("user_id"),rs.getString("direction"),asset.assetKey(),asset.displaySymbol(),asset.networkCode(),human(rs.getBigDecimal("amount_base_units").toBigIntegerExact(),asset.decimals()),rs.getString("status"),rs.getString("provider_status"),rs.getString("tx_hash"),rs.getString("destination_address"),rs.getString("destination_tag"),rs.getInt("confirmations"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getString("failure_reason"));}
    private static BigInteger baseUnits(String amount,int decimals){try{BigDecimal value=new BigDecimal(amount);if(value.signum()<=0)throw new IllegalArgumentException();BigInteger units=value.setScale(decimals,RoundingMode.UNNECESSARY).movePointRight(decimals).toBigIntegerExact();if(units.toString().length()>65)throw new IllegalArgumentException();return units;}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Amount must be positive and fit the asset precision and ledger range");}}
    private static BigDecimal humanDecimal(BigInteger units,int decimals){return new BigDecimal(units,decimals);}
    private static String human(BigInteger units,int decimals){return humanDecimal(units,decimals).stripTrailingZeros().toPlainString();}
    private static boolean asBoolean(Object value){if(value instanceof Boolean b)return b;if(value instanceof Number n)return n.intValue()!=0;return value!=null&&Boolean.parseBoolean(value.toString());}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String blank(String value){return value==null||value.isBlank()?null:value;}
}
