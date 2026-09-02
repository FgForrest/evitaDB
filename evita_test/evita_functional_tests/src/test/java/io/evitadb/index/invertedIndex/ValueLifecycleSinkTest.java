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

package io.evitadb.index.invertedIndex;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the seam a value-id-keyed consumer is maintained through: that {@link ValueLifecycleSink} hears about a
 * distinct value exactly when the shared value tree brings one into existence or takes one out of it, hears about
 * NOTHING when a write merely joins or leaves an existing value, and is handed the normalized form of the value
 * together with the id the tree stamped it with.
 *
 * The zero-notification cases are the load-bearing ones. Churn over values that already exist is the overwhelming
 * majority of what a write path does, and the whole point of keying a substring index by value id rather than by
 * entity primary key is that such churn costs the consumer nothing at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Value lifecycle reporting of the shared value tree")
class ValueLifecycleSinkTest {

	/**
	 * Stands in for the trigram substring index — the seam takes a name, so a test consumer needs no production type.
	 */
	private static final String TEST_CONSUMER = "value-lifecycle-sink-test";

	/**
	 * A normalizer that visibly rewrites its input, so a test can tell whether the sink was handed the value the
	 * caller passed in or the one the tree actually stores.
	 */
	private static final Function<Object, Serializable> UPPER_CASING = value -> ((String) value).toUpperCase();

	/**
	 * @return a fresh tree of `String` values that already carries value ids
	 */
	@Nonnull
	private static InvertedIndex treeWithIds() {
		final InvertedIndex tree = new InvertedIndex(
			String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
		);
		tree.attachValueIdConsumer(TEST_CONSUMER);
		return tree;
	}

	/**
	 * Builds the fixture the descent-cost assertions measure: an id-carrying tree holding `alpha` over three records
	 * and `beta` over one, so a removal from the first leaves its value alive while a removal from the second kills
	 * it. The comparator is the caller's, and the returned tree has already been populated through it — the caller
	 * resets its count before the removal it wants to price.
	 *
	 * @param comparator the counting comparator this tree orders its values by
	 * @return the populated tree, carrying value ids
	 */
	@Nonnull
	private static InvertedIndex countingTreeWithIds(@Nonnull Comparator<Comparable<?>> comparator) {
		final InvertedIndex tree = new InvertedIndex(
			String.class, FilterIndex.NO_NORMALIZATION, comparator, 0
		);
		tree.attachValueIdConsumer(TEST_CONSUMER);
		tree.addRecord("alpha", 1, 2, 3);
		tree.addRecord("beta", 4);
		return tree;
	}

	/**
	 * @param attributeIndexKey the key the filter structure is filed under
	 * @return an owner filter index over `String[]` whose shared value tree already carries value ids
	 */
	@Nonnull
	private static FilterIndex arrayFilterIndexWithIds(@Nonnull AttributeIndexKey attributeIndexKey) {
		final FilterIndex filterIndex = new OwnerFilterIndex(attributeIndexKey, String[].class);
		filterIndex.getInvertedIndex().attachValueIdConsumer(TEST_CONSUMER);
		return filterIndex;
	}

	@Nested
	@DisplayName("a birth is reported, and only a birth")
	class Births {

		@Test
		@DisplayName("a value the tree has never held is reported once, with the id it was stamped with")
		void shouldReportANewValueOnce() {
			final InvertedIndex tree = treeWithIds();
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", 1, sink);
			assertEquals(List.of("created:" + tree.getValueId("alpha") + ":alpha"), sink.events);
		}

		@Test
		@DisplayName("a second record on an existing value reports nothing at all")
		void shouldReportNothingWhenARecordJoinsAnExistingValue() {
			// this is the property the whole value-id design rests on: churn over values that already exist must not
			// reach the consumer, so a substring index pays nothing for it
			final InvertedIndex tree = treeWithIds();
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", 1, sink);
			sink.events.clear();
			tree.addRecord("alpha", 2, sink);
			tree.addRecord("alpha", 3, sink);
			assertTrue(sink.events.isEmpty(), () -> "expected no notification, got " + sink.events);
		}

