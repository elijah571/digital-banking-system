package org.elibanking.common.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.elibanking.common.money.Money;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit rules that keep floating point out of money code. Use them from a service test:
 * <pre>
 * &#64;AnalyzeClasses(packages = "org.elibanking.accountservice", importOptions = ImportOption.DoNotIncludeTests.class)
 * class MoneyArchitectureTest {
 *     &#64;ArchTest static final ArchRule noFloatingPoint = MoneyArchRules.NO_FLOATING_POINT_IN_MONEY_CODE;
 *     ...
 * }
 * </pre>
 *
 * <p><b>Money code</b> is any class in a {@code ..money..}, {@code ..ledger..} or {@code ..wallet..} package, and any
 * class that has a field, parameter or return value of type {@link BigDecimal} or {@link Money}.
 */
public final class MoneyArchRules {

    private static final Set<String> FLOATING_POINT = Set.of(
            "double", "float", Double.class.getName(), Float.class.getName());
    private static final Set<String> MONEY_TYPES = Set.of(BigDecimal.class.getName(), Money.class.getName());

    public static final DescribedPredicate<JavaClass> MONEY_CODE =
            DescribedPredicate.describe("are money code", MoneyArchRules::isMoneyCode);

    /** No double / float / Double / Float field, parameter, return type, or call to Double/Float in money code. */
    public static final ArchRule NO_FLOATING_POINT_IN_MONEY_CODE = classes()
            .that(MONEY_CODE)
            .should(notUseFloatingPoint())
            .allowEmptyShould(true)
            .because("money must be BigDecimal / Money, never binary floating point");

    /** {@code new BigDecimal(double)}, {@code BigDecimal.valueOf(double)}, {@code doubleValue()}, {@code floatValue()}. */
    public static final ArchRule NO_DOUBLE_BIGDECIMAL_CONVERSION = noClasses()
            .should().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .orShould().callMethod(BigDecimal.class, "doubleValue")
            .orShould().callMethod(BigDecimal.class, "floatValue")
            .because("converting between BigDecimal and double loses precision");

    /**
     * {@code BigDecimal.equals} is scale-sensitive ({@code 1.0 != 1.00}); use {@code compareTo}. Calls from inside an
     * {@code equals} method (e.g. Lombok-generated structural equality) are allowed.
     */
    public static final ArchRule NO_BIGDECIMAL_EQUALS = noClasses()
            .should(callBigDecimalEqualsOutsideEquals())
            .because("BigDecimal.equals compares scale too; use compareTo");

    private MoneyArchRules() {
    }

    private static boolean isMoneyCode(JavaClass c) {
        String pkg = c.getPackageName();
        if (pkg.contains(".money") || pkg.contains(".ledger") || pkg.contains(".wallet")) {
            return true;
        }
        return c.getFields().stream().anyMatch(f -> MONEY_TYPES.contains(f.getRawType().getName()))
                || c.getCodeUnits().stream().anyMatch(MoneyArchRules::hasMoneySignature);
    }

    private static boolean hasMoneySignature(JavaCodeUnit unit) {
        return Stream.concat(unit.getRawParameterTypes().stream(), Stream.of(unit.getRawReturnType()))
                .anyMatch(t -> MONEY_TYPES.contains(t.getName()));
    }

    private static ArchCondition<JavaClass> notUseFloatingPoint() {
        return new ArchCondition<>("not use double/float/Double/Float") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                for (JavaField f : c.getFields()) {
                    if (FLOATING_POINT.contains(f.getRawType().getName())) {
                        events.add(SimpleConditionEvent.violated(f, f.getFullName() + " is " + f.getRawType().getName()));
                    }
                }
                for (JavaCodeUnit unit : c.getCodeUnits()) {
                    if (FLOATING_POINT.contains(unit.getRawReturnType().getName())) {
                        events.add(SimpleConditionEvent.violated(unit, unit.getFullName() + " returns floating point"));
                    }
                    unit.getRawParameterTypes().stream()
                            .filter(t -> FLOATING_POINT.contains(t.getName()))
                            .findAny()
                            .ifPresent(t -> events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " takes a " + t.getName() + " parameter")));
                    unit.getMethodCallsFromSelf().stream()
                            .filter(call -> FLOATING_POINT.contains(call.getTargetOwner().getName()))
                            .forEach(call -> events.add(SimpleConditionEvent.violated(call, call.getDescription())));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> callBigDecimalEqualsOutsideEquals() {
        return new ArchCondition<>("call BigDecimal.equals outside an equals method") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
                    boolean bigDecimalEquals = call.getTargetOwner().isEquivalentTo(BigDecimal.class)
                            && call.getName().equals("equals");
                    if (bigDecimalEquals && !call.getOrigin().getName().equals("equals")) {
                        events.add(SimpleConditionEvent.satisfied(call, call.getDescription()));
                    }
                }
            }
        };
    }
}
