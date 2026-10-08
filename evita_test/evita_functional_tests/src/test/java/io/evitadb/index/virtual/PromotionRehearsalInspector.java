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


package io.evitadb.index.virtual;

import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.PromotionRehearsal;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleWithScope;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Rehearses the materialization of every single-owner partition that gains its second owner, at the very point it
 * gains it, and compares the result with the live partition - the promotion a virtual partition will go through, run
 * against a partition that is still real.
 *
 * At the promotion point the live partition holds its first owner's data and nothing of the second owner's but its
 * membership. The rehearsal writes the first owner into a partition of the same key that no collection knows (the
 * **rebuilt** partition) through the production write a materialization uses, and the two are compared on the spot,
 * inside the mutation and its transaction:
 *
 * - the rebuilt partition in full - anything it holds is the materialization's doing;
 * - the live partition projected on the first owner - its membership, its language records and its attribute
 *   records keyed by the first owner, and its chain records keyed by the first owner or by the referenced entity -
 *   so a record another entity left in the partition is never mistaken for the first owner's.
 *
 * The two must be equal, the locales included. A first owner mutated by the same root mutation has its locale changes
 * pending: the live partition learns them only when the first owner's own mutation applies its changes, after every
 * mutation of the root mutation was dispatched, and the materialization writes the locales the first owner had before
 * the root mutation, so that the same finalization moves the rebuilt partition exactly as it moves the live one. The
 * pending changes are recorded with the outcome, so that a test can prove its history had some.
 *
 * The rebuilt partition is released in a `finally` - its transactional memory removed, so the commit's sweep of the
 * transaction's layers finds nothing of it - and never escapes the call. Only descriptions of both sides are kept.
 *
 * The comparison never throws inside the mutation - an outcome is recorded and asserted by the test afterwards - but
 * a failing materialization does, which fails the mutation: that is how a materialization that reaches beyond the
 * partition it writes is caught.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class PromotionRehearsalInspector implements Consumer<PromotionRehearsal> {
	/**
	 * The outcome of every rehearsal, in the order they ran.
	 */
	private final List<Outcome> outcomes = new ArrayList<>(8);

	/**
	 * The outcome of one rehearsal.
	 *
	 * @param entityType   the entity type owning the reference
	 * @param partitionKey the key of the partition
	 * @param partition    the primary key of the partition
	 * @param owner        the first owner, the one rebuilt
	 * @param joiningOwner the second owner, whose joining made the promotion point
	 * @param expected       the live partition projected on the first owner
	 * @param rebuilt        the rebuilt partition
	 * @param pendingLocales the first owner's pending locale changes when the rehearsal ran, each as
	 *                       `+locale [scopes]` or `-locale [scopes]`
	 */
	record Outcome(
		@Nonnull String entityType,
		@Nonnull EntityIndexKey partitionKey,
		int partition,
		int owner,
		int joiningOwner,
		@Nonnull SortedMap<String, String> expected,
		@Nonnull SortedMap<String, String> rebuilt,
		@Nonnull List<String> pendingLocales
	) {

		/**
		 * Returns TRUE when the rebuilt partition is exactly the expectation.
		 *
		 * @return TRUE when the rehearsal matched
		 */
		boolean matched() {
			return this.expected.equals(this.rebuilt);
		}

		/**
		 * Lists the lines in which the expectation and the rebuilt partition differ.
		 *
		 * @return the differences, one per line
		 */
		@Nonnull
		List<String> differences() {
			final SortedMap<String, String> all = new TreeMap<>(this.expected);
			all.putAll(this.rebuilt);
			final List<String> differences = new ArrayList<>(4);
			for (final String path : all.keySet()) {
				final String expectedLine = this.expected.get(path);
				final String rebuiltLine = this.rebuilt.get(path);
				if (!Objects.equals(expectedLine, rebuiltLine)) {
					differences.add(path + ": expected " + expectedLine + ", rebuilt " + rebuiltLine);
				}
			}
			return differences;
		}
	}

	@Override
	public void accept(@Nonnull PromotionRehearsal rehearsal) {
		final EntityIndex live = (EntityIndex) rehearsal.getPartition();
		final ReducedEntityIndex rebuilt = new ReducedEntityIndex(
			live.getPrimaryKey(), rehearsal.getEntityType(), live.getIndexKey(), false
		);
		try {
			rehearsal.populate(rebuilt);
			final int owner = rehearsal.getOwnerPrimaryKey();
			final int target = rehearsal.getPartition().getReferenceKey().primaryKey();
			final SortedMap<String, String> expected = describe(
				live, record -> record == owner, record -> record == owner || record == target
			);
			final SortedMap<String, String> rebuiltLines = describe(rebuilt, record -> true, record -> true);
			final List<String> pendingLocales = pendingLocalesOf(rehearsal);
			synchronized (this.outcomes) {
				this.outcomes.add(
					new Outcome(
						rehearsal.getEntityType(), live.getIndexKey(), live.getPrimaryKey(), owner,
						rehearsal.getJoiningOwnerPrimaryKey(), expected, rebuiltLines, pendingLocales
					)
				);
			}
		} finally {
			// the rebuilt partition is known to nothing - its transactional memory is released here or never
			rebuilt.removeLayer();
		}
	}

	/**
	 * Returns the outcomes of every rehearsal so far.
	 *
	 * @return a copy of the outcomes, in the order the rehearsals ran
	 */
	@Nonnull
	List<Outcome> getOutcomes() {
		synchronized (this.outcomes) {
			return List.copyOf(this.outcomes);
		}
	}

	/**
	 * Asserts that at least one rehearsal ran since the given number of outcomes and that every one of them matched.
	 *
	 * @param label names the checkpoint in failure messages
	 * @param from  the number of outcomes before the checked part of the history
	 * @return the outcomes checked
	 */
	@Nonnull
	List<Outcome> assertAllMatched(@Nonnull String label, int from) {
		final List<Outcome> all = getOutcomes();
		final List<Outcome> checked = all.subList(from, all.size());
		assertFalse(checked.isEmpty(), label + ": no promotion point was rehearsed - the check would be vacuous");
		for (final Outcome outcome : checked) {
			assertEquals(
				List.of(), outcome.differences(),
				label + ": the partition " + outcome.partition() + " rebuilt for its owner " + outcome.owner() +
					" differs from the live one when owner " + outcome.joiningOwner() + " joins it"
			);
		}
		return checked;
	}

	/**
	 * Lists the first owner's pending locale changes - those its mutation applies to its partitions when it applies its
	 * changes.
	 *
	 * @param rehearsal the rehearsal
	 * @return the pending locale changes, each as `+locale [scopes]` or `-locale [scopes]`
	 */
	@Nonnull
	private static List<String> pendingLocalesOf(@Nonnull PromotionRehearsal rehearsal) {
		final List<String> pending = new ArrayList<>(2);
		for (final LocaleWithScope removed : rehearsal.getOwnerRemovedLocales()) {
			pending.add("-" + removed.locale().toLanguageTag() + " " + removed.scope());
		}
		for (final LocaleWithScope added : rehearsal.getOwnerAddedLocales()) {
			pending.add("+" + added.locale().toLanguageTag() + " " + added.scope());
		}
		return pending;
	}

	/**
	 * Describes an entity index as a map of lines, keeping only the records the filters accept: its members, its
	 * language records and its attribute indexes - a filter index by value, a sort index by position, a unique index
	 * by its records and its value-to-record mapping, a chain index by the predecessor of every element. Values are
	 * encoded by {@link CanonicalValue}. An index holding no accepted record leaves no line.
	 *
	 * @param index        the index
	 * @param ownerRecords accepts the records keyed by an owner
	 * @param chainRecords accepts the records of a chain index - keyed by an owner or by the referenced entity
	 * @return the lines by path
	 */
	@Nonnull
	static SortedMap<String, String> describe(
		@Nonnull EntityIndex index,
		@Nonnull IntPredicate ownerRecords,
		@Nonnull IntPredicate chainRecords
	) {
		final SortedMap<String, String> lines = new TreeMap<>();
		putIfAny(lines, "members", filter(index.getAllPrimaryKeys(), ownerRecords));
		for (final Locale locale : index.getLanguages()) {
			putIfAny(
				lines, languagePath(locale),
				filter(index.getRecordsWithLanguageFormula(locale).compute(), ownerRecords)
			);
		}
		index.forEachAttributeIndexKey(AttributeIndexType.FILTER, key -> {
			final FilterIndex filterIndex = index.getFilterIndex(key);
			if (filterIndex != null) {
				final StringBuilder buckets = new StringBuilder(64);
				for (final ValueToRecord bucket : filterIndex.getHistogramOfAllRecords().getBuckets()) {
					final String records = filter(bucket.getRecordIds(), ownerRecords);
					if (!records.isEmpty()) {
						buckets.append(CanonicalValue.encode(bucket.getValue())).append('=').append(records)
							.append("; ");
					}
				}
				putIfAny(lines, path("filter", key), buckets.toString());
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.SORT, key -> {
			final SortIndex sortIndex = index.getSortIndex(key);
			if (sortIndex != null) {
				final SortIndexWalk walk = SortIndexWalk.of(sortIndex);
				final StringBuilder positions = new StringBuilder(64);
				for (int i = 0; i < walk.records().length; i++) {
					if (ownerRecords.test(walk.records()[i])) {
						positions.append(walk.records()[i]).append('=')
							.append(CanonicalValue.encode(walk.values()[i])).append("; ");
					}
				}
				putIfAny(lines, path("sort", key), positions.toString());
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.UNIQUE, key -> {
			final UniqueIndex uniqueIndex = index.getUniqueIndex(key);
			if (uniqueIndex != null) {
				putIfAny(lines, path("unique", key), filter(uniqueIndex.getRecordIds(), ownerRecords));
				if (uniqueIndex instanceof final OwnerUniqueIndex ownerUniqueIndex) {
					final StringBuilder mapping = new StringBuilder(64);
					ownerUniqueIndex.forEachValueToRecord(
						(value, recordId) -> {
							if (ownerRecords.test(recordId)) {
								mapping.append(CanonicalValue.encode(value)).append('=').append(recordId)
									.append("; ");
							}
						}
					);
					putIfAny(lines, path("unique", key) + "/mapping", mapping.toString());
				}
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.CHAIN, key -> {
			final ChainIndex chainIndex = index.getChainIndex(key);
			if (chainIndex != null) {
				final int[] elements = chainIndex.getUnorderedLookup().getArray().clone();
				Arrays.sort(elements);
				final StringBuilder predecessors = new StringBuilder(32);
				for (final int element : elements) {
					if (chainRecords.test(element)) {
						final OptionalInt predecessor = chainIndex.getPredecessorPrimaryKey(element);
						predecessors.append(element).append("<-")
							.append(predecessor.isPresent() ? String.valueOf(predecessor.getAsInt()) : "NONE")
							.append("; ");
					}
				}
				putIfAny(lines, path("chain", key), predecessors.toString());
			}
		});
		return lines;
	}

	/**
	 * Returns the path of the language line of a locale.
	 *
	 * @param locale the locale
	 * @return the path
	 */
	@Nonnull
	private static String languagePath(@Nonnull Locale locale) {
		return "language/" + locale.toLanguageTag();
	}

	/**
	 * Returns the path of the line of an attribute index - `kind/reference.attribute@locale`.
	 *
	 * @param kind the kind of the attribute index
	 * @param key  the key of the attribute index
	 * @return the path
	 */
	@Nonnull
	private static String path(@Nonnull String kind, @Nonnull AttributeIndexKey key) {
		return kind + "/" + key.referenceName() + "." + key.attributeName() +
			(key.locale() == null ? "" : "@" + key.locale().toLanguageTag());
	}

	/**
	 * Renders the elements of a bitmap the filter accepts.
	 *
	 * @param bitmap the bitmap
	 * @param filter accepts the elements to render
	 * @return the rendering, empty when no element is accepted
	 */
	@Nonnull
	private static String filter(@Nonnull Bitmap bitmap, @Nonnull IntPredicate filter) {
		final int[] accepted = Arrays.stream(bitmap.getArray()).filter(filter).toArray();
		return accepted.length == 0 ? "" : Arrays.toString(accepted);
	}

	/**
	 * Puts a line unless its value is empty.
	 *
	 * @param lines the lines
	 * @param path  the path of the line
	 * @param value the value of the line
	 */
	private static void putIfAny(
		@Nonnull SortedMap<String, String> lines,
		@Nonnull String path,
		@Nullable String value
	) {
		if (value != null && !value.isEmpty()) {
			lines.put(path, value);
		}
	}
}
