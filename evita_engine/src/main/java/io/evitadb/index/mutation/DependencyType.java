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

package io.evitadb.index.mutation;

/**
 * Classifies the relationship between a mutated entity and the owner entity in a cross-entity expression trigger.
 * Used as a registry key in `CatalogExpressionTriggerRegistry` to look up which triggers should fire when a given
 * entity type's attributes change.
 *
 * Only cross-entity relationships are represented here. Local dependencies (`$entity.attributes['x']`,
 * `$reference.attributes['x']`) are handled inline in `ReferenceIndexMutator` and do not require this enum.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum DependencyType {

	/**
	 * The mutated entity is the **referenced entity** of the reference.
	 * Expression path: `$reference.referencedEntity.attributes['x']`
	 *
	 * The trigger fires when an attribute on the referenced entity changes (or the entity is removed) and the
	 * expression reads that attribute. Fan-out is typically 1:1 (one referenced entity per reference instance).
	 */
	REFERENCED_ENTITY_ATTRIBUTE,

	/**
	 * The mutated entity is the **group entity** of the reference.
	 * Expression path: `$reference.groupEntity?.attributes['x']`
	 *
	 * The trigger fires when an attribute on the group entity changes (or the entity is removed). Fan-out can be
	 * significant — one group entity may be shared across many references (and thus many owner entities).
	 */
	GROUP_ENTITY_ATTRIBUTE,

	/**
	 * The mutated entity is the **referenced entity** of the reference, and the dependency is on a reference
	 * attribute of that entity. Expression path: `$reference.referencedEntity.references['r'].attributes['x']`.
	 *
	 * The trigger fires when a reference mutation on the referenced entity affects reference 'r' (insert, remove,
	 * or attribute 'x' change).
	 */
	REFERENCED_ENTITY_REFERENCE_ATTRIBUTE,

	/**
	 * The mutated entity is the **group entity** of the reference, and the dependency is on a reference attribute
	 * of that entity. Expression path: `$reference.groupEntity?.references['r'].attributes['x']`.
	 *
	 * Fan-out can be significant (same as {@link #GROUP_ENTITY_ATTRIBUTE}).
	 */
	GROUP_ENTITY_REFERENCE_ATTRIBUTE

}
