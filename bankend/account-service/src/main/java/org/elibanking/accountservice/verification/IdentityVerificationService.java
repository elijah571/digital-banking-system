package org.elibanking.accountservice.verification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.elibanking.accountservice.entity.Account;
import org.elibanking.accountservice.entity.AccountTier;
import org.elibanking.accountservice.exception.AccountNotFoundException;
import org.elibanking.accountservice.exception.AccountOperationException;
import org.elibanking.accountservice.repository.AccountRepository;
import org.elibanking.accountservice.security.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.Base64;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IdentityVerificationService {

    private static final String PROVIDER = "MANUAL";
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Pattern NAME_SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    @Value("${account.verification.verified-daily-limit:5000000.00}")
    private BigDecimal verifiedDailyLimit;

    @PostConstruct
    void validateVerifiedDailyLimit() {
        if (verifiedDailyLimit.signum() <= 0
                || verifiedDailyLimit.stripTrailingZeros().scale() > 2
                || verifiedDailyLimit.precision() - verifiedDailyLimit.scale() > 13) {
            throw new IllegalArgumentException("Verified daily limit must be positive and fit DECIMAL(15,2)");
        }
    }

    @Transactional(readOnly = true)
    public VerificationStatusResponse status(Caller caller) {
        List<Account> ownedAccounts = requireAccounts(caller);
        var stored = jdbc.query("""
                SELECT identity_status, upgrade_status, bvn_last_four,
                       document_type, address_document_type, address_status,
                       identity_face_match_score, identity_liveness_score,
                       upgrade_face_match_score, upgrade_liveness_score,
                       identity_verified_at, address_verified_at, account_upgraded_at
                FROM account_verification
                WHERE owner_id = ?
                """, (rs, row) -> new StoredStatus(
                rs.getString("identity_status"),
                rs.getString("upgrade_status"),
                rs.getString("bvn_last_four"),
                rs.getString("document_type"),
                rs.getString("address_document_type"),
                rs.getString("address_status"),
                rs.getBigDecimal("identity_face_match_score"),
                rs.getBigDecimal("identity_liveness_score"),
                rs.getBigDecimal("upgrade_face_match_score"),
                rs.getBigDecimal("upgrade_liveness_score"),
                rs.getObject("identity_verified_at", java.time.LocalDateTime.class),
                rs.getObject("address_verified_at", java.time.LocalDateTime.class),
                rs.getObject("account_upgraded_at", java.time.LocalDateTime.class)
        ), caller.userId());
        Account account = ownedAccounts.getFirst();
        if (stored.isEmpty()) {
            return new VerificationStatusResponse(PROVIDER, "NOT_STARTED", "NOT_STARTED",
                    account.getAccountTier(), account.getDailyTransactionLimit(), null, null, null, "NOT_STARTED",
                    null, null, null, null, null, null, null);
        }
        StoredStatus value = stored.getFirst();
        return new VerificationStatusResponse(PROVIDER, value.identityStatus(), value.upgradeStatus(),
                account.getAccountTier(), account.getDailyTransactionLimit(), mask(value.bvnLastFour()),
                value.documentType(), value.addressDocumentType(), value.addressStatus(),
                value.identityFaceMatchScore(), value.identityLivenessScore(),
                value.upgradeFaceMatchScore(), value.upgradeLivenessScore(),
                value.identityVerifiedAt(), value.addressVerifiedAt(), value.accountUpgradedAt());
    }

    public VerificationStatusResponse verifyIdentity(Caller caller, DocumentIdentityRequest request) {
        requireAccounts(caller);
        if (hasVerifiedIdentity(caller.userId())) {
            return statusFor(caller.userId());
        }
        String documentImage = normalizeImage(request.documentImage());
        String video = normalizeVideo(request.faceVideo());
        jdbc.update("""
                INSERT INTO account_verification (owner_id, provider, identity_status, document_type,
                    identity_document_image, identity_face_video, identity_consent_at, identity_verified_at)
                VALUES (?, 'MANUAL', 'PENDING_REVIEW', ?, ?, ?, CURRENT_TIMESTAMP(6), NULL)
                ON DUPLICATE KEY UPDATE provider='MANUAL', identity_status='PENDING_REVIEW', document_type=VALUES(document_type),
                    identity_document_image=VALUES(identity_document_image), identity_face_video=VALUES(identity_face_video),
                    identity_consent_at=CURRENT_TIMESTAMP(6), identity_verified_at=NULL, review_reason=NULL,
                    reviewed_by=NULL, reviewed_at=NULL
                """, caller.userId(), request.documentType(), documentImage, video);
        return statusFor(caller.userId());
    }

    public VerificationStatusResponse upgradeAccount(Caller caller, AccountUpgradeRequest request) {
        List<Account> ownedAccounts = requireAccounts(caller);
        if (isUpgradeComplete(ownedAccounts)) {
            return statusFor(caller.userId());
        }
        Account account = ownedAccounts.getFirst();
        ensureNamesMatchAccount(account.getAccountHolderName(), request.firstName(), request.lastName());
        String addressDocument = normalizeImage(request.addressDocumentImage());
        String video = normalizeVideo(request.faceVideo());
        jdbc.update("""
                INSERT INTO account_verification (owner_id, provider, upgrade_status, bvn_last_four,
                    address_document_type, address_status, upgrade_document_image, upgrade_face_video,
                    first_name, last_name, residential_address, upgrade_consent_at)
                VALUES (?, 'MANUAL', 'PENDING_REVIEW', ?, ?, 'PENDING_REVIEW', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE provider='MANUAL', upgrade_status='PENDING_REVIEW', bvn_last_four=VALUES(bvn_last_four),
                    address_document_type=VALUES(address_document_type), address_status='PENDING_REVIEW',
                    upgrade_document_image=VALUES(upgrade_document_image), upgrade_face_video=VALUES(upgrade_face_video),
                    first_name=VALUES(first_name), last_name=VALUES(last_name), residential_address=VALUES(residential_address),
                    upgrade_consent_at=CURRENT_TIMESTAMP(6), account_upgraded_at=NULL, review_reason=NULL,
                    reviewed_by=NULL, reviewed_at=NULL
                """, caller.userId(), lastFour(request.bvn()), request.addressDocumentType(), addressDocument, video,
                request.firstName().trim(), request.lastName().trim(), request.residentialAddress().trim());
        return statusFor(caller.userId());
    }

    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> pendingReviews(Caller caller) {
        requireAdmin(caller);
        return jdbc.queryForList("""
                SELECT v.owner_id AS ownerId, v.identity_status AS identityStatus, v.upgrade_status AS upgradeStatus,
                       v.document_type AS documentType, v.address_document_type AS addressDocumentType,
                       v.identity_document_image AS identityDocumentImage, v.identity_face_video AS identityFaceVideo,
                       v.upgrade_document_image AS upgradeDocumentImage, v.upgrade_face_video AS upgradeFaceVideo,
                       v.first_name AS firstName, v.last_name AS lastName, v.residential_address AS residentialAddress,
                       CONCAT('*******', v.bvn_last_four) AS maskedBvn, v.created_at AS submittedAt
                FROM account_verification v
                WHERE v.identity_status='PENDING_REVIEW' OR v.upgrade_status='PENDING_REVIEW'
                ORDER BY v.updated_at ASC
                """);
    }

    @Transactional
    public VerificationStatusResponse review(Caller caller, String ownerId, String type, boolean approve, String reason) {
        requireAdmin(caller);
        String column = "IDENTITY".equals(type) ? "identity_status" : "UPGRADE".equals(type) ? "upgrade_status" : null;
        if (column == null) throw new AccountOperationException("Review type must be IDENTITY or UPGRADE");
        String current = jdbc.queryForObject("SELECT " + column + " FROM account_verification WHERE owner_id=?", String.class, ownerId);
        if (!"PENDING_REVIEW".equals(current)) throw new AccountOperationException("This request is no longer pending review");
        String status = approve ? "VERIFIED" : "REJECTED";
        if ("UPGRADE".equals(type) && approve) {
            int updated = accounts.upgradeOwnedAccounts(ownerId, verifiedDailyLimit);
            if (updated == 0) throw new AccountNotFoundException("No account found for this customer");
        }
        String approvalTime = approve ? "CURRENT_TIMESTAMP(6)" : "NULL";
        jdbc.update("UPDATE account_verification SET " + column + "=?, review_reason=?, reviewed_by=?, reviewed_at=CURRENT_TIMESTAMP(6)"
                + ("UPGRADE".equals(type) ? ", address_status=?, address_verified_at=" + approvalTime + ", account_upgraded_at=" + approvalTime
                : ", identity_verified_at=" + approvalTime)
                + " WHERE owner_id=?", "UPGRADE".equals(type) ? new Object[]{status, reason, caller.userId(), status, ownerId}
                : new Object[]{status, reason, caller.userId(), ownerId});
        return statusFor(ownerId);
    }

    private static void requireAdmin(Caller caller) {
        if (caller == null || !caller.hasPermission(org.elibanking.common.security.AdminRbac.KYC_REVIEW)) {
            throw new AccountOperationException("KYC review permission is required");
        }
    }

    private static String normalizeVideo(String raw) {
        String value = raw.trim();
        if (value.startsWith("data:video/webm;base64,")) value = value.substring("data:video/webm;base64,".length());
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 1024 || bytes.length > 20 * 1024 * 1024) throw new IllegalArgumentException();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IllegalArgumentException ex) {
            throw new AccountOperationException("Face verification video must be valid WebM under 20 MB");
        }
    }

    private List<Account> requireAccounts(Caller caller) {
        if (caller.userId() == null) {
            throw new AccountOperationException("Identity verification is only available to signed-in customers");
        }
        List<Account> ownedAccounts = accounts.findAllByOwnerIdOrderByCreatedAtDesc(caller.userId());
        if (ownedAccounts.isEmpty()) {
            throw new AccountNotFoundException("No account found for the authenticated user");
        }
        return ownedAccounts;
    }

    private boolean hasVerifiedIdentity(String ownerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM account_verification
                    WHERE owner_id = ? AND identity_status = 'VERIFIED'
                )
                """, Boolean.class, ownerId));
    }

    private boolean isUpgradeComplete(List<Account> ownedAccounts) {
        return ownedAccounts.stream().allMatch(account -> account.getAccountTier() == AccountTier.VERIFIED);
    }

    private VerificationStatusResponse statusFor(String ownerId) {
        return status(new Caller(ownerId, null, "USER"));
    }

    private static String normalizeImage(String rawImage) {
        String image = rawImage.trim();
        if (image.startsWith("data:")) {
            if (image.startsWith("data:image/jpeg;base64,")) {
                image = image.substring("data:image/jpeg;base64,".length());
            } else if (image.startsWith("data:image/png;base64,")) {
                image = image.substring("data:image/png;base64,".length());
            } else {
                throw new AccountOperationException("Images must be JPEG or PNG");
            }
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(image);
        } catch (IllegalArgumentException e) {
            throw new AccountOperationException("Image must be valid base64");
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new AccountOperationException("Images must be smaller than 5 MB");
        }
        boolean jpeg = bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
        boolean png = bytes.length >= 8 && ByteBuffer.wrap(bytes).getLong() == 0x89504e470d0a1a0aL;
        if (!jpeg && !png) {
            throw new AccountOperationException("Images must be JPEG or PNG");
        }
        return (jpeg ? "data:image/jpeg;base64," : "data:image/png;base64,") + Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean documentTypeMatches(String requested, String detected) {
        String type = normalizeName(detected);
        return switch (requested) {
            case "PASSPORT" -> type.contains("PASSPORT");
            case "DRIVING_LICENSE" -> type.contains("DRIVER") || type.contains("LICENSE");
            case "NIN_SLIP" -> type.contains("NIN") || type.contains("NATIONAL");
            case "VOTER_CARD" -> type.contains("VOTER");
            default -> false;
        };
    }

    private static boolean addressDocumentMatches(String firstName, String lastName,
                                                  String address, List<String> documentText) {
        String text = normalizeName(String.join(" ", documentText));
        String normalizedAddress = normalizeName(address);
        String[] addressWords = normalizedAddress.split(" ");
        List<String> documentWords = Arrays.asList(text.split(" "));
        boolean addressPresent = addressWords.length > 0 && Arrays.stream(addressWords)
                .filter(word -> word.length() > 1)
                .allMatch(documentWords::contains);
        boolean namePresent = Arrays.stream(normalizeName(firstName).split(" "))
                .allMatch(documentWords::contains)
                && Arrays.stream(normalizeName(lastName).split(" ")).allMatch(documentWords::contains);
        return addressPresent && namePresent;
    }

    private static void ensureNamesMatchAccount(String accountName, String firstName, String lastName) {
        String[] expected = normalizeName(accountName).split(" ");
        String first = normalizeName(firstName);
        String last = normalizeName(lastName);
        if (expected.length == 0 || !expected[0].equals(first) || !expected[expected.length - 1].equals(last)) {
            throw new AccountOperationException("The entered name must match your account name");
        }
    }

    private static String normalizeName(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        return WHITESPACE.matcher(NAME_SEPARATORS.matcher(decomposed).replaceAll(" ")).replaceAll(" ").trim();
    }

    private static String lastFour(String bvn) {
        return bvn.substring(bvn.length() - 4);
    }

    private static String mask(String lastFour) {
        return lastFour == null ? null : "*******" + lastFour;
    }

    private record StoredStatus(String identityStatus, String upgradeStatus, String bvnLastFour,
                                String documentType, String addressDocumentType, String addressStatus,
                                BigDecimal identityFaceMatchScore, BigDecimal identityLivenessScore,
                                BigDecimal upgradeFaceMatchScore, BigDecimal upgradeLivenessScore,
                                java.time.LocalDateTime identityVerifiedAt,
                                java.time.LocalDateTime addressVerifiedAt,
                                java.time.LocalDateTime accountUpgradedAt) {
    }
}
