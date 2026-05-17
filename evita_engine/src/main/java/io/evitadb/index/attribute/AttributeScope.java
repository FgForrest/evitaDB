/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.attribute;

/**
 * Structural marker that distinguishes an [AttributeIndex] holding **entity-level** attributes from
 * one holding **reference-level** attributes. The scope reflects schema intent — i.e. whether the
 * indexed values belong to the entity itself or to a relation captured by a reference — and is
 * derived from the owning [io.evitadb.index.EntityIndex] subclass.
 *
 * The enum is consumed by the [AttributeIndex] sealed hierarchy ([EntityAttributeIndex] vs
 * [ReferenceAttributeIndex]) so call sites can branch on scope without re-inspecting the
 * surrounding schema. It is NOT serialized — the subclass identity is reconstructed from the
 * owning [io.evitadb.index.EntityIndexKey.discriminator] on reload.
 */
public enum AttributeScope {

	/**
	 * The index stores attributes defined directly on the entity. Owned by
	 * [io.evitadb.index.GlobalEntityIndex]. The owning [io.evitadb.index.EntityIndexKey] never
	 * carries a [io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey]
	 * discriminator.
	 */
	ENTITY,

	/**
	 * The index stores attributes attached to a reference. Owned by
	 * [io.evitadb.index.AbstractReducedEntityIndex] (its concrete leaves
	 * [io.evitadb.index.ReducedEntityIndex], [io.evitadb.index.ReducedGroupEntityIndex]) and by
	 * [io.evitadb.index.ReferencedTypeEntityIndex]. The owning [io.evitadb.index.EntityIndexKey]
	 * discriminator identifies the relation either as a
	 * [io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey] (reduced) or as a
	 * raw reference name string (referenced-type).
	 */
	REFERENCE

}
