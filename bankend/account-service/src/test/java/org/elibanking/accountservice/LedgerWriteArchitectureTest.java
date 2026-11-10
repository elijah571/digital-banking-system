package org.elibanking.accountservice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Only the classes that own a table may write it through {@link JdbcTemplate}. {@code LedgerPostingService} is the only
 * writer of {@code ledger_account} and {@code journal_*} (wallet-plan 3.1). The others write their own tables:
 * {@code AuditLogWriter} (audit_log), {@code OutboxWriter} (outbox inserts), {@code OutboxRelay} (outbox publish state)
 * {@code IdempotencyService} (idempotency_record), {@code ReceiptReview} (receipt_review), and verification state and
 * attempt writers. A new writer must be added here on purpose. The SQL inside migrations is reviewed by hand.
 */
@AnalyzeClasses(packages = "org.elibanking.accountservice", importOptions = ImportOption.DoNotIncludeTests.class)
class LedgerWriteArchitectureTest {

    @ArchTest
    static final ArchRule onlyTheOwnersOfATableWriteItThroughJdbc = noClasses()
            .that().doNotHaveFullyQualifiedName("org.elibanking.accountservice.ledger.LedgerPostingService")
            .and().doNotHaveFullyQualifiedName("org.elibanking.accountservice.audit.AuditLogWriter")
            .and().doNotHaveFullyQualifiedName("org.elibanking.accountservice.outbox.OutboxWriter")
            .and().doNotHaveFullyQualifiedName("org.elibanking.accountservice.outbox.OutboxRelay")
            .and().doNotHaveFullyQualifiedName("org.elibanking.accountservice.idempotency.IdempotencyService")
            .and().doNotHaveFullyQualifiedName("org.elibanking.accountservice.ledger.ReceiptReview")
            .and().doNotHaveFullyQualifiedName("org.elibanking.accountservice.verification.VerificationStateWriter")
            .and().doNotHaveFullyQualifiedName("org.elibanking.accountservice.verification.VerificationAttemptLimiter")
            .should().callMethodWhere(target(nameMatching("update|batchUpdate|execute"))
                    .and(target(owner(assignableTo(JdbcTemplate.class)))))
            .because("only LedgerPostingService writes ledger_account and journal_*; see docs/ledger.md");
}
