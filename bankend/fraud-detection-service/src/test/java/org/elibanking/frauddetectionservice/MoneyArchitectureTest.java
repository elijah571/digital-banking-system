package org.elibanking.frauddetectionservice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.elibanking.common.archunit.MoneyArchRules;

/** Fails the build if floating point creeps into money code (rules: banking-common MoneyArchRules). */
@AnalyzeClasses(packages = "org.elibanking.frauddetectionservice", importOptions = ImportOption.DoNotIncludeTests.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPointInMoneyCode = MoneyArchRules.NO_FLOATING_POINT_IN_MONEY_CODE;

    @ArchTest
    static final ArchRule noDoubleBigDecimalConversion = MoneyArchRules.NO_DOUBLE_BIGDECIMAL_CONVERSION;

    @ArchTest
    static final ArchRule noBigDecimalEquals = MoneyArchRules.NO_BIGDECIMAL_EQUALS;
}