		@Test
		@DisplayName("a multi-record insert of one value reports it once")
		void shouldReportAMultiRecordInsertOnce() {
			final InvertedIndex tree = treeWithIds();
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", sink, 1, 2, 3);
			assertEquals(1, sink.events.size(), () -> "expected exactly one notification, got " + sink.events);
			assertEquals("created:" + tree.getValueId("alpha") + ":alpha", sink.events.get(0));
		}

		@Test
		@DisplayName("a value carrying an unpaired surrogate is reported like any other")
		void shouldReportAValueCarryingAnUnpairedSurrogate() {
			// A birth is reported from inside the insert that caused it, and the id is resolved by RE-PROBING the tree
			// for the bucket just created. That makes this notification the place where a key column storing something
			// other than the value it was handed stops being a silent divergence and becomes a hard failure: the
			// re-probe misses, no id comes back, and the insert raises an internal error naming the value-id machinery
			// rather than the encoding. A lone UTF-16 surrogate is such a value - UTF-8 has no representation for one.
			final InvertedIndex tree = treeWithIds();
			final RecordingSink sink = new RecordingSink();
			final String value = "a\uD800c";
			tree.addRecord(value, 1, sink);
			assertEquals(List.of("created:" + tree.getValueId(value) + ":" + value), sink.events);
		}

		@Test
		@DisplayName("the sink is handed the NORMALIZED value, not the one the caller passed")
		void shouldReportTheNormalizedValue() {
			// the consumer indexes what the tree stores, and the query path normalizes its pattern the same way, so
			// handing over the raw value would make the two disagree on every attribute whose normalizer does anything
			final InvertedIndex tree = new InvertedIndex(
				String.class, UPPER_CASING, Comparator.naturalOrder(), 0
			);
			tree.attachValueIdConsumer(TEST_CONSUMER);
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", 1, sink);
			assertEquals(List.of("created:" + tree.getValueId("alpha") + ":ALPHA"), sink.events);
		}

		@Test
		@DisplayName("reporting a birth on a tree without value ids is refused")
		void shouldRefuseReportingOnATreeWithoutValueIds() {
			// a sink can only be fed ids by a tree that mints them; a tree that does not would report zero for every
			// value, and a consumer keyed by that would collapse the whole attribute onto one id
			final InvertedIndex tree = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.addRecord("alpha", 1, new RecordingSink())
			);
			assertTrue(
				error.getPrivateMessage().contains("carries no value id"),
				"the refusal must say the tree carries no ids, but was: " + error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("an array write reports every distinct element it brings into existence")
		void shouldReportEveryDistinctValueOfAnArrayWrite() {
			// the array arm loops the tree's single-value insert per element, so the sink is told once PER ELEMENT -
			// a contract stated on this interface and asserted nowhere at this level. The repeat is the discriminating
			// half: a consumer that heard "one birth and one silent add" would look identical from the postings alone
			final FilterIndex filterIndex = arrayFilterIndexWithIds(new AttributeIndexKey(null, "tags", null));
			final RecordingSink sink = new RecordingSink();

			filterIndex.addRecord(1, new String[]{"alpha", "beta", "alpha"}, sink);

			final InvertedIndex tree = filterIndex.getInvertedIndex();
			assertEquals(
				List.of(
					"created:" + tree.getValueId("alpha") + ":alpha",
					"created:" + tree.getValueId("beta") + ":beta"
				),
				sink.events,
				"exactly one notification per distinct element, in element order"
			);
		}

	}

	@Nested
	@DisplayName("a death is reported, and only a death")
	class Deaths {

