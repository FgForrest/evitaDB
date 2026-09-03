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

import io.evitadb.externalApi.api.model.UnionDescriptor;

/**
 * One element of a parent chain fetched under the `COMPLETE` parents behaviour: either the ancestor itself, with the
 * body that was asked for, or a {@link ParentPointerDescriptor} standing in for a body that could not be materialized.
 *
 * The union deliberately carries **no discriminator**. An ancestor and the pointer that stands in for it report the
 * same entity type, so the `type` property cannot tell the two apart; a consumer distinguishes them by the shape of
 * the object it receives (GraphQL by the fragment that matched, REST by the branch of the `oneOf` that validates).
 *
 * The member types are added by each API's schema builder, because they differ per collection - and, on REST, per
 * localized variant of the entity object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ParentUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("*ParentUnion")
		.description("""
			One ancestor of a parent chain fetched with the `COMPLETE` parents behaviour - either the ancestor with
			the requested body, or a bodyless pointer standing in for a body that could not be materialized.
			""")
		.build();
}
