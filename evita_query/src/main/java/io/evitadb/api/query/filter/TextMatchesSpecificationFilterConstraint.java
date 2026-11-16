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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;

/**
 * Marks place selectors restricting a {@link TextMatches} fulltext search. Three level markers carry the allowed
 * nesting: {@link TextMatchesEntitySpecificationFilterConstraint} for direct children of `textMatches`, the only one
 * extending this interface, {@link TextMatchesReferenceSpecificationFilterConstraint} for children of `inReference`
 * and {@link TextMatchesTargetSpecificationFilterConstraint} for children of `inEntity`. Selectors are consumed by
 * the enclosing search and have no standalone meaning.
 *
 * Future refinements of matching get their own creator parameter on {@link TextMatches}; they are not place
 * selectors and must not be added to its `in` parameter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface TextMatchesSpecificationFilterConstraint extends FilterConstraint {
}
