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

package io.evitadb.index.bPlusTree;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The concrete {@link Range} subtype a {@link RangeValueColumn} stores — the class {@code keyAt} has to rebuild, and
 * the class every write and search path narrows its key to before reading a bound.
 *
 * ## Why the six subtypes are enumerated rather than tested with {@code isAssignableFrom}
 *
 * `Range` and `NumberRange` are both instantiable-looking supertypes that a caller can legitimately hand to an index:
 * `FilterIndex` is routinely built over the abstract `NumberRange.class` in tests, and only the **six** concrete
 * classes below are supported schema attribute types (see {@code EvitaDataTypes}'s supported-type set). An
 * `isAssignableFrom` test would therefore capture a declared type this column has no subtype to rebuild, and the
 * column would have to fail at the first `keyAt`. Selecting on exact class equality instead lets everything else fall
 * through to the universal boxed column, which handles an abstract declared type exactly as it always did.
 *
 * The **declared** type is what decides, not the runtime class of the first key: `ReevaluateExpressionExecutor`
 * re-indexes raw bucket values and enforces `plainType.isInstance(bucketValue)`, so a column that answered with any
 * other subtype would fail there rather than here.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
enum RangeKind {

	/**
	 * {@link ByteNumberRange} — both bounds fit a {@code long} directly; an open bound is the constructor's own
	 * {@code Long.MIN_VALUE} / {@code Long.MAX_VALUE} sentinel, so nothing beyond the two longs is stored.
	 */
	BYTE_NUMBER(ByteNumberRange.class),

	/**
	 * {@link ShortNumberRange} — as {@link #BYTE_NUMBER}.
	 */
	SHORT_NUMBER(ShortNumberRange.class),

	/**
	 * {@link IntegerNumberRange} — as {@link #BYTE_NUMBER}.
	 */
	INTEGER_NUMBER(IntegerNumberRange.class),

	/**
	 * {@link LongNumberRange} — as {@link #BYTE_NUMBER}, with the one documented ambiguity of the family: an
	 * **explicit** {@code Long.MIN_VALUE} / {@code Long.MAX_VALUE} bound encodes to the very same long an open bound
	 * does, so the two are indistinguishable once stored. That is invisible to the tree and to the range index —
	 * every comparison in the engine runs on {@code getFrom()} / {@code getTo()}, and
	 * {@code cloneWithDifferentBounds} re-encodes either form to the same long.
	 *
	 * It is **not** invisible to consolidation for the one range that saturates **both** sentinels: read
	 * independently, each would decode to an open bound and the rebuilt range would carry two `null` precise
	 * bounds, which `LongNumberRange`'s constructor refuses and {@code Range.consolidateRange} walks straight into.
	 * That pair is therefore decoded with both bounds materialized — {@code RangeValueColumn#decodeLongRange} is
	 * what upholds it, and re-encoding the result lands on the same two longs.
	 */
	LONG_NUMBER(LongNumberRange.class),

	/**
	 * {@link BigDecimalNumberRange} — the two longs are the bounds' unscaled values at the index's
	 * {@code indexedDecimalPlaces}, which the column carries; the precise {@link java.math.BigDecimal} bounds are
	 * rebuilt from them at that same scale. The fully-open {@code (Long.MIN_VALUE, Long.MAX_VALUE)} pair is the
	 * shared {@link BigDecimalNumberRange#INFINITE} constant and is returned by reference.
	 */
	BIG_DECIMAL_NUMBER(BigDecimalNumberRange.class),

	/**
	 * {@link DateTimeRange} — its two comparison longs are the bounds' whole epoch milliseconds, and an open bound is
	 * {@link DateTimeRange#OPEN_FROM_THRESHOLD} / {@link DateTimeRange#OPEN_TO_THRESHOLD}, the same
	 * {@code Long.MIN_VALUE} / {@code Long.MAX_VALUE} pair the numeric kinds spend. A closed bound saturates one step
	 * short of them, so the sentinels alone say which side is open and the bounds are rebuilt at UTC. This kind used
	 * to need a third backing array for the two bounds' zone offsets; see {@link RangeValueColumn} for what made
	 * that necessary and what removed it.
	 */
	DATE_TIME(DateTimeRange.class);

	/**
	 * The concrete subtype this kind stores — the class {@code keyAt} rebuilds and the component type
	 * {@code asBoxedArray} materializes.
	 */
	@Nonnull private final Class<? extends Range<?>> type;

	RangeKind(@Nonnull Class<? extends Range<?>> type) {
		this.type = type;
	}

	/**
	 * Returns the concrete {@link Range} subtype this kind stores.
	 *
	 * @return the concrete subtype
	 */
	@Nonnull
	Class<? extends Range<?>> type() {
		return this.type;
	}

	/**
	 * Resolves the kind for a declared attribute type, by **exact class equality**.
	 *
	 * @param plainType the plain (non-array) declared attribute type
	 * @return the matching kind, or {@code null} when the type is not one of the six concrete range subtypes
	 */
	@Nullable
	static RangeKind forType(@Nonnull Class<?> plainType) {
		for (final RangeKind kind : values()) {
			if (kind.type == plainType) {
				return kind;
			}
		}
		return null;
	}
}
