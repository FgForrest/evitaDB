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

import io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor;
import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferencesContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * Implementation of {@link ObjectPropertyAccessor} for {@link EntityContract} objects.
 * Enables dot-property access expressions in EvitaEL to navigate the entity structure,
 * e.g. `entity.attributes`, `entity.localizedAttributes`, `entity.associatedData`,
 * `entity.localizedAssociatedData`, `entity.references`, `entity.parentEntity`,
 * `entity.allLocales`, `entity.locales`, `entity.scope`, `entity.priceForSale`,
 * `entity.prices`, `entity.allPricesForSale`, `entity.accompanyingPrice`,
 * `entity.priceInnerRecordHandling`, `entity.version`, or `entity.dropped`.
 *
 * Each supported property returns a scoped DTO wrapper that limits the entity contract to
 * a specific sub-domain (attributes, associated data, or references) so that downstream
 * {@link ObjectElementAccessor}
 * implementations can correctly evaluate bracket-access expressions on the result. The DTO
 * also carries a localization flag where applicable, distinguishing between localized and
 * non-localized access paths.
 *
 * @see ObjectPropertyAccessor
 * @see AttributesContractAccessor
 * @see AssociatedDataContractAccessor
 * @see ReferencesContractAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class EntityContractAccessor implements ObjectPropertyAccessor {

	public static final String ENTITY_VARIABLE_NAME = "entity";
	public static final String PRIMARY_KEY_PROPERTY = "primaryKey";
	public static final String ATTRIBUTES_PROPERTY = "attributes";
	public static final String LOCALIZED_ATTRIBUTES_PROPERTY = "localizedAttributes";
	public static final String ASSOCIATED_DATA_PROPERTY = "associatedData";
	public static final String LOCALIZED_ASSOCIATED_DATA_PROPERTY = "localizedAssociatedData";
	public static final String REFERENCES_PROPERTY = "references";
	public static final String PARENT_ENTITY_PROPERTY = "parentEntity";
	public static final String ALL_LOCALES_PROPERTY = "allLocales";
	public static final String LOCALES_PROPERTY = "locales";
	public static final String SCOPE_PROPERTY = "scope";
	public static final String ALL_PRICES_FOR_SALE_PROPERTY = "allPricesForSale";
	public static final String PRICE_FOR_SALE_PROPERTY = "priceForSale";
	public static final String PRICES_PROPERTY = "prices";
	public static final String ACCOMPANYING_PRICE_PROPERTY = "accompanyingPrice";
	public static final String PRICE_INNER_RECORD_HANDLING_PROPERTY = "priceInnerRecordHandling";
	public static final String VERSION_PROPERTY = "version";
	public static final String DROPPED_PROPERTY = "dropped";

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { EntityContract.class };
	}

	@Nullable
	@Override
	public Serializable get(@Nonnull Serializable object, @Nonnull String propertyIdentifier) throws ExpressionEvaluationException {
		if (!(object instanceof EntityContract entity)) {
			throw new ExpressionEvaluationException(
				"Cannot access property on object of type `" + object.getClass().getName() + "`. Expected EntityContract.",
				"Cannot access property."
			);
		}

		return switch (propertyIdentifier) {
			case PRIMARY_KEY_PROPERTY -> entity.getPrimaryKey();
			case ATTRIBUTES_PROPERTY -> new EntityAttributesEvaluationDto(entity, false);
			case LOCALIZED_ATTRIBUTES_PROPERTY -> new EntityAttributesEvaluationDto(entity, true);
			case ASSOCIATED_DATA_PROPERTY -> new EntityAssociatedDataEvaluationDto(entity, false);
			case LOCALIZED_ASSOCIATED_DATA_PROPERTY -> new EntityAssociatedDataEvaluationDto(entity, true);
			case REFERENCES_PROPERTY -> new EntityReferencesEvaluationDto(entity);
			case PARENT_ENTITY_PROPERTY -> entity.parentAvailable() ? (Serializable) entity.getParentEntity().orElse(null) : null;
			case ALL_LOCALES_PROPERTY -> localesToLanguageTags(entity.getAllLocales());
			case LOCALES_PROPERTY -> localesToLanguageTags(entity.getLocales());
			case SCOPE_PROPERTY -> entity.getScope().name();
			case ALL_PRICES_FOR_SALE_PROPERTY -> entity.pricesAvailable() ? (Serializable) new ArrayList<>(entity.getAllPricesForSale()) : null;
			case PRICE_FOR_SALE_PROPERTY -> entity.getPriceForSaleIfAvailable().orElse(null);
			case PRICES_PROPERTY -> entity.pricesAvailable() ? (Serializable) new ArrayList<>(entity.getPrices()) : null;
			case ACCOMPANYING_PRICE_PROPERTY -> entity.isPriceForSaleContextAvailable() ? entity.getAccompanyingPrice().orElse(null) : null;
			case PRICE_INNER_RECORD_HANDLING_PROPERTY -> entity.pricesAvailable() ? entity.getPriceInnerRecordHandling().name() : null;
			case VERSION_PROPERTY -> entity.version();
			case DROPPED_PROPERTY -> entity.dropped();
			default ->
				throw new ExpressionEvaluationException(
					"Property `" + propertyIdentifier + "` does not exist on EntityContract.",
					"Property `" + propertyIdentifier + "` does not exist on entity."
				);
		};
	}

	/**
	 * Converts a set of {@link Locale} instances to an {@link ArrayList} of language tag strings.
	 */
	@Nonnull
	private static Serializable localesToLanguageTags(@Nonnull Set<Locale> locales) {
		final ArrayList<String> tags = new ArrayList<>(locales.size());
		for (Locale locale : locales) {
			tags.add(locale.toLanguageTag());
		}
		return tags;
	}

	/**
	 * DTO to limit the scope of the source {@link EntityContract} only to its attributes for correct evaluation of the accessor for attributes.
	 */
	public record EntityAttributesEvaluationDto(@Delegate EntityContract delegate, boolean requestedLocalizedAttributes)
		implements AttributesContract<EntityAttributeSchemaContract> {}

	/**
	 * DTO to limit the scope of the source {@link EntityContract} only to its associated data for correct evaluation of the accessor for associated data.
	 */
	public record EntityAssociatedDataEvaluationDto(@Delegate EntityContract delegate, boolean requestedLocalizedAssociatedData)
		implements AssociatedDataContract {}

	/**
	 * DTO to limit the scope of the source {@link EntityContract} only to its references for correct evaluation of the accessor for references.
	 */
	public record EntityReferencesEvaluationDto(@Delegate EntityContract delegate)
		implements ReferencesContract {}
}
