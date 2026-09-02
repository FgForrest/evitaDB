/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.performance.substring.state;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;

/**
 * Whether the booted instance runs evitaDB's formula cache, and on what settings.
 *
 * # The one deliberate deviation from production
 *
 * {@link #ENABLED} copies the shipped server defaults (`evita-configuration.yaml`) - an anteroom of 100 000 records, a
 * complexity floor of 10 000, a usage floor of 2 - with a single exception: the cache re-evaluation period is
 * {@link #REEVALUATE_EACH_SECONDS} second instead of the shipped 60.
 *
 * That deviation is not optional. Admission is asynchronous and driven by that timer: an adept is recorded on first
 * computation, promoted only when the timer next fires, and its payload stored only on the request *after* the
 * promotion. At the shipped 60 seconds a JMH trial with a sane warmup would finish before the first promotion, and the
 * benchmark would report "the cache does not help" when what it measured was "the cache had not started yet" - the
 * exact confusion `CacheAdmissionProbe` exists to prevent.
 *
 * Shortening the timer changes **when** a formula is admitted, never **whether** it qualifies: the complexity floor,
 * the usage floor and the space-to-performance ordering are all left at their shipped values, so a formula that this
 * configuration admits is one production would admit too, just later.
 *
 * # Overrides, for the counterfactual
 *
 * When a cell reports that nothing was admitted, the next question is always whether *anything* could have been - a
 * cache that admits nothing at any setting is a different finding from one whose floor this particular formula sits
 * below. The three admission parameters are therefore overridable through the system properties named below, so the
 * counterfactual can be run without editing this file and without touching the defaults the headline numbers are
 * measured at.
 *
 * The properties are read inside the **forked** JVM, which does not inherit the launcher's - pass them through JMH's
 * `-jvmArgsAppend`, exactly as the corpus properties of the spike benchmarks are passed:
 *
 * ```shell
 * -jvmArgsAppend "-Devita.substring.cache.minimalComplexityThreshold=0"
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum SubstringCacheMode {

	/**
	 * The formula cache runs on the shipped defaults, with the re-evaluation period shortened so admission is
	 * reachable inside a JMH trial.
	 */
	ENABLED,

	/**
	 * No formula cache at all - every measured query is planned and computed in full. This is what the other two
	 * benchmarks in this package run on, so that they measure execution rather than cache hit rate.
	 */
	DISABLED;

	/**
	 * System property overriding {@link #REEVALUATE_EACH_SECONDS}.
	 */
	public static final String REEVALUATE_EACH_SECONDS_PROPERTY = "evita.substring.cache.reevaluateEachSeconds";

	/**
	 * System property overriding {@link #MINIMAL_COMPLEXITY_THRESHOLD}.
	 */
	public static final String MINIMAL_COMPLEXITY_THRESHOLD_PROPERTY =
		"evita.substring.cache.minimalComplexityThreshold";

	/**
	 * System property overriding {@link #MINIMAL_USAGE_THRESHOLD}.
	 */
	public static final String MINIMAL_USAGE_THRESHOLD_PROPERTY = "evita.substring.cache.minimalUsageThreshold";

	/**
	 * Cache re-evaluation period. See the class comment for why this, and only this, departs from the shipped
	 * configuration.
	 */
	public static final int REEVALUATE_EACH_SECONDS = 1;

	/**
	 * Anteroom size, as shipped in `evita-configuration.yaml`.
	 */
	private static final int ANTEROOM_RECORD_COUNT = 100_000;

	/**
	 * Minimal estimated formula cost that makes a formula a cache adept, as shipped. The same number is also the
	 * eden's `minimalSpaceToPerformanceRatio`, so it gates admission twice - once on the formula's cost and once on
	 * its cost-per-byte-per-use.
	 */
	private static final long MINIMAL_COMPLEXITY_THRESHOLD = 10_000L;

	/**
	 * Number of usages below which an adept contributes nothing to its space-to-performance ratio, as shipped.
	 */
	private static final int MINIMAL_USAGE_THRESHOLD = 2;

	/**
	 * @return the cache configuration this mode boots the instance with
	 */
	@Nonnull
	public CacheOptions toCacheOptions() {
		if (this == DISABLED) {
			return CacheOptions.builder().enabled(false).build();
		}
		return CacheOptions.builder()
			.enabled(true)
			.reevaluateEachSeconds(
				(int) longProperty(REEVALUATE_EACH_SECONDS_PROPERTY, REEVALUATE_EACH_SECONDS)
			)
			.anteroomRecordCount(ANTEROOM_RECORD_COUNT)
			.minimalComplexityThreshold(
				longProperty(MINIMAL_COMPLEXITY_THRESHOLD_PROPERTY, MINIMAL_COMPLEXITY_THRESHOLD)
			)
			.minimalUsageThreshold(
				(int) longProperty(MINIMAL_USAGE_THRESHOLD_PROPERTY, MINIMAL_USAGE_THRESHOLD)
			)
			.build();
	}

	/**
	 * @return a one-line rendering of the settings this mode will actually boot with, overrides included
	 */
	@Nonnull
	public String describeSettings() {
		if (this == DISABLED) {
			return "DISABLED";
		}
		return "ENABLED(reevaluateEachSeconds="
			+ longProperty(REEVALUATE_EACH_SECONDS_PROPERTY, REEVALUATE_EACH_SECONDS)
			+ ", minimalComplexityThreshold="
			+ longProperty(MINIMAL_COMPLEXITY_THRESHOLD_PROPERTY, MINIMAL_COMPLEXITY_THRESHOLD)
			+ ", minimalUsageThreshold="
			+ longProperty(MINIMAL_USAGE_THRESHOLD_PROPERTY, MINIMAL_USAGE_THRESHOLD)
			+ ")";
	}

	/**
	 * Reads a numeric system property, refusing a value it cannot parse rather than silently falling back to the
	 * default - a typo in an override would otherwise produce a run labelled with settings it did not use.
	 *
	 * @param name         the property
	 * @param defaultValue the value used when the property is absent or blank
	 * @return the effective value
	 */
	private static long longProperty(@Nonnull String name, long defaultValue) {
		final String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			throw new GenericEvitaInternalError(
				"System property `" + name + "` is `" + value + "`, which is not a number - the run would be "
					+ "labelled with cache settings it did not use!",
				"A cache override system property is not a number!",
				e
			);
		}
	}

}
