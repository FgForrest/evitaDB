/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query;

/**
 * Ancestor for specifying property type of query. Property type specifies on which property query is able
 * to operate.
 *
 * @see GenericConstraint
 * @see EntityConstraint
 * @see AttributeConstraint
 * @see AssociatedDataConstraint
 * @see PriceConstraint
 * @see ReferenceConstraint
 * @see HierarchyConstraint
 * @see FacetConstraint
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface PropertyTypeDefiningConstraint<T extends TypeDefiningConstraint<T>> extends Constraint<T> {
}

/*
- generic
  - represented by `io.evitadb.api.query.GenericConstraint` interface
- entity
  - represented by `io.evitadb.api.query.EntityConstraint` interface
- attribute
  - represented by `io.evitadb.api.query.AttributeConstraint` interface
- associated data
  - represented by `io.evitadb.api.query.AssociatedDataConstraint` interface
- price
  - represented by `io.evitadb.api.query.PriceConstraint` interface
- reference
  - represented by `io.evitadb.api.query.ReferenceConstraint` interface
- hierarchy
  - represented by `io.evitadb.api.query.HierarchyConstraint` interface
- facet
  - represented by `io.evitadb.api.query.FacetConstraint` interface
 */
