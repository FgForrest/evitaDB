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

package io.evitadb.test.upgrade;

import io.evitadb.dataType.DateTimeRange;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Values the release fixtures are built from, chosen so that the released engines and the current one treat them
 * differently. The comments state how the 2026.2 release compared them (`DateTimeRange` in epoch seconds, an open bound
 * taking the extreme local date-time at the other bound's offset) and how the current engine does (milliseconds, open
 * bounds as the extreme `long`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ReleaseFixtureValues {

	/**
	 * The moment every fixture value is anchored to: 2026-01-01T00:00:00Z.
	 */
	public static final OffsetDateTime BASE = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * `until(10:00+01:00)` — the same instant as {@link #U2} at another offset. Release-distinct (the open lower bound
	 * takes the offset of the upper one), equal today.
	 */
	public static final DateTimeRange U1 = DateTimeRange.until(
		OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1))
	);
	/**
	 * `until(11:00+02:00)` — the same instant as {@link #U1} at another offset. The release orders it before `U1`.
	 */
	public static final DateTimeRange U2 = DateTimeRange.until(
		OffsetDateTime.of(2026, 1, 1, 11, 0, 0, 0, ZoneOffset.ofHours(2))
	);
	/**
	 * `[00.900, 02.900]` after {@link #BASE}. Release-equal to {@link #C} (same whole seconds), distinct today; the
	 * release orders it before {@link #B}, today it sorts after `B`.
	 */
	public static final DateTimeRange A = DateTimeRange.between(
		BASE.plusNanos(900_000_000L), BASE.plusSeconds(2).plusNanos(900_000_000L)
	);
	/**
	 * `[00.100, 03.100]` after {@link #BASE}.
	 */
	public static final DateTimeRange B = DateTimeRange.between(
		BASE.plusNanos(100_000_000L), BASE.plusSeconds(3).plusNanos(100_000_000L)
	);
	/**
	 * `[00.100, 02.100]` after {@link #BASE}; release-equal to {@link #A}.
	 */
	public static final DateTimeRange C = DateTimeRange.between(
		BASE.plusNanos(100_000_000L), BASE.plusSeconds(2).plusNanos(100_000_000L)
	);
	/**
	 * A range a year before {@link #BASE}, unrelated to every other value.
	 */
	public static final DateTimeRange FAR = DateTimeRange.between(BASE.minusYears(1), BASE.minusYears(1).plusMonths(1));
	/**
	 * A range a year after {@link #BASE}, the value fixture tests move entities to.
	 */
	public static final DateTimeRange LATER = DateTimeRange.between(BASE.plusYears(1), BASE.plusYears(1).plusMonths(1));
	/**
	 * `until(10:00:00.100+01:00)`: release-equal to {@link #UNTIL_900_PLUS_ONE} (same second, same offset) and equal
	 * today to {@link #UNTIL_100_PLUS_TWO} (same instant).
	 */
	public static final DateTimeRange UNTIL_100_PLUS_ONE = DateTimeRange.until(
		OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 100_000_000, ZoneOffset.ofHours(1))
	);
	/**
	 * `until(10:00:00.900+01:00)`: release-equal to {@link #UNTIL_100_PLUS_ONE}, distinct from every value today.
	 */
	public static final DateTimeRange UNTIL_900_PLUS_ONE = DateTimeRange.until(
		OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 900_000_000, ZoneOffset.ofHours(1))
	);
	/**
	 * `until(11:00:00.100+02:00)`: release-distinct from {@link #UNTIL_100_PLUS_ONE} (other offset), equal to it today.
	 */
	public static final DateTimeRange UNTIL_100_PLUS_TWO = DateTimeRange.until(
		OffsetDateTime.of(2026, 1, 1, 11, 0, 0, 100_000_000, ZoneOffset.ofHours(2))
	);
	/**
	 * `"a<ZERO WIDTH SPACE>b"` — collator-equal to `"ab"`, which the release folded into one key and the current engine
	 * tells apart by a tie-break on the raw string (`"ab"` sorts first).
	 */
	public static final String ZWSP = "a​b";

	private ReleaseFixtureValues() {
		// constants only
	}

}
