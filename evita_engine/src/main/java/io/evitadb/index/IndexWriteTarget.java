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

import javax.annotation.Nonnull;

/**
 * The target an attribute or sortable attribute compound write of an entity mutation is directed at: either an
 * {@link EntityIndex} object, which receives the write into its own attribute, sort and chain indexes, or a
 * {@link VirtualReferencePartition}, a single-owner reference partition represented by its row in the registry of
 * single-owner partitions ({@link VirtualReferencePartitionIndex}) rather than by an index object.
 *
 * The attribute index mutator computes the values of a write the same way for both: it normalizes them, rescales
 * them to the indexed decimal places, assembles the compounds and selects their locales. An index object receives
 * them; a virtual partition has no object, so its registry row is the only place its values are written to (see
 * {@link io.evitadb.index.mutation.local.VirtualReferencePartitionWriter}). A partition that has an index object is
 * that object - a {@link ReducedEntityIndex} is both an {@link EntityIndex} and a {@link ReferencePartition} - so a
 * caller holding a partition handle passes it on as it is, and no write allocates a wrapper.
 *
 * The type is sealed so that the two kinds of target are told apart by a type test: everything that is not an
 * {@link EntityIndex} is a {@link VirtualReferencePartition}.
 *
 * Not to be confused with the `Target` of {@link io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor},
 * which says whether a primary key is the existing or the new one, nor with
 * {@link io.evitadb.index.mutation.IndexMutationTarget}, which provides the indexes of a collection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public sealed interface IndexWriteTarget permits EntityIndex, ReferencePartition {

	/**
	 * Returns the key of the target, carrying its type, its scope and its discriminator.
	 *
	 * @return the key of the target
	 */
	@Nonnull
	EntityIndexKey getIndexKey();

}
