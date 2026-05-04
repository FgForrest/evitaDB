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
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces the project-wide test-tagging policy: every executed test method must
 * carry at least one tag from {@link TestTags#LAYER_TAGS} and at least one tag
 * from {@link TestTags#CAPABILITY_TAGS}.
 *
 * The listener walks the resolved {@link TestPlan} once at startup and inspects
 * the effective tag set of each test identifier (method-level tags merged with
 * tags inherited from enclosing classes / nested classes).
 *
 * Operating modes are selected via the {@value #POLICY_PROPERTY} system
 * property:
 *
 * - `strict` (default) — fail the entire run before the first test executes;
 *   the exception message lists every offending test.
 * - `warn` — log every violation as a warning, never fail the run. Useful
 *   when ad-hoc evolving the tag taxonomy.
 * - `off` — disable the listener entirely. Useful for ad-hoc local iteration
 *   on a single test class that is not yet tagged.
 *
 * This listener is registered via the standard JUnit Platform service-loader
 * mechanism in
 * `META-INF/services/org.junit.platform.launcher.TestExecutionListener`.
 */
public class TestTagPolicyListener implements TestExecutionListener {

	/**
	 * Name of the system property used to select the operating mode.
	 */
	public static final String POLICY_PROPERTY = "test.tag.policy";

	private static final Logger log = LoggerFactory.getLogger(TestTagPolicyListener.class);

	/**
	 * Maximum number of violations included in a strict-mode failure message
	 * before the rest are summarised. Prevents massive stack traces while still
	 * giving a clear sample of the problem.
	 */
	private static final int STRICT_MESSAGE_VIOLATION_LIMIT = 50;

	@Override
	public void testPlanExecutionStarted(@Nonnull TestPlan testPlan) {
		final Mode mode = Mode.resolve();
		if (mode == Mode.OFF) {
			return;
		}

		final List<Violation> violations = new ArrayList<>(32);
		for (final TestIdentifier root : testPlan.getRoots()) {
			collectViolations(testPlan, root, violations);
		}
		if (violations.isEmpty()) {
			return;
		}

		switch (mode) {
			case WARN -> reportWarnings(violations);
			case STRICT -> throw new IllegalStateException(buildStrictMessage(violations));
			default -> throw new IllegalStateException("Unhandled tag policy mode: " + mode);
		}
	}

	/**
	 * Recursively walks the test plan and records a {@link Violation} for any
	 * test method whose effective tag set fails the policy check.
	 */
	private static void collectViolations(
		@Nonnull TestPlan plan,
		@Nonnull TestIdentifier identifier,
		@Nonnull List<Violation> sink
	) {
		if (identifier.isTest()) {
			final Set<String> effectiveTags = effectiveTags(plan, identifier);
			final boolean hasLayer = anyMatch(effectiveTags, TestTags.LAYER_TAGS);
			final boolean hasCapability = anyMatch(effectiveTags, TestTags.CAPABILITY_TAGS);
			if (!hasLayer || !hasCapability) {
				sink.add(new Violation(
					identifier.getUniqueId(),
					identifier.getDisplayName(),
					effectiveTags,
					hasLayer,
					hasCapability
				));
			}
		}
		for (final TestIdentifier child : plan.getChildren(identifier)) {
			collectViolations(plan, child, sink);
		}
	}

	/**
	 * Collects all tags that apply to {@code identifier}, including those
	 * inherited from enclosing containers (test class, nested class, engine).
	 */
	@Nonnull
	private static Set<String> effectiveTags(@Nonnull TestPlan plan, @Nonnull TestIdentifier identifier) {
		final Set<String> tags = new LinkedHashSet<>();
		Optional<TestIdentifier> current = Optional.of(identifier);
		while (current.isPresent()) {
			final TestIdentifier id = current.get();
			for (final TestTag tag : id.getTags()) {
				tags.add(tag.getName());
			}
			current = plan.getParent(id);
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
				"(set -D{}=strict to fail the build, -D{}=off to silence this listener)",
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
	 * Operating mode of the listener, selected via {@link #POLICY_PROPERTY}.
	 */
	enum Mode {
		WARN, STRICT, OFF;

		@Nonnull
		static Mode resolve() {
			final String raw = System.getProperty(POLICY_PROPERTY);
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
	 * Internal record describing a single test method that fails the policy
	 * check, kept package-private for unit testing.
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
