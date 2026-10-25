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

package io.evitadb.index.attribute;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.comparator.NullsFirstComparatorWrapper;
import io.evitadb.comparator.NullsLastComparatorWrapper;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Rewrites an owner {@link SortIndex} persisted by release 2026.2 or an older one into the form and the order the index
 * works with now. It is one step of the storage migration to protocol 7 (`Migration_2026_3`), which runs it for every
 * owner sort index of a catalog written by such a release before it converts the collection's single-owner reference
 * partitions, so the load path never meets an owner sort index of such a catalog in its old form or order.
 *
 * An owner sort index - a sort-only attribute, every sortable attribute compound - of those releases differs from the
 * one the current engine writes in four ways. (A sortable attribute that is filterable too loads as a view over its
 * filter index, which ignores the values its part carries - there is nothing to rewrite.)
 *
 * - **Raw temporal values.** It stored `OffsetDateTime`, `LocalDateTime` and `LocalTime` values verbatim, while the
 *   filter index and every sort index viewing it stored their millisecond form.
 * - **The order of a localized `String` element.** It compared a `String` element of a localized index by the bare
 *   collator ({@link LocalizedStringComparator}), which equates two spellings such as `ab` with and without a
 *   zero-width space between its letters; the current index breaks that tie
 *   ({@link EqualsConsistentLocalizedStringComparator}). A compound holding such a pair was ordered by its next
 *   element, which the current comparator may reverse. The load path builds the leaves of a paged index from its pages
 *   as they are stored, so it refuses such a pair as damage - by the bulk load of a page, or by the check of the
 *   boundary between two pages - and an inline index loads with its positional record order disagreeing with its
 *   values.
 * - **The order of a `Locale` element.** It compared a `Locale` element - localized index or not - by its language tag
 *   alone, which drops an ill-formed variant and so equates `en-US` with and without one; the current index breaks
 *   that tie ({@link ComparableLocale}). A compound holding such a pair fails to load, or loads misordered, the same
 *   way.
 * - **The order of a `DateTimeRange` element.** It compared a `DateTimeRange` element - localized index or not - at
 *   whole epoch seconds, an open bound derived from the zone offset of the other one
 *   ({@link InvertedIndex#compareAtSecondGranularity}); the current index compares milliseconds, an open bound being
 *   one constant. Two ranges opening in the same second tie then and differ now, and two ranges open at one end whose
 *   other bounds carry different offsets compare the other way now, tie or not - so a plain index is misordered too.
 *   The load path re-sorts a paged index whose every value is a `DateTimeRange`, never a compound or an inline index:
 *   a paged compound fails to load, and an inline index loads misordered.
 *
 * The rewrite brings such an index to the form and the order the current engine writes:
 *
 * - every value is normalized by the very normalizer the index applies to a new value;
 * - values that now coincide - one moment written at two offsets, or two values differing only below the
 *   millisecond - are merged into one, and their records are ordered by record id within it as in any other block;
 * - the values are re-sorted by the current comparator - two compound values tying on a temporal element only after
 *   it was normalized, on a localized `String` element only under the bare collator, or on a `Locale` element only by
 *   its language tag, are decided by the next element, which may order them the other way, and two `DateTimeRange`
 *   values may compare the other way outright;
 * - the index is rebuilt afresh and written whole: an inline index as a new inline part, a paged one as new leaf pages
 *   whose page sequences lie above the persisted high-water, a new root, and the removal of every persisted leaf page
 *   - so no legacy page survives, and no new page reuses the identity of an old one.
 *
 * An index holding no raw temporal value whose values already ascend strictly under the current comparator is left
 * as it is: it is read, and nothing is written for it.
 *
 * # Buckets the release merged
 *
 * The release kept two values its comparator found equal in one bucket - `ab` and `ab` with a zero-width space,
 * `en-US` with and without an ill-formed variant, or two ranges equal at whole seconds, held by two records, in a
 * plain sort index or in a compound whose other elements tie too. Such a bucket stays exactly as the release wrote it,
 * under the value it stored and with every record it holds, whether the index is left as it is or rewritten: the
 * bucket does not say which of its records holds which value, and this rewrite reads no entity body to find out.
 *
 * # The rewrite emits its parts itself
 *
 * The rebuilt index hands its parts to the caller's sink directly ({@link SortIndex#doAppendStorageParts}); it is
 * never flagged dirty for a later flush to write. Collecting the parts of an index is a persistence step - it stamps
 * page sequences, clears leaf flags and stages the next page list - so a rewrite left to a flush would be at the mercy
 * of whatever collects the index first; a collect into a discarded sink would stage the rewritten leaves without
 * writing them, and they would be lost.
 *
 * # What it refuses
 *
 * A paged index is rewritten only when its persisted values are **strictly ascending** across all its pages under the
 * comparator the release that wrote them ordered the index by - the order every owner wrote its pages in. A stale
 * leaf-page twin fails that test, its values overlapping those of the page it is a twin of, and is refused as
 * corruption: merging it would resurrect records that were deliberately removed.
 *
 * The test must use the released comparator, not the current one, because the two differ: a localized `String`
 * element was ordered by the bare collator, a `Locale` element by its language tag and a `DateTimeRange` element at
 * whole seconds, so a released compound holding two values one of them equates, ordered by its next element, and an
 * index holding two ranges the release compared the other way, are in a legitimate order the current comparator may
 * reverse - the very order this rewrite exists to correct. The rebuilt index is ordered by the current comparator, as
 * every index is.
 *
 * # What it does not cover
 *
 * Nothing on the load path normalizes a raw temporal sort value or re-sorts persisted values any more, except a paged
 * index whose every value is a `DateTimeRange`, which it re-sorts at whole seconds. A catalog that reaches the load
 * path at protocol 7 holding either - which only a development build of 2026.3 can have written, directly or through
 * an archive of such a catalog - is not supported: its values are read as they are stored.
 *
 * The class performs no IO: the migration reads the parts and drains the sink into the collection data store.
 *
 * Re-canonicalizing here heals keys written by older versions and must use the legacy function only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated its only caller is `Migration_2026_3`, the storage migration of catalogs written by 2026.2 and older
 * releases; it is public only because that migration lives in another module, and it is removed together with it -
 * and with {@link InvertedIndex#supersedePersistedPages(int, int[])}, which exists only for it
 */
@Deprecated(since = "2026.3", forRemoval = true)
public final class LegacyOwnerSortIndexCorrection {

	private LegacyOwnerSortIndexCorrection() {
		// static helpers only
	}

	/**
	 * Returns `true` when an owner sort index of the passed elements may differ from the one the current engine
	 * writes - when any element is an `OffsetDateTime`, `LocalDateTime` or `LocalTime`, which the release stored raw,
	 * a `String` of a localized index, which the release ordered by the bare collator, a `Locale`, which it ordered
	 * by its language tag alone, or a `DateTimeRange`, which it ordered at whole seconds. A `String` element is
	 * localized exactly when {@link SortIndex#createComparatorFor} compares it by a collator: when the attribute index
	 * key of the index carries a locale. A `Locale` or a `DateTimeRange` element qualifies whether the index is
	 * localized or not.
	 *
	 * @param comparatorBase one descriptor per element (a single entry for plain attributes)
	 * @param locale         the locale of the attribute index key of the index, `null` when it is not localized
	 * @return `true` when the persisted values have to be checked and possibly rewritten
	 */
	public static boolean isApplicable(@Nonnull ComparatorSource[] comparatorBase, @Nullable Locale locale) {
		for (final ComparatorSource comparatorSource : comparatorBase) {
			if (
				SortIndex.isTemporal(comparatorSource.type()) || isCollated(comparatorSource, locale) ||
					isLocale(comparatorSource) || isDateTimeRange(comparatorSource)
			) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Rewrites one persisted owner sort index whose values may still be raw, or ordered by the comparator of the
	 * release - see the class documentation - and emits its parts into `sink`: the root, every leaf page of a paged
	 * result, and a removal of every leaf page the persisted root lists. When every persisted value is normalized
	 * already and the values ascend strictly under the current comparator, nothing is emitted.
	 *
	 * @param root           the persisted root part of an owner sort index - inline, or paged - never the slim part of
	 *                       a sort index viewing a filter index, which carries no values of its own
	 * @param perPageBuckets the buckets of every leaf page the paged root lists, in the order it lists them, or `null`
	 *                       for an inline root
	 * @param sink           receives the parts of the rewritten index and the removals of the persisted leaf pages
	 * @return `true` when the index was rewritten and its parts were emitted, `false` when it was left as it is
	 * @throws GenericEvitaInternalError when the index has no temporal, localized `String`, `Locale` or `DateTimeRange`
	 *                                   element, the leaf pages do not match the root, or the persisted values of a
	 *                                   paged index to rewrite are not strictly ascending under the released
	 *                                   comparator
	 */
	public static boolean rewrite(
		@Nonnull SortIndexStoragePart root,
		@Nullable ValueToRecord[][] perPageBuckets,
		@Nonnull TrappedChanges sink
	) {
		final ComparatorSource[] comparatorBase = root.getComparatorBase();
		final Locale locale = root.getAttributeIndexKey().locale();
		Assert.isPremiseValid(
			isApplicable(comparatorBase, locale),
			() -> "The sort index of `" + root.getAttributeIndexKey() + "` holds no temporal, localized text, " +
				"locale or date time range element to rewrite!"
		);
		final Function<Object, Serializable> normalizer = createNormalizer(comparatorBase);
		final Comparator<?> comparator = createComparator(comparatorBase, locale);
		final InvertedIndex ownedTree;
		if (root.isPaged()) {
			final int[] pageSequences = root.getLeafPageSequencesOrThrowException();
			Assert.isPremiseValid(
				perPageBuckets != null && perPageBuckets.length == pageSequences.length,
				() -> "The sort index of `" + root.getAttributeIndexKey() + "` lists " + pageSequences.length +
					" leaf pages, but " + (perPageBuckets == null ? "none" : perPageBuckets.length) + " were read!"
			);
			// the order is checked only when every value is normalized - the current comparator orders normalized ones
			if (!holdsRawValue(perPageBuckets, normalizer) && isStrictlyAscending(perPageBuckets, comparator)) {
				return false;
			}
			assertStrictlyAscending(root, perPageBuckets, createReleasedComparator(comparatorBase, locale));
			ownedTree = OwnerSortIndex.createOwnedTree(comparatorBase, comparator, root.getIndexedDecimalPlaces());
			for (final ValueToRecord[] buckets : perPageBuckets) {
				for (final ValueToRecord bucket : buckets) {
					// a value that coincides with one inserted already joins its bucket, and the tree keeps the values
					// in the order they compare in now, whatever the persisted one
					ownedTree.addRecord(normalizer.apply(bucket.getValue()), bucket.getRecordIds().getArray());
				}
			}
			// the rebuilt leaves continue the persisted page stream: their pages lie above the persisted high-water,
			// and every persisted page is freed by the emission below
			ownedTree.supersedePersistedPages(root.getHighWaterPageSequence(), pageSequences);
		} else {
			Assert.isPremiseValid(
				perPageBuckets == null,
				() -> "The sort index of `" + root.getAttributeIndexKey() + "` is inline, but leaf pages were passed!"
			);
			final Serializable[] sortedRecordValues = root.getSortedRecordsValues();
			// the order is checked only when every value is normalized - the current comparator orders normalized ones
			if (!holdsRawValue(sortedRecordValues, normalizer) && isStrictlyAscending(sortedRecordValues, comparator)) {
				return false;
			}
			ownedTree = OwnerSortIndex.createOwnedTree(comparatorBase, comparator, root.getIndexedDecimalPlaces());
			final int[] sortedRecords = root.getSortedRecords();
			final Map<Serializable, Integer> cardinalities = root.getValueCardinalities();
			int offset = 0;
			for (final Serializable value : sortedRecordValues) {
				// the cardinalities are keyed by the persisted value, not by the normalized one
				final int cardinality = cardinalities.getOrDefault(value, 1);
				ownedTree.addRecord(
					normalizer.apply(value), Arrays.copyOfRange(sortedRecords, offset, offset + cardinality)
				);
				offset += cardinality;
			}
		}
		// the persisted positional array orders the records by the persisted values - it is rebuilt from the tree,
		// which orders merged blocks by record id like every other block; the index only emits its parts, which do not
		// carry the reference key, so none is passed
		final OwnerSortIndex rewritten = new OwnerSortIndex(
			comparatorBase, null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
			new TransactionalUnorderedIntArray(SortIndexView.reconstructSortedRecords(ownedTree)), ownedTree,
			SortIndex.createLegacyNormalizerFor(comparatorBase, root.getIndexedDecimalPlaces())
		);
		// emitted here and now rather than flagged dirty for a later flush - see the class documentation
		rewritten.doAppendStorageParts(root.getEntityIndexPrimaryKey(), sink);
		return true;
	}

	/**
	 * Returns `true` when the element is a `String` compared by a collator - one of an index whose attribute index key
	 * carries a locale, exactly as {@link SortIndex#createComparatorFor} decides it.
	 *
	 * @param comparatorSource the descriptor of the element
	 * @param locale           the locale of the attribute index key of the index, `null` when it is not localized
	 * @return `true` for a localized `String` element
	 */
	private static boolean isCollated(@Nonnull ComparatorSource comparatorSource, @Nullable Locale locale) {
		return locale != null && String.class.isAssignableFrom(comparatorSource.type());
	}

	/**
	 * Returns `true` when the element is a `Locale`, which the index stores as a {@link ComparableLocale} - decided by
	 * the declared type, as {@link SortIndex} decides it, whether the index is localized or not.
	 *
	 * @param comparatorSource the descriptor of the element
	 * @return `true` for a `Locale` element
	 */
	private static boolean isLocale(@Nonnull ComparatorSource comparatorSource) {
		return Locale.class.isAssignableFrom(comparatorSource.type());
	}

	/**
	 * Returns `true` when the element is a `DateTimeRange`, which the index stores as it was written - decided by the
	 * declared type, as {@link SortIndex} decides a type, whether the index is localized or not.
	 *
	 * @param comparatorSource the descriptor of the element
	 * @return `true` for a `DateTimeRange` element
	 */
	private static boolean isDateTimeRange(@Nonnull ComparatorSource comparatorSource) {
		return DateTimeRange.class.isAssignableFrom(comparatorSource.type());
	}

	/**
	 * Returns `true` when any of the persisted values is raw - when the normalizer maps it onto another instance.
	 *
	 * @param values     the persisted values
	 * @param normalizer the normalizer of persisted values
	 * @return `true` when a value is not in its normalized form
	 */
	private static boolean holdsRawValue(
		@Nonnull Serializable[] values,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		for (final Serializable value : values) {
			if (normalizer.apply(value) != value) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns `true` when any value of the persisted leaf pages is raw - when the normalizer maps it onto another
	 * instance.
	 *
	 * @param perPageBuckets the buckets of every leaf page
	 * @param normalizer     the normalizer of persisted values
	 * @return `true` when a value is not in its normalized form
	 */
	private static boolean holdsRawValue(
		@Nonnull ValueToRecord[][] perPageBuckets,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		for (final ValueToRecord[] buckets : perPageBuckets) {
			for (final ValueToRecord bucket : buckets) {
				if (normalizer.apply(bucket.getValue()) != bucket.getValue()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns `true` when the persisted values of an inline index ascend strictly under the passed comparator.
	 *
	 * @param values     the persisted values, in the order they are stored in
	 * @param comparator the comparator the current index orders its values by
	 * @return `true` when every value sorts strictly before the next one
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean isStrictlyAscending(@Nonnull Serializable[] values, @Nonnull Comparator comparator) {
		for (int i = 1; i < values.length; i++) {
			if (comparator.compare(values[i - 1], values[i]) >= 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns `true` when the persisted values of a paged index ascend strictly across all its pages under the passed
	 * comparator - the boundaries between two pages included, which the load path checks as well.
	 *
	 * @param perPageBuckets the buckets of every leaf page, in the order the root lists them
	 * @param comparator     the comparator the current index orders its values by
	 * @return `true` when every value sorts strictly before the next one
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean isStrictlyAscending(
		@Nonnull ValueToRecord[][] perPageBuckets,
		@Nonnull Comparator comparator
	) {
		Serializable previousValue = null;
		for (final ValueToRecord[] buckets : perPageBuckets) {
			for (final ValueToRecord bucket : buckets) {
				final Serializable value = bucket.getValue();
				if (previousValue != null && comparator.compare(previousValue, value) >= 0) {
					return false;
				}
				previousValue = value;
			}
		}
		return true;
	}

	/**
	 * Asserts that the persisted values of a paged index are strictly ascending across all its pages under the
	 * comparator the release that wrote them ordered the index by - the order every owner wrote its pages in (see
	 * {@link #createReleasedComparator}). Pages out of that order are corruption - a stale leaf-page twin is the known
	 * cause - and are refused rather than merged, see the class documentation.
	 *
	 * @param root           the persisted root, which names the index in the message
	 * @param perPageBuckets the buckets of every leaf page, in the order the root lists them
	 * @param comparator     the comparator the release that wrote the pages ordered the index by
	 * @throws GenericEvitaInternalError when two consecutive values are not strictly ascending
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void assertStrictlyAscending(
		@Nonnull SortIndexStoragePart root,
		@Nonnull ValueToRecord[][] perPageBuckets,
		@Nonnull Comparator comparator
	) {
		Serializable previousValue = null;
		for (int i = 0; i < perPageBuckets.length; i++) {
			for (final ValueToRecord bucket : perPageBuckets[i]) {
				final Serializable value = bucket.getValue();
				if (previousValue != null && comparator.compare(previousValue, value) >= 0) {
					throw new GenericEvitaInternalError(
						"The leaf pages of the sort index of `" + root.getAttributeIndexKey() + "` in entity index " +
							root.getEntityIndexPrimaryKey() + " are not in ascending order - `" + previousValue +
							"` is followed by `" + value + "` on leaf page " +
							root.getLeafPageSequencesOrThrowException()[i] + ". No release writes its pages so, so " +
							"the index is damaged and is not rewritten: rebuild the entity collection with the " +
							"release that wrote the catalog, and start this version again.",
						"The persisted pages of a sort index are not in ascending order."
					);
				}
				previousValue = value;
			}
		}
	}

	/**
	 * Creates the comparator the releases that wrote paged owner sort indexes - 2026.2, the first one to page them -
	 * ordered such an index by, which is the order its persisted pages are in. It is `SortIndex#createComparatorFor`
	 * and `SortIndex#createCombinedComparatorFor` of the release tag `v2026.2.20`: a `String` element of a localized
	 * index compared by the bare collator ({@link LocalizedStringComparator}) - with no tie-break between two spellings
	 * the collator equates, which the current index adds ({@link EqualsConsistentLocalizedStringComparator}) - a
	 * `Locale` element by `ComparableLocale#compareTo` of that tag, the language tag alone, with no tie-break either,
	 * a `DateTimeRange` element by `DateTimeRange#compareTo` of that tag
	 * ({@link InvertedIndex#compareAtSecondGranularity}), every other element in its natural order, each in its own
	 * direction and with its own NULL handling, a compound element by element. The values are the raw ones the release
	 * stored, so the natural order of a raw temporal value is the order of its declared type.
	 *
	 * @param comparatorBase one descriptor per element (a single entry for plain attributes)
	 * @param locale         the locale of the attribute index key of the index, `null` when it is not localized
	 * @return the comparator the release ordered the index by
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Comparator createReleasedComparator(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable Locale locale
	) {
		final Comparator[] elementComparators = new Comparator[comparatorBase.length];
		for (int i = 0; i < comparatorBase.length; i++) {
			final ComparatorSource source = comparatorBase[i];
			final Comparator natural;
			if (isCollated(source, locale)) {
				natural = new LocalizedStringComparator(locale);
			} else if (isLocale(source)) {
				// `ComparableLocale#compareTo` of the release: the language tag alone, which drops an ill-formed
				// variant
				natural = Comparator.comparing((ComparableLocale it) -> it.getLocale().toLanguageTag());
			} else if (isDateTimeRange(source)) {
				// `DateTimeRange#compareTo` of the release: whole epoch seconds, an open bound derived from the zone
				// offset of the other one
				natural = (Comparator<DateTimeRange>) InvertedIndex::compareAtSecondGranularity;
			} else {
				natural = Comparator.naturalOrder();
			}
			final Comparator directed = source.orderDirection() == OrderDirection.ASC ? natural : natural.reversed();
			elementComparators[i] = source.orderBehaviour() == OrderBehaviour.NULLS_LAST ?
				new NullsLastComparatorWrapper(directed) : new NullsFirstComparatorWrapper(directed);
		}
		if (comparatorBase.length == 1) {
			return elementComparators[0];
		}
		return (left, right) -> {
			final Serializable[] leftElements = ((ComparableArray) left).array();
			final Serializable[] rightElements = ((ComparableArray) right).array();
			for (int i = 0; i < elementComparators.length; i++) {
				final int comparison = elementComparators[i].compare(leftElements[i], rightElements[i]);
				if (comparison != 0) {
					return comparison;
				}
			}
			return 0;
		};
	}

	/**
	 * Creates the comparator an owner sort index of the passed elements orders its values by - the one
	 * {@link SortIndex} derives for itself.
	 *
	 * @param comparatorBase one descriptor per element (a single entry for plain attributes)
	 * @param locale         the locale of the attribute index key of the index, `null` when it is not localized
	 * @return the value comparator
	 */
	@Nonnull
	private static Comparator<?> createComparator(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable Locale locale
	) {
		return comparatorBase.length == 1
			? SortIndex.createComparatorFor(locale, comparatorBase[0])
			: SortIndex.createCombinedComparatorFor(locale, comparatorBase);
	}

	/**
	 * Clamps a persisted temporal value to the window of moments the current engine can hold, see
	 * {@link EvitaDataTypes#clampToSupportedWindow(OffsetDateTime)}. Release 2026.2 and older accepted any
	 * `OffsetDateTime` and `LocalDateTime`, the JDK sentinels included, and keyed their indexes by the `Instant` of
	 * such a value; the current engine keys by whole epoch milliseconds, which an `Instant` outside the window does
	 * not have, and clamps the same values as they enter. The clamp only moves a value lying outside the window and
	 * never truncates, so a value inside it is returned as the very same instance.
	 *
	 * The method is public because `Migration_2026_3`, in another module, clamps the filter keys, cardinality
	 * counters and unique keys it re-keys with the very same rule.
	 *
	 * @param value an `OffsetDateTime`, a `LocalDateTime`, an `Instant` (the key of a filter index) or anything else
	 * @return the clamped value when it is temporal and lies outside the window, otherwise the value itself
	 */
	@Nullable
	public static Serializable clampToSupportedWindow(@Nullable Object value) {
		if (value instanceof OffsetDateTime offsetDateTime) {
			return EvitaDataTypes.clampToSupportedWindow(offsetDateTime);
		} else if (value instanceof LocalDateTime localDateTime) {
			return EvitaDataTypes.clampToSupportedWindow(localDateTime);
		} else if (value instanceof Instant instant) {
			return EvitaDataTypes.clampToSupportedWindow(instant);
		} else {
			return (Serializable) value;
		}
	}

	/**
	 * Creates the normalizer of a persisted value of `type`: {@link FilterIndex#getNormalizer(Class, int)} behind
	 * {@link #clampToSupportedWindow}, so a value outside the window of the current engine is keyed by the edge of the
	 * window it is clamped to. A type that is not temporal gets the plain normalizer.
	 *
	 * @param type                 the plain type of the attribute
	 * @param indexedDecimalPlaces the scale of a `BigDecimal` attribute, ignored for every other type
	 * @return the normalizer
	 */
	@Nonnull
	public static Function<Object, Serializable> createClampingNormalizer(
		@Nonnull Class<?> type,
		int indexedDecimalPlaces
	) {
		final Function<Object, Serializable> normalizer = type == String.class
			? FilterIndex.getLegacyStringNormalizer() : FilterIndex.getNormalizer(type, indexedDecimalPlaces);
		return SortIndex.isTemporal(type) ?
			value -> normalizer.apply(clampToSupportedWindow(value)) : normalizer;
	}

	/**
	 * Creates the normalizer of persisted values: the temporal normalizer the sort index applies to a new value
	 * ({@link FilterIndex#getNormalizer(Class, int)}) behind the clamp to the supported window (see
	 * {@link #createClampingNormalizer}), applied to a persisted plain value or to the temporal elements of a
	 * persisted {@link ComparableArray}. Every other element is left as persisted - it was normalized when it was
	 * written, and not every normalizer accepts its own output - so an index with no temporal element gets the
	 * identity. The function returns the very instance it is given when that is normalized already, which is how a raw
	 * value is told apart from a current one.
	 *
	 * @param comparatorBase one descriptor per element (a single entry for plain attributes)
	 * @return the normalizer of persisted values
	 */
	@Nonnull
	private static Function<Object, Serializable> createNormalizer(@Nonnull ComparatorSource[] comparatorBase) {
		//noinspection unchecked
		final Function<Object, Serializable>[] elementNormalizers = new Function[comparatorBase.length];
		boolean temporal = false;
		for (int i = 0; i < comparatorBase.length; i++) {
			final Class<?> type = comparatorBase[i].type();
			if (SortIndex.isTemporal(type)) {
				elementNormalizers[i] = createClampingNormalizer(type, 0);
				temporal = true;
			}
		}
		if (!temporal) {
			return Serializable.class::cast;
		} else if (comparatorBase.length == 1) {
			return elementNormalizers[0];
		} else {
			return value -> {
				final ComparableArray persisted = (ComparableArray) value;
				final Serializable[] elements = persisted.array();
				Serializable[] normalized = null;
				for (int i = 0; i < elements.length; i++) {
					final Function<Object, Serializable> elementNormalizer = elementNormalizers[i];
					if (elementNormalizer != null) {
						final Serializable normalizedElement = elementNormalizer.apply(elements[i]);
						if (normalizedElement != elements[i]) {
							// copy on the first change - the persisted array stays as it was read
							if (normalized == null) {
								normalized = Arrays.copyOf(elements, elements.length);
							}
							normalized[i] = normalizedElement;
						}
					}
				}
				return normalized == null ? persisted : new ComparableArray(normalized);
			};
		}
	}

}
