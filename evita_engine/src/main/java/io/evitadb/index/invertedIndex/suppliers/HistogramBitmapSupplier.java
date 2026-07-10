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

package io.evitadb.index.invertedIndex.suppliers;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Implementation of {@link BitmapSupplier} that provides access to the data stored in {@link InvertedIndex}
 * in a lazy fashion. The expensive computations happen in {@link #get()} method. This class is meant to be used in
 * combination with {@link DeferredFormula}.
 *
 * The formula cache identity is split across two independent axes:
 *
 * - **lookup hash** ({@link #getHash()}) - the strong 64-bit content hash of the ordered bucket *values* (each value
 *   folded through the {@link ValueHashStrategy} resolved once from the uniform bucket value type), so two
 *   suppliers over different value ranges of the same field never collide in the cache.
 * - **staleness** ({@link #gatherTransactionalIds()}) - the set of version ids of the leaf pages the histogram slice
 *   crossed. A commit that mutates a crossed page mints a fresh id for that page, invalidating exactly the cached
 *   ranges that read it; ranges over untouched pages stay valid. Slices spanning more than
 *   {@link TransactionalDataRelatedStructure#EXCESSIVE_HIGH_CARDINALITY} leaves collapse
 *   to the single whole-index id to bound the footprint.
 *
 * `HASH_FUNCTION` is inherited from {@link TransactionalDataRelatedStructure}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HistogramBitmapSupplier implements BitmapSupplier {
	private static final long CLASS_ID = 516692463222738021L;
	/**
	 * Ordered bucket slice this supplier serves: the bucket *values* form the cache lookup key and the bucket record
	 * sets are unioned by {@link #get()}.
	 */
	private final ValueToRecord[] histogramBuckets;
	/**
	 * Contains memoized result once {@link #get()} is invoked for the first time. Additional calls of
	 * {@link #get()} will return this memoized result without paying the computational costs
	 */
	protected Bitmap memoizedResult;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private final Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	private final Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceRatio()} of this formula.
	 */
	private final Long costToPerformance;
	/**
	 * Contains memoized value of {@link #getEstimatedCardinality()} of this formula.
	 */
	private final Integer estimatedCardinality;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private final Long hash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private final long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private final Long transactionalIdHash;

	/**
	 * Convenience constructor for a single whole-index staleness id — delegates to the leaf-granular
	 * {@link #HistogramBitmapSupplier(long[], ValueToRecord[])} with the one-element set `{indexTransactionId}`.
	 *
	 * @param indexTransactionId the owning {@link InvertedIndex}'s field-level transactional id, used as the sole
	 *                           staleness version of this histogram range
	 * @param histogramBuckets   the ordered bucket slice whose values form the lookup key and whose record sets
	 *                           {@link #get()} unions
	 */
	public HistogramBitmapSupplier(long indexTransactionId, @Nonnull ValueToRecord[] histogramBuckets) {
		this(new long[] {indexTransactionId}, histogramBuckets);
	}

	/**
	 * @param indexTransactionIds the canonical (sorted, deduplicated) set of version ids of the leaf pages this
	 *                            histogram slice crossed — so a cached range survives writes to other pages — or the
	 *                            single whole-index id when the slice spans too many leaves. This is the staleness
	 *                            version of the cached histogram range.
	 * @param histogramBuckets    the ordered bucket slice whose values form the lookup key and whose record sets
	 *                            {@link #get()} unions
	 */
	public HistogramBitmapSupplier(@Nonnull long[] indexTransactionIds, @Nonnull ValueToRecord[] histogramBuckets) {
		this.histogramBuckets = histogramBuckets;
		// LOOKUP key: CLASS_ID followed by a strong 64-bit content hash of EVERY bucket value, in bucket order.
		// Bucket order is already monotonic and is part of the cache identity, so it is kept (no sorting). This is the
		// sole per-bucket disambiguator between cached ranges of the same field.
		final long[] hashInput = new long[histogramBuckets.length + 1];
		hashInput[0] = CLASS_ID;
		if (histogramBuckets.length > 0) {
			// all buckets share one attribute value type, so the hashing strategy is resolved once from
			// the first bucket and then applied to every value
			final ValueHashStrategy valueHasher = ValueHashStrategy.forType(
				histogramBuckets[0].getValue().getClass()
			);
			for (int i = 0; i < histogramBuckets.length; i++) {
				hashInput[i + 1] = valueHasher.hash(HASH_FUNCTION, histogramBuckets[i].getValue());
			}
		}
		this.hash = HASH_FUNCTION.hashLongs(hashInput);
		int cardinality = 0;
		for (final ValueToRecord bucket : histogramBuckets) {
			cardinality += bucket.size();
		}
		this.estimatedCardinality = cardinality;
		this.estimatedCost = this.estimatedCardinality * getOperationCost();
		this.cost = this.estimatedCost;
		this.costToPerformance = getCost() / (get().size() * getOperationCost());
		// STALENESS version: the set of leaf-page version ids the slice crossed. A commit that mutates a crossed page
		// mints a fresh id for that page, invalidating exactly the cached ranges that read it; ranges over untouched
		// pages stay valid. The set is capped to the single whole-index id for slices spanning too many leaves.
		this.transactionalIds = indexTransactionIds;
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(indexTransactionIds);
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getHash() {
		Assert.isPremiseValid(this.hash != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		Assert.isPremiseValid(this.transactionalIdHash != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		Assert.isPremiseValid(this.transactionalIds != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		Assert.isPremiseValid(this.estimatedCost != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		Assert.isPremiseValid(this.cost != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		return 242;
	}

	@Override
	public long getCostToPerformanceRatio() {
		Assert.isPremiseValid(this.costToPerformance != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.costToPerformance;
	}

	@Override
	public int getEstimatedCardinality() {
		Assert.isPremiseValid(this.estimatedCardinality != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.estimatedCardinality;
	}

	@Override
	public Bitmap get() {
		if (this.memoizedResult == null) {
			final CompositeIntArray result = new CompositeIntArray();
			Arrays.stream(this.histogramBuckets)
				.map(ValueToRecord::getRecordIds)
				.map(Bitmap::getArray)
				.forEach(it -> result.addAll(it, 0, it.length));
			this.memoizedResult = new ArrayBitmap(result);
		}
		return this.memoizedResult;
	}

	/**
	 * The specialized 64-bit value-hashing strategy bound to the uniform bucket value type. All buckets in a
	 * single supplier belong to one attribute and share one runtime value type, so the strategy is resolved
	 * once from the first bucket and its specialized hash method is then applied to every value - avoiding the
	 * per-value type dispatch and the `toString()` round-trip on the primitive and `String` paths. A true
	 * 64-bit hash is mandatory because the value enters the formula-cache lookup identity only through it; a
	 * 32-bit `value.hashCode()` has findable structured collisions (e.g. `"Aa"`/`"BB"`) that would cause a
	 * wrong cache hit between two value ranges of the same unmutated field. The {@link #TO_STRING} fallback
	 * covers the exotic comparable types (`BigDecimal`, `Instant`, `ComparableCurrency`, `ComparableLocale`,
	 * `LocalDate`, `Range`, ...) hashed via a stable `toString()`.
	 */
	enum ValueHashStrategy {

		STRING {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashChars((String) value);
			}
		},
		INTEGER {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashInt((Integer) value);
			}
		},
		LONG {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashLong((Long) value);
			}
		},
		SHORT {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashShort((Short) value);
			}
		},
		BYTE {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashByte((Byte) value);
			}
		},
		CHARACTER {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashChar((Character) value);
			}
		},
		BOOLEAN {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashBoolean((Boolean) value);
			}
		},
		TO_STRING {
			@Override
			long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value) {
				return fn.hashChars(value.toString());
			}
		};

		/**
		 * Hashes a single normalized bucket value using this strategy's specialized 64-bit function.
		 *
		 * @param fn    the 64-bit hash function
		 * @param value the normalized bucket value, always of this strategy's bound type
		 * @return the 64-bit content hash of the value
		 */
		abstract long hash(@Nonnull LongHashFunction fn, @Nonnull Serializable value);

		/**
		 * Resolves the hashing strategy for the uniform bucket value type. Exotic or unanticipated comparable
		 * types fall back to a stable `toString()` hash.
		 *
		 * @param valueType the runtime type shared by every bucket value in the supplier
		 * @return the specialized hashing strategy for that type
		 */
		@Nonnull
		static ValueHashStrategy forType(@Nonnull Class<?> valueType) {
			if (valueType == String.class) {
				return STRING;
			} else if (valueType == Integer.class) {
				return INTEGER;
			} else if (valueType == Long.class) {
				return LONG;
			} else if (valueType == Short.class) {
				return SHORT;
			} else if (valueType == Byte.class) {
				return BYTE;
			} else if (valueType == Character.class) {
				return CHARACTER;
			} else if (valueType == Boolean.class) {
				return BOOLEAN;
			} else {
				return TO_STRING;
			}
		}
	}
}
