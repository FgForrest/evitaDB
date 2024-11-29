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

package io.evitadb.core.query.sort.translator;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderInScope;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link OrderInScope} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class OrderInScopeTranslator implements OrderingConstraintTranslator<OrderInScope>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull OrderInScope inScope, @Nonnull OrderByVisitor orderByVisitor) {
		final Set<Scope> requestedScopes = orderByVisitor.getScopes();
		final Scope scopeToUse = inScope.getScope();
		Assert.isTrue(
			requestedScopes.contains(scopeToUse),
			"Scope `" + scopeToUse + "` used in `inScope` order container was not requested by `scope` constraint!"
		);

		return orderByVisitor.getProcessingScope()
			.doWithScope(
				scopeToUse,
				() -> {
					for (OrderConstraint innerConstraint : inScope.getChildren()) {
						innerConstraint.accept(orderByVisitor);
					}
					return Stream.empty();
				}
			);
	}

}
