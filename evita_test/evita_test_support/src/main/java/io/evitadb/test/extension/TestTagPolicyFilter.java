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

import io.evitadb.test.TestTags;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces the project-wide test-tagging policy: every discovered test method must carry at least
 * one tag from {@link TestTags#LAYER_TAGS} and at least one tag from {@link TestTags#CAPABILITY_TAGS}.
 *
 * Operating modes are selected via the {@value #POLICY_PROPERTY} system property:
 *
 * - `strict` (default) — fail the entire run before the first test executes; the exception message
 *   lists every offending test.
 * - `warn` — log every violation as a warning, never fail the run. Useful when ad-hoc evolving the
 *   tag taxonomy.
 * - `off` — disable the check entirely. Useful for ad-hoc local iteration on a single test class
 *   that is not yet tagged.
 *
 * The check is registered via the standard JUnit Platform service-loader mechanism in
 * `META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter` (and, for module-path runs,
 * via the matching `provides` clause in `module-info.java`).
 *
 * ## Why a filter and not a `TestExecutionListener`
 *
 * This gate used to be a `TestExecutionListener` that threw from `testPlanExecutionStarted`. That
 * never failed anything: `CompositeTestExecutionListener#notifyEach` deliberately catches every
 * `Throwable` a listener raises and only logs it, so a misbehaving listener cannot break a run. The
 * build stayed green while the gate silently did nothing.
 *
 * Post-discovery filtering is different — `EngineDiscoveryOrchestrator#applyPostDiscoveryFilters`
 * is invoked outside any `try`/`catch`, so an exception raised here propagates through
 * `Launcher#execute` into the build tool and fails the run. Two further properties follow from the
 * phase this runs in, and both are improvements over the listener:
 *
 * - Filters see the **unfiltered** discovery tree. Tag filters (`-Dgroups`) are themselves
 *   post-discovery filters registered *after* this one, so an untagged test — which is invisible to
 *   any positive tag selection — is still checked.
 * - `TestDescriptor#accept` visits a node before its children, so the very first descriptor this
 *   filter receives per engine is the engine root, with the whole tree already built. That is what
 *   makes it possible to report *every* violation in one message instead of failing on the first.
 *
 * This filter never excludes anything; it only observes and, in strict mode, aborts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TestTagPolicyFilter implements PostDiscoveryFilter {

	/**
	 * Name of the system property used to select the operating mode.
	 */
	public static final String POLICY_PROPERTY = "test.tag.policy";

	private static final Logger log = LoggerFactory.getLogger(TestTagPolicyFilter.class);

	/**
	 * Maximum number of violations included in a strict-mode failure message before the rest are
	 * summarised. Prevents massive stack traces while still giving a clear sample of the problem.
	 */
	private static final int STRICT_MESSAGE_VIOLATION_LIMIT = 50;

	/**
	 * Mode this instance was constructed with, or `null` when the mode is to be resolved from
	 * {@link #POLICY_PROPERTY}. Only tests pass an explicit mode; the service-loaded instance always
	 * reads the system property.
	 */
	@Nullable private final Mode explicitMode;

	/**
	 * Constructor used by the JUnit Platform service loader — the mode comes from
	 * {@value #POLICY_PROPERTY}.
	 */
	public TestTagPolicyFilter() {
		this(null);
	}

	TestTagPolicyFilter(@Nullable Mode explicitMode) {
		this.explicitMode = explicitMode;
	}

	@Nonnull
	@Override
	public FilterResult apply(@Nonnull TestDescriptor descriptor) {
		// the engine root is the first descriptor handed to the filter and the only moment at which
		// the entire discovered tree is known - see the class documentation
		if (descriptor.isRoot()) {
			final Mode mode = this.explicitMode == null
				? Mode.resolve(System.getProperty(POLICY_PROPERTY))
				: this.explicitMode;
			if (mode != Mode.OFF) {
				final List<Violation> violations = new ArrayList<>(32);
				collectViolations(descriptor, violations);
				if (!violations.isEmpty()) {
					switch (mode) {
						case WARN -> reportWarnings(violations);
						case STRICT -> throw new TestTagPolicyViolationException(buildStrictMessage(violations));
						default -> throw new IllegalStateException("Unhandled tag policy mode: " + mode);
					}
				}
			}
		}
		// the policy gate must never change which tests run
		return FilterResult.included(null);
	}

	/**
	 * Recursively walks the discovered tree and records a {@link Violation} for any test method
	 * whose effective tag set fails the policy check.
	 */
	private static void collectViolations(@Nonnull TestDescriptor descriptor, @Nonnull List<Violation> sink) {
		if (isTestMethod(descriptor)) {
			final Set<String> effectiveTags = effectiveTags(descriptor);
			final boolean hasLayer = anyMatch(effectiveTags, TestTags.LAYER_TAGS);
			final boolean hasCapability = anyMatch(effectiveTags, TestTags.CAPABILITY_TAGS);
			if (!hasLayer || !hasCapability) {
				sink.add(new Violation(
					descriptor.getUniqueId().toString(),
					descriptor.getDisplayName(),
					effectiveTags,
					hasLayer,
					hasCapability
				));
			}
		}
		for (final TestDescriptor child : descriptor.getChildren()) {
			collectViolations(child, sink);
		}
	}

	/**
	 * Tells whether the descriptor represents a single test method. Plain `@Test` methods are
	 * reported as tests, but `@ParameterizedTest` / `@TestTemplate` / `@TestFactory` methods are
	 * reported as *containers* at discovery time — their children only materialise during
	 * execution. Both kinds are anchored to a {@link MethodSource}, which is what makes them
	 * distinguishable from class-level containers.
	 */
	private static boolean isTestMethod(@Nonnull TestDescriptor descriptor) {
		return descriptor.getType().isTest() || descriptor.getSource().orElse(null) instanceof MethodSource;
	}

	/**
	 * Collects all tags that apply to {@code descriptor}, including those inherited from enclosing
	 * containers (test class, nested class, engine).
	 */
	@Nonnull
	private static Set<String> effectiveTags(@Nonnull TestDescriptor descriptor) {
		final Set<String> tags = new LinkedHashSet<>();
		Optional<TestDescriptor> current = Optional.of(descriptor);
		while (current.isPresent()) {
			final TestDescriptor node = current.get();
			for (final TestTag tag : node.getTags()) {
				tags.add(tag.getName());
			}
			current = node.getParent();
		}
		return tags;
	}

	private static boolean anyMatch(@Nonnull Set<String> tags, @Nonnull Set<String> dictionary) {
		for (final String tag : tags) {
			if (dictionary.contains(tag)) {
				return true;
			}
		}
		return false;
	}

	private static void reportWarnings(@Nonnull List<Violation> violations) {
		log.warn(
			"Test tag policy: {} test(s) are missing required tags " +
				"(set -D{}=strict to fail the build, -D{}=off to silence this check)",
			violations.size(), POLICY_PROPERTY, POLICY_PROPERTY
		);
		for (final Violation violation : violations) {
			log.warn(" - {}", violation.format());
		}
	}

	@Nonnull
	private static String buildStrictMessage(@Nonnull List<Violation> violations) {
		final int reported = Math.min(violations.size(), STRICT_MESSAGE_VIOLATION_LIMIT);
		final StringBuilder builder = new StringBuilder(256 + reported * 200);
		builder.append("Test tag policy violated: ")
			.append(violations.size())
			.append(" test(s) are missing required tags. ")
			.append("Every test must carry at least one tag from TestTags.LAYER_TAGS ")
			.append("and at least one from TestTags.CAPABILITY_TAGS.\n");
		for (int i = 0; i < reported; i++) {
			builder.append("  - ").append(violations.get(i).format()).append('\n');
		}
		if (reported < violations.size()) {
			builder.append("  ... and ")
				.append(violations.size() - reported)
				.append(" more.\n");
		}
		return builder.toString();
	}

	/**
	 * Operating mode of the gate, selected via {@link #POLICY_PROPERTY}.
	 */
	enum Mode {
		WARN, STRICT, OFF;

		/**
		 * Translates the raw {@value #POLICY_PROPERTY} value into a mode. Takes the raw string
		 * rather than reading the system property itself so that it stays testable under the
		 * suite's parallel execution, where mutating a global property would be a race.
		 */
		@Nonnull
		static Mode resolve(@Nullable String raw) {
			if (raw == null || raw.isBlank()) {
				return STRICT;
			}
			final String normalised = raw.trim().toLowerCase(Locale.ROOT);
			return switch (normalised) {
				case "warn" -> WARN;
				case "strict" -> STRICT;
				case "off", "false", "disabled" -> OFF;
				default -> throw new IllegalArgumentException(
					"Unknown value for -D" + POLICY_PROPERTY + ": '" + raw + "'. " +
						"Expected one of: warn, strict, off."
				);
			};
		}
	}

	/**
	 * Internal record describing a single test method that fails the policy check, kept
	 * package-private for unit testing.
	 */
	record Violation(
		@Nonnull String uniqueId,
		@Nonnull String displayName,
		@Nonnull Set<String> tags,
		boolean hasLayer,
		boolean hasCapability
	) {

		@Nonnull
		String format() {
			final Set<String> missing = new HashSet<>();
			if (!this.hasLayer) {
				missing.add("layer");
			}
			if (!this.hasCapability) {
				missing.add("capability");
			}
			return this.displayName + "  [missing: " + String.join("+", missing) +
				"; current tags: " + (this.tags.isEmpty() ? "<none>" : this.tags) +
				"; uniqueId=" + this.uniqueId + "]";
		}
	}

}
