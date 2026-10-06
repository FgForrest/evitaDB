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
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;

/**
 * A reference partition is the rows of one `(scope, RepresentativeReferenceKey)` of a reference - the owners that
 * reference one target entity (with one combination of representative attribute values), together with whatever the
 * engine indexes about those rows. It is the unit a `referenceHaving` body, a pick-first sort or a
 * `referenceContent(filterBy/orderBy)` evaluates row by row. The word is unrelated to the
 * {@link io.evitadb.api.requestResponse.schema.ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} indexing level: a
 * reference indexed only `FOR_FILTERING` has partitions too, and they are the ones this handle exists for.
 *
 * **This type is the only way code outside the index layer is meant to reach a partition.** A partition has two
 * representations:
 *
 * - a materialized {@link ReducedEntityIndex}, which implements this interface directly, so a partition costs no
 *   wrapper object and keeps its identity - every partition has one, and every write reaches the partition through it;
 * - a {@link VirtualReferencePartition}, a value read out of the registry of single-owner partitions, which only the
 *   read resolvers honouring the collection's read switch hand out for a registered partition.
 *
 * The interface is sealed so that code which genuinely needs the object - a write that goes through the generic
 * {@link EntityIndex} write API, the query body channel that is bound to {@link Index} (see
 * {@link ReferencePartitions#toReducedEntityIndex}), a sort that reads the partition's sort or chain index - unwraps
 * it in an exhaustive `switch`, and the compiler lists every such place: the virtual arm of a write path throws, and so
 * does the virtual arm of a query route that does not read virtual partitions yet. Partitions are resolved through
 * {@link ReferencePartitions} and the resolver surfaces built on it; a guard test in the functional test suite rejects
 * new direct accesses to the reduced index family.
 *
 * The read surface is deliberately narrow: identity (primary key, key, representative key, target) and owners. The
 * sort and chain structures of a partition are not part of it - a virtual partition has none of its own, its values
 * live in the registry - so whoever reads them holds a {@link ReducedEntityIndex} and says so in its type. Nothing
 * returning the partition's filter or attribute indexes belongs here either.
 *
 * The group family ({@link ReducedGroupEntityIndex}) is not a reference partition and never will be.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public sealed interface ReferencePartition permits ReducedEntityIndex, VirtualReferencePartition {

	/**
	 * Returns the primary key of the partition. It is unique within the entity collection that owns the partition
	 * only - every collection allocates its own sequence - and it is also the discriminator a partition-scoped formula
	 * is tagged with.
	 *
	 * @return the primary key of the partition
	 */
	int getPrimaryKey();

	/**
	 * Returns the key of the partition, carrying its scope and its {@link RepresentativeReferenceKey}.
	 *
	 * @return the key of the partition
	 */
	@Nonnull
	EntityIndexKey getIndexKey();

	/**
	 * Returns the representative reference key the partition holds the rows of.
	 *
	 * @return the representative reference key
	 */
	@Nonnull
	RepresentativeReferenceKey getRepresentativeReferenceKey();

	/**
	 * Returns the reference key of the partition - the reference name and the primary key of the target entity.
	 *
	 * @return the reference key
	 */
	@Nonnull
	ReferenceKey getReferenceKey();

	/**
	 * Returns the primary keys of the owners whose rows the partition holds.
	 *
	 * @return the owner primary keys
	 */
	@Nonnull
	Bitmap getAllPrimaryKeys();

	/**
	 * Returns a formula over the primary keys of the owners whose rows the partition holds.
	 *
	 * @return formula of the owner primary keys
	 */
	@Nonnull
	Formula getAllPrimaryKeysFormula();

	/**
	 * Returns the number of owners whose rows the partition holds.
	 *
	 * @return the number of owners
	 */
	int size();

}