		@Test
		@DisplayName("the loss of a value's last record is reported with the id it held")
		void shouldReportAValueLosingItsLastRecord() {
			final InvertedIndex tree = treeWithIds();
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", 1, sink);
			final int valueId = tree.getValueId("alpha");
			sink.events.clear();
			tree.removeRecord("alpha", sink, 1);
			assertEquals(List.of("removed:" + valueId + ":alpha"), sink.events);
		}

		@Test
		@DisplayName("a removal that leaves the value alive reports nothing")
		void shouldReportNothingWhileTheValueSurvives() {
			final InvertedIndex tree = treeWithIds();
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", sink, 1, 2);
			sink.events.clear();
			tree.removeRecord("alpha", sink, 1);
			assertTrue(sink.events.isEmpty(), () -> "expected no notification, got " + sink.events);
			assertEquals(1, tree.getRecordsEqualTo("alpha").size());
		}

		@Test
		@DisplayName("a removal of a value the tree does not hold reports nothing")
		void shouldReportNothingForAValueTheTreeDoesNotHold() {
			final InvertedIndex tree = treeWithIds();
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", 1, sink);
			sink.events.clear();
			tree.removeRecord("beta", sink, 1);
			assertTrue(sink.events.isEmpty(), () -> "expected no notification, got " + sink.events);
		}

		@Test
		@DisplayName("reporting a death on a tree without value ids is refused")
		void shouldRefuseReportingADeathOnATreeWithoutValueIds() {
			// symmetric with the birth refusal: a tree that mints no ids would report the unassigned id for every
			// value that dies, and a consumer acting on that would drop whatever happens to hold id zero
			final InvertedIndex tree = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			tree.addRecord("alpha", 1);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.removeRecord("alpha", new RecordingSink(), 1)
			);
			assertTrue(
				error.getPrivateMessage().contains("carried no value id"),
				"the refusal must say the bucket carried no id, but was: " + error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("the reported value is the NORMALIZED one here too")
		void shouldReportTheNormalizedValueOnDeath() {
			final InvertedIndex tree = new InvertedIndex(
				String.class, UPPER_CASING, Comparator.naturalOrder(), 0
			);
			tree.attachValueIdConsumer(TEST_CONSUMER);
			final RecordingSink sink = new RecordingSink();
			tree.addRecord("alpha", 1, sink);
			final int valueId = tree.getValueId("alpha");
			sink.events.clear();
			tree.removeRecord("alpha", sink, 1);
			assertEquals(List.of("removed:" + valueId + ":ALPHA"), sink.events);
		}

		@Test
		@DisplayName("a sink costs the removal no extra descent, whether the value survives or dies")
		void shouldResolveTheIdWithoutASecondDescent() {
			// the id a death is reported with used to be read by a full tree descent BEFORE every sinked removal,
			// including the overwhelmingly common one where the value survives and the sink is told nothing at all.
			// The tree now hands the id back out of the removal's own descent, so a sinked removal descends exactly
			// as often as an unsinked one - which is what the seam documents, and what makes churn over existing
			// values free. The comparator counts the descents: the tree compares only while descending. The two trees
			// are built identically and driven through the same removals, so the counts are comparable one for one -
			// which they would not be across two different values of ONE tree, whose keys sit at different depths
			final CountingComparator sinklessComparator = new CountingComparator();
			final InvertedIndex sinklessTree = countingTreeWithIds(sinklessComparator);
			final CountingComparator sinkedComparator = new CountingComparator();
			final InvertedIndex sinkedTree = countingTreeWithIds(sinkedComparator);
			final RecordingSink sink = new RecordingSink();
			final int dyingValueId = sinkedTree.getValueId("beta");

			// a removal the value survives: three records share "alpha", so taking one out leaves the bucket alive
			sinklessComparator.comparisons = 0;
			sinklessTree.removeRecord("alpha", 1);
			sinkedComparator.comparisons = 0;
			sinkedTree.removeRecord("alpha", sink, 1);

			assertTrue(sink.events.isEmpty(), () -> "the value survived the removal, got " + sink.events);
			assertEquals(
				sinklessComparator.comparisons, sinkedComparator.comparisons,
				"a removal that reports nothing must not pay a descent to resolve an id it never uses"
			);

			// the death branch is the only one that consumes the id, and it must not buy a descent for it either:
			// "beta" holds a lone record, so removing it deletes the bucket and the sink hears about it
			sinklessComparator.comparisons = 0;
			sinklessTree.removeRecord("beta", 4);
			sinkedComparator.comparisons = 0;
			sinkedTree.removeRecord("beta", sink, 4);

			assertEquals(List.of("removed:" + dyingValueId + ":beta"), sink.events);
			assertEquals(
				sinklessComparator.comparisons, sinkedComparator.comparisons,
				"reporting a death must ride on the removal's own descent, not buy a second one"
			);
		}

	}

