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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;

/**
 * Implementation of {@link EntityToBitmapFilter} that selects the prefetched entities holding an attribute value the
 * {@link AttributeValueMatcher} of a constraint accepts. It is the prefetch alternative of the filter index lookup the
 * attribute translators perform, and it asks the very matcher the index lookup is verified with, so both answer alike.
 * Translators capture value inputs with {@link #forConstraint}; raw function entry points retain instance identity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeBitmapFilter implements EntityToBitmapFilter {
	/** Unique identifier generated from the fully qualified holder name. */
	private static final long CLASS_ID = -4541169866901759265L;
	/** Immutable value inputs captured by the controlled factory, or an opaque function marker. */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final Identity declaredIdentity;

	/**
	 * Contains name of the attribute value that has to be retrieved and passed to the filter predicate.
	 */
	@IdentityExempt(IdentityExempt.Reason.DERIVED)
	private final String attributeName;
	/**
	 * Contains the requirements for entity prefetching necessary to be fulfilled in order this filter works.
	 */
	@IdentityExempt(IdentityExempt.Reason.DERIVED)
	private final EntityContentRequire requirements;
	/**
	 * Contains the attribute schema location function, that will extract the proper attribute schema from the entity
	 * schema. The processing scope owns this accessor; the declared identity captures the required traits.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final TriFunction<EntitySchemaContract, String, AttributeTrait[],
		AttributeSchemaContract> attributeSchemaAccessor;
	/**
	 * Contains the processing scope's attribute value accessor. The declared identity captures its locale.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor;
	/**
	 * Contains the factory function that will create a predicate (based on passed entity schema) that must be fulfilled
	 * in order attribute value is accepted by the filter. The controlled factory builds this strategy from the declared
	 * constraint inputs; raw function construction retains an opaque identity.
	 */
	@IdentityExempt(IdentityExempt.Reason.DERIVED)
	private final Function<AttributeSchemaContract, Predicate<Stream<Optional<AttributeValue>>>> filterFactory;
	/**
	 * Contains array of all attribute required traits.
	 */
	@IdentityExempt(IdentityExempt.Reason.DERIVED)
	private final AttributeTrait[] requiredAttributeTraits;
	/**
	 * Contains the Bitmap result that has been memoized for efficiency.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Bitmap memoizedResult;

	/**
	 * Creates the filter that selects an entity when any value it holds under the attribute name satisfies the
	 * {@link AttributeValueMatcher}. An array value satisfies it when any of its elements does, a missing value never
	 * does. The attribute content the filter needs prefetched is the attribute alone.
	 *
	 * @param attributeName           name of the attribute whose values are tested
	 * @param attributeSchemaAccessor locates the attribute schema in the entity schema
	 * @param attributeValueAccessor  locates the attribute values in the entity body
	 * @param matcherFactory          creates the matcher from the schema of the attribute, called once per entity type
	 *                                of the examined entities
	 * @param requiredAttributeTraits the traits the attribute schema must have
	 * @return the filter
	 */
	@Nonnull
	public static AttributeBitmapFilter matching(
		@Nonnull String attributeName,
		@Nonnull TriFunction<EntitySchemaContract, String, AttributeTrait[], AttributeSchemaContract>
			attributeSchemaAccessor,
		@Nonnull BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Function<AttributeSchemaContract, AttributeValueMatcher> matcherFactory,
		@Nonnull AttributeTrait... requiredAttributeTraits
	) {
		return new AttributeBitmapFilter(
			attributeName,
			attributeContent(attributeName),
			attributeSchemaAccessor,
			attributeValueAccessor,
			attributeSchema -> {
				final AttributeValueMatcher matcher = matcherFactory.apply(attributeSchema);
				return values -> values.anyMatch(value -> value.isPresent() && matcher.matches(value.get().value()));
			},
			requiredAttributeTraits
		);
	}

	/**
	 * Creates the filter from a raw factory of the test of the entity values. Prefer {@link #matching}, which wraps a
	 * matcher into that test.
	 *
	 * @param attributeName the name of the attribute
	 * @param requirements the content the prefetched entities must be fetched with
	 * @param attributeSchemaAccessor resolves the schema of the attribute in the entity schema
	 * @param attributeValueAccessor reads the values of the attribute of an entity
	 * @param filterFactory creates the test of the values of an entity from the attribute schema
	 * @param requiredAttributeTraits the traits the attribute schema must have
	 */
	public AttributeBitmapFilter(
		@Nonnull String attributeName,
		@Nonnull EntityContentRequire requirements,
		@Nonnull TriFunction<EntitySchemaContract, String, AttributeTrait[], AttributeSchemaContract> attributeSchemaAccessor,
		@Nonnull BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Function<AttributeSchemaContract, Predicate<Stream<Optional<AttributeValue>>>> filterFactory,
		@Nonnull AttributeTrait... requiredAttributeTraits
	) {
		this(attributeName, requirements, attributeSchemaAccessor, attributeValueAccessor, filterFactory,
			new Identity(List.of(), requirements.toString(), null, traitsMask(requiredAttributeTraits),
				false, null, List.of(), IdentityToken.OPAQUE), requiredAttributeTraits);
	}

	@Nonnull
	@Override
	public EntityFetch getEntityRequire() {
		return new EntityFetch(this.requirements);
	}

	@Nonnull
	@Override
	public Bitmap filter(@Nonnull QueryExecutionContext context) {
		if (this.memoizedResult == null) {
			final List<ServerEntityDecorator> prefetchedEntities = context.getPrefetchedEntities();
			if (prefetchedEntities == null) {
				this.memoizedResult = EmptyBitmap.INSTANCE;
			} else {
				String entityType = null;
				Predicate<Stream<Optional<AttributeValue>>> filter = null;
				final BaseBitmap result = new BaseBitmap();
				// iterate over all entities
				for (SealedEntity entity : prefetchedEntities) {
					/* we can be sure entities are sorted by type because:
					   1. all entities share the same type
					   2. or entities are fetched via {@link QueryPlanningContext#prefetchEntities(EntityReference[], EntityContentRequire[])}
					      that fetches them by entity type in bulk
					*/
					final EntitySchemaContract entitySchema = entity.getSchema();
					if (!Objects.equals(entityType, entitySchema.getName())) {
						entityType = entitySchema.getName();
						final AttributeSchemaContract attributeSchema = this.attributeSchemaAccessor.apply(
							entitySchema, this.attributeName, this.requiredAttributeTraits
						);
						filter = this.filterFactory.apply(attributeSchema);
					}
					// and filter by predicate
					if (filter != null) {
						final Stream<Optional<AttributeValue>> valueStream = this.attributeValueAccessor.apply(entity, this.attributeName);
						if (valueStream != null && filter.test(valueStream)) {
							result.add(context.translateEntity(entity));
						}
					}
				}
				this.memoizedResult = result;
			}
		}
		return this.memoizedResult;
	}

	/** Captures the execution strategy together with its detached value inputs. */
	private AttributeBitmapFilter(
		@Nonnull String attributeName,
		@Nonnull EntityContentRequire requirements,
		@Nonnull TriFunction<EntitySchemaContract, String, AttributeTrait[], AttributeSchemaContract> attributeSchemaAccessor,
		@Nonnull BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Function<AttributeSchemaContract, Predicate<Stream<Optional<AttributeValue>>>> filterFactory,
		@Nonnull Identity declaredIdentity,
		@Nonnull AttributeTrait... requiredAttributeTraits
	) {
		this.attributeName = attributeName;
		this.requirements = requirements;
		this.attributeSchemaAccessor = attributeSchemaAccessor;
		this.attributeValueAccessor = attributeValueAccessor;
		this.filterFactory = filterFactory;
		this.requiredAttributeTraits = requiredAttributeTraits.clone();
		this.declaredIdentity = declaredIdentity;
	}

	/**
	 * Captures a constraint whose executable matcher the caller constructs from exactly these value inputs.
	 * The accessors must be those of the query's processing scope. Unknown argument encodings retain instance identity.
	 *
	 * @param constraint constraint implemented by the supplied filter factory
	 * @param attributeName attribute read by the filter
	 * @param requirements content needed by the filter
	 * @param attributeSchemaAccessor processing scope's schema accessor
	 * @param attributeValueAccessor processing scope's value accessor in the given locale
	 * @param filterFactory matcher implementing the constraint
	 * @param locale locale used by the value accessor
	 * @param matcherPerEntityType whether each entity type supplies its own matcher
	 * @param moment implicit query moment, absent when the resolved matcher does not use it
	 * @param executionContext detached schema and scope inputs, null for an undeclared matcher
	 * @param requiredAttributeTraits required schema traits
	 * @return filter holding detached constraint arguments
	 */
	@Nonnull
	public static AttributeBitmapFilter forConstraint(
		@Nonnull FilterConstraint constraint,
		@Nonnull String attributeName,
		@Nonnull EntityContentRequire requirements,
		@Nonnull TriFunction<EntitySchemaContract, String, AttributeTrait[], AttributeSchemaContract> attributeSchemaAccessor,
		@Nonnull BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Function<AttributeSchemaContract, Predicate<Stream<Optional<AttributeValue>>>> filterFactory,
		@Nullable Locale locale,
		boolean matcherPerEntityType,
		@Nullable OffsetDateTime moment,
		@Nullable List<String> executionContext,
		@Nonnull AttributeTrait... requiredAttributeTraits
	) {
		final List<String> arguments = captureConstraint(constraint);
		return new AttributeBitmapFilter(
			attributeName, requirements, attributeSchemaAccessor, attributeValueAccessor, filterFactory,
			new Identity(arguments == null ? List.of() : arguments, requirements.toString(), locale,
				traitsMask(requiredAttributeTraits), matcherPerEntityType, moment,
				executionContext == null ? List.of() : executionContext,
				arguments == null || executionContext == null ? IdentityToken.OPAQUE : null), requiredAttributeTraits
		);
	}

	/**
	 * Captures the plain type and localization read when a main-query matcher is constructed. Only decimal
	 * normalization reads precision, and only a scalar decimal probe is checked against the filter-index domain.
	 * Array handling instead follows each examined value and does not read the declared array shape.
	 *
	 * @param schema resolved attribute schema
	 * @param requestedScopes scopes used by decimal probe validation
	 * @return immutable scalar matcher inputs
	 */
	@Nonnull
	public static List<String> captureMatcherSchema(
		@Nonnull AttributeSchemaContract schema, @Nonnull Set<Scope> requestedScopes
	) {
		final Class<? extends Serializable> plainType = schema.getPlainType();
		final List<String> result = new ArrayList<>(4);
		result.add(plainType.getName());
		result.add(Boolean.toString(schema.isLocalized()));
		if (BigDecimal.class.isAssignableFrom(plainType) || BigDecimalNumberRange.class.isAssignableFrom(plainType)) {
			result.add(Integer.toString(schema.getIndexedDecimalPlaces()));
		}
		if (BigDecimal.class.equals(plainType)) {
			boolean hasFilterIndex = false;
			for (final Scope scope : requestedScopes) {
				if (schema.hasFilterIndexInScope(scope)) {
					hasFilterIndex = true;
					break;
				}
			}
			result.add(Boolean.toString(hasFilterIndex));
		}
		return List.copyOf(result);
	}

	/**
	 * Resolves the query-owned schema override used by deferred schema lookup. In its absence the entity supplies
	 * its own schema at match time; its type cannot be assumed while the alternative is constructed.
	 *
	 * @param visitor query whose processing scope owns the accessors
	 * @param attributeName attribute being matched
	 * @return catalog or reference override, or null for a schema supplied by each examined entity
	 */
	@Nullable
	public static AttributeSchemaContract resolveSchemaOverride(
		@Nonnull FilterByVisitor visitor, @Nonnull String attributeName
	) {
		final AttributeSchemaContract globalSchema = visitor.getCatalogSchema().getAttribute(attributeName).orElse(null);
		if (globalSchema != null) {
			return globalSchema;
		}
		final ReferenceSchemaContract referenceSchema =
			visitor.getProcessingScope().getAttributeSchemaAccessor().getReferenceSchema();
		return referenceSchema == null ? null : referenceSchema.getAttribute(attributeName).orElse(null);
	}

	/**
	 * Captures the query-owned inputs read by the schema and value accessors. A fixed matcher supplies its own
	 * detached construction inputs; deferred construction also depends on any catalog or reference override.
	 * Trait validation reads the request scopes restored after translation and only the flags of those traits.
	 *
	 * @param visitor query whose processing scope owns the accessors
	 * @param attributeName attribute being matched
	 * @param matcherSchemaInputs schema inputs captured with a fixed matcher, empty otherwise
	 * @param deferredMatcher whether the alternative constructs a matcher from each resolved schema
	 * @param requiredTraits traits checked by schema validation
	 * @return immutable execution inputs without retaining the visitor, scopes or schemas
	 */
	@Nonnull
	public static List<String> captureExecutionContext(
		@Nonnull FilterByVisitor visitor,
		@Nonnull String attributeName,
		@Nonnull List<String> matcherSchemaInputs,
		boolean deferredMatcher,
		@Nonnull AttributeTrait... requiredTraits
	) {
		final ProcessingScope<?> processingScope = visitor.getProcessingScope();
		final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema();
		final ReferenceSchemaContract accessorReferenceSchema =
			processingScope.getAttributeSchemaAccessor().getReferenceSchema();
		final int requiredTraitsMask = traitsMask(requiredTraits);
		final AttributeSchemaContract schemaOverride = deferredMatcher || requiredTraitsMask != 0 ?
			resolveSchemaOverride(visitor, attributeName) : null;
		final List<String> result = new ArrayList<>(16 + matcherSchemaInputs.size());
		// The value accessor selects reference rows, while the schema accessor selects their attribute definitions.
		result.add(referenceSchema == null ? "" : referenceSchema.getName());
		result.add(accessorReferenceSchema == null ? "" : accessorReferenceSchema.getName());
		if (deferredMatcher && (schemaOverride == null || BigDecimal.class.equals(schemaOverride.getPlainType()))) {
			for (final Scope scope : Scope.values()) {
				result.add(Boolean.toString(processingScope.getScopes().contains(scope)));
			}
		}
		if (requiredTraitsMask != 0) {
			for (final Scope scope : Scope.values()) {
				result.add(Boolean.toString(visitor.getScopes().contains(scope)));
				if (visitor.getScopes().contains(scope) && accessorReferenceSchema != null) {
					result.add(Boolean.toString(accessorReferenceSchema.isIndexedInScope(scope)));
				}
			}
		}
		result.add(Integer.toString(matcherSchemaInputs.size()));
		result.addAll(matcherSchemaInputs);
		if (deferredMatcher || requiredTraitsMask != 0) {
			result.add(schemaOverride == null ? "entity schema" : "schema override");
			if (schemaOverride != null) {
				if (deferredMatcher) {
					result.addAll(captureMatcherSchema(schemaOverride, processingScope.getScopes()));
				}
				for (final Scope scope : Scope.values()) {
					if (visitor.getScopes().contains(scope)) {
						if ((requiredTraitsMask & (1 << AttributeTrait.FILTERABLE.ordinal())) != 0) {
							result.add(Boolean.toString(schemaOverride.isFilterableInScope(scope)));
						}
						if ((requiredTraitsMask & ((1 << AttributeTrait.FILTERABLE.ordinal()) |
							(1 << AttributeTrait.UNIQUE.ordinal()))) != 0) {
							result.add(Boolean.toString(schemaOverride.isUniqueInScope(scope)));
						}
						if ((requiredTraitsMask & (1 << AttributeTrait.SORTABLE.ordinal())) != 0) {
							result.add(Boolean.toString(schemaOverride.isSortableInScope(scope)));
						}
					}
				}
			}
		}
		return List.copyOf(result);
	}

	/** Converts trait membership to a primitive mask without retaining the caller's array. */
	private static int traitsMask(@Nonnull AttributeTrait[] traits) {
		int result = 0;
		for (final AttributeTrait trait : traits) {
			result |= 1 << trait.ordinal();
		}
		return result;
	}

	/** Captures each argument's concrete type and canonical text without retaining any constraint or argument. */
	@Nullable
	private static List<String> captureConstraint(@Nonnull FilterConstraint constraint) {
		final Serializable[] arguments = constraint.getArguments();
		final List<String> result = new ArrayList<>(1 + 2 * arguments.length);
		result.add(constraint.getName());
		for (final Serializable argument : arguments) {
			if (argument == null) {
				result.add("null");
				result.add("");
			} else {
				final String text = argumentText(argument);
				if (text == null) {
					return null;
				}
				result.add(argument.getClass().getName());
				result.add(text);
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Encodes only immutable supported scalars. Arrays have no EvitaQL formatting branch and remain opaque,
	 * including nested arrays. Temporal text retains nanoseconds; decimal ranges also name their comparison precision.
	 */
	@Nullable
	private static String argumentText(@Nullable Serializable argument) {
		if (argument == null) {
			return null;
		}
		final Class<?> type = argument.getClass();
		if (type == String.class || type == Character.class || type == Byte.class || type == Short.class ||
			type == Integer.class || type == Long.class || type == Boolean.class || type == BigDecimal.class ||
			type == OffsetDateTime.class || type == LocalDateTime.class || type == LocalDate.class ||
			type == LocalTime.class || type == Currency.class || type == UUID.class ||
			type == ByteNumberRange.class || type == ShortNumberRange.class ||
			type == IntegerNumberRange.class || type == LongNumberRange.class || type == DateTimeRange.class) {
			return argument.toString();
		} else if (argument instanceof AttributeSpecialValue specialValue) {
			return specialValue.name();
		} else if (argument instanceof Locale locale) {
			final String languageTag = locale.toLanguageTag();
			return Locale.forLanguageTag(languageTag).equals(locale) ? languageTag : null;
		} else if (type == BigDecimalNumberRange.class) {
			final BigDecimalNumberRange range = (BigDecimalNumberRange) argument;
			if ((range.getPreciseFrom() != null && range.getPreciseFrom().getClass() != BigDecimal.class) ||
				(range.getPreciseTo() != null && range.getPreciseTo().getClass() != BigDecimal.class)) {
				return null;
			}
			return range + ";precision=" + range.getEffectiveRetainedDecimalPlaces() +
				";explicit=" + range.getRetainedDecimalPlaces() +
				";from=" + range.getFrom() + ";to=" + range.getTo();
		}
		return null;
	}

	/** Returns the stable holder discriminator. */
	@Override
	public long getClassId() {
		return CLASS_ID;
	}

	/** Returns the immutable inputs captured when the matcher was constructed; allocates no objects. */
	@Nonnull
	@Override
	public IdentityRecord identity() {
		return this.declaredIdentity;
	}

	/**
	 * Detached constraint arguments and execution choices of an attribute matcher.
	 *
	 * @param constraint operator followed by argument type and canonical text pairs
	 * @param requirements required prefetched content in EvitaQL
	 * @param locale locale read by the value accessor
	 * @param requiredTraits primitive trait membership mask
	 * @param matcherPerEntityType whether matcher creation depends on each entity type
	 * @param moment implicit query moment
	 * @param executionContext detached schema, entity type, reference and scope inputs
	 * @param matcher opaque marker for an uncontrolled or unsupported matcher, null for declared value inputs
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(
		@Nonnull List<String> constraint,
		@Nonnull String requirements,
		@Nullable Locale locale,
		int requiredTraits,
		boolean matcherPerEntityType,
		@Nullable OffsetDateTime moment,
		@Nonnull List<String> executionContext,
		@Nullable IdentityToken matcher
	) implements IdentityRecord {
		/** Detaches the argument sequence from the factory's temporary storage. */
		public Identity {
			constraint = List.copyOf(constraint);
			executionContext = List.copyOf(executionContext);
		}

		/** Returns the owning holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Hashes and charges every retained argument string and execution choice. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.addStrings(this.constraint);
			accumulator.add(this.requirements);
			accumulator.add(this.locale);
			accumulator.add(this.requiredTraits);
			accumulator.add(this.matcherPerEntityType);
			accumulator.add(this.moment);
			accumulator.addStrings(this.executionContext);
			accumulator.add(this.matcher);
		}
	}
}
