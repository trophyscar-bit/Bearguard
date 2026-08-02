package dev.frostguard.engine.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture conformance for the centralized tap-input layer inside
 * {@code fg-engine}.
 *
 * <p>Only the designated input package
 * ({@code dev.frostguard.engine.input}) may call the low-level emulator tap
 * primitives. The incremental migration is complete: the former frozen
 * baseline of pre-existing helpers is empty, and this rule now enforces the
 * final zero-bypass architecture across the whole engine module.</p>
 */
class TapInputArchitectureTest {

    /**
     * The migration baseline is complete: every engine helper, injection
     * rule, and scheduler component now routes taps through
     * {@link dev.frostguard.engine.input.TapInteractionService}. This set
     * must stay empty forever; it is kept only so any future regression
     * produces an explicit, reviewable diff here as well as a test failure.
     */
    private static final Set<String> FROZEN_BASELINE = Set.of();

    /** The only package whose production code may touch the tap primitives. */
    private static final String INPUT_PACKAGE = "dev.frostguard.engine.input";

    /** The dispatcher itself delegates to the emulator backend internally. */
    private static final String CONTROLLER_CLASS = "dev.frostguard.engine.emulator.EmulatorController";

    private static JavaClasses engineClasses;

    @BeforeAll
    static void importClasses() {
        engineClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.frostguard.engine");
    }

    /**
     * Matches both direct calls and method references (e.g.
     * {@code controller::touchPoint}), so the layer cannot be bypassed
     * through a lambda-shaped indirection either.
     */
    private static DescribedPredicate<JavaAccess<?>> lowLevelTapOn(String ownerClass) {
        return new DescribedPredicate<>("a low-level tap primitive of " + ownerClass) {
            @Override
            public boolean test(JavaAccess<?> access) {
                if (!access.getTargetOwner().getFullName().equals(ownerClass)) {
                    return false;
                }
                String name = access.getTarget().getName();
                return name.equals("touchPoint") || name.equals("touchArea");
            }
        };
    }

    @Test
    void onlyInputLayerAndFrozenBaselineMayCallLowLevelTapPrimitives() {
        DescribedPredicate<JavaClass> notInBaseline =
                new DescribedPredicate<>("are not part of the frozen migration baseline") {
                    @Override
                    public boolean test(JavaClass input) {
                        return !FROZEN_BASELINE.contains(input.getFullName());
                    }
                };

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(INPUT_PACKAGE)
                .and().doNotHaveFullyQualifiedName(CONTROLLER_CLASS)
                .and(notInBaseline)
                .should().accessTargetWhere(lowLevelTapOn(CONTROLLER_CLASS))
                .because("all tap input must go through the shared input layer "
                        + "(dev.frostguard.engine.input.TapInteractionService); the migration "
                        + "is complete and the baseline must remain empty");

        rule.check(engineClasses);
    }

    @Test
    void nothingOutsideEmulatorPackageMayCallEmulatorInstanceTapsDirectly() {
        DescribedPredicate<JavaAccess<?>> instanceTap =
                new DescribedPredicate<>("a tap primitive of EmulatorInstance") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        if (!access.getTargetOwner().getFullName()
                                .equals("dev.frostguard.engine.emulator.EmulatorInstance")) {
                            return false;
                        }
                        String name = access.getTarget().getName();
                        return name.equals("touchArea") || name.equals("tap");
                    }
                };

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("dev.frostguard.engine.emulator..")
                .should().accessTargetWhere(instanceTap)
                .because("EmulatorInstance tap primitives are internal to the emulator layer");

        rule.check(engineClasses);
    }
}
