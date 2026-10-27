/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.test.extension;

import io.evitadb.test.extension.TestTagPolicyFilter.Mode;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

/**
 * Verifies that {@link TestTagPolicyFilter} really aborts a run when a discovered test method is
 * missing its layer / capability tags.
 *
 * The subject of these tests is the *propagation channel*, not the tag arithmetic. The predecessor
 * of this filter was a `TestExecutionListener` whose strict-mode exception the JUnit Platform
 * swallowed by design, so the gate silently passed everything for its entire lifetime while every
 * unit test of its tag logic stayed green. Every test here therefore drives a real {@link Launcher}
 * and asserts on what escapes it.
 *
 * The fixtures must stay invisible to every other run, or the ambient gate rejects them. Being static
 * nested classes rather than `@Nested` keeps them out of Surefire, which skips nested classes by name,
 * but not out of a package or module run in an IDE: Jupiter's classpath scan treats any non-private
 * static nested class with test methods as a test class. The fixtures are therefore `abstract`, which
 * the scan skips, and {@link #requestFor(Class)} selects a concrete subclass generated at runtime —
 * a class that exists in no classpath root, so no scan can find it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(TEST_HARNESS)
@DisplayName("Test tag policy gate")
class TestTagPolicyFilterTest {

	/**
	 * Concrete subclass of each abstract fixture, generated on first use. A subclass inherits the
	 * fixture's test methods, and its class-level tags too, because `@Tag` is `@Inherited`. It is
	 * defined in this class's package and class loader so that it can reach the fixture's
	 * package-private constructor.
	 */
	private static final ClassValue<Class<?>> CONCRETE_FIXTURES = new ClassValue<>() {
		@Override
		protected Class<?> computeValue(@Nonnull Class<?> fixture) {
			try (DynamicType.Unloaded<?> subclass = new ByteBuddy().subclass(fixture).make()) {
				return subclass
					.load(fixture.getClassLoader(), ClassLoadingStrategy.UsingLookup.of(MethodHandles.lookup()))
					.getLoaded();
			}
		}
	};

	/**
	 * Builds a launcher with *only* the tag-policy filter registered, in the requested mode.
	 * Auto-registration is switched off so that the ambient service-loaded gate — the one guarding
	 * the real suite — cannot double-report or interfere with the fixtures selected here.
	 */
	@Nonnull
	private static Launcher launcherWithPolicyGate(@Nullable Mode mode) {
		final LauncherConfig config = LauncherConfig.builder()
			.enableTestEngineAutoRegistration(true)
			.enablePostDiscoveryFilterAutoRegistration(false)
			.enableTestExecutionListenerAutoRegistration(false)
			.enableLauncherSessionListenerAutoRegistration(false)
			.enableLauncherDiscoveryListenerAutoRegistration(false)
			.addPostDiscoveryFilters(new TestTagPolicyFilter(mode))
			.build();
		return LauncherFactory.create(config);
	}

	/**
	 * Builds a request selecting the runtime-generated concrete subclass of the abstract `fixture`.
	 */
	@Nonnull
	private static LauncherDiscoveryRequest requestFor(@Nonnull Class<?> fixture) {
		return LauncherDiscoveryRequestBuilder.request()
			.selectors(selectClass(CONCRETE_FIXTURES.get(fixture)))
			.build();
	}

	@Nested
	@Tag(TEST_HARNESS)
	@DisplayName("Fixture isolation")
	class FixtureIsolation {

		@Test
		@DisplayName("keeps the fixtures out of a package scan, which is how an IDE runs a package or module")
		void shouldHideFixturesFromPackageScan() {
			final Launcher launcher = launcherWithPolicyGate(Mode.STRICT);
			final LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
				.selectors(selectPackage(TestTagPolicyFilterTest.class.getPackageName()))
				.build();

			final TestPlan plan = assertDoesNotThrow(() -> launcher.discover(request));
			assertTrue(plan.containsTests(), "the scan must still find the tests of this class");
		}
	}

	@Nested
	@Tag(TEST_HARNESS)
	@DisplayName("Strict mode")
	class StrictMode {

		@Test
		@DisplayName("aborts discovery when a test carries no tags at all")
		void shouldFailDiscoveryForCompletelyUntaggedTest() {
			final Launcher launcher = launcherWithPolicyGate(Mode.STRICT);
			final LauncherDiscoveryRequest request = requestFor(UntaggedFixture.class);

			final TestTagPolicyViolationException exception = assertThrows(
				TestTagPolicyViolationException.class,
				() -> launcher.discover(request)
			);
			assertTrue(exception.getMessage().contains("layer"), exception.getMessage());
			assertTrue(exception.getMessage().contains("capability"), exception.getMessage());
			assertTrue(exception.getMessage().contains("shouldDoNothing"), exception.getMessage());
		}

		@Test
		@DisplayName("aborts when only the capability axis is missing")
		void shouldFailDiscoveryForLayerOnlyTest() {
			final Launcher launcher = launcherWithPolicyGate(Mode.STRICT);

			final TestTagPolicyViolationException exception = assertThrows(
				TestTagPolicyViolationException.class,
				() -> launcher.discover(requestFor(LayerOnlyFixture.class))
			);
			assertTrue(exception.getMessage().contains("capability"), exception.getMessage());
		}

		@Test
		@DisplayName("reports every violation, not just the first one")
		void shouldReportAllViolationsInSingleMessage() {
			final Launcher launcher = launcherWithPolicyGate(Mode.STRICT);

			final TestTagPolicyViolationException exception = assertThrows(
				TestTagPolicyViolationException.class,
				() -> launcher.discover(requestFor(UntaggedFixture.class))
			);
			assertTrue(exception.getMessage().contains("shouldDoNothing"), exception.getMessage());
			assertTrue(exception.getMessage().contains("shouldDoNothingEither"), exception.getMessage());
			assertTrue(exception.getMessage().contains("2 test(s)"), exception.getMessage());
		}

		@Test
		@DisplayName("catches a @ParameterizedTest, which discovery reports as a container")
		void shouldFailDiscoveryForUntaggedParameterizedTest() {
			final Launcher launcher = launcherWithPolicyGate(Mode.STRICT);

			final TestTagPolicyViolationException exception = assertThrows(
				TestTagPolicyViolationException.class,
				() -> launcher.discover(requestFor(UntaggedParameterizedFixture.class))
			);
			assertTrue(exception.getMessage().contains("shouldAcceptValue"), exception.getMessage());
		}

		@Test
		@DisplayName("lets a fully tagged test through untouched")
		void shouldAcceptFullyTaggedTest() {
			final Launcher launcher = launcherWithPolicyGate(Mode.STRICT);

			final TestPlan plan = assertDoesNotThrow(() -> launcher.discover(requestFor(TaggedFixture.class)));
			assertNotNull(plan);
			assertTrue(plan.containsTests(), "the gate must not remove the tests it approves");
		}

		@Test
		@DisplayName("combines a class-level layer tag with a method-level capability tag")
		void shouldCombineClassAndMethodLevelTags() {
			final Launcher launcher = launcherWithPolicyGate(Mode.STRICT);

			assertDoesNotThrow(() -> launcher.discover(requestFor(MethodTaggedFixture.class)));
		}
	}

	@Nested
	@Tag(TEST_HARNESS)
	@DisplayName("Downgraded modes")
	class DowngradedModes {

		@Test
		@DisplayName("warn mode logs but keeps the untagged tests in the plan")
		void shouldNotFailDiscoveryInWarnMode() {
			final Launcher launcher = launcherWithPolicyGate(Mode.WARN);

			final TestPlan plan = assertDoesNotThrow(() -> launcher.discover(requestFor(UntaggedFixture.class)));
			assertTrue(plan.containsTests(), "a downgraded gate must still let the tests run");
		}

		@Test
		@DisplayName("off mode does not even look at the tags")
		void shouldNotFailDiscoveryWhenSwitchedOff() {
			final Launcher launcher = launcherWithPolicyGate(Mode.OFF);

			final TestPlan plan = assertDoesNotThrow(() -> launcher.discover(requestFor(UntaggedFixture.class)));
			assertTrue(plan.containsTests(), "a disabled gate must still let the tests run");
		}
	}

	@Nested
	@Tag(TEST_HARNESS)
	@DisplayName("Mode resolution from -Dtest.tag.policy")
	class ModeResolution {

		@ParameterizedTest(name = "\"{0}\" is not a mode")
		@ValueSource(strings = {"nonsense", "STRICT!", "yes"})
		@DisplayName("an unrecognised value is rejected outright")
		void shouldRejectUnknownValue(@Nonnull String raw) {
			final IllegalArgumentException exception = assertThrows(
				IllegalArgumentException.class,
				() -> Mode.resolve(raw)
			);
			assertTrue(exception.getMessage().contains(raw), exception.getMessage());
		}

		@ParameterizedTest(name = "\"{0}\" resolves to OFF")
		@ValueSource(strings = {"off", "false", "disabled", "OFF", " off "})
		void shouldResolveOffAliases(@Nonnull String raw) {
			assertEquals(Mode.OFF, Mode.resolve(raw));
		}

		@Test
		@DisplayName("an unset property means strict — the gate is on by default")
		void shouldDefaultToStrict() {
			assertEquals(Mode.STRICT, Mode.resolve(null));
			assertEquals(Mode.STRICT, Mode.resolve("  "));
			assertEquals(Mode.STRICT, Mode.resolve("strict"));
		}

		@Test
		@DisplayName("warn is recognised")
		void shouldResolveWarn() {
			assertEquals(Mode.WARN, Mode.resolve("warn"));
		}
	}

	/* ------------------------------------------------------------------ */
	/* Fixtures - abstract so that no scan discovers them, see class doc   */
	/* ------------------------------------------------------------------ */

	abstract static class UntaggedFixture {

		@Test
		void shouldDoNothing() {
			// intentionally empty - only its (missing) tags matter
		}

		@Test
		void shouldDoNothingEither() {
			// intentionally empty - only its (missing) tags matter
		}
	}

	@Tag(ENGINE)
	abstract static class LayerOnlyFixture {

		@Test
		void shouldDoNothing() {
			// intentionally empty - only its (missing) tags matter
		}
	}

	abstract static class UntaggedParameterizedFixture {

		@ParameterizedTest
		@ValueSource(ints = {1, 2})
		void shouldAcceptValue(int value) {
			// intentionally empty - only its (missing) tags matter
		}
	}

	@Tag(ENGINE)
	@Tag(QUERY)
	abstract static class TaggedFixture {

		@Test
		void shouldDoNothing() {
			// intentionally empty - only its tags matter
		}
	}

	@Tag(ENGINE)
	abstract static class MethodTaggedFixture {

		@Test
		@Tag(QUERY)
		void shouldDoNothing() {
			// intentionally empty - only its tags matter
		}
	}

}
