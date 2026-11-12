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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.identity.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Controlled value matchers and uncontrolled function-based attribute filters.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AttributeBitmapFilterIdentityFixture implements IdentityFixture<AttributeBitmapFilter> {
	/** Shared schema accessor used to prove opacity for identical function inputs. */
	private static final io.evitadb.function.TriFunction<
		io.evitadb.api.requestResponse.schema.EntitySchemaContract, String, AttributeTrait[],
		io.evitadb.api.requestResponse.schema.AttributeSchemaContract> SCHEMA_ACCESSOR =
		(schema, name, traits) -> schema.getAttribute(name).orElseThrow();
	/** Shared value accessor of the uncontrolled construction mode. */
	private static final java.util.function.BiFunction<io.evitadb.api.requestResponse.data.EntityContract, String,
		java.util.stream.Stream<java.util.Optional<io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue>>>
		VALUE_ACCESSOR = (entity, name) -> java.util.stream.Stream.of(entity.getAttributeValue(name));
	/** Shared opaque value test supplied to separate holders. */
	private static final java.util.function.Function<io.evitadb.api.requestResponse.schema.AttributeSchemaContract,
		java.util.function.Predicate<java.util.stream.Stream<
			java.util.Optional<io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue>>>>
		FILTER_FACTORY = schema -> values -> values.anyMatch(java.util.Optional::isPresent);
	/** Shared equality matcher factory of the raw matching mode. */
	private static final java.util.function.Function<io.evitadb.api.requestResponse.schema.AttributeSchemaContract,
		AttributeValueMatcher> MATCHER_FACTORY = schema -> AttributeValueMatcher.equalToAny(
			new AttributeIndexKey(null, "a", null), schema.getPlainType(),
			schema.getIndexedDecimalPlaces(), new Serializable[]{1});
	/** Covers both matcher modes and the two uncontrolled construction entry points. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<AttributeBitmapFilter>> modes() {
		return Map.of(
			"known schema", mode(false),
			"matcher per entity type", mode(true),
			"raw functions", new ConstructionMode<>(null, Map.of(), Map.of("functions", () -> raw()),
				Map.of(), Map.of(), Set.of(), Set.of(), Set.of()),
			"raw matching", new ConstructionMode<>(null, Map.of(), Map.of("matcher factory", () ->
				AttributeBitmapFilter.matching("a", SCHEMA_ACCESSOR, VALUE_ACCESSOR, MATCHER_FACTORY)),
				Map.of(), Map.of(), Set.of(), Set.of(), Set.of())
		);
	}

	/** Supplies independent variants of every captured input, including an unsupported argument. */
	@Nonnull
	private static ConstructionMode<AttributeBitmapFilter> mode(boolean perEntityType) {
		final AttributeEquals unsupported = attributeEquals("a", 1);
		unsupported.getArguments()[1] = new int[]{1, 2};
		return new ConstructionMode<>(
			() -> create(attributeEquals("a", 1), attributeContent("a"), null, perEntityType, null,
				AttributeTrait.FILTERABLE),
			Map.of(
				"constraint", () -> create(attributeEquals("a", 2), attributeContent("a"), null,
					perEntityType, null, AttributeTrait.FILTERABLE),
				"attribute", () -> create(attributeEquals("b", 1), attributeContent("b"), null,
					perEntityType, null, AttributeTrait.FILTERABLE),
				"requirements", () -> create(attributeEquals("a", 1), attributeContentAll(), null,
					perEntityType, null, AttributeTrait.FILTERABLE),
				"locale", () -> create(attributeEquals("a", 1), attributeContent("a"), Locale.ENGLISH,
					perEntityType, null, AttributeTrait.FILTERABLE),
				"traits", () -> create(attributeEquals("a", 1), attributeContent("a"), null,
					perEntityType, null, AttributeTrait.SORTABLE),
				"matcher mode", () -> create(attributeEquals("a", 1), attributeContent("a"), null,
					!perEntityType, null, AttributeTrait.FILTERABLE),
				"execution context", () -> create(attributeEquals("a", 1), attributeContent("a"), null,
					perEntityType, null, List.of("different context"), AttributeTrait.FILTERABLE),
				"implicit moment", () -> create(attributeEquals("a", 1), attributeContent("a"), null,
					perEntityType, OffsetDateTime.parse("2026-01-01T00:00:00Z"), AttributeTrait.FILTERABLE)
			),
			Map.of("unformatted array", () -> create(unsupported, attributeContent("a"), null,
				perEntityType, null, AttributeTrait.FILTERABLE)),
			Map.of(), Map.of(), Set.of(), Set.of(), Set.of()
		);
	}

	/** Builds a controlled equality matcher using the real value matcher. */
	@Nonnull
	static AttributeBitmapFilter create(
		@Nonnull FilterConstraint constraint,
		@Nonnull EntityContentRequire requirements,
		@Nullable Locale locale,
		boolean perEntityType,
		@Nullable OffsetDateTime moment,
		@Nonnull AttributeTrait... traits
	) {
		return create(constraint, requirements, locale, perEntityType, moment, List.of("controlled"), traits);
	}

	/** Builds a controlled matcher with explicit execution inputs. */
	@Nonnull
	static AttributeBitmapFilter create(
		@Nonnull FilterConstraint constraint,
		@Nonnull EntityContentRequire requirements,
		@Nullable Locale locale,
		boolean perEntityType,
		@Nullable OffsetDateTime moment,
		@Nonnull List<String> executionContext,
		@Nonnull AttributeTrait... traits
	) {
		final String name = (String) constraint.getArguments()[0];
		final Serializable value = constraint.getArguments().length > 1 ? constraint.getArguments()[1] : null;
		return AttributeBitmapFilter.forConstraint(
			constraint, name, requirements,
			(schema, attributeName, requiredTraits) -> schema.getAttribute(attributeName).orElseThrow(),
			(entity, attributeName) -> java.util.stream.Stream.of(locale == null ?
				entity.getAttributeValue(attributeName) : entity.getAttributeValue(attributeName, locale)),
			schema -> {
				final AttributeValueMatcher matcher;
				if (constraint instanceof io.evitadb.api.query.filter.AttributeBetween between) {
					matcher = new io.evitadb.core.query.filter.translator.attribute.AttributeBetweenTranslator()
						.createMatcher(between, schema, new AttributeIndexKey(null, name, locale),
							() -> java.util.Objects.requireNonNull(moment));
				} else {
					matcher = AttributeValueMatcher.equalToAny(new AttributeIndexKey(null, name, locale),
						schema.getPlainType(), schema.getIndexedDecimalPlaces(), new Serializable[]{value});
				}
				return values -> values.anyMatch(attribute -> attribute.isPresent() && matcher.matches(attribute.get().value()));
			},
			locale, perEntityType, moment, executionContext, traits
		);
	}

	/** Creates an uncontrolled function filter, whose key must remain tied to its holder. */
	@Nonnull
	static AttributeBitmapFilter raw() {
		return new AttributeBitmapFilter("a", attributeContent("a"),
			SCHEMA_ACCESSOR, VALUE_ACCESSOR, FILTER_FACTORY);
	}

	/** Builds the same leaf side key used by a selection formula. */
	@Nonnull
	static FormulaKey key(@Nonnull AttributeBitmapFilter filter) {
		return FormulaKey.of(filter.identity(), filter.getClassId(), FormulaKey.NO_CHILDREN, filter);
	}
}
