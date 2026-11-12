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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and operations for {@link LocaleHierarchyEntityPredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class LocaleHierarchyEntityPredicateIdentityFixture implements IdentityFixture<LocaleHierarchyEntityPredicate> {
	/** Covers the value, opaque and oversized forms admitted by construction. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<LocaleHierarchyEntityPredicate>> modes() {
		final GlobalEntityIndex index = new GlobalEntityIndex(
			1, "category", new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		index.upsertLanguage(Locale.ENGLISH, 1, EntitySchema._internalBuild("category"));
		return Map.of("indexed locale", new ConstructionMode<>(
			() -> new LocaleHierarchyEntityPredicate(index, Locale.ENGLISH),
			Map.of("missing locale", () -> new LocaleHierarchyEntityPredicate(index, Locale.FRENCH)),
			Map.of(), Map.of(), Map.of(), Set.of(), Set.of("filteringFormula"), Set.of()));
	}
}
