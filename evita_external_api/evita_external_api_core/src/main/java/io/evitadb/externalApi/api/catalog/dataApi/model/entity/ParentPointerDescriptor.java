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

package io.evitadb.externalApi.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Ancestor that the hierarchy index still holds but whose requested body could not be materialized - it holds no data
 * in the locale the query filters by, it was deleted, or the parent primary key never belonged to an entity at all.
 * Only a `hierarchyContent` asking for the `COMPLETE` parents behaviour ever reports one: the traversal substitutes
 * the pointer for the missing body and keeps walking above it, so an ancestor carrying a full body may well sit above
 * a pointer.
 *
 * The object carries the classifier of the ancestor and nothing of its body. It is deliberately *not*
 * {@link EntityDescriptor#THIS_REFERENCE}, which additionally promises a version and a scope - neither of which
 * a bodyless ancestor can supply.
 *
 * The two static properties below are all the descriptor itself declares; each API then adds whatever link its own
 * chain shape needs. REST nests the axis, so its pointer object additionally carries the recursive
 * `parentEntityComplete` property that lets the chain continue above the pointer; GraphQL reports the axis as a flat
 * list and therefore adds nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ParentPointerDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*ParentPointer")
		.description("""
			Ancestor whose requested body could not be materialized - it holds no data in the queried locale, it was
			deleted, or the parent primary key never belonged to an entity. Only the `COMPLETE` parents behaviour of
			the `hierarchyContent` requirement reports one; the traversal continues above it, so an ancestor with
			a body may follow a bodyless pointer.
			""")
		.staticProperty(EntityDescriptor.PRIMARY_KEY)
		.staticProperty(EntityDescriptor.TYPE)
		.build();
}
