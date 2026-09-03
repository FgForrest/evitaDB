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
import io.evitadb.dataType.NumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToLongFunction;

import static io.evitadb.utils.ArrayUtils.EMPTY_LONG_ARRAY;

/**
 * Primitive {@link ValueColumn} for {@link Range} attribute keys, backed by two parallel {@code long[]} arrays — the
 * ranges' comparison bounds {@code getFrom()} and {@code getTo()} — plus, for {@link DateTimeRange} only, a third
 * {@code long[] meta} carrying the two bounds' zone offsets.
 *
 * A range's **entire** ordering and equality surface is that pair of longs: {@code compareTo} is lexicographic on
 * {@code (getFrom(), getTo())} in both hierarchies ({@link DateTimeRange}, {@code NumberRange}) and
 * {@code equals} / {@code hashCode} are generated from those same two fields and nothing else. So
 * {@code compareTo == 0} ⟺ {@code equals} ⟺ equal long pair, with no tie-break anywhere — which is what makes two
 * {@code long[]} columns an exact, order-preserving representation of the tree's key set rather than a lossy digest
 * of it.
 *
 * ## The {@code meta} word, and why an {@code int} could not carry it
 *
 * The five {@code NumberRange} subtypes need nothing beyond the two longs: an open bound is stored as the very
 * {@code Long.MIN_VALUE} / {@code Long.MAX_VALUE} sentinel their own constructors compute, so it round-trips for
 * free. {@link DateTimeRange} is different — **its open-bound sentinel is not a constant**. It is
 * {@code LocalDateTime.MIN.atOffset(otherBound.getOffset()).toEpochSecond()} (and {@code MAX} symmetrically), so it
 * depends on the zone offset of the *other* bound, and a "rebuild everything at UTC" scheme both loses the stored
 * long and throws {@code DateTimeException} at the extremes.
 *
 * The offsets of **closed** bounds are load-bearing as well, which is the reason this column stores both rather than
 * only the surviving one. {@code Range.consolidateRange} merges two overlapping ranges through
 * {@code cloneWithDifferentBounds(previous.getPreciseFrom(), current.getPreciseTo())}, and when an open range meets a
 * closed one the survivor is a one-sided range whose sentinel is recomputed from **the other range's** offset. Store
 * a both-closed range with no offset and rebuild it at UTC, and that consolidation lands on a different threshold
 * than the original objects did — after which {@code FilterIndex.removeRecordDelta} asks the range index to drop a
 * threshold no {@code addRange} ever inserted. Silent range-index corruption, no exception.
 *
 * The layout is therefore one {@code long} per key, packed via {@link io.evitadb.utils.NumberUtils#pack(int, int)}:
 * **high 32 bits the from-bound's {@code ZoneOffset.getTotalSeconds()}, low 32 bits the to-bound's**, with
 * {@link #OPEN_BOUND} ({@code Integer.MIN_VALUE}) in a half meaning that bound is open. Two full {@code int} halves
 * rather than a bit-packed field because the bit budget does not fit anything smaller: {@code ZoneOffset} admits
 * ±64800 s, so two offsets plus the open flags need 36 bits and neither an {@code int[]} nor a {@code short[]} nor a
 * {@code byte[]} can carry them. At most one bound is ever open ({@code DateTimeRange} refuses a both-null
 * construction), which is asserted on decode.
 *
 * ## The binding invariant
 *
 * *{@code Range.consolidateRange} applied to reconstructed ranges yields the same {@code getFrom()} /
 * {@code getTo()} as applied to the originals.* Equality is **not** the requirement — consolidation is, because it is
 * what the write path actually does with the values it reads back out of this tree
 * ({@code FilterIndex.addRecordDelta} / {@code removeRecordDelta} → {@code getValuesForRecord} →
 * {@code consolidateRange} → {@code RangeIndex.addRecord} / {@code removeRecord}).
 *
 * It rests on exactly two facts:
 *
 * - **A closed bound's {@code toEpochSecond()} is offset-independent.** It identifies an instant, so rebuilding the
 *   bound at its own stored offset reproduces the stored long whatever that offset is.
 * - **{@code meta} carries the offset of every bound that can survive into a one-sided
 *   {@code cloneWithDifferentBounds}** — which is every bound, closed ones included, for the reason above.
 *
 * ## Sub-second bounds are truncated, and no comparison can see it
 *
 * {@link DateTimeRange} holds its bounds as {@link OffsetDateTime} but derives both comparison longs with
 * {@code toEpochSecond()}, so a range rebuilt from this column carries **zero nanoseconds** where the original may
 * have carried some. evitaDB does not truncate range bounds on input, so this is a real difference in the precise
 * bounds — and it is invisible to every path that **compares** them, because {@code DateTimeRange}'s {@code equals}
 * and {@code hashCode} are generated from the two comparison longs alone. The array-delta subtraction in
 * {@code FilterIndex.removeRecordDelta} (which compares reconstructed ranges against the caller's originals with
 * {@code equals}), the tree's own comparator-driven lookups, the consolidation above and the {@code RangeIndex}
 * thresholds are therefore all decided by the long pair, never by the bound objects. The one visible consequence is
 * that a persisted index copy of a range value renders with whole seconds; the client-facing value is served from
 * the entity's attributes storage, not from here.
 *
 * **Reconstruction is a separate question from comparison, and it rests on the data type's own bound assertion.**
 * Truncation can collapse a range narrower than one second into a zero-width one, and when its two bounds carry
 * different offsets that zero-width range is expressible only as two differently-offset bounds naming one moment.
 * {@code DateTimeRange.assertFromLesserThanTo} admits it because it compares the **instant** — the same order
 * {@code equals} and {@code compareTo} derive from — so {@link #keyAt} can rebuild every range the write path
 * accepted. An offset-sensitive assertion there would be stricter than the type's own notion of equality and would
 * throw on a value this column had already indexed, on every dirtied leaf inside a transaction and on every reload.
 * Pinned by {@code RangeValueColumnTest.shouldRebuildASubSecondRangeWhoseBoundsCarryDifferentOffsets}.
 *
 * ## Why the bound reads are narrowed to a concrete class first
 *
 * {@code getFrom()} / {@code getTo()} are declared on {@link Range}, which has **six** implementors — enough for a
 * call site reached by more than one attribute type to go megamorphic and stop inlining, which is verbatim the
 * failure mode the cursor-free insert path's escape analysis was broken by once already (a megamorphic
 * {@code ValueColumn.size()} planted inside the leaf's insert). The hierarchy has only **two** declarations of those
 * two methods, though: one on {@link DateTimeRange}, a final class, and one on {@code NumberRange}, which none of
 * its five permitted subclasses overrides. So every read of a bound on a write or search path branches on the
 * column's own kind first and narrows to whichever of the two that branch guarantees — an exact final type on one
 * side, a hierarchy-unique target on the other, and no interface dispatch on either.
 *
 * ## Sizing
 *
 * Every live array follows the live content rather than the leaf block size and is grown, trimmed and cleared **in
 * lockstep**, so they always share one physical length: an empty column parks all of them on the JVM-wide shared
 * empty array and owns nothing, the first insert allocates {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots in each,
 * and growth doubles up to {@link #capacity()}. The {@code meta} array is **materialized only for the
 * {@link RangeKind#DATE_TIME} kind**; for the five numeric kinds it stays on the shared empty constant for the
 * column's whole life and is never grown, cleared, copied or trimmed — which is why {@link #observableLiveRun()}
 * folds its length into the bound only for the kind that has one. See {@link ValueColumn} for the family-wide
 * contract.
 *
 * Per-key cost is exact by construction: **16 B** (two {@code long}s) for the five numeric kinds and **24 B**
 * (three) for {@link DateTimeRange} — field widths, not an estimate. {@code LeafIndexHeapSizeTest} pins the fixed,
 * non-per-key overhead on top of that for a one-key index: 464 B for the plain integral shape this column sits
 * next to, and 568 B for a {@link DateTimeRange} column against a 584 B budget — a 104 B gap that is the wider key
 * column, three four-slot {@code long[]} arrays at 48 B each instead of the integral shape's one. The same test
 * notes that a numeric-kind column pays 48 B less for the {@code meta} array it never materializes, i.e. 520 B for
 * one key. None of these figures price the boxed range graph this column replaces; no test pins that cost, so this
 * doc does not guess at one.
 *
 * ## Selection
 *
 * Reachable **only** through {@link ValueColumnFactory#forFilterKey}, under natural order and for the six concrete
 * range subtypes; see there for why {@link ValueColumnFactory#forKey} must stay structurally unable to select it.
 *
 * @param <M> the boxed key type as seen by the tree's generic API (always one of the six {@link Range} subtypes at
 *            runtime)
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RangeValueColumn<M extends Comparable<M>> implements ValueColumn<M> {

	/**
	 * The value a half of a {@code meta} word carries when that bound is open. {@code Integer.MIN_VALUE} can never
	 * collide with a real offset — {@code ZoneOffset} runs to ±64800 s.
	 */
	private static final int OPEN_BOUND = Integer.MIN_VALUE;

	/**
	 * The concrete {@link Range} subtype this column stores; fixed for its lifetime (one attribute index = one value
	 * type). Decides what {@link #keyAt} rebuilds and whether {@link #meta} is materialized.
	 */
	@Nonnull private final RangeKind kind;
	/**
	 * The decimal-places scale a {@link BigDecimalNumberRange}'s comparison longs are encoded at, taken from the
	 * owning index. Meaningful only for {@link RangeKind#BIG_DECIMAL_NUMBER}, where it is what makes a rebuilt range
	 * re-derive the same longs during consolidation; ignored by every other kind.
	 */
	private final int indexedDecimalPlaces;
	/**
	 * The logical capacity — the leaf block size, fixed for the column's lifetime. See {@link #capacity()}.
	 */
	private final int capacity;
	/**
	 * The number of live keys held in the backing arrays, normally equal to the owning leaf's {@code peek + 1} — see
	 * {@link ValueColumn} for the two transient windows in which it is not.
	 */
	private int size;
	/**
	 * Each key's {@code getFrom()} comparison bound (parallel with {@link #to}, always the same physical length).
	 * Slots in {@code [size, from.length)} are always zero.
	 */
	@Nonnull private long[] from;
	/**
	 * Each key's {@code getTo()} comparison bound (parallel with {@link #from}, always the same physical length).
	 * Slots in {@code [size, to.length)} are always zero.
	 */
	@Nonnull private long[] to;
	/**
	 * Each key's packed zone offsets — high 32 bits the from-bound's, low 32 bits the to-bound's, {@link #OPEN_BOUND}
	 * in a half meaning that bound is open. Materialized (and kept the same physical length as {@link #from}) only
	 * for {@link RangeKind#DATE_TIME}; for every other kind this stays the shared empty array forever.
	 */
	@Nonnull private long[] meta;

	/**
	 * Creates an empty column for a leaf of the given block size. No backing storage is allocated until the first
	 * write — every array field parks on the JVM-wide shared empty array.
	 *
	 * @param kind                 the concrete range subtype this column stores
	 * @param indexedDecimalPlaces the scale {@link BigDecimalNumberRange} bounds are encoded at (0 for other kinds)
	 * @param capacity             the logical capacity (the leaf block size)
	 */
	RangeValueColumn(@Nonnull RangeKind kind, int indexedDecimalPlaces, int capacity) {
		this.kind = kind;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.capacity = capacity;
		this.size = 0;
		this.from = EMPTY_LONG_ARRAY;
		this.to = EMPTY_LONG_ARRAY;
		this.meta = EMPTY_LONG_ARRAY;
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate / trim paths). All live arrays must have the same
	 * length; slots are kept in lockstep by every mutation.
	 *
	 * @param kind                 the concrete range subtype
	 * @param indexedDecimalPlaces the scale {@link BigDecimalNumberRange} bounds are encoded at
	 * @param capacity             the logical capacity
	 * @param size                 the live key count
	 * @param from                 the lower-bound backing array to adopt
	 * @param to                   the upper-bound backing array to adopt (same length as {@code from})
	 * @param meta                 the packed-offsets backing array to adopt (same length as {@code from} for the
	 *                             {@link RangeKind#DATE_TIME} kind, the shared empty array otherwise)
	 */
	private RangeValueColumn(
		@Nonnull RangeKind kind, int indexedDecimalPlaces, int capacity, int size,
		@Nonnull long[] from, @Nonnull long[] to, @Nonnull long[] meta
	) {
		this.kind = kind;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.capacity = capacity;
		this.size = size;
		this.from = from;
		this.to = to;
		this.meta = meta;
	}

	@Override
	public int capacity() {
		return this.capacity;
	}

	@Override
	public int size() {
		return this.size;
	}

	/**
	 * {@inheritDoc}
	 *
	 * Bounded by the SHORTEST of the live parallel arrays. They are grown together, but by two or three separate
	 * reallocations, so a reader with no happens-before edge can catch them mid-grow.
	 *
	 * **The {@code meta} array counts only for the kind that materializes it.** Folding a permanently zero-length
	 * array into the bound unconditionally would report a live run of {@code 0} for every numeric range tree and
	 * silently truncate every cursor walking one.
	 *
	 * A live run still bounds only *which slots exist*, not which of the two or three per-slot stores a reader has
	 * observed, so a session-free reader can assemble a key out of two neighbouring slots while one is being
	 * written in place. This column is the one in the family whose {@code keyAt} **validates** what it read, so
	 * such a hybrid surfaces as a thrown {@link GenericEvitaInternalError} rather than as a stale value — see
	 * {@code decodeDateTimeRange}.
	 */
	@Override
	public int observableLiveRun() {
		final int bound = Math.min(this.size, Math.min(this.from.length, this.to.length));
		return this.kind.isMetaCarrying() ? Math.min(bound, this.meta.length) : bound;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new RangeValueColumn<>(this.kind, this.indexedDecimalPlaces, capacity);
	}

	@Nonnull
	@Override
	public ValueColumn<M> trimmed() {
		final int target = ColumnSizing.trimmedLength(this.size, this.from.length, this.capacity);
		if (target == this.from.length) {
			return this;
		}
		return new RangeValueColumn<>(
			this.kind, this.indexedDecimalPlaces, this.capacity, this.size,
			Arrays.copyOf(this.from, target), Arrays.copyOf(this.to, target),
			this.meta.length == 0 ? this.meta : Arrays.copyOf(this.meta, target)
		);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		// an empty column keeps the shared empty arrays rather than cloning them into private zero-length ones - the
		// clones would cost an object header each and break the shared-array identity every heap walk subtracts.
		// The same guard covers `meta` on a numeric kind, whose array is permanently the shared constant
		return new RangeValueColumn<>(
			this.kind, this.indexedDecimalPlaces, this.capacity, this.size,
			this.from.length == 0 ? this.from : this.from.clone(),
			this.to.length == 0 ? this.to : this.to.clone(),
			this.meta.length == 0 ? this.meta : this.meta.clone()
		);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicateForInsert() {
		// every live array takes the SAME target length: the internal constructor demands they stay equal, and every
		// reader bounds itself by the shortest of them, so growing only some would hide part of the headroom
		final int target = ColumnSizing.headroomLength(this.size, this.from.length, this.capacity);
		return new RangeValueColumn<>(
			this.kind, this.indexedDecimalPlaces, this.capacity, this.size,
			this.from.length == 0 ? this.from : Arrays.copyOf(this.from, target),
			this.to.length == 0 ? this.to : Arrays.copyOf(this.to, target),
			this.meta.length == 0 ? this.meta : Arrays.copyOf(this.meta, target)
		);
	}

	/**
	 * {@inheritDoc}
	 *
	 * ## Why the rebuilt range reproduces the stored longs by construction
	 *
	 * Every kind is rebuilt through a **public** factory, never reflectively and never through a bound-setting back
	 * door, so the reconstructed object passes through the very constructor the original did and recomputes its own
	 * comparison longs. That those recomputed longs equal the stored ones is the whole correctness proof, and it is
	 * one line per kind:
	 *
	 * - **The four integral {@code NumberRange} kinds** store {@code getFrom()} / {@code getTo()} verbatim, and their
	 *   constructors map a {@code null} bound to exactly the {@code Long.MIN_VALUE} / {@code Long.MAX_VALUE} sentinel
	 *   this column reads back as "open". {@code _internalBuild} is handed both the boxed bounds and the two longs,
	 *   so nothing is recomputed at all. {@link LongNumberRange} is the only one of the four whose bounds can reach
	 *   those sentinels explicitly; its saturated pair is decoded with both bounds materialized, for the reason
	 *   {@code decodeLongRange} spells out.
	 * - **{@link BigDecimalNumberRange}** rebuilds each bound as {@code BigDecimal.valueOf(long,
	 *   indexedDecimalPlaces)} — the exact inverse of the
	 *   {@code setScale(…).scaleByPowerOfTen(…).longValueExact()} the index encoded it with — and carries
	 *   {@code indexedDecimalPlaces} into the object as its {@code retainedDecimalPlaces}. That last part is what
	 *   makes {@code cloneWithDifferentBounds} re-derive the same longs during consolidation instead of falling
	 *   back to the bounds' intrinsic scale.
	 * - **{@link DateTimeRange}** rebuilds only its **closed** bound(s), each at its own stored offset. A closed
	 *   bound's {@code toEpochSecond()} identifies an instant and is therefore reproduced exactly; the surviving
	 *   bound's offset then drives the constructor's own recomputation of the **open** side's sentinel
	 *   ({@code LocalDateTime.MIN/MAX.atOffset(thatOffset).toEpochSecond()}), which is by definition the long this
	 *   column stored. An open bound is never handed to a date-time factory — building an {@link OffsetDateTime} out
	 *   of its sentinel long is what would throw {@code DateTimeException}.
	 */
	@Nonnull
	@Override
	public M keyAt(int index) {
		// boxing boundary - decoded exactly where the boxed leaf would have materialized the key
		final long lower = this.from[index];
		final long upper = this.to[index];
		return (M) switch (this.kind) {
			case BYTE_NUMBER -> ByteNumberRange._internalBuild(
				lower == Long.MIN_VALUE ? null : (byte) lower,
				upper == Long.MAX_VALUE ? null : (byte) upper,
				null, lower, upper
			);
			case SHORT_NUMBER -> ShortNumberRange._internalBuild(
				lower == Long.MIN_VALUE ? null : (short) lower,
				upper == Long.MAX_VALUE ? null : (short) upper,
				null, lower, upper
			);
			case INTEGER_NUMBER -> IntegerNumberRange._internalBuild(
				lower == Long.MIN_VALUE ? null : (int) lower,
				upper == Long.MAX_VALUE ? null : (int) upper,
				null, lower, upper
			);
			case LONG_NUMBER -> decodeLongRange(lower, upper);
			case BIG_DECIMAL_NUMBER -> decodeBigDecimalRange(lower, upper);
			case DATE_TIME -> decodeDateTimeRange(lower, upper, this.meta[index]);
		};
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		if (this.size == this.from.length) {
			growAndInsertKeyAt(index, value);
			return;
		}
		shiftAndWriteKeyAt(index, value);
	}

	@Override
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		ColumnSizing.assertLoadFitsCapacity(count, this.capacity);
		// always fresh arrays: the contract says this column is freshly allocated, and reusing the existing backing
		// would make this the one mutator in the family that writes into arrays it did not allocate
		final long[] targetFrom = newLongArray(count);
		final long[] targetTo = newLongArray(count);
		final long[] targetMeta = this.kind.isMetaCarrying() ? newLongArray(count) : EMPTY_LONG_ARRAY;
		// the kind is read, never inferred from the allocation: a zero-length load parks `targetMeta` on the shared
		// empty constant, which would send a date-time column down the numeric arm and its `NumberRange` cast
		if (this.kind.isMetaCarrying()) {
			for (int i = 0; i < count; i++) {
				final DateTimeRange range = (DateTimeRange) keys[i];
				targetFrom[i] = range.getFrom();
				targetTo[i] = range.getTo();
				targetMeta[i] = encodeMeta(range);
			}
		} else {
			for (int i = 0; i < count; i++) {
				final NumberRange<?> range = (NumberRange<?>) keys[i];
				targetFrom[i] = range.getFrom();
				targetTo[i] = range.getTo();
			}
		}
		this.from = targetFrom;
		this.to = targetTo;
		this.meta = targetMeta;
		this.size = count;
	}

	@Override
	public void removeKeyAt(int index) {
		if (index >= this.size) {
			// the run past `size` is already empty - dropping one empty slot out of it leaves it empty
			return;
		}
		// lockstep left-shift of every live array
		System.arraycopy(this.from, index + 1, this.from, index, this.size - index - 1);
		System.arraycopy(this.to, index + 1, this.to, index, this.size - index - 1);
		if (this.kind.isMetaCarrying()) {
			System.arraycopy(this.meta, index + 1, this.meta, index, this.size - index - 1);
		}
		this.size--;
		this.from[this.size] = 0L;
		this.to[this.size] = 0L;
		if (this.kind.isMetaCarrying()) {
			this.meta[this.size] = 0L;
		}
	}

	@Override
	public void clearAt(int index) {
		if (index < this.size) {
			Arrays.fill(this.from, index, this.size, 0L);
			Arrays.fill(this.to, index, this.size, 0L);
			if (this.kind.isMetaCarrying()) {
				Arrays.fill(this.meta, index, this.size, 0L);
			}
			this.size = index;
		}
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		assertSourceRangeIsLive(srcPos, length);
		// lockstep bulk move of every live array (overlap-safe, like System.arraycopy)
		final RangeValueColumn<M> target = asSameKind(dst);
		final int oldSize = target.size;
		final int required = dstPos + length;
		target.ensurePhysicalLength(required);
		if (dstPos > oldSize) {
			// a right shift opens a hole between the destination's old live end and dstPos; it must read as empty
			Arrays.fill(target.from, oldSize, dstPos, 0L);
			Arrays.fill(target.to, oldSize, dstPos, 0L);
			if (this.kind.isMetaCarrying()) {
				Arrays.fill(target.meta, oldSize, dstPos, 0L);
			}
		}
		System.arraycopy(this.from, srcPos, target.from, dstPos, length);
		System.arraycopy(this.to, srcPos, target.to, dstPos, length);
		if (this.kind.isMetaCarrying()) {
			System.arraycopy(this.meta, srcPos, target.meta, dstPos, length);
		}
		target.size = Math.max(oldSize, required);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		if (fromInclusive < this.size) {
			Arrays.fill(this.from, fromInclusive, this.size, 0L);
			Arrays.fill(this.to, fromInclusive, this.size, 0L);
			if (this.kind.isMetaCarrying()) {
				Arrays.fill(this.meta, fromInclusive, this.size, 0L);
			}
			this.size = fromInclusive;
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * **The comparator argument is deliberately ignored, and that is sound rather than convenient.** The factory
	 * selects this column only under {@code ValueColumnFactory.isNaturalOrder}; the comparator a range-typed filter
	 * index gives its tree is the {@code Comparator.naturalOrder()} singleton; and natural order for both range
	 * hierarchies is exactly {@code getFrom()} then {@code getTo()} on the two longs this column stores
	 * ({@code DateTimeRange.compareTo}, {@code NumberRange.compareTo}). Their {@code equals} / {@code hashCode} are
	 * generated from the same two fields, so {@code compareTo == 0} ⟺ {@code equals} ⟺ equal long pair, with no
	 * tie-break anywhere that a boxed comparison could see and this one could not.
	 *
	 * The probe is read once into its two primitive components; {@link #keyAt} is never called here, so the search
	 * allocates nothing.
	 */
	@Nonnull
	@Override
	public InsertionPosition findKeyPosition(@Nonnull M key, int from, int to, @Nullable Comparator<M> comparator) {
		final long probeFrom;
		final long probeTo;
		if (this.kind.isMetaCarrying()) {
			final DateTimeRange probe = (DateTimeRange) key;
			probeFrom = probe.getFrom();
			probeTo = probe.getTo();
		} else {
			final NumberRange<?> probe = (NumberRange<?>) key;
			probeFrom = probe.getFrom();
			probeTo = probe.getTo();
		}
		// binary search over [from, to) comparing (from, to) lexicographically; the return encoding mirrors
		// ArrayUtils.computeInsertPositionOfLongInOrderedArray exactly (empty-range ⇒ position 0, not present)
		if (to <= from) {
			return new InsertionPosition(0, false);
		}
		int low = from;
		int high = to - 1;
		while (low <= high) {
			final int mid = (low + high) >>> 1;
			final int cmp = compareAt(mid, probeFrom, probeTo);
			if (cmp < 0) {
				low = mid + 1;
			} else if (cmp > 0) {
				high = mid - 1;
			} else {
				return new InsertionPosition(mid, true);
			}
		}
		// on a miss `low` is already the positive insertion point — the same positive position() the other columns
		// and the ArrayUtils.compute… helpers expose (they decode their negative result before constructing this)
		return new InsertionPosition(low, false);
	}

	@Override
	public void appendKey(@Nonnull StringBuilder sb, int index) {
		sb.append(keyAt(index));
	}

	@Nonnull
	@Override
	@SuppressWarnings({"unchecked", "SuspiciousArrayCast"})
	public M[] asBoxedArray() {
		// cold path only (consistency verification / toString) — never the query hot path; the live run is the whole
		// array, which satisfies the interface's "length >= size, tail empty" contract exactly. The component type is
		// the DECLARED concrete subtype, which is what `ReevaluateExpressionExecutor` re-indexes against
		final Object[] boxed = (Object[]) Array.newInstance(this.kind.type(), this.size);
		for (int i = 0; i < this.size; i++) {
			boxed[i] = keyAt(i);
		}
		return (M[]) boxed;
	}

	/**
	 * {@inheritDoc}
	 *
	 * **This column flips the tree's {@code separatorKeysAreOwned} verdict from {@code false} to {@code true}**, and
	 * that moves a range tree's self-reported heap in two opposite directions at once. The check is
	 * `!(leaf.keys instanceof BoxedObjectColumn)`, so the moment a range tree stops using the boxed column its
	 * internal-node separators start being charged: {@link #keyAt} mints a fresh object per separator, which can no
	 * longer alias a leaf key the way a boxed column's promoted-by-reference separator did. So a before/after diff of
	 * {@code getHeapSizeInBytes} shows the column's own saving (down roughly a kilobyte per leaf) **and** one newly
	 * priced range object per separator (up), and neither figure alone is the change.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// four references (kind, from, to, meta) and three ints (indexedDecimalPlaces, capacity, size)
		long size = layout.sizeOfObject(4L * layout.referenceSize() + 3L * Integer.BYTES);
		// the `kind` enum constant is shared JVM-wide and belongs to no column; an empty column parks its array
		// fields on the JVM-wide shared empty array, which likewise costs it nothing beyond the slots counted above
		if (this.from != EMPTY_LONG_ARRAY) {
			size += layout.sizeOfArray(this.from.length, Long.BYTES);
		}
		if (this.to != EMPTY_LONG_ARRAY) {
			size += layout.sizeOfArray(this.to.length, Long.BYTES);
		}
		if (this.meta != EMPTY_LONG_ARRAY) {
			size += layout.sizeOfArray(this.meta.length, Long.BYTES);
		}
		return size;
	}

	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer) {
		// keys decompose into primitive (from, to, meta) slots - the range is materialized on demand and never
		// retained, so there is nothing for the sizer to price
		return getHeapSizeInBytes();
	}

	/**
	 * Rebuilds a {@link LongNumberRange} from its two comparison longs.
	 *
	 * `LongNumberRange` is the one kind whose bounds span the whole `long` domain, so an **explicit** bound can
	 * carry the very value the open-bound sentinel spends. Read independently, the saturated pair
	 * `(Long.MIN_VALUE, Long.MAX_VALUE)` — which `between(MIN, MAX)`, `from(MIN)` and `to(MAX)` all store — would
	 * therefore rebuild with **both** precise bounds `null`, a shape `LongNumberRange`'s own constructor refuses:
	 * {@link io.evitadb.dataType.Range#consolidateRange} clones the winner of a merge with its two precise bounds
	 * and would hand that constructor `(null, null)`. The saturated pair is consequently decoded with both bounds
	 * materialized, which re-encodes to the identical two longs — so the tree, the range index and equality see
	 * nothing move, and the result is `equals` to all three constructions above.
	 *
	 * @param lower the stored lower comparison bound
	 * @param upper the stored upper comparison bound
	 * @return the rebuilt range
	 */
	@Nonnull
	private static LongNumberRange decodeLongRange(long lower, long upper) {
		if (lower == Long.MIN_VALUE && upper == Long.MAX_VALUE) {
			return LongNumberRange._internalBuild(Long.MIN_VALUE, Long.MAX_VALUE, null, lower, upper);
		}
		return LongNumberRange._internalBuild(
			lower == Long.MIN_VALUE ? null : lower,
			upper == Long.MAX_VALUE ? null : upper,
			null, lower, upper
		);
	}

	/**
	 * Rebuilds a {@link BigDecimalNumberRange} from its two comparison longs at the column's
	 * {@code indexedDecimalPlaces}.
	 *
	 * The fully-open pair is the shared {@link BigDecimalNumberRange#INFINITE} constant and is returned **by
	 * reference**, which keeps the {@code ==} identity {@code union} / {@code intersect} test against. That identity
	 * survives the write path today — {@code FilterIndex}'s rescaling passes a fully-open range through unchanged —
	 * so the tree really does hold the constant itself, and rebuilding an equal-but-distinct object here would be
	 * the only thing that broke it.
	 *
	 * @param lower the stored lower comparison bound
	 * @param upper the stored upper comparison bound
	 * @return the rebuilt range
	 */
	@Nonnull
	private BigDecimalNumberRange decodeBigDecimalRange(long lower, long upper) {
		if (lower == Long.MIN_VALUE && upper == Long.MAX_VALUE) {
			return BigDecimalNumberRange.INFINITE;
		}
		return BigDecimalNumberRange._internalBuild(
			lower == Long.MIN_VALUE ? null : BigDecimal.valueOf(lower, this.indexedDecimalPlaces),
			upper == Long.MAX_VALUE ? null : BigDecimal.valueOf(upper, this.indexedDecimalPlaces),
			this.indexedDecimalPlaces, lower, upper
		);
	}

	/**
	 * Rebuilds a {@link DateTimeRange} from its two comparison longs and its packed offsets, through the public
	 * {@code between} / {@code since} / {@code until} factories.
	 *
	 * Only a **closed** bound is materialized, each at its own stored offset; the open side's sentinel is left to the
	 * constructor, which recomputes it from the surviving bound's offset and thereby reproduces the stored long by
	 * construction. A bound is rebuilt with {@link LocalDateTime#ofEpochSecond(long, int, ZoneOffset)} rather than
	 * through {@code Instant}: the two agree over the whole {@link OffsetDateTime} domain, but the local-date-time
	 * route is the direct inverse of the {@code toEpochSecond()} that produced the stored long, and reaches the
	 * bound without materializing an intermediate {@code Instant} to convert away again.
	 *
	 * An inverted pair is refused up front. No {@link DateTimeRange} can carry one — its own factories validate the
	 * bounds — so seeing one means the three per-slot loads did not come from one slot: a session-free reader
	 * assembled a hybrid key out of a column being shifted in place. Left to {@code DateTimeRange.between}, that
	 * would surface as a client-facing {@code EvitaInvalidUsageException} blaming the caller's bounds, out of a read
	 * the column contract promises will merely under-report.
	 *
	 * @param lower  the stored lower comparison bound
	 * @param upper  the stored upper comparison bound
	 * @param packed the stored packed offsets
	 * @return the rebuilt range
	 */
	@Nonnull
	private static DateTimeRange decodeDateTimeRange(long lower, long upper, long packed) {
		if (lower > upper) {
			throw new GenericEvitaInternalError(
				"A stored date-time range's lower bound is above its upper one, which no `DateTimeRange` can be - " +
					"this is a torn read of a column being written concurrently."
			);
		}
		final int fromOffset = NumberUtils.unpackHigh(packed);
		final int toOffset = NumberUtils.unpackLow(packed);
		if (fromOffset == OPEN_BOUND) {
			if (toOffset == OPEN_BOUND) {
				// DateTimeRange refuses a both-null construction, so no stored key can have carried this
				throw new GenericEvitaInternalError(
					"A stored date-time range claims both of its bounds are open, which no `DateTimeRange` can be."
				);
			}
			return DateTimeRange.until(atOffset(upper, toOffset));
		} else if (toOffset == OPEN_BOUND) {
			return DateTimeRange.since(atOffset(lower, fromOffset));
		} else {
			return DateTimeRange.between(atOffset(lower, fromOffset), atOffset(upper, toOffset));
		}
	}

	/**
	 * Renders an epoch second at a stored zone offset as the {@link OffsetDateTime} it came from, to the second.
	 *
	 * @param epochSecond    the stored comparison bound
	 * @param offsetSeconds  the bound's zone offset in seconds
	 * @return the bound, with zero nanoseconds
	 */
	@Nonnull
	private static OffsetDateTime atOffset(long epochSecond, int offsetSeconds) {
		final ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);
		return LocalDateTime.ofEpochSecond(epochSecond, 0, offset).atOffset(offset);
	}

	/**
	 * Packs a {@link DateTimeRange}'s two zone offsets into one {@code long} via {@link NumberUtils#pack(int, int)}
	 * — high 32 bits the from-bound's, low 32 the to-bound's, {@link #OPEN_BOUND} for an open bound.
	 *
	 * @param range the range to encode
	 * @return the packed offsets
	 */
	private static long encodeMeta(@Nonnull DateTimeRange range) {
		final OffsetDateTime preciseFrom = range.getPreciseFrom();
		final OffsetDateTime preciseTo = range.getPreciseTo();
		final int fromOffset = preciseFrom == null ? OPEN_BOUND : preciseFrom.getOffset().getTotalSeconds();
		final int toOffset = preciseTo == null ? OPEN_BOUND : preciseTo.getOffset().getTotalSeconds();
		return NumberUtils.pack(fromOffset, toOffset);
	}

	/**
	 * Refuses a source range that reaches past this column's live run. A key column has no empty key it could
	 * substitute, so absorbing the violation would turn a caller bug into a tree that silently holds wrong keys —
	 * the failure mode the leaf's split-range argument already warns about, where half a leaf can vanish with no
	 * exception at all.
	 *
	 * @param srcPos the start index the caller is reading from
	 * @param length the number of keys the caller is reading
	 */
	private void assertSourceRangeIsLive(int srcPos, int length) {
		if (srcPos < 0 || srcPos + length > this.size) {
			throwSourceRangeNotLive(srcPos, length);
		}
	}

	/**
	 * Builds and throws the out-of-range report. Kept out of {@link #assertSourceRangeIsLive} so the check itself is
	 * a pair of integer compares that allocates nothing: it runs on every range copy, and `createLayer()` performs one
	 * per column on the first transactional touch of every leaf, so a message supplier here would allocate thousands
	 * of objects per commit for a check that never fails.
	 *
	 * @param srcPos the start index the caller was reading from
	 * @param length the number of keys the caller was reading
	 */
	private void throwSourceRangeNotLive(int srcPos, int length) {
		throw new GenericEvitaInternalError(
			"Key column source range [" + srcPos + ", " + (srcPos + length) + ") runs past its live run ("
				+ this.size + ") — a key column has no empty key to substitute."
		);
	}

	/**
	 * Reallocates every live backing array so each holds at least {@code requiredLength} slots, carrying the live
	 * keys across. Kept out of the mutators so their steady-state path stays a single field compare against the array
	 * length — the cursor-free insert path's escape analysis depends on that path staying small.
	 *
	 * @param requiredLength the number of slots the caller is about to address
	 */
	private void ensurePhysicalLength(int requiredLength) {
		if (requiredLength > this.from.length) {
			final int grown = ColumnSizing.grownLength(this.from.length, requiredLength, this.capacity);
			this.from = Arrays.copyOf(this.from, grown);
			this.to = Arrays.copyOf(this.to, grown);
			if (this.kind.isMetaCarrying()) {
				this.meta = Arrays.copyOf(this.meta, grown);
			}
		}
	}

	/**
	 * The out-of-line half of {@link #insertKeyAt}: grows every live backing array, then performs the very same
	 * lockstep shift-and-write the fast path performs.
	 *
	 * @param index the insertion position
	 * @param value the key to insert
	 */
	private void growAndInsertKeyAt(int index, @Nonnull M value) {
		ensurePhysicalLength(this.size + 1);
		shiftAndWriteKeyAt(index, value);
	}

	/**
	 * Lockstep right-shift of every live array followed by the decomposition of {@code value} into its slots. Shared
	 * by both halves of {@link #insertKeyAt} — it is a private, statically bound method, so the fast path keeps its
	 * single field compare and the shared body still inlines into it.
	 *
	 * @param index the insertion position; the backing arrays must already hold {@code size + 1} slots
	 * @param value the key to insert
	 */
	private void shiftAndWriteKeyAt(int index, @Nonnull M value) {
		final int tail = this.size - index;
		System.arraycopy(this.from, index, this.from, index + 1, tail);
		System.arraycopy(this.to, index, this.to, index + 1, tail);
		// the key is narrowed to one of the TWO classes that actually declare `getFrom` / `getTo` rather than to
		// `Range`, which has six implementors — see the class javadoc for why that matters on this path
		if (this.kind.isMetaCarrying()) {
			System.arraycopy(this.meta, index, this.meta, index + 1, tail);
			final DateTimeRange range = (DateTimeRange) value;
			this.from[index] = range.getFrom();
			this.to[index] = range.getTo();
			this.meta[index] = encodeMeta(range);
		} else {
			final NumberRange<?> range = (NumberRange<?>) value;
			this.from[index] = range.getFrom();
			this.to[index] = range.getTo();
		}
		this.size++;
	}

	/**
	 * Allocates a backing array of the given length, keeping a zero length on the shared empty array.
	 *
	 * @param length the array length
	 * @return the fresh array
	 */
	@Nonnull
	private static long[] newLongArray(int length) {
		return length == 0 ? EMPTY_LONG_ARRAY : new long[length];
	}

	/**
	 * Compares the key stored at {@code index} with the probe {@code (probeFrom, probeTo)} lexicographically — by
	 * lower bound first, then by upper bound — which matches natural {@link Range} order exactly.
	 *
	 * @param index     the slot to compare
	 * @param probeFrom the probe's lower comparison bound
	 * @param probeTo   the probe's upper comparison bound
	 * @return a negative / zero / positive value as the stored key is less than / equal to / greater than the probe
	 */
	private int compareAt(int index, long probeFrom, long probeTo) {
		final int fromCmp = Long.compare(this.from[index], probeFrom);
		return fromCmp != 0 ? fromCmp : Long.compare(this.to[index], probeTo);
	}

	/**
	 * Narrows a sibling column to the same concrete encoding. One attribute index means one value type at one scale,
	 * so both always agree in production; a mismatch would move keys between two incompatible encodings and is
	 * refused rather than absorbed.
	 *
	 * Two things make an encoding: the {@link RangeKind} — a {@code DateTimeRange} slot arriving in a column with no
	 * {@code meta} array to receive its offsets, say — and, for {@link RangeKind#BIG_DECIMAL_NUMBER},
	 * {@code indexedDecimalPlaces}. The scale is the subtler half: the longs move across intact and the destination
	 * then rebuilds every one of them at **its** scale, so an unguarded copy between two big-decimal columns of
	 * different scale silently rewrites the values it moved.
	 *
	 * @param other the sibling column
	 * @return {@code other} as a {@link RangeValueColumn} of this column's kind and scale
	 */
	@Nonnull
	private RangeValueColumn<M> asSameKind(@Nonnull ValueColumn<M> other) {
		if (other instanceof RangeValueColumn<M> range && range.kind == this.kind
			&& range.indexedDecimalPlaces == this.indexedDecimalPlaces) {
			return range;
		}
		final String actual = other instanceof RangeValueColumn<M> range
			? range.kind + " range column at scale " + range.indexedDecimalPlaces
			: other.getClass().getName();
		throw new IllegalArgumentException(
			"Cannot mix value column kinds within one tree: expected a " + this.kind + " range column at scale "
				+ this.indexedDecimalPlaces + " but got " + actual
		);
	}
}
