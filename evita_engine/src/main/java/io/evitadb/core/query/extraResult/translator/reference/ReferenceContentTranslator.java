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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.exception.ReferenceContentMisplacedException;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.fetch.ReferencedEntityFetcher;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.Optional.of;

/**
 * This implementation of {@link RequireConstraintTranslator} adds only a requirement for prefetching references when
 * {@link ReferenceContent} requirement is encountered. This requirement signalizes that we would need to use
 * the {@link ReferenceFetcher} implementation to fetch referenced entities, and we'd need the information about entity
 * references already present at that moment.
 *
 * The filter and the ordering of the fetched references are checked against the schemas here, while the query is
 * planned - see {@link ReferencedEntityFetcher#verifyReferenceContentConstraints}. The fetch evaluates them only when
 * there is a reference to filter or order, so a constraint the schemas refuse would otherwise fail the query or pass
 * depending on the data it returns. The check is handed the locale and the scopes the owners of the references are
 * fetched in: those of the query at its top level, and below it those each enclosing `referenceContent` fetches its
 * entity or group bodies in (see {@link #resolveOwnerLevel}). A scope named for the bodies also bounds what an
 * `inScope` of the filter may name, as it does in the fetch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceContentTranslator implements RequireConstraintTranslator<ReferenceContent>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull ReferenceContent referenceContent, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final Optional<EntitySchemaContract> entitySchema = extraResultPlanningVisitor.getCurrentEntitySchema();

			Assert.isTrue(
				entitySchema.isPresent(),
				() -> new ReferenceContentMisplacedException(
					extraResultPlanningVisitor.getEntityContentRequireChain(referenceContent)
				)
			);
			final EntitySchemaContract schema = entitySchema.get();
			final OwnerLevel owner = resolveOwnerLevel(referenceContent, extraResultPlanningVisitor);
			final QueryUtils.ReferenceBodyScopes ownerScope = owner.scope();
			final Set<Scope> ownerScopes = ownerScope == null ?
				extraResultPlanningVisitor.getProcessingScope().getScopes() : ownerScope.scopes();

			of(referenceContent.getReferenceNames())
				.filter(it -> !ArrayUtils.isEmpty(it))
				.map(Arrays::stream)
				.map(it -> it.map(schema::getReferenceOrThrowException))
				.orElseGet(() -> schema.getReferences().values().stream())
				.forEach(referenceSchema -> {
					ReferencedEntityFetcher.verifyReferenceContentConstraints(
						extraResultPlanningVisitor.getQueryContext(),
						schema,
						referenceSchema,
						referenceContent.getFilterBy().orElse(null),
						referenceContent.getOrderBy().orElse(null),
						ownerScopes,
						ownerScope == null ? null : ownerScope.requestedScopes(),
						owner.locale()
					);
					extraResultPlanningVisitor.executeInContext(
						referenceContent,
						() -> referenceSchema,
						() -> schema,
						() -> {
							for (RequireConstraint innerConstraint : referenceContent.getChildren()) {
								innerConstraint.accept(extraResultPlanningVisitor);
							}
							return null;
						}
					);
				});
		}

		if (extraResultPlanningVisitor.isScopeOfQueriedEntity() && (referenceContent.getFilterBy().isPresent() || referenceContent.getOrderBy().isPresent())) {
			extraResultPlanningVisitor.addRequirementToPrefetch(ReferenceContent.ALL_REFERENCES);
		}

		return null;
	}

	/**
	 * Resolves the level owning the references being planned. Walking the requirements outermost first, each enclosing
	 * entity fetch resolves the referenced-entity level, and each enclosing group fetch resolves the group level:
	 *
	 * - the locale of the level, which inherits the reference level's opinion, then its owner's locale, when it has no
	 *   opinion of its own
	 * - the scope of the level, which the referenced entity bodies take from the referenced entity level and the group
	 *   bodies from the group level's alternatives ({@link QueryUtils#resolveReferenceBodyScopes(FilterBy, boolean)}),
	 *   as the requests fetching them do, and inherit when their level names none
	 *
	 * @param referenceContent           the `referenceContent` being planned
	 * @param extraResultPlanningVisitor the visitor planning it
	 * @return the locale and the scope of the owners
	 */
	@Nonnull
	private static OwnerLevel resolveOwnerLevel(
		@Nonnull ReferenceContent referenceContent,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor
	) {
		Locale ownerLocale = extraResultPlanningVisitor.getQueryContext().getLocale();
		ReferenceContent enclosing = null;
		final Iterator<RequireConstraint> requirementChain =
			extraResultPlanningVisitor.getEntityContentRequireChain(referenceContent).iterator();
		while (requirementChain.hasNext()) {
			final RequireConstraint requirement = requirementChain.next();
			if (requirement != referenceContent && requirement instanceof ReferenceContent parent) {
				enclosing = parent;
			} else if (enclosing != null && requirement instanceof EntityFetchRequire) {
				ownerLocale = QueryUtils.resolveReferenceBodyLocale(
					enclosing.getFilterBy().orElse(null), requirement instanceof EntityGroupFetch, ownerLocale
				);
				enclosing = null;
			}
		}
		return new OwnerLevel(ownerLocale, resolveOwnerScope(referenceContent, extraResultPlanningVisitor));
	}

	/**
	 * Resolves searched and admitted scopes of the entity owning a requirement, outermost body level first.
	 * Each entity or group fetch materializes its own matching alternatives against the immediate owner's pair.
	 * A deeper inheriting level retains any outer explicit pair. Plain inheritance uses processing scopes for search
	 * and the query's requested scopes for admission, preserving the planning context's existing inheritance base.
	 *
	 * @param requirement the requirement being planned
	 * @param extraResultPlanningVisitor the visitor planning it
	 * @return the explicit owner pair, NULL when every enclosing body level inherits
	 */
	@Nullable
	static QueryUtils.ReferenceBodyScopes resolveOwnerScope(
		@Nonnull EntityContentRequire requirement,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor
	) {
		QueryUtils.ReferenceBodyScopes ownerScope = null;
		ReferenceContent enclosing = null;
		final Iterator<RequireConstraint> requirementChain =
			extraResultPlanningVisitor.getEntityContentRequireChain(requirement).iterator();
		while (requirementChain.hasNext()) {
			final RequireConstraint chained = requirementChain.next();
			if (chained != requirement && chained instanceof ReferenceContent parent) {
				enclosing = parent;
			} else if (enclosing != null && chained instanceof EntityFetchRequire) {
				final Set<Scope> inheritedScopes = ownerScope == null ?
					extraResultPlanningVisitor.getProcessingScope().getScopes() : ownerScope.scopes();
				final Set<Scope> inheritedRequestedScopes = ownerScope == null ?
					extraResultPlanningVisitor.getQueryContext().getEvitaRequest().getRequestedScopes() :
					ownerScope.requestedScopes();
				final QueryUtils.ReferenceBodyScopes bodyScope = QueryUtils.materializeReferenceBodyScopes(
					QueryUtils.resolveReferenceBodyScopes(
						enclosing.getFilterBy().orElse(null), chained instanceof EntityGroupFetch
					), inheritedScopes, inheritedRequestedScopes
				);
				if (bodyScope != null) {
					ownerScope = bodyScope;
				}
				enclosing = null;
			}
		}
		return ownerScope;
	}

	/**
	 * The level owning the references being planned.
	 *
	 * @param locale the locale the owners are fetched in, NULL when there is none
	 * @param scope the searched and admitted owner scopes, NULL for plain planning inheritance
	 */
	private record OwnerLevel(@Nullable Locale locale, @Nullable QueryUtils.ReferenceBodyScopes scope) {
	}

}
