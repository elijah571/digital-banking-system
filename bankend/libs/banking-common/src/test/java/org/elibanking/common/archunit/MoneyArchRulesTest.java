package org.elibanking.common.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.elibanking.common.archunit.fixtures.BadMoney;
import org.elibanking.common.archunit.fixtures.GoodMoney;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules must catch real violations (otherwise a green service ArchUnit test proves nothing). */
class MoneyArchRulesTest {

    private final JavaClasses bad = new ClassFileImporter().importClasses(BadMoney.class);
    private final JavaClasses good = new ClassFileImporter().importClasses(GoodMoney.class);

    @Test
    void floatingPointIsCaught() {
        EvaluationResult result = MoneyArchRules.NO_FLOATING_POINT_IN_MONEY_CODE.evaluate(bad);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains("rate", "asDouble", "fromDouble");
    }

    @Test
    void doubleConversionsAreCaught() {
        EvaluationResult result = MoneyArchRules.NO_DOUBLE_BIGDECIMAL_CONVERSION.evaluate(bad);
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains("doubleValue", "valueOf");
    }

    @Test
    void bigDecimalEqualsIsCaughtOutsideEquals() {
        EvaluationResult result = MoneyArchRules.NO_BIGDECIMAL_EQUALS.evaluate(bad);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("same");
    }

    @Test
    void cleanCodePasses() {
        assertThat(MoneyArchRules.NO_FLOATING_POINT_IN_MONEY_CODE.evaluate(good).hasViolation()).isFalse();
        assertThat(MoneyArchRules.NO_DOUBLE_BIGDECIMAL_CONVERSION.evaluate(good).hasViolation()).isFalse();
        assertThat(MoneyArchRules.NO_BIGDECIMAL_EQUALS.evaluate(good).hasViolation()).isFalse();
    }
}