	@Nested
	@DisplayName("the value walk hands out every value with its id")
	class ValueWalk {

		@Test
		@DisplayName("every distinct value is handed out once, with the id it was stamped with")
		void shouldWalkEveryValueWithItsId() {
			// this is how a consumer rebuilds itself from a tree that has just come back from disk, and it has one
			// covering test today - a refusal, reached indirectly. The positive shape is what a load really runs
			final InvertedIndex tree = treeWithIds();
			tree.addRecord("gamma", 1);
			tree.addRecord("alpha", 2);
			tree.addRecord("beta", 3);
			tree.addRecord("alpha", 4);

			final List<String> walked = new ArrayList<>(4);
			tree.forEachValueId((value, valueId) -> walked.add(valueId + ":" + value));

			assertEquals(
				List.of(
					tree.getValueId("alpha") + ":alpha",
					tree.getValueId("beta") + ":beta",
					tree.getValueId("gamma") + ":gamma"
				),
				walked,
				"a value held by two records is still one value, handed out once"
			);
		}

		@Test
		@DisplayName("the walk runs in ascending value order")
		void shouldWalkInAscendingValueOrder() {
			// the ascending order is a promise the method's own contract makes and nothing depends on yet, which is
			// precisely why it would rot unnoticed: the rebuild it serves today is order-insensitive, the next
			// consumer may not be
			final InvertedIndex tree = treeWithIds();
			for (final String value : new String[]{"delta", "alpha", "charlie", "bravo"}) {
				tree.addRecord(value, 1);
			}

			final List<String> walked = new ArrayList<>(4);
			tree.forEachValueId((value, valueId) -> walked.add((String) value));

			assertEquals(List.of("alpha", "bravo", "charlie", "delta"), walked);
		}

	}

	/**
	 * Counts every comparison the tree it is handed to makes. The tree compares only while descending to a bucket, so
	 * on a fixture of one fixed shape the count is a stable proxy for how many descents one write performed.
	 */
	private static final class CountingComparator implements Comparator<Comparable<?>> {

		/**
		 * How many comparisons have been made since this field was last reset by the test.
		 */
		private int comparisons;

		@SuppressWarnings({"unchecked", "rawtypes"})
		@Override
		public int compare(Comparable<?> left, Comparable<?> right) {
			this.comparisons++;
			return ((Comparable) left).compareTo(right);
		}

	}

	/**
	 * Records what it is told, in the order it is told, so a test can assert both the content and the ABSENCE of
	 * notifications — the latter being what the zero-cost-churn property comes down to.
	 */
	private static final class RecordingSink implements ValueLifecycleSink {

		/**
		 * One `kind:valueId:value` line per notification.
		 */
		private final List<String> events = new ArrayList<>(4);

		@Override
		public void valueCreated(int valueId, @Nonnull Serializable normalizedValue) {
			this.events.add("created:" + valueId + ":" + normalizedValue);
		}

		@Override
		public void valueRemoved(int valueId, @Nonnull Serializable normalizedValue) {
			this.events.add("removed:" + valueId + ":" + normalizedValue);
		}

	}

}
