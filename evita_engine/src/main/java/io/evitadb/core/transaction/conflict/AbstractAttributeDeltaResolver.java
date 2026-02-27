/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.transaction.conflict;

import io.evitadb.api.requestResponse.mutation.conflict.CommutativeConflictKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract base class for resolving conflicts in attribute delta mutations.
 * This resolver handles commutative numeric operations on entity attributes, allowing multiple
 * concurrent transactions to apply delta changes (increments/decrements) that can be safely
 * aggregated without conflicts.
 *
 * <p>The resolver maintains the accumulated delta value and lazily loads the initial attribute
 * value from the catalog when needed. It implements the {@link CommutativeConflictResolver}
 * interface to support conflict-free concurrent modifications.</p>
 *
 * <p>Subclasses must implement {@link #getInitialAttributeValue()} to provide the mechanism
 * for retrieving the initial attribute value from the appropriate storage location.</p>
 *
 * @param <T> the type of conflict key that identifies the attribute and contains the delta value
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
abstract class AbstractAttributeDeltaResolver<T extends CommutativeConflictKey<Number>> implements CommutativeConflictResolver<Number> {
    /**
     * The catalog containing the entity collection where the attribute resides.
     */
    protected final Catalog catalog;
    /**
     * The conflict key identifying the attribute and providing the initial delta value.
     */
    protected final T conflictKey;
    /**
     * The accumulated numeric value representing the current state after applying all delta mutations.
     * This value is initialized from the stored attribute value and updated with each delta accumulation.
     */
    protected Number accumulatedValue;
    /**
     * Flag indicating whether the initial value has been loaded from the catalog.
     */
    private boolean initialized;
    /**
     * The initial numeric value of the attribute taken from the catalog.
     */
    @Nullable
    private Number initialValue;

    public AbstractAttributeDeltaResolver(@Nonnull Catalog catalog, @Nonnull T attributeDeltaConflictKey) {
        this.catalog = catalog;
        this.conflictKey = attributeDeltaConflictKey;
        this.accumulatedValue = attributeDeltaConflictKey.deltaValue();
    }

    @Nonnull
    @Override
    public Number accumulatedValue() {
        if (!this.initialized) {
            this.initialValue = getInitialAttributeValue();
            this.initialized = true;
        }
        return this.initialValue == null ?
            this.accumulatedValue :
            this.conflictKey.aggregate(this.initialValue, this.accumulatedValue);
    }

    @Override
    public void accumulate(@Nonnull Number deltaValue) {
        this.accumulatedValue = NumberUtils.sum(this.accumulatedValue, deltaValue);
    }

    /**
     * Retrieves the initial numeric value of the attribute. This value is typically
     * loaded from a persistent store or data source and serves as the starting
     * point for further computations or accumulations.
     *
     * @return the initial numeric value of the attribute, or {@code null} if no
     * initial value is available
     */
    @Nullable
    protected abstract Number getInitialAttributeValue();

}
