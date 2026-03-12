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

package io.evitadb.core.expression.proxy;

import io.evitadb.exception.ExpressionEvaluationException;
import one.edee.oss.proxycian.PredicateMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable schema-load-time artifact holding everything needed for trigger-time proxy instantiation. Built by
 * {@link ExpressionProxyFactory#buildDescriptor(io.evitadb.dataType.expression.ExpressionNode)} from the static
 * analysis of an expression tree.
 *
 * Contains pre-composed arrays of {@link PredicateMethodClassification} partials for entity and reference proxies,
 * a {@link StoragePartRecipe} describing which storage parts to fetch, and flags indicating whether nested entity
 * proxies are needed.
 *
 * At trigger time, the partial arrays are passed to `ByteBuddyDispatcherInvocationHandler` constructors together
 * with fresh per-entity state records to instantiate the actual proxy objects.
 *
 * @param entityPartials             array of entity proxy partials in composition order (always-included first,
 *                                   then specific, then catch-all last)
 * @param referencePartials          array of reference proxy partials, or `null` if no references are accessed
 * @param entityRecipe               recipe describing which storage parts to fetch for the entity
 * @param needsReferencedEntityProxy whether the reference proxy needs a nested referenced entity proxy
 * @param needsGroupEntityProxy      whether the reference proxy needs a nested group entity proxy
 * @param referencedEntityPartials   array of partials for the nested referenced entity proxy, or `null`
 * @param groupEntityPartials        array of partials for the nested group entity proxy, or `null`
 * @param referencedEntityRecipe     recipe for fetching storage parts of the nested referenced entity, or `null`
 * @param groupEntityRecipe          recipe for fetching storage parts of the nested group entity, or `null`
 */
public record ExpressionProxyDescriptor(
	@Nonnull PredicateMethodClassification<?, ?, ?>[] entityPartials,
	@Nullable PredicateMethodClassification<?, ?, ?>[] referencePartials,
	@Nonnull StoragePartRecipe entityRecipe,
	boolean needsReferencedEntityProxy,
	boolean needsGroupEntityProxy,
	@Nullable PredicateMethodClassification<?, ?, ?>[] referencedEntityPartials,
	@Nullable PredicateMethodClassification<?, ?, ?>[] groupEntityPartials,
	@Nullable StoragePartRecipe referencedEntityRecipe,
	@Nullable StoragePartRecipe groupEntityRecipe
) {

	/**
	 * Returns the reference proxy partials or throws {@link ExpressionEvaluationException} if they are not
	 * available. This method should be used by the instantiator when creating reference proxies — the caller
	 * has already determined that a reference proxy is needed but the descriptor may lack the partials due to
	 * a misconfigured expression analysis.
	 *
	 * @return the non-null reference partials array
	 * @throws ExpressionEvaluationException if referencePartials is null
	 */
	@Nonnull
	public PredicateMethodClassification<?, ?, ?>[] referencePartialsOrThrowException() {
		if (this.referencePartials == null) {
			throw new ExpressionEvaluationException(
				"Reference proxy partials are not available in the expression proxy descriptor. " +
					"This indicates a mismatch between the expression analysis (which did not detect " +
					"reference access) and the instantiation request (which expects a reference proxy).",
				"Reference proxy partials are not available for expression evaluation."
			);
		}
		return this.referencePartials;
	}

	/**
	 * Returns the nested referenced entity partials or throws if not available.
	 *
	 * @return the non-null referenced entity partials array
	 * @throws ExpressionEvaluationException if referencedEntityPartials is null
	 */
	@Nonnull
	public PredicateMethodClassification<?, ?, ?>[] referencedEntityPartialsOrThrowException() {
		if (this.referencedEntityPartials == null) {
			throw new ExpressionEvaluationException(
				"Referenced entity proxy partials are not available in the expression proxy descriptor. " +
					"This indicates the expression does not access referenced entity data.",
				"Referenced entity proxy partials are not available for expression evaluation."
			);
		}
		return this.referencedEntityPartials;
	}

	/**
	 * Returns the nested group entity partials or throws if not available.
	 *
	 * @return the non-null group entity partials array
	 * @throws ExpressionEvaluationException if groupEntityPartials is null
	 */
	@Nonnull
	public PredicateMethodClassification<?, ?, ?>[] groupEntityPartialsOrThrowException() {
		if (this.groupEntityPartials == null) {
			throw new ExpressionEvaluationException(
				"Group entity proxy partials are not available in the expression proxy descriptor. " +
					"This indicates the expression does not access group entity data.",
				"Group entity proxy partials are not available for expression evaluation."
			);
		}
		return this.groupEntityPartials;
	}

	/**
	 * Returns the nested referenced entity recipe or throws if not available.
	 *
	 * @return the non-null referenced entity recipe
	 * @throws ExpressionEvaluationException if referencedEntityRecipe is null
	 */
	@Nonnull
	public StoragePartRecipe referencedEntityRecipeOrThrowException() {
		if (this.referencedEntityRecipe == null) {
			throw new ExpressionEvaluationException(
				"Referenced entity storage recipe is not available in the expression proxy descriptor.",
				"Referenced entity storage recipe is not available for expression evaluation."
			);
		}
		return this.referencedEntityRecipe;
	}

	/**
	 * Returns the nested group entity recipe or throws if not available.
	 *
	 * @return the non-null group entity recipe
	 * @throws ExpressionEvaluationException if groupEntityRecipe is null
	 */
	@Nonnull
	public StoragePartRecipe groupEntityRecipeOrThrowException() {
		if (this.groupEntityRecipe == null) {
			throw new ExpressionEvaluationException(
				"Group entity storage recipe is not available in the expression proxy descriptor.",
				"Group entity storage recipe is not available for expression evaluation."
			);
		}
		return this.groupEntityRecipe;
	}
}
