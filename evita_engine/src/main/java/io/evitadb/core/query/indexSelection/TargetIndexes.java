/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.indexSelection;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import lombok.Data;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/**
 * This data transfer object encapsulates set of {@link EntityIndex} that relate to specific {@link FilterConstraint}.
 * The disjunction of all {@link EntityIndex#getAllPrimaryKeys()} would produce the correct result for passed query
 * if there are no other constraints in the input query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Data
public class TargetIndexes<T extends Index<?>> {
	public static final TargetIndexes<EntityIndex> EMPTY = new TargetIndexes<>("EMPTY", EntityIndex.class, Collections.emptyList());
	private final String indexDescription;
	private final FilterConstraint representedConstraint;
	private final Class<T> indexType;
	private final List<T> indexes;

	public TargetIndexes(@Nonnull String indexDescription, @Nonnull Class<T> indexType, @Nonnull List<T> indexes) {
		this.indexDescription = indexDescription;
		this.representedConstraint = null;
		this.indexType = indexType;
		this.indexes = indexes;
	}

	public TargetIndexes(@Nonnull String indexDescription, @Nonnull FilterConstraint representedConstraint, @Nonnull Class<T> indexType, @Nonnull List<T> indexes) {
		this.indexDescription = indexDescription;
		this.representedConstraint = representedConstraint;
		this.indexType = indexType;
		this.indexes = indexes;
	}

	/**
	 * Returns true if this instance contains no references to target {@link EntityIndex entity indexes}.
	 */
	public boolean isEmpty() {
		return this.indexes.isEmpty();
	}

	@Override
	public String toString() {
		return "Index type: " + indexDescription;
	}

	/**
	 * Prints {@link #toString()} including estimated costs (that are computed and passed from outside).
	 */
	public String toStringWithCosts(long estimatedCost) {
		return this + ", estimated costs " + estimatedCost;
	}

	/**
	 * Returns true if the largest global index was selected.
	 */
	public boolean isGlobalIndex() {
		return this.indexes.size() == 1 && this.indexes.get(0) instanceof GlobalEntityIndex;
	}

	/**
	 * Returns true if the catalog index was selected.
	 */
	public boolean isCatalogIndex() {
		return this.indexes.size() == 1 && this.indexes.get(0) instanceof CatalogIndex;
	}

}
