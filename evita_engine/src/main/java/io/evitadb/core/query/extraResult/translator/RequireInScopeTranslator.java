/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.extraResult.translator;


import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.RequireInScope;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * This implementation of {@link RequireConstraintTranslator} simply traverses contents of {@link RequireInScope} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class RequireInScopeTranslator implements RequireConstraintTranslator<RequireInScope>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull RequireInScope inScope, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		final Set<Scope> requestedScopes = extraResultPlanningVisitor.getScopes();
		final Scope scopeToUse = inScope.getScope();
		Assert.isTrue(
			requestedScopes.contains(scopeToUse),
			"Scope `" + scopeToUse + "` used in `inScope` order container was not requested by `scope` constraint!"
		);

		return extraResultPlanningVisitor.getProcessingScope()
			.doWithScope(
				scopeToUse,
				() -> {
					for (RequireConstraint innerConstraint : inScope.getChildren()) {
						innerConstraint.accept(extraResultPlanningVisitor);
					}
					return null;
				}
			);
	}

}
