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
import java.util.LinkedList;
import java.util.List;
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
	 * Verifies the associated data for a given AssociatedDataContent, EntitySchemaContract, and ExtraResultPlanningVisitor.
	 *
	 * @param associatedDataContent      The AssociatedDataContent containing the associated data names.
	 * @param entitySchema               The EntitySchemaContract object representing the entity schema.
	 * @param extraResultPlanningVisitor The ExtraResultPlanningVisitor object for additional result planning.
	 */
	public static void verifyAssociatedData(
		@Nonnull AssociatedDataContent associatedDataContent,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor
	) {
		final String[] associatedDataNames = associatedDataContent.getAssociatedDataNames();
		if (!ArrayUtils.isEmpty(associatedDataNames)) {
			final EvitaRequest evitaRequest = extraResultPlanningVisitor.getEvitaRequest();
			if (evitaRequest.getRequiredLocales() == null && evitaRequest.getImplicitLocale() == null) {
				verifyAssociatedDataKnownAndNotLocalized(associatedDataNames, entitySchema);
			} else {
				verifyAssociatedDataKnown(associatedDataNames, entitySchema);
			}
		}
	}

	/**
	 * Verifies that the given associated data names are known and not localized in the provided entity schema.
	 *
	 * @param associatedDataNames an array of associated data names
	 * @param entitySchema        the entity schema contract to check against
	 */
	private static void verifyAssociatedDataKnownAndNotLocalized(
		@Nonnull String[] associatedDataNames,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final List<String> missingLocalizedAssociatedData = new LinkedList<>();
		for (String associatedDataName : associatedDataNames) {
			final Optional<AssociatedDataSchemaContract> associatedDataSchema = entitySchema.getAssociatedData(associatedDataName);
			Assert.isTrue(
				associatedDataSchema.isPresent(),
				() -> new AssociatedDataNotFoundException(associatedDataName, entitySchema)
			);
			// unique attributes could provide implicit locale
			if (associatedDataSchema.get().isLocalized()) {
				missingLocalizedAssociatedData.add(associatedDataName);
			}
		}

		if (!missingLocalizedAssociatedData.isEmpty()) {
			throw new EntityLocaleMissingException(
				new String[0], missingLocalizedAssociatedData.toArray(new String[0])
			);
		}
	}

	/**
	 * Verifies that all the given associated data names are present in the entity schema.
	 *
	 * @param associatedDataNames an array of associated data names to be verified
	 * @param entitySchema        the entity schema to check for the associated data
	 * @throws AssociatedDataNotFoundException if any of the associated data names is not found in the entity schema
	 */
	private static void verifyAssociatedDataKnown(
		@Nonnull String[] associatedDataNames,
		@Nonnull EntitySchemaContract entitySchema
	) {
		for (String associatedDataName : associatedDataNames) {
			Assert.isTrue(
				entitySchema.getAssociatedData(associatedDataName).isPresent(),
				() -> new AssociatedDataNotFoundException(associatedDataName, entitySchema)
			);
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
