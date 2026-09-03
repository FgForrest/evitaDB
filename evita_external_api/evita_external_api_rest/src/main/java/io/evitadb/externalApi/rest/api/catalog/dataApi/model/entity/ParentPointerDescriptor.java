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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.CompleteParentPointerDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Ancestor reported by {@link RestEntityDescriptor#PARENT_ENTITY} as a bare classifier, because the `hierarchyContent`
 * requirement it was fetched with asked for no ancestor body at all. Such a requirement can have nothing fail, so the
 * whole primary-key chain is reported - every element of it being one of these.
 *
 * The object carries the classifier of the ancestor and nothing of its body. It is deliberately *not*
 * {@link EntityDescriptor#THIS_REFERENCE}, which additionally promises a version and a scope - neither of which
 * an ancestor fetched without a body can supply.
 *
 * The object exists only because REST nests the axis: it holds the recursive `parentEntity` link that lets the chain
 * continue above it. GraphQL reports the same ancestors as a flat list of entity objects and needs no type of its own
 * for them, because a selection that never reaches past the classifier never resolves the fields a body would answer.
 *
 * Not to be confused with {@link CompleteParentPointerDescriptor}, which stands for an ancestor whose body *was*
 * requested and could not be materialized. That one is reported by {@link RestEntityDescriptor#PARENT_ENTITY_COMPLETE}
 * and links its chain through `parentEntityComplete`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ParentPointerDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*ParentPointer")
		.description("""
			Ancestor reported without a body, because the `hierarchyContent` requirement asked for none. Such
			a requirement reports the whole primary-key chain, so every ancestor above this one is bodyless as well.
			""")
		.staticProperty(EntityDescriptor.PRIMARY_KEY)
		.staticProperty(EntityDescriptor.TYPE)
		.build();
}
