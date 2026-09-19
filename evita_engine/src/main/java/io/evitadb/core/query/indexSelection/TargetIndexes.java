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

package io.evitadb.core.query.indexSelection;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This data transfer object encapsulates set of {@link EntityIndex} that relate to specific {@link FilterConstraint}.
 * The disjunction of all {@link EntityIndex#getAllPrimaryKeys()} would produce the correct result for passed query
 * if there are no other constraints in the input query.
 *
 * Only accessors are generated, deliberately. A setter for {@link #indexes} would let the resolved list disagree
 * with {@link #indexCount}, which {@link #isEmpty()} and {@link #isCatalogIndex()} answer from without resolving.
 * Generated equality would span both the lazily memoized {@link #indexes} - so the hash code would change on the
 * first {@link #getIndexes()} - and the per-instance {@link #indexSupplier} lambda, which never compares equal to
 * another. Nothing compares these sets: they are looked up by constraint identity, not by value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Getter
public class TargetIndexes<T extends Index<?>> {
	public static final TargetIndexes<GlobalEntityIndex> EMPTY = new TargetIndexes<>("EMPTY", GlobalEntityIndex.class, Collections.emptyList());
	/**
	 * Human readable description for the index set.
	 */
	private final String indexDescription;
	/**
	 * The filtering constraint instance from the input query the indexes are related to.
	 */
	private final FilterConstraint representedConstraint;
	/**
	 * The type of the indexes.
	 */
	private final Class<T> indexType;
	/**
	 * The list of indexes themselves, or `null` while it is still deferred - see {@link #indexSupplier}.
	 */
	private List<T> indexes;
	/**
	 * Resolves {@link #indexes} on first demand, or `null` when they were passed in already resolved.
	 *
	 * Materialising a reduced-index candidate means resolving one {@link EntityIndex} object per partition the
	 * reference advertises, which on a production catalog is six figures and dominates the cost of the whole
	 * query. A candidate that is rejected during index selection never needs those objects to be *planned* -
	 * `QueryPlanner` builds no formula for it - so the resolution is deferred until something genuinely
	 * asks for the objects, and then memoized.
	 *
	 * Deferring is safe only because it is never a way to avoid work that has to happen: the supplier closes
	 * over the already-computed candidate primary keys and an accessor, both immutable, and every consumer that
	 * does need the objects still gets exactly the same list it would have got before. What it avoids is
	 * building that list for a candidate nobody consults.
	 *
	 * No accessor is generated for it: handing the supplier out would let a caller resolve the indexes past
	 * the memoization in {@link #getIndexes()} and pay the whole cost a second time.
	 */
	@Getter(AccessLevel.NONE)
	private final Supplier<List<T>> indexSupplier;
	/**
	 * How many indexes this set holds, known without resolving them.
	 *
	 * Kept separately because {@link #isEmpty()} is consulted for *every* candidate, rejected ones included
	 * (see {@code IndexSelectionResult#isEmpty()}), and reading it off the list would force the very
	 * resolution {@link #indexSupplier} exists to defer - making the laziness worthless at the first caller.
	 */
	private final int indexCount;
	/**
	 * The set of obstacles that prevent the index from being eligible for separate query plan.
	 */
	private final EnumSet<EligibilityObstacle> eligibilityObstacles;

	public TargetIndexes(@Nonnull String indexDescription, @Nonnull Class<T> indexType, @Nonnull List<T> indexes) {
		this.indexDescription = indexDescription;
		this.representedConstraint = null;
		this.indexType = indexType;
		this.indexes = indexes;
		this.indexSupplier = null;
		this.indexCount = indexes.size();
		this.eligibilityObstacles = EnumSet.noneOf(EligibilityObstacle.class);
	}

	public TargetIndexes(
		@Nonnull String indexDescription,
		@Nonnull FilterConstraint representedConstraint,
		@Nonnull Class<T> indexType,
		@Nonnull List<T> indexes,
		@Nonnull EligibilityObstacle... eligibilityObstacle
	) {
		this.indexDescription = indexDescription;
		this.representedConstraint = representedConstraint;
		this.indexType = indexType;
		this.indexes = indexes;
		this.indexSupplier = null;
		this.indexCount = indexes.size();
		this.eligibilityObstacles = EnumSet.noneOf(EligibilityObstacle.class);
		Collections.addAll(this.eligibilityObstacles, eligibilityObstacle);
	}

	/**
	 * Creates a set whose indexes are resolved only when they are first asked for.
	 *
	 * Use this when the obstacle set is already known without looking at the indexes themselves - the reference
	 * is not partitioned, or the candidate count alone already exceeds the cardinality limit - so that a
	 * candidate the planner is going to reject costs the schema check and nothing more.
	 *
	 * @param indexDescription      human readable description of the index set
	 * @param representedConstraint the constraint the indexes answer
	 * @param indexType             type of the indexes the supplier will produce
	 * @param indexCount            how many indexes the supplier will produce, known in advance
	 * @param indexSupplier         resolves the indexes on first demand
	 * @param eligibilityObstacle   obstacles that make this set ineligible for a separate query plan
	 */
	public TargetIndexes(
		@Nonnull String indexDescription,
		@Nonnull FilterConstraint representedConstraint,
		@Nonnull Class<T> indexType,
		int indexCount,
		@Nonnull Supplier<List<T>> indexSupplier,
		@Nonnull EligibilityObstacle... eligibilityObstacle
	) {
		this.indexDescription = indexDescription;
		this.representedConstraint = representedConstraint;
		this.indexType = indexType;
		this.indexes = null;
		this.indexSupplier = indexSupplier;
		this.indexCount = indexCount;
		this.eligibilityObstacles = EnumSet.noneOf(EligibilityObstacle.class);
		Collections.addAll(this.eligibilityObstacles, eligibilityObstacle);
	}

	/**
	 * Returns the indexes of this set, resolving them first if they were deferred.
	 *
	 * @return the indexes; never `null`
	 */
	@Nonnull
	public List<T> getIndexes() {
		if (this.indexes == null) {
			this.indexes = Objects.requireNonNull(this.indexSupplier).get();
		}
		return this.indexes;
	}

	/**
	 * Returns true if this instance contains no references to target {@link EntityIndex entity indexes}.
	 */
	public boolean isEmpty() {
		return this.indexCount == 0;
	}

	/**
	 * Returns whether this index set is eligible to be executed via a separate query plan.
	 * Eligibility is determined solely by the absence of {@link EligibilityObstacle} records
	 * for this instance (i.e., when {@code eligibilityObstacles} is empty).
	 *
	 * @return true if there are no eligibility obstacles; false otherwise
	 */
	public boolean isEligibleForSeparateQueryPlan() {
		return this.eligibilityObstacles.isEmpty();
	}

	/**
	 * Combines the names of all eligibility obstacles into a single, comma-separated string.
	 *
	 * @return a string representation of all eligibility obstacles, separated by commas
	 */
	@Nonnull
	public String getEligibilityObstacleString() {
		return this.eligibilityObstacles.stream().map(Enum::name).collect(Collectors.joining(", "));
	}

	@Override
	public String toString() {
		return "Index type: " + this.indexDescription +
			(this.eligibilityObstacles.isEmpty() ? "" :
				" (not eligible for separate query plan due to: " + getEligibilityObstacleString() + ")"
			);
	}

	/**
	 * Prints {@link #toString()} including estimated costs (that are computed and passed from outside).
	 */
	public String toStringWithCosts(long estimatedCost) {
		return this + ", estimated costs " + estimatedCost +
			(this.eligibilityObstacles.isEmpty() ? "" :
				" (not eligible for separate query plan due to: " + getEligibilityObstacleString() + ")"
			);
	}

	/**
	 * Returns true if the largest global index was selected.
	 */
	public boolean isGlobalIndex() {
		return getIndexes().stream().allMatch(GlobalEntityIndex.class::isInstance);
	}

	/**
	 * Returns true if the catalog index was selected.
	 */
	public boolean isCatalogIndex() {
		return this.indexCount == 1 && getIndexes().get(0) instanceof CatalogIndex;
	}

	/**
	 * Returns a stream of elements of the requested type from the indexes.
	 *
	 * @param requestedType the Class object representing the requested type of elements
	 * @param <S> the type parameter for the requested elements
	 * @return a Stream of elements of the requested type from the indexes
	 * @throws NullPointerException if requestedType is null
	 */
	@Nonnull
	public <S> Stream<S> getIndexStream(@Nonnull Class<S> requestedType) {
		return getIndexes()
			.stream()
			.filter(requestedType::isInstance)
			.map(requestedType::cast);
	}

	/**
	 * The {@code EligibilityObstacle} enumeration represents specific obstacles that may determine
	 * the ineligibility of a set of indexes to be utilized in certain queries or operations.
	 *
	 * This enumeration is generally used to denote conditions or factors that prevent
	 * the execution of a query via a separate query plan or impact the performance
	 * and feasibility of utilizing specific indexes in a database operation.
	 */
	public enum EligibilityObstacle {

		/**
		 * Indicates that the index is not partitioned by the reference schema definition. Such index doesn't contain
		 * all necessary data to execute correct filtering / sorting operations.
		 */
		NOT_PARTITIONED_INDEX,
		/**
		 * Indicates that the index cardinality is too high to be worth considering. Because we need to collect all
		 * the data from the indexes, we require that the sum of the index cardinalities is lesser than 50%.
		 *
		 * It is also raised from the candidate count alone, without summing anything - the count is a lower bound
		 * on that sum, so a count already over the limit settles the question. The converse does not hold: when a
		 * reference is rejected on its schema the sum is never computed, so this obstacle appears next to
		 * {@link #NOT_PARTITIONED_INDEX} only where the count alone decided it.
		 */
		HIGH_CARDINALITY

	}

}
