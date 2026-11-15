package org.elibanking.cryptoservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.elibanking.cryptoservice.asset.CryptoAsset;
import org.elibanking.cryptoservice.asset.CryptoAssetService;
import org.elibanking.cryptoservice.service.CryptoWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/crypto")
public class CryptoController {
    public record DepositAddressRequest(@NotBlank @Size(max=100) String assetKey) {}
    public record WithdrawalBody(@NotBlank @Size(max=100) String assetKey,
            @NotBlank @Pattern(regexp="^\\d+(\\.\\d+)?$") String amount,
            @NotBlank @Size(max=255) String destinationAddress,@Size(max=255) String destinationTag,
            @NotBlank @Pattern(regexp="^\\d{4}$") String pin) {
        @Override public String toString(){return "WithdrawalBody[assetKey="+assetKey+", amount="+amount+", destinationAddress=redacted, destinationTag=redacted, pin=****]";}
    }
    public record AdminDecision(@Size(max=500) String reason) {}
    public record WebhookAck(boolean recorded) {}
    private final CryptoAssetService assets; private final CryptoWorkflowService workflow;
    public CryptoController(CryptoAssetService assets,CryptoWorkflowService workflow){this.assets=assets;this.workflow=workflow;}

    @GetMapping("/assets") public List<CryptoAsset> assets(){return assets.enabledAssets();}
    @GetMapping("/networks") public List<Map<String,Object>> networks(){
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(CryptoAsset a:assets.enabledAssets()){Map<String,Object> network=result.computeIfAbsent(a.networkCode(),key->{Map<String,Object> m=new LinkedHashMap<>();m.put("networkCode",key);m.put("assets",new java.util.ArrayList<Map<String,Object>>());return m;});
            @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)network.get("assets");items.add(Map.of("assetKey",a.assetKey(),"symbol",a.displaySymbol(),"name",a.displayName()));}
        return List.copyOf(result.values());
    }
    @GetMapping("/balances") public List<Map<String,Object>> balances(@AuthenticationPrincipal Jwt jwt){return workflow.balances(userId(jwt));}
    @PostMapping("/deposit-addresses") public CryptoWorkflowService.DepositAddress address(@Valid @RequestBody DepositAddressRequest request,@AuthenticationPrincipal Jwt jwt){return workflow.depositAddress(userId(jwt),request.assetKey());}
    @GetMapping("/deposit-addresses") public List<CryptoWorkflowService.DepositAddress> addresses(@AuthenticationPrincipal Jwt jwt){return workflow.addresses(userId(jwt));}
    @PostMapping("/withdrawals") public ResponseEntity<CryptoWorkflowService.TransactionView> withdraw(
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody WithdrawalBody body,@AuthenticationPrincipal Jwt jwt){
        var result=workflow.requestWithdrawal(userId(jwt),key,new CryptoWorkflowService.WithdrawalRequest(body.assetKey(),body.amount(),body.destinationAddress(),body.destinationTag(),body.pin()));
        return ResponseEntity.accepted().body(result);
    }
    @PostMapping("/withdrawals/{id}/cancel") public CryptoWorkflowService.TransactionView cancel(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return workflow.cancel(userId(jwt),id);}
    @GetMapping("/transactions") public List<CryptoWorkflowService.TransactionView> transactions(@AuthenticationPrincipal Jwt jwt){return workflow.transactions(userId(jwt));}
    @GetMapping("/transactions/{id}") public CryptoWorkflowService.TransactionView transaction(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return workflow.getTransaction(userId(jwt),id);}

    @PostMapping("/webhooks/fireblocks") public ResponseEntity<WebhookAck> webhook(@RequestBody byte[] rawBody,
            @RequestHeader(value="Fireblocks-Webhook-Signature",required=false) String signature){workflow.acceptWebhook(rawBody,signature);return ResponseEntity.ok(new WebhookAck(true));}
    @GetMapping("/admin/reviews") public List<CryptoWorkflowService.TransactionView> reviews(){return workflow.adminReviews();}
    @PostMapping("/admin/transactions/{id}/approve") public CryptoWorkflowService.TransactionView approve(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return workflow.decide(id,jwt.getSubject(),true,null);}
    @PostMapping("/admin/transactions/{id}/reject") public CryptoWorkflowService.TransactionView reject(@PathVariable String id,@Valid @RequestBody AdminDecision body,@AuthenticationPrincipal Jwt jwt){return workflow.decide(id,jwt.getSubject(),false,body.reason());}
    @PutMapping("/admin/assets") public CryptoAsset upsertAsset(@Valid @RequestBody CryptoAssetService.Upsert request){return assets.upsert(request);}
    @GetMapping("/admin/assets") public List<CryptoAsset> allAssets(){return assets.allAssets();}

    private static String userId(Jwt jwt){if(jwt==null||"SERVICE".equals(jwt.getClaimAsString("role")))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"Customer authentication required");return jwt.getSubject();}
}
