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

package io.evitadb.api.query.expression.object.accessor.entity;

import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor.EntityAssociatedDataEvaluationDto;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link ObjectElementAccessor} for {@link AssociatedDataContract} objects.
 * Enables bracket-access expressions in EvitaEL to retrieve associated data values by name,
 * e.g. `entity.associatedData['description']` or `entity.localizedAssociatedData['description']`.
 *
 * The accessor resolves associated data through
 * {@link EntityContractAccessor.EntityAssociatedDataEvaluationDto} which carries the flag
 * indicating whether localized or non-localized associated data was requested. For localized
 * associated data, the accessor returns an unmodifiable {@link Map} keyed by {@link Locale}
 * with the corresponding values. For non-localized associated data, the raw value is returned
 * directly.
 *
 * @see ObjectElementAccessor
 * @see EntityContractAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class AssociatedDataContractAccessor implements ObjectElementAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { AssociatedDataContract.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String elementName
	) throws ExpressionEvaluationException {
		if (!(object instanceof AssociatedDataContract associatedDataContract)) {
			throw new ExpressionEvaluationException(
				"Cannot access element by name on object of type `" + object.getClass().getName() + "`. Expected AssociatedDataContract.",
				"Cannot access element."
			);
		}

		try {
			final AssociatedDataSchemaContract associatedDataSchema = associatedDataContract.getAssociatedDataSchema(elementName)
				.orElseThrow(() -> new ExpressionEvaluationException(
					"Associated schema for element `" + elementName + "` not found.",
					"Associated `" + elementName + "` not found."
				));

			if (associatedDataContract instanceof EntityAssociatedDataEvaluationDto entityAssociatedData) {
				return getAssociatedData(
					elementName,
					associatedDataSchema,
					entityAssociatedData,
					entityAssociatedData.requestedLocalizedAssociatedData(),
					() -> entityAssociatedData.delegate().getAssociatedDataLocales()
				);
			} else {
				throw new ExpressionEvaluationException(
					"Cannot access associated data `" + elementName + "` on AssociatedDataContract implementation `" + associatedDataContract.getClass().getSimpleName() + "`.",
					"Cannot access associated data `" + elementName + "`."
				);
			}
		} catch (ContextMissingException | AssociatedDataNotFoundException e) {
			throw new ExpressionEvaluationException(
				"Cannot access associated data `" + elementName + "` on AssociatedDataContract: " + e.getMessage(),
				"Cannot found non-localized associated data `" + elementName + "`.",
				e
			);
		}
	}

	/**
	 * Retrieves the associated data value for the given name. When localized associated data
	 * is requested, returns an unmodifiable {@link Map} of {@link Locale} to value entries
	 * across all available locales. When non-localized associated data is requested, returns
	 * the raw value directly. Throws if the localization expectation does not match the schema.
	 */
	@Nullable
	private static Serializable getAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull AssociatedDataSchemaContract associatedDataSchema,
		@Nonnull AssociatedDataContract associatedDataProvider,
		boolean requestedLocalizedAssociatedData,
		@Nonnull Supplier<Set<Locale>> availableLocalesProvider
	) {
		if (requestedLocalizedAssociatedData) {
			Assert.isTrue(
				associatedDataSchema.isLocalized(),
				() -> new ExpressionEvaluationException(
					"Associated data `" + associatedDataName + "` is not localized. Use `.associatedData['" + associatedDataName + "']` instead to access non-localized associated data."
				)
			);

			final Set<Locale> availableLocales = availableLocalesProvider.get();
			final Map<Object, Object> associatedDataMap = createHashMap(availableLocales.size());
			for (final Locale locale : availableLocales) {
				associatedDataMap.put(locale, associatedDataProvider.getAssociatedData(associatedDataName, locale));
			}
			return (Serializable) Collections.unmodifiableMap(associatedDataMap);
		} else {
			Assert.isTrue(
				!associatedDataSchema.isLocalized(),
				() -> new ExpressionEvaluationException(
					"Associated data `" + associatedDataName + "` is localized. Use `.localizedAssociatedData['" + associatedDataName + "']` instead to access localized associated data."
				)
			);

			return associatedDataProvider.getAssociatedData(associatedDataName);
		}
	}
}
