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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;

import javax.annotation.Nonnull;

/**
 * A reference partition read through the registry of single-owner partitions ({@link VirtualReferencePartitionIndex})
 * rather than through a {@link ReducedEntityIndex}: a single-owner partition is fully described by its primary key, its
 * key, its only owner and the referenced-type entity index carrying its row, so it needs no index object of its own.
 *
 * The handle is a value: it copies what the registry holds of the partition at the moment it is resolved and refers
 * to the referenced-type entity index of that moment, so a query that resolved it keeps reading the snapshot it planned
 * against. It is created by the read resolvers that honour the read switch of the collection (see
 * {@link ReferencePartitions}) and by the write acquisition of a mutation that writes the single-owner partitions of
 * the reference virtually, for a partition that has its registry row and no index object. A write path that takes a
 * virtual partition writes what the partition holds - its sort values and chain predecessors - into the registry row
 * only, and leaves out what a partition of a reference indexed for filtering keeps nowhere (language records, facets);
 * every other exhaustive `switch` over {@link ReferencePartition} on a write path rejects it, and so does every write
 * path of a mutation that does not write the partition virtually.
 *
 * While the reduced entity index of a registered partition still exists, the handle and the index describe the same
 * partition under the same primary key - the read switch only decides which of the two a query reads.
 *
 * @param primaryKey                 the primary key of the partition, the one the reduced entity index of the
 *                                   partition has too
 * @param indexKey                   the key of the partition, carrying its scope and representative reference key
 * @param representativeReferenceKey the representative reference key of the partition - the same instance the
 *                                   `indexKey` carries as its discriminator
 * @param ownerPrimaryKey            the primary key of the partition's only owner
 * @param typeIndex                  the referenced-type entity index of the reference in the partition's scope, which
 *                                   carries the partition's row
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record VirtualReferencePartition(
	int primaryKey,
	@Nonnull EntityIndexKey indexKey,
	@Nonnull RepresentativeReferenceKey representativeReferenceKey,
	int ownerPrimaryKey,
	@Nonnull ReferencedTypeEntityIndex typeIndex
) implements ReferencePartition {

	/**
	 * Checks that the key carries the representative reference key. The key itself is built by
	 * {@link ReferencePartitions}, the only place a partition key is built.
	 *
	 * @param primaryKey                 the primary key of the partition
	 * @param indexKey                   the key of the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @param ownerPrimaryKey            the primary key of the partition's only owner
	 * @param typeIndex                  the referenced-type entity index carrying the partition's row
	 */
	public VirtualReferencePartition {
		// a plain branch rather than an assertion with a message, so the valid path builds no string
		if (indexKey.discriminator() != representativeReferenceKey) {
			throw new GenericEvitaInternalError(
				"Virtual partition " + primaryKey + " must be keyed by its own representative reference key `" +
					representativeReferenceKey + "`, but its key is `" + indexKey + "`!"
			);
		}
	}

	@Override
	public int getPrimaryKey() {
		return this.primaryKey;
	}

	@Nonnull
	@Override
	public EntityIndexKey getIndexKey() {
		return this.indexKey;
	}

	@Nonnull
	@Override
	public RepresentativeReferenceKey getRepresentativeReferenceKey() {
		return this.representativeReferenceKey;
	}

	@Nonnull
	@Override
	public ReferenceKey getReferenceKey() {
		return this.representativeReferenceKey.referenceKey();
	}

	/**
	 * {@inheritDoc}
	 *
	 * The partition has exactly one owner, so the result is a read-only bitmap of that owner, created per call.
	 */
	@Nonnull
	@Override
	public Bitmap getAllPrimaryKeys() {
		return new SingleRecordBitmap(this.ownerPrimaryKey);
	}

	/**
	 * {@inheritDoc}
	 *
	 * The formula is a constant over the only owner, identified by its content - it carries no transactional
	 * dependency, because the owner it names is a value copied out of the registry when the handle was resolved.
	 */
	@Nonnull
	@Override
	public Formula getAllPrimaryKeysFormula() {
		return new ConstantFormula(getAllPrimaryKeys());
	}

	/**
	 * {@inheritDoc}
	 *
	 * A virtual partition is a single-owner partition by definition.
	 */
	@Override
	public int size() {
		return 1;
	}

}
