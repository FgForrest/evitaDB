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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.AssociatedDataConstraint;
import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.HierarchyConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.PropertyTypeDefiningConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Property type of query specifies on which data the query will be operating.
 * Each property type has to correspond to some existing query interface implementing {@link PropertyTypeDefiningConstraint} and other
 * specific constraints implement that interface.
 * <p>
 * This enum exists only to make easier searching through registered constraints so that user knows which property types are supported.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
@Getter
public enum ConstraintPropertyType {

	/**
	 * Constraints that don't operate on particular data but rather serves as helpers, wrappers or generic constraints, e.g.
	 * containers like AND, OR, or NOT.
	 */
	GENERIC(GenericConstraint.class),
	/**
	 * Constraints that operate on data directly accessible from entity (e.g. primary key).
	 */
	ENTITY(EntityConstraint.class),
	/**
	 * Constraints that operate on data in entity/reference attributes.
	 */
	ATTRIBUTE(AttributeConstraint.class),
	/**
	 * Constraints that operate on data in entity associated data.
	 */
	ASSOCIATED_DATA(AssociatedDataConstraint.class),
	/**
	 * Constraints that operate on data in entity prices.
	 */
	PRICE(PriceConstraint.class),
	/**
	 * Constraints that operate on generic entity references.
	 */
	REFERENCE(ReferenceConstraint.class),
	/**
	 * Constraints that operate on hierarchical data accessible through entities hierarchical references or placements.
	 */
	HIERARCHY(HierarchyConstraint.class),
	/**
	 * Constraints that operate on facet references of entities.
	 */
	FACET(FacetConstraint.class);

	@SuppressWarnings("rawtypes")
	private final Class<? extends PropertyTypeDefiningConstraint> representingInterface;
}
