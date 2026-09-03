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

import io.evitadb.externalApi.api.catalog.dataApi.model.entity.CompleteParentUnionDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptor;

/**
 * One element of the parent chain reported by {@link RestEntityDescriptor#PARENT_ENTITY}: either the ancestor itself,
 * with the body that was asked for, or a {@link ParentPointerDescriptor} carrying nothing but its classifier, which is
 * what a `hierarchyContent` requirement asking for no ancestor body at all reports.
 *
 * Both branches occur, and never within one response: a requirement that asked for ancestor bodies reports the chain
 * cut below the first ancestor that could not supply one, so every element of it is a materialized entity; a
 * requirement that asked for none reports the whole primary-key chain, so every element of it is a pointer.
 *
 * The union deliberately carries **no discriminator**. An ancestor and a bodyless one report the same entity type, so
 * the `type` property cannot tell the two apart; what tells them apart is the shape - see
 * {@link CompleteParentUnionDescriptor} for the same reasoning applied to the sibling axis.
 *
 * The member types are added by the schema builder, because they differ per collection and per localized variant of
 * the entity object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ParentUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("*ParentUnion")
		.description("""
			One ancestor of the parent chain reported by `parentEntity` - either the ancestor with the requested body,
			or, when the `hierarchyContent` requirement asked for no ancestor body at all, a bodyless ancestor
			carrying nothing but its primary key and type.
			""")
		.build();
}
