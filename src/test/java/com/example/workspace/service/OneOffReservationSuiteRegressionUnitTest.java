package com.example.workspace.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-layer obligation SCN-019#A1 (job WBATCH-009): the existing one-time reservation test suites
 * still pass after the series feature is added.
 *
 * <p>Boundary: this test does not re-state one-off behaviour, because the pre-existing unit suites
 * are already executed by the same Surefire run on the post-feature build. The claim under test is
 * that those suites still exist, are still enabled and still pass, i.e. that the series feature was
 * not delivered by deleting, disabling or breaking the one-off unit regressions. Every
 * {@code @Test} method of the two pre-existing one-off unit suites is therefore executed here,
 * independently of whether Surefire happened to select them for this run.</p>
 *
 * <p>The expected statuses of one-off bookings, the seriesId of a persisted one-off row and the
 * series-related API obligations are covered by other layers/assertions and are not claimed here.</p>
 */
class OneOffReservationSuiteRegressionUnitTest {

    /** The pre-existing one-off unit suites of this repository's application module. */
    private static final List<String> EXISTING_ONE_OFF_UNIT_SUITES = List.of(
            "com.example.workspace.service.ReservationServiceTest",
            "com.example.workspace.service.ResourceServiceTest");

    /**
     * SCN-019#A1 - every pre-existing one-off unit test still exists, is enabled and passes.
     *
     * <p>Fails if a one-off unit test was removed, annotated with the JUnit Disabled annotation, or now throws,
     * so the regression cannot be satisfied by shrinking the existing suite.</p>
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-019")
    @Tag("test_type:unit")
    @Tag("fault_model:feature_regression")
    void existingOneOffUnitSuitesStillPass() {
        List<String> failures = new ArrayList<>();
        int executed = 0;
        for (String suiteClassName : EXISTING_ONE_OFF_UNIT_SUITES) {
            executed += runExistingSuite(suiteClassName, failures);
        }

        assertTrue(executed > 0,
                "the pre-existing one-off unit suites must still exist and be executable on the post-feature "
                        + "build, found " + executed + " executable tests in " + EXISTING_ONE_OFF_UNIT_SUITES);
        assertTrue(failures.isEmpty(),
                () -> "the pre-existing one-off unit suites must still pass on the post-feature build; "
                        + "failures: " + failures);
    }

    private int runExistingSuite(String suiteClassName, List<String> failures) {
        Class<?> suite;
        try {
            suite = Class.forName(suiteClassName);
        } catch (ClassNotFoundException notFound) {
            failures.add(suiteClassName + ": existing one-off unit suite is missing (" + notFound.getMessage() + ")");
            return 0;
        }

        List<Method> tests = annotatedMethods(suite, Test.class);
        tests.sort((left, right) -> left.getName().compareTo(right.getName()));
        int executed = 0;

        for (Method test : tests) {
            if (test.isAnnotationPresent(Disabled.class)) {
                failures.add(suiteClassName + "#" + test.getName()
                        + ": existing one-off regression test is disabled on the post-feature build");
                continue;
            }

            Object suiteInstance;
            try {
                Constructor<?> constructor = suite.getDeclaredConstructor();
                constructor.setAccessible(true);
                suiteInstance = constructor.newInstance();
            } catch (ReflectiveOperationException | RuntimeException instantiationFailure) {
                failures.add(suiteClassName + ": cannot instantiate the existing one-off unit suite ("
                        + instantiationFailure + ")");
                return executed;
            }

            try {
                invokeAll(suiteInstance, suite, BeforeEach.class);
                test.setAccessible(true);
                test.invoke(suiteInstance);
                executed++;
            } catch (InvocationTargetException failure) {
                failures.add(suiteClassName + "#" + test.getName() + ": " + failure.getTargetException());
            } catch (ReflectiveOperationException | RuntimeException failure) {
                failures.add(suiteClassName + "#" + test.getName() + ": " + failure);
            } finally {
                try {
                    invokeAll(suiteInstance, suite, AfterEach.class);
                } catch (ReflectiveOperationException | RuntimeException cleanupFailure) {
                    failures.add(suiteClassName + "#" + test.getName()
                            + ": lifecycle cleanup failed (" + cleanupFailure + ")");
                }
            }
        }

        return executed;
    }

    private void invokeAll(Object suiteInstance, Class<?> suite, Class<? extends Annotation> annotationType)
            throws ReflectiveOperationException {
        List<Method> lifecycleMethods = annotatedMethods(suite, annotationType);
        lifecycleMethods.sort((left, right) -> left.getName().compareTo(right.getName()));
        for (Method lifecycleMethod : lifecycleMethods) {
            lifecycleMethod.setAccessible(true);
            lifecycleMethod.invoke(suiteInstance);
        }
    }

    private List<Method> annotatedMethods(Class<?> suite, Class<? extends Annotation> annotationType) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = suite; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isSynthetic() && method.isAnnotationPresent(annotationType)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }
}
