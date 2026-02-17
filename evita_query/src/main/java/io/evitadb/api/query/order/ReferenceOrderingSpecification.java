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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.OrderConstraint;

/**
 * Marker interface for ordering constraints that specify how entities should be ordered when they reference multiple
 * target entities through a single reference type. When an entity has multiple references of the same type, each
 * potentially pointing to a different target entity with its own ordering attributes, this specification defines the
 * strategy for resolving the final sort position of the source entity.
 *
 * Implementing classes:
 *
 * - {@link PickFirstByEntityProperty} — picks the first referenced entity (according to a nested ordering) and uses
 *   its attribute value as the sort key for the source entity
 * - {@link TraverseByEntityProperty} — traverses the hierarchy of referenced entities to determine ordering,
 *   supporting both depth-first and breadth-first traversal modes
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ReferenceProperty#getReferenceOrderingSpecification()
 */
public interface ReferenceOrderingSpecification extends Constraint<OrderConstraint> {
}
