/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.exception.AssociatedDataContentMisplacedException;
import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * This implementation of {@link RequireConstraintTranslator} adds only a requirement for prefetching references when
 * {@link AssociatedDataContent} requirement is encountered. This requirement signalizes that we would need to use
 * the {@link ReferenceFetcher} implementation to fetch referenced entities, and we'd need the information about entity
 * references already present at that moment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AssociatedDataContentTranslator implements RequireConstraintTranslator<AssociatedDataContent> {

	/**
	 * Validates named associated data in the request of their body level, which
	 * {@link ExtraResultPlanningVisitor#getEntityFetchRequest} derives. Localized names require that level to resolve
	 * a locale, provide an implicit locale, or request localized data explicitly.
	 *
	 * @param associatedDataContent      The AssociatedDataContent containing the associated data names.
	 * @param entitySchema               The EntitySchemaContract object representing the entity schema.
	 * @param extraResultPlanningVisitor The ExtraResultPlanningVisitor object for additional result planning.
	 * @throws AssociatedDataNotFoundException when named associated data is not in the schema
	 * @throws EntityLocaleMissingException    when named associated data is localized and the body level has no locale
	 */
	public static void verifyAssociatedData(
		@Nonnull AssociatedDataContent associatedDataContent,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor
	) {
		verifyAssociatedData(
			associatedDataContent, entitySchema, extraResultPlanningVisitor.getEntityFetchRequest(associatedDataContent)
		);
	}

	/**
	 * Validates named associated data against the given request - used by callers that derive the request of the body
	 * themselves, such as the summary translators, whose bodies take their locale from the summary filter.
	 *
	 * @param associatedDataContent the requirement naming the associated data
	 * @param entitySchema          the schema of the entity whose associated data is named
	 * @param evitaRequest          the request fetching the body, whose required or implicit locales decide whether
	 *                              localized names are accepted
	 * @throws AssociatedDataNotFoundException when named associated data is not in the schema
	 * @throws EntityLocaleMissingException    when named associated data is localized and the request has no locale
	 */
	public static void verifyAssociatedData(
		@Nonnull AssociatedDataContent associatedDataContent,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EvitaRequest evitaRequest
	) {
		verifyAssociatedData(
			associatedDataContent, entitySchema,
			evitaRequest.getRequiredLocales() != null || evitaRequest.getImplicitLocale() != null
		);
	}

	/**
	 * Checks named associated data without deriving a request or allocating on a successful lookup.
	 *
	 * @param associatedDataContent the client content requirement as written
	 * @param entitySchema the schema of the body being checked
	 * @param localeAvailable whether a locale is available at this level - the body's own, one inherited from an
	 *                        enclosing fetch, or one resolved from the reference filter
	 * @throws AssociatedDataNotFoundException when a named value is unknown
	 * @throws EntityLocaleMissingException when named localized values require a locale
	 */
	public static void verifyAssociatedData(
		@Nonnull AssociatedDataContent associatedDataContent,
		@Nonnull EntitySchemaContract entitySchema,
		boolean localeAvailable
	) {
		final Serializable[] names = associatedDataContent.getArguments();
		String[] missing = null;
		int missingCount = 0;
		for (int i = 0; i < names.length; i++) {
			final String name = (String) names[i];
			final AssociatedDataSchemaContract data = entitySchema.getAssociatedData().get(name);
			if (data == null) {
				throw new AssociatedDataNotFoundException(name, entitySchema);
			}
			if (!localeAvailable && data.isLocalized()) {
				if (missing == null) {
					missing = new String[names.length];
				}
				if (!ArrayUtils.contains(missing, name)) {
					missing[missingCount++] = name;
				}
			}
		}
		if (missing != null) {
			throw new EntityLocaleMissingException(new String[0], Arrays.copyOf(missing, missingCount));
		}
	}

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull AssociatedDataContent associatedDataContent, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final Optional<EntitySchemaContract> entitySchema = extraResultPlanningVisitor.getCurrentEntitySchema();
			Assert.isTrue(
				entitySchema.isPresent(),
				() -> new AssociatedDataContentMisplacedException(
					extraResultPlanningVisitor.getEntityContentRequireChain(associatedDataContent)
				)
			);

			verifyAssociatedData(associatedDataContent, entitySchema.orElseThrow(), extraResultPlanningVisitor);
		}
		if (extraResultPlanningVisitor.isScopeOfQueriedEntity()) {
			extraResultPlanningVisitor.addRequirementToPrefetch(associatedDataContent);
		}
		return null;
	}

}
