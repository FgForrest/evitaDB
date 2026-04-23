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

package io.evitadb.core.query.extraResult.translator.histogram.cache;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CacheableHistogram}. Exercises:
 *
 * - constructor validation (empty buckets, non-monotonic thresholds, max-below-last-threshold);
 * - the two `convertToHistogram` overloads (plain and the boundary-entity variant).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CacheableHistogram")
class CacheableHistogramTest {

	private static final Predicate<BigDecimal> NEVER_REQUESTED = value -> false;
	private static final Predicate<BigDecimal> ALWAYS_REQUESTED = value -> true;

	/**
	 * Produces a three-bucket histogram with thresholds `{1, 5, 10}` and `max = 20`, populated with
	 * predictable occurrence and relative frequency values. Used as a shared fixture by the
	 * conversion tests.
	 *
	 * @return a fresh, non-null `CacheableHistogram` instance
	 */
	@Nonnull
	private static CacheableHistogram sampleHistogram() {
		final CacheableBucket[] buckets = new CacheableBucket[]{
			new CacheableBucket(new BigDecimal("1"), 3, new BigDecimal("30")),
			new CacheableBucket(new BigDecimal("5"), 5, new BigDecimal("50")),
			new CacheableBucket(new BigDecimal("10"), 2, new BigDecimal("20"))
		};
		return new CacheableHistogram(buckets, new BigDecimal("20"));
	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidation {

		@Test
		@DisplayName("should throw IllegalArgumentException when buckets array is empty")
		void shouldThrowIllegalArgumentExceptionWhenBucketsAreEmpty() {
			assertThrows(
				IllegalArgumentException.class,
				() -> new CacheableHistogram(new CacheableBucket[0], BigDecimal.TEN)
			);
		}

		@Test
		@DisplayName("should throw IllegalArgumentException when last bucket threshold exceeds max")
		void shouldThrowIllegalArgumentExceptionWhenLastThresholdExceedsMax() {
			final CacheableBucket[] buckets = new CacheableBucket[]{
				new CacheableBucket(new BigDecimal("1"), 1, BigDecimal.ZERO),
				new CacheableBucket(new BigDecimal("100"), 1, BigDecimal.ZERO)
			};
			assertThrows(
				IllegalArgumentException.class,
				() -> new CacheableHistogram(buckets, new BigDecimal("50"))
			);
		}

		@Test
		@DisplayName("should throw IllegalArgumentException when two adjacent thresholds are equal")
		void shouldThrowIllegalArgumentExceptionWhenAdjacentThresholdsAreEqual() {
			final CacheableBucket[] buckets = new CacheableBucket[]{
				new CacheableBucket(new BigDecimal("5"), 1, BigDecimal.ZERO),
				new CacheableBucket(new BigDecimal("5"), 1, BigDecimal.ZERO)
			};
			assertThrows(
				IllegalArgumentException.class,
				() -> new CacheableHistogram(buckets, new BigDecimal("10"))
			);
		}

		@Test
		@DisplayName("should throw IllegalArgumentException when thresholds are decreasing")
		void shouldThrowIllegalArgumentExceptionWhenThresholdsDecreasing() {
			final CacheableBucket[] buckets = new CacheableBucket[]{
				new CacheableBucket(new BigDecimal("10"), 1, BigDecimal.ZERO),
				new CacheableBucket(new BigDecimal("5"), 1, BigDecimal.ZERO)
			};
			assertThrows(
				IllegalArgumentException.class,
				() -> new CacheableHistogram(buckets, new BigDecimal("20"))
			);
		}

		@Test
		@DisplayName("should accept single bucket where threshold equals max")
		void shouldAcceptSingleBucketWhereThresholdEqualsMax() {
			final CacheableBucket[] buckets = new CacheableBucket[]{
				new CacheableBucket(new BigDecimal("10"), 5, new BigDecimal("100"))
			};
			final CacheableHistogram histogram = new CacheableHistogram(buckets, new BigDecimal("10"));

			assertEquals(new BigDecimal("10"), histogram.getMin());
			assertEquals(new BigDecimal("10"), histogram.getMax());
			assertEquals(5, histogram.getOverallCount());
		}
	}

	@Nested
	@DisplayName("Simple convertToHistogram")
	class ConvertToHistogramSimple {

		@Test
		@DisplayName("should preserve thresholds, occurrences and max when converting to HistogramContract")
		void shouldPreserveBucketContentsWhenConverting() {
			final CacheableHistogram source = sampleHistogram();

			final HistogramContract result = source.convertToHistogram(NEVER_REQUESTED);

			assertNotNull(result);
			assertEquals(new BigDecimal("1"), result.getMin());
			assertEquals(new BigDecimal("20"), result.getMax());
			assertEquals(10, result.getOverallCount(), "overall count must be 3 + 5 + 2");

			final Bucket[] buckets = result.getBuckets();
			assertEquals(3, buckets.length);
			assertEquals(new BigDecimal("1"), buckets[0].threshold());
			assertEquals(3, buckets[0].occurrences());
			assertEquals(new BigDecimal("10"), buckets[2].threshold());
			assertEquals(2, buckets[2].occurrences());
		}

		@Test
		@DisplayName("should flip requested=true for buckets the predicate accepts")
		void shouldApplyPredicateToMarkRequested() {
			final CacheableHistogram source = sampleHistogram();

			final HistogramContract result = source.convertToHistogram(
				threshold -> threshold.compareTo(new BigDecimal("3")) >= 0
			);

			final Bucket[] buckets = result.getBuckets();
			// threshold 1 -> not requested; thresholds 5 and 10 -> requested
			assertFalse(buckets[0].requested());
			assertTrue(buckets[1].requested());
			assertTrue(buckets[2].requested());
		}

		@Test
		@DisplayName("should mark all buckets requested when predicate always returns true")
		void shouldMarkAllRequestedWhenPredicateAlwaysTrue() {
			final CacheableHistogram source = sampleHistogram();

			final HistogramContract result = source.convertToHistogram(ALWAYS_REQUESTED);

			for (final Bucket bucket : result.getBuckets()) {
				assertTrue(bucket.requested(), "Every bucket must be requested when predicate is always true");
			}
		}
	}

	@Nested
	@DisplayName("Boundary-entity convertToHistogram")
	class ConvertToHistogramWithBoundaryEntities {

		@Test
		@DisplayName("should not attach boundary entities when both arguments are null")
		void shouldNotAttachBoundaryEntitiesWhenBothNull() {
			final CacheableHistogram source = sampleHistogram();

			final HistogramContract result = source.convertToHistogram(NEVER_REQUESTED, null, null);

			assertNotNull(result);
			final Optional<SealedEntity> min = result.getMinReferencedEntity();
			final Optional<SealedEntity> max = result.getMaxReferencedEntity();
			assertFalse(min.isPresent(), "min boundary entity must be absent when null was supplied");
			assertFalse(max.isPresent(), "max boundary entity must be absent when null was supplied");
		}

		@Test
		@DisplayName("should expose both boundary entities on the result when supplied")
		void shouldExposeBothBoundaryEntitiesWhenSupplied() {
			final CacheableHistogram source = sampleHistogram();
			final SealedEntity minEntity = mock(SealedEntity.class);
			final SealedEntity maxEntity = mock(SealedEntity.class);

			final HistogramContract result = source.convertToHistogram(
				NEVER_REQUESTED, minEntity, maxEntity
			);

			assertTrue(result.getMinReferencedEntity().isPresent());
			assertTrue(result.getMaxReferencedEntity().isPresent());
			assertSame(minEntity, result.getMinReferencedEntity().get());
			assertSame(maxEntity, result.getMaxReferencedEntity().get());
		}

		@Test
		@DisplayName("should preserve bucket contents alongside attached boundary entities")
		void shouldPreserveBucketContentsAlongsideBoundaryEntities() {
			final CacheableHistogram source = sampleHistogram();
			final SealedEntity minEntity = mock(SealedEntity.class);
			final SealedEntity maxEntity = mock(SealedEntity.class);

			final HistogramContract result = source.convertToHistogram(
				ALWAYS_REQUESTED, minEntity, maxEntity
			);

			assertEquals(source.getMin(), result.getMin());
			assertEquals(source.getMax(), result.getMax());
			assertEquals(source.getOverallCount(), result.getOverallCount());
			assertEquals(source.getBuckets().length, result.getBuckets().length);
		}
	}

	/**
	 * `CacheableHistogram` is serialized into the extra-result cache. This test pins one invariant:
	 * Java serialization round-trips preserve equality so cache deserialization reconstructs the
	 * same logical histogram. If the `@Serial` contract or the field layout ever diverges between
	 * cache-write and cache-read, this test catches it.
	 */
	@Nested
	@DisplayName("Serialization round-trip")
	class SerializationRoundTrip {

		/**
		 * Serializes and immediately deserializes the given histogram via the standard
		 * {@link ObjectOutputStream}/{@link ObjectInputStream} pair, returning the reconstructed
		 * instance. The round-trip exercises the `@Serial` contract the class carries for the
		 * extra-result cache's on-disk representation.
		 *
		 * @param source histogram to round-trip
		 * @return a new {@link CacheableHistogram} deserialized from the serialized form of `source`
		 * @throws Exception propagated I/O or class-load failures
		 */
		@Nonnull
		private static CacheableHistogram roundTrip(@Nonnull CacheableHistogram source) throws Exception {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
			try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
				oos.writeObject(source);
			}
			try (
				ObjectInputStream ois = new ObjectInputStream(
					new ByteArrayInputStream(baos.toByteArray())
				)
			) {
				return (CacheableHistogram) ois.readObject();
			}
		}

		@Test
		@DisplayName("should round-trip via Java serialization preserving equality")
		void shouldRoundTripPreservingEquality() throws Exception {
			final CacheableHistogram source = sampleHistogram();

			final CacheableHistogram round = roundTrip(source);

			assertNotSame(source, round, "Round-trip must produce a distinct instance");
			assertEquals(source, round, "Round-tripped instance must equal the original");
			assertEquals(source.hashCode(), round.hashCode(),
				"Equal instances must share hashCode (equals-hashCode contract)");
			// spot-check one bucket value survives the round-trip
			assertEquals(source.getBuckets()[0].threshold(), round.getBuckets()[0].threshold());
			assertEquals(source.getMax(), round.getMax());
		}
	}
}
