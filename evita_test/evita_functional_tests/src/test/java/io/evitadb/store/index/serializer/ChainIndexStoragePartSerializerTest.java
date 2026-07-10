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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.index.attribute.ChainIndex.ElementState;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and lazy-upgrade coverage for {@link ChainIndexStoragePartSerializer} (the slim per-chain format) and the
 * preserved {@link ChainIndexStoragePartSerializer_2026_1} (the 2026.1 released pre-slimming fat format). Besides
 * direct serializer round-trips, the suite flushes live {@link ChainIndex} instances through
 * {@link ChainIndex#appendStorageParts},
 * serializes the resulting part through the production dispatcher, reloads it via the four-arg {@link ChainIndex}
 * constructor and asserts the reconstructed chain matches the original on element order and consistency — including an
 * inconsistent multi-run chain and a circular chain, where the head's predecessor/state are the only non-derivable
 * data the slim format carries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ChainIndexStoragePartSerializer round-trip (slim per-chain format)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(SERIALIZATION)
class ChainIndexStoragePartSerializerTest {
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "order", null);
	/** The pre-slimming serial-version-uid of {@link ChainIndexStoragePart} (kept registered). */
	private static final long LEGACY_2026_1_UID = 8894604958733971199L;

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a storage part directly from explicit chains + element states with a fixed storage part id.
	 *
	 * @param chains        the chain runs
	 * @param elementStates the per-element states
	 * @return the storage part (with storage part id set)
	 */
	@Nonnull
	private static ChainIndexStoragePart part(
		@Nonnull int[][] chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		return new ChainIndexStoragePart(1, ATTRIBUTE_KEY, elementStates, chains, 7L);
	}

	/**
	 * Asserts the two storage parts carry identical chains and element states.
	 *
	 * @param expected the original part
	 * @param actual   the reconstructed part
	 */
	private static void assertPartEquals(
		@Nonnull ChainIndexStoragePart expected,
		@Nonnull ChainIndexStoragePart actual
	) {
		assertEquals(expected.getEntityIndexPrimaryKey(), actual.getEntityIndexPrimaryKey());
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK());
		assertEquals(expected.getAttributeIndexKey(), actual.getAttributeIndexKey());
		assertEquals(expected.isPaged(), actual.isPaged(), "paged discriminator");
		assertEquals(expected.getHighWaterPageSequence(), actual.getHighWaterPageSequence(), "high-water page sequence");
		assertArrayEquals(expected.getPageSequences(), actual.getPageSequences(), "page sequences");
		final int[][] expectedChains = expected.getChains();
		final int[][] actualChains = actual.getChains();
		assertEquals(expectedChains.length, actualChains.length, "chain count");
		for (int i = 0; i < expectedChains.length; i++) {
			assertArrayEquals(expectedChains[i], actualChains[i], "chain run #" + i);
		}
		assertEquals(expected.getElementStates(), actual.getElementStates(), "element states");
	}

	@Nested
	@DisplayName("Direct serializer round-trip")
	class DirectRoundTrip {

		@Test
		@DisplayName("round-trips a single consistent chain through the production dispatcher")
		void shouldRoundTripSingleChain() {
			final int[][] chains = {{10, 20, 30}};
			final Map<Integer, ChainElementState> states = Map.of(
				10, new ChainElementState(10, ChainableType.HEAD_PK, ElementState.HEAD),
				20, new ChainElementState(10, 10, ElementState.SUCCESSOR),
				30, new ChainElementState(10, 20, ElementState.SUCCESSOR)
			);
			final ChainIndexStoragePart original = part(chains, states);

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, original, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		@Test
		@DisplayName("round-trips multiple chains (multi-run, inconsistent) through the production dispatcher")
		void shouldRoundTripMultipleChains() {
			// two runs whose heads carry distinct predecessor/state — exactly the non-derivable data the slim format keeps
			final int[][] chains = {{1, 2}, {4, 5}};
			final Map<Integer, ChainElementState> states = Map.of(
				1, new ChainElementState(1, ChainableType.HEAD_PK, ElementState.HEAD),
				2, new ChainElementState(1, 1, ElementState.SUCCESSOR),
				// 4 is a head but a SUCCESSOR (orphan) pointing at a dangling predecessor 3
				4, new ChainElementState(4, 3, ElementState.SUCCESSOR),
				5, new ChainElementState(4, 4, ElementState.SUCCESSOR)
			);
			final ChainIndexStoragePart original = part(chains, states);

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, original, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		@Test
		@DisplayName("round-trips a circular chain head through the production dispatcher")
		void shouldRoundTripCircularChain() {
			final int[][] chains = {{2, 3, 1}};
			final Map<Integer, ChainElementState> states = Map.of(
				2, new ChainElementState(2, 1, ElementState.CIRCULAR),
				3, new ChainElementState(2, 2, ElementState.SUCCESSOR),
				1, new ChainElementState(2, 3, ElementState.SUCCESSOR)
			);
			final ChainIndexStoragePart original = part(chains, states);

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, original, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		@Test
		@DisplayName("round-trips an empty chain index")
		void shouldRoundTripEmpty() {
			final ChainIndexStoragePart original = part(new int[0][], Map.of());

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, original, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
			assertInstanceOf(ChainIndexStoragePart.class, deserialized);
		}
	}

	@Nested
	@DisplayName("Paged discriminator round-trip")
	class PagedRoundTrip {

		@Test
		@DisplayName("round-trips a PAGED root carrying only the page-stream metadata")
		void shouldRoundTripPagedRoot() {
			final int[] pageSequences = {0, 1, 4, 9};
			final ChainIndexStoragePart original = ChainIndexStoragePart.paged(
				1, ATTRIBUTE_KEY, 12, pageSequences, 7L
			);

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, original, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
			assertTrue(deserialized.isPaged(), "the reconstructed root must stay PAGED");
			assertEquals(12, deserialized.getHighWaterPageSequence());
			assertArrayEquals(pageSequences, deserialized.getPageSequencesOrThrowException());
			// a PAGED root carries no inline chain data - it is reconstructed from the leaf pages on load
			assertEquals(0, deserialized.getChains().length, "a PAGED root carries no chain runs");
			assertTrue(deserialized.getElementStates().isEmpty(), "a PAGED root carries no element states");
		}

		@Test
		@DisplayName("round-trips a PAGED root with an empty live-leaf list")
		void shouldRoundTripPagedRootWithNoLeaves() {
			final ChainIndexStoragePart original = ChainIndexStoragePart.paged(
				1, ATTRIBUTE_KEY, 0, new int[0], 7L
			);

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, original, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
			assertTrue(deserialized.isPaged());
			assertEquals(0, deserialized.getPageSequencesOrThrowException().length);
		}

		@Test
		@DisplayName("a SINGLE root still reports paged == false and round-trips unchanged")
		void shouldKeepSingleRootUnpaged() {
			final int[][] chains = {{10, 20, 30}};
			final Map<Integer, ChainElementState> states = Map.of(
				10, new ChainElementState(10, ChainableType.HEAD_PK, ElementState.HEAD),
				20, new ChainElementState(10, 10, ElementState.SUCCESSOR),
				30, new ChainElementState(10, 20, ElementState.SUCCESSOR)
			);
			final ChainIndexStoragePart original = part(chains, states);
			assertFalse(original.isPaged(), "a SINGLE root must report paged == false");

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, original, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
			assertFalse(deserialized.isPaged(), "the reconstructed SINGLE root must stay unpaged");
		}
	}

	@Nested
	@DisplayName("Lazy upgrade from the pre-slimming format")
	class LazyUpgrade {

		@Test
		@DisplayName("reads a pre-slimming fat blob through the dispatcher and reconstructs the same part")
		void shouldReadPreSlimmingFatFormat() {
			final int[][] chains = {{10, 20, 30}};
			final Map<Integer, ChainElementState> states = Map.of(
				10, new ChainElementState(10, ChainableType.HEAD_PK, ElementState.HEAD),
				20, new ChainElementState(10, 10, ElementState.SUCCESSOR),
				30, new ChainElementState(10, 20, ElementState.SUCCESSOR)
			);
			final ChainIndexStoragePart original = part(chains, states);

			final byte[] legacyBytes = encodePreSlimmingBytes(original);

			final ChainIndexStoragePart deserialized = StoragePartSerializerTestSupport.decode(
				ChainIndexStoragePartSerializerTest.this.kryo, legacyBytes, ChainIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		/**
		 * Hand-encodes the 2026.1 released pre-slimming fat format for the given part (uid-prefixed), mirroring the
		 * dropped 2026.1 writer's wire exactly so the production dispatcher routes it to the registered 2026.1 reader.
		 * The preserved {@link ChainIndexStoragePartSerializer_2026_1} is the frozen prior-production reader; its write
		 * path deliberately throws, so the legacy blob is reproduced here by hand rather than generated by it.
		 *
		 * @param part the storage part to encode in the pre-slimming format
		 * @return the legacy-format bytes (uid-prefixed)
		 */
		@Nonnull
		private byte[] encodePreSlimmingBytes(@Nonnull ChainIndexStoragePart part) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				// the SerialVersionBasedSerializer prefixes every payload with the serializer uid as a plain 8-byte long
				output.writeLong(LEGACY_2026_1_UID);
				output.writeInt(part.getEntityIndexPrimaryKey());
				output.writeVarLong(part.getStoragePartPK(), true);
				output.writeVarInt(
					ChainIndexStoragePartSerializerTest.this.keyCompressor.getId(part.getAttributeIndexKey()), true
				);

				final Map<Integer, ChainElementState> elementStates = part.getElementStates();
				output.writeVarInt(elementStates.size(), true);
				for (final Map.Entry<Integer, ChainElementState> entry : elementStates.entrySet()) {
					output.writeInt(entry.getKey());
					final ChainElementState state = entry.getValue();
					output.writeInt(state.inChainOfHeadWithPrimaryKey());
					output.writeInt(state.predecessorPrimaryKey());
					output.writeInt(state.state().ordinal());
				}

				final int[][] chains = part.getChains();
				output.writeVarInt(chains.length, true);
				for (final int[] chain : chains) {
					output.writeVarInt(chain.length, true);
					output.writeInts(chain, 0, chain.length);
				}
			}
			return os.toByteArray();
		}
	}

	@Nested
	@DisplayName("Flush via appendStorageParts then reload")
	class FlushAndReload {

		@Test
		@DisplayName("an inconsistent multi-run chain survives flush + slim serialize + reload")
		void shouldSurviveFlushReloadForInconsistentChain() {
			final ChainIndex index = new ChainIndex(ATTRIBUTE_KEY);
			// build a consistent chain 1<-2<-3 then repoint the middle at an absent predecessor 99 to split it
			index.upsertPredecessor(Predecessor.HEAD, 1);
			index.upsertPredecessor(new Predecessor(1), 2);
			index.upsertPredecessor(new Predecessor(2), 3);
			index.upsertPredecessor(new Predecessor(99), 2);
			assertEquals(ConsistencyState.INCONSISTENT, index.getConsistencyReport().state());

			final ChainIndexStoragePart reloadedPart = flushAndReload(index);
			final ChainIndex reloaded = new ChainIndex(
				ATTRIBUTE_KEY, reloadedPart.getChains(), reloadedPart.getElementStates()
			);

			assertEquals(
				ConsistencyState.INCONSISTENT, reloaded.getConsistencyReport().state(),
				() -> "reloaded chain consistency drifted: " + reloaded.getConsistencyReport()
			);
			assertArrayEquals(
				index.getUnorderedLookup().getArray(), reloaded.getUnorderedLookup().getArray(),
				"reloaded element order must match the original"
			);
			// the split head 2 keeps its dangling predecessor 99 and SUCCESSOR flag verbatim across the round-trip;
			// element states are inspected on the reconstructed (public) storage part
			final ChainElementState splitHead = reloadedPart.getElementStates().get(2);
			assertNotNull(splitHead);
			assertEquals(99, splitHead.predecessorPrimaryKey());
			assertEquals(ElementState.SUCCESSOR, splitHead.state());
		}

		@Test
		@DisplayName("a circular chain survives flush + slim serialize + reload")
		void shouldSurviveFlushReloadForCircularChain() {
			final ChainIndex index = new ChainIndex(ATTRIBUTE_KEY);
			// close a loop 1->2->3->1 — a single circular (semi-consistent) chain
			index.upsertPredecessor(Predecessor.HEAD, 1);
			index.upsertPredecessor(new Predecessor(1), 2);
			index.upsertPredecessor(new Predecessor(2), 3);
			index.upsertPredecessor(new Predecessor(3), 1);

			final ChainIndexStoragePart reloadedPart = flushAndReload(index);
			final ChainIndex reloaded = new ChainIndex(
				ATTRIBUTE_KEY, reloadedPart.getChains(), reloadedPart.getElementStates()
			);

			assertEquals(
				index.getConsistencyReport().state(), reloaded.getConsistencyReport().state(),
				() -> "reloaded circular chain consistency drifted: " + reloaded.getConsistencyReport()
			);
			assertArrayEquals(
				index.getUnorderedLookup().getArray(), reloaded.getUnorderedLookup().getArray(),
				"reloaded element order must match the original"
			);
		}

		/**
		 * Flushes a live index to a storage part, serializes it through the production dispatcher and reads it back —
		 * yielding the reconstructed part whose chains + element states feed the load path (the four-arg
		 * {@link ChainIndex} constructor).
		 *
		 * @param index the live index to flush
		 * @return the reconstructed storage part
		 */
		@Nonnull
		private ChainIndexStoragePart flushAndReload(@Nonnull ChainIndex index) {
			final TrappedChanges trappedChanges = new TrappedChanges();
			index.appendStorageParts(1, trappedChanges);
			assertEquals(
				1, trappedChanges.getTrappedChangesCount(),
				"a dirty single-leaf chain must emit exactly one SINGLE part"
			);
			final ChainIndexStoragePart part =
				(ChainIndexStoragePart) trappedChanges.getTrappedChangesIterator().next();
			part.setStoragePartPK(7L);

			return StoragePartSerializerTestSupport.roundTrip(
				ChainIndexStoragePartSerializerTest.this.kryo, part, ChainIndexStoragePart.class
			);
		}
	}

}
