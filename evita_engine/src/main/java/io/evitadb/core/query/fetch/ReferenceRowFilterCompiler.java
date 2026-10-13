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

package io.evitadb.core.query.fetch;

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.attribute.AbstractAttributeStringSearchTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeInRangeTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Compiles the filter of the fetched references (`referenceContent(..., filterBy(...))`) into a
 * {@link ReferenceRowFilter} - a tree of {@link RowTest} nodes judging one reference row of one owner entity at a time.
 * The planning check and the fetch run this one compiler, so what the check accepts the fetch evaluates, and nothing
 * either of them refuses depends on data.
 *
 * ## What the filter may contain
 *
 * A closed set of constraints has a meaning on a single row, and everything else is refused with an
 * {@link EvitaInvalidUsageException} naming the constraint and the reference:
 *
 * - `and`, `or`, `not` and `inScope` anywhere in the tree;
 * - the leaves `entityPrimaryKeyInSet` (the referenced key), `entityLocaleEquals` (a locale of the owner entity) and
 *   the twelve attribute constraints of the reference;
 * - `entityHaving` and `groupHaving`, whose content is a filter over another collection - it is not walked here, but
 *   planned as a nested query by {@link NestedSet};
 * - `scope(...)` only directly in the root `filterBy`, where it widens the scopes the references are looked up in and
 *   produces no node. Below the root it has no meaning on a row, and it is refused rather than read as "true".
 *
 * A constraint the query model considers not applicable (a container without children) is dropped as the query model
 * itself drops it, and a container left without children is dropped with it. A filter left with no node accepts every
 * row.
 *
 * ## Which locale an attribute leaf reads
 *
 * The locale of the leaf is the locale of the filter's own `entityLocaleEquals`, looked up exactly as the body fetch
 * looks it up ({@link ReferencedEntityFetcher#findReferenceFilterLocale}), and the locale of the owner request when the
 * filter names none. A localized attribute without any locale is refused with {@link EntityLocaleMissingException},
 * unless the attribute is unique across locales in a scope the leaf is compiled for - the rule the attribute
 * translators apply.
 *
 * ## Readings chosen per scope of the owner
 *
 * The flags of an attribute may differ per scope, and so does the index the main query would read it from: a
 * localized attribute unique across locales in a scope is read in any locale there, and in the leaf locale where it is
 * not. Every attribute node therefore carries one reading per scope it is compiled for, each chosen by that scope's own
 * declaration, and a row is read by the scope of its owner. An attribute unique in one scope and only filterable in
 * another is read in each scope by that scope's declaration - not by a lookup in the unique index of one scope that
 * passes over the other.
 *
 * The attribute leaves request no capability of the schema: the row is read from the reference itself, so the
 * attribute need not be filterable or unique, and the reference need not be indexed, in any scope. The nested filters
 * are planned through the attribute translators of their collection and request what they use there.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ReferenceRowFilterCompiler {
	/**
	 * All scopes, indexed by their ordinal.
	 */
	private static final Scope[] ALL_SCOPES = Scope.values();
	/**
	 * The keys of a reading in which no value can count.
	 */
	private static final AttributeKey[] NO_KEYS = new AttributeKey[0];
	/**
	 * The traits an attribute leaf requires - none, see the class documentation.
	 */
	private static final AttributeTrait[] NO_TRAITS = new AttributeTrait[0];

	/**
	 * The inputs of the compilation.
	 */
	@Nonnull private final CompileContext context;
	/**
	 * The locale the localized attribute leaves read, NULL when neither the filter nor the owner request names one.
	 */
	@Nullable private final Locale leafLocale;
	/**
	 * The referenced keys the root `entityPrimaryKeyInSet` constraints admit, NULL when the root names none.
	 */
	@Nullable private final Bitmap rootTargetBound;

	/**
	 * The inputs of one compilation of the filter of the fetched references.
	 *
	 * The visitor decides when the nested `entityHaving` / `groupHaving` sets are resolved: a visitor that only checks
	 * the filter ({@link FilterByVisitor#createConstraintCheckVisitor}) resolves them during the compilation, so that
	 * the nested filters are validated and their capabilities counted whatever the data, any other visitor resolves
	 * them when the first row reaches them (see {@link NestedSet}).
	 *
	 * @param entitySchema    the schema of the entity owning the references
	 * @param referenceSchema the schema of the filtered reference
	 * @param filterByVisitor the visitor the nested queries are planned with
	 * @param requestScopes   the scopes of the request the references are fetched for - the owners live in them
	 * @param ownerLocale     the locale of the request the references are fetched for, NULL when it names none
	 * @param existingTargets supplier of the existing targets of the rows to test, which restrict the nested queries
	 *                        of `entityHaving`; NULL when they are not known (a check) and the queries are not
	 *                        restricted
	 * @param rowGroups       supplier of the groups of the rows to test, which restrict the nested queries of
	 *                        `groupHaving`; NULL when they are not known (a check) and the queries are not
	 *                        restricted
	 */
	record CompileContext(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Set<Scope> requestScopes,
		@Nullable Locale ownerLocale,
		@Nullable Supplier<Bitmap> existingTargets,
		@Nullable Supplier<Bitmap> rowGroups
	) {
	}

	/**
	 * Compiles the filter of the fetched references.
	 *
	 * @param filterBy the filter of the fetched references, NULL when there is none
	 * @param context  the inputs of the compilation
	 * @return the compiled filter
	 * @throws EvitaInvalidUsageException when the filter contains a constraint without a meaning on a row, or a leaf
	 *                                    that cannot be evaluated whatever the data
	 */
	@Nonnull
	static ReferenceRowFilter compile(@Nullable FilterBy filterBy, @Nonnull CompileContext context) {
		if (filterBy == null) {
			return ReferenceRowFilter.ACCEPT_ALL;
		}
		final Locale filterLocale = ReferencedEntityFetcher.findReferenceFilterLocale(filterBy);
		final ReferenceRowFilterCompiler compiler = new ReferenceRowFilterCompiler(
			context,
			filterLocale == null ? context.ownerLocale() : filterLocale,
			getRootTargetBound(filterBy)
		);
		final RowTest root = compiler.compileRoot(
			filterBy, ReferencedEntityFetcher.gatherSearchedScopes(filterBy, context.requestScopes())
		);
		return root == null ? ReferenceRowFilter.ACCEPT_ALL : new ReferenceRowFilter(root);
	}

	/**
	 * Returns the referenced keys the `entityPrimaryKeyInSet` constraints directly in the root of the filter admit -
	 * every other condition is conjoined with them, so no row referencing another key can pass. A key set nested in an
	 * `or` or a `not` bounds nothing and is not read.
	 *
	 * @param filterBy the filter of the fetched references
	 * @return the admitted keys, NULL when the root names none
	 */
	@Nullable
	private static Bitmap getRootTargetBound(@Nonnull FilterBy filterBy) {
		Bitmap bound = null;
		for (FilterConstraint child : filterBy.getChildren()) {
			if (child instanceof EntityPrimaryKeyInSet primaryKeyInSet) {
				final Bitmap keys = new BaseBitmap(primaryKeyInSet.getPrimaryKeys());
				bound = bound == null ? keys : new BaseBitmap(intersect(bound, keys));
			}
		}
		return bound;
	}

	/**
	 * Returns the keys present in both bitmaps.
	 *
	 * @param first  the first bitmap
	 * @param second the second bitmap
	 * @return the keys of the intersection, ascending
	 */
	@Nonnull
	private static int[] intersect(@Nonnull Bitmap first, @Nonnull Bitmap second) {
		return PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(first), RoaringBitmapBackedBitmap.getRoaringBitmap(second)
		).toArray();
	}

	/**
	 * Tells whether the localized attribute is read in the leaf locale alone in the scope - which it is everywhere but
	 * where the attribute is unique across locales, as its unique index then holds a value of any locale.
	 *
	 * @param attributeSchema the schema of the attribute
	 * @param scope           the scope of the owner
	 * @return true when only the value in the leaf locale counts in the scope
	 */
	private static boolean isBoundToLocale(@Nonnull AttributeSchemaContract attributeSchema, @Nonnull Scope scope) {
		return !attributeSchema.isUniqueInScope(scope) || attributeSchema.isUniqueWithinLocaleInScope(scope);
	}

	/**
	 * Returns the same keys for every scope of the compilation.
	 *
	 * @param scopes the scopes the node is compiled for
	 * @param keys   the keys to read
	 * @return the keys indexed by {@link Scope#ordinal()}
	 */
	@Nonnull
	private static AttributeKey[][] getKeysInEveryScope(@Nonnull Set<Scope> scopes, @Nonnull AttributeKey[] keys) {
		final AttributeKey[][] keysByScope = new AttributeKey[ALL_SCOPES.length][];
		for (Scope scope : scopes) {
			keysByScope[scope.ordinal()] = keys;
		}
		return keysByScope;
	}

	/**
	 * Returns the single node standing for the compiled children - NULL when there is none, the child itself when
	 * there is one, and the container otherwise.
	 *
	 * @param children  the compiled children
	 * @param container the factory of the container
	 * @return the node, or NULL
	 */
	@Nullable
	private static RowTest combine(@Nonnull List<RowTest> children, @Nonnull Function<RowTest[], RowTest> container) {
		return switch (children.size()) {
			case 0 -> null;
			case 1 -> children.get(0);
			default -> container.apply(children.toArray(RowTest[]::new));
		};
	}

	/**
	 * Creates the compiler.
	 *
	 * @param context         the inputs of the compilation
	 * @param leafLocale      the locale the localized attribute leaves read, NULL when none is known
	 * @param rootTargetBound the referenced keys the root admits, NULL when it names none
	 */
	private ReferenceRowFilterCompiler(
		@Nonnull CompileContext context,
		@Nullable Locale leafLocale,
		@Nullable Bitmap rootTargetBound
	) {
		this.context = context;
		this.leafLocale = leafLocale;
		this.rootTargetBound = rootTargetBound;
	}

	/**
	 * Compiles the root of the filter - the children of `filterBy`, conjoined. A `scope(...)` among them produces no
	 * node: it has widened the examined scopes already.
	 *
	 * @param filterBy       the filter of the fetched references
	 * @param examinedScopes the scopes the references are looked up in - those of the request and of the root
	 *                       `scope(...)`
	 * @return the root node, NULL when the filter has no condition on the row
	 */
	@Nullable
	private RowTest compileRoot(@Nonnull FilterBy filterBy, @Nonnull Set<Scope> examinedScopes) {
		final FilterConstraint[] children = filterBy.getChildren();
		final List<RowTest> compiled = new ArrayList<>(children.length);
		for (FilterConstraint child : children) {
			if (!(child instanceof EntityScope)) {
				addIfPresent(compiled, compile(child, examinedScopes));
			}
		}
		return combine(compiled, RowTest.And::new);
	}

	/**
	 * Compiles the children of a container.
	 *
	 * @param children the children
	 * @param scopes   the scopes the children are compiled for
	 * @return the compiled children, without those dropped
	 */
	@Nonnull
	private List<RowTest> compileChildren(@Nonnull FilterConstraint[] children, @Nonnull Set<Scope> scopes) {
		final List<RowTest> compiled = new ArrayList<>(children.length);
		for (FilterConstraint child : children) {
			addIfPresent(compiled, compile(child, scopes));
		}
		return compiled;
	}

	/**
	 * Adds the node to the list unless it is NULL.
	 *
	 * @param nodes the list
	 * @param node  the node, or NULL
	 */
	private static void addIfPresent(@Nonnull List<RowTest> nodes, @Nullable RowTest node) {
		if (node != null) {
			nodes.add(node);
		}
	}

	/**
	 * Compiles one constraint below the root.
	 *
	 * @param constraint the constraint
	 * @param scopes     the scopes the constraint is compiled for - the examined scopes, or the scope of the
	 *                   enclosing `inScope`
	 * @return the node, NULL when the constraint is dropped
	 * @throws EvitaInvalidUsageException when the constraint has no meaning on a row
	 */
	@Nullable
	private RowTest compile(@Nonnull FilterConstraint constraint, @Nonnull Set<Scope> scopes) {
		if (!constraint.isApplicable()) {
			return null;
		}
		return switch (constraint) {
			case And and -> combine(compileChildren(and.getChildren(), scopes), RowTest.And::new);
			case Or or -> combine(compileChildren(or.getChildren(), scopes), RowTest.Or::new);
			case Not not -> {
				final RowTest child = compile(not.getChild(), scopes);
				yield child == null ? null : new RowTest.Not(child);
			}
			case FilterInScope inScope -> compileInScope(inScope);
			case EntityLocaleEquals localeEquals -> new RowTest.OwnerLocale(localeEquals.getLocale());
			case EntityPrimaryKeyInSet primaryKeyInSet -> {
				final int[] primaryKeys = primaryKeyInSet.getPrimaryKeys();
				Arrays.sort(primaryKeys);
				yield new RowTest.TargetIn(primaryKeys);
			}
			case EntityHaving entityHaving -> compileEntityHaving(entityHaving, scopes);
			case GroupHaving groupHaving -> compileGroupHaving(groupHaving, scopes);
			case AttributeEquals equals -> compileEquality(
				equals.getAttributeName(), scopes,
				(indexKey, attributeSchema) -> AttributeValueMatcher.comparing(
					indexKey, attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces(),
					equals.getAttributeValue(), result -> result == 0
				)
			);
			case AttributeInSet inSet -> compileEquality(
				inSet.getAttributeName(), scopes,
				(indexKey, attributeSchema) -> AttributeValueMatcher.equalToAny(
					indexKey, attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces(),
					inSet.getAttributeValues()
				)
			);
			case AttributeLessThan lessThan -> compileComparison(lessThan, scopes, result -> result < 0);
			case AttributeLessThanEquals lessThanEquals ->
				compileComparison(lessThanEquals, scopes, result -> result <= 0);
			case AttributeGreaterThan greaterThan -> compileComparison(greaterThan, scopes, result -> result > 0);
			case AttributeGreaterThanEquals greaterThanEquals ->
				compileComparison(greaterThanEquals, scopes, result -> result >= 0);
			case AttributeBetween between -> compileInLeafLocale(
				between.getAttributeName(), scopes,
				(indexKey, attributeSchema) -> AttributeValueMatcher.between(
					indexKey, attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces(),
					between.getFrom(), between.getTo()
				)
			);
			case AttributeStartsWith startsWith -> compileStringSearch(startsWith, scopes, String::startsWith);
			case AttributeEndsWith endsWith -> compileStringSearch(endsWith, scopes, String::endsWith);
			case AttributeContains contains -> compileStringSearch(contains, scopes, String::contains);
			case AttributeInRange inRange -> compileInLeafLocale(
				inRange.getAttributeName(), scopes,
				(indexKey, attributeSchema) -> AttributeValueMatcher.enveloping(
					attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces(),
					AttributeInRangeTranslator.getComparableValue(
						inRange, this.context.filterByVisitor()::getNow,
						attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces()
					)
				)
			);
			case AttributeIs attributeIs -> compilePresence(attributeIs, scopes);
			case EntityScope entityScope -> throw new EvitaInvalidUsageException(
				"Constraint `" + entityScope + "` can be used in the filter of the fetched references `" +
					this.context.referenceSchema().getName() + "` only directly in its `filterBy` - it selects " +
					"the scopes the references are looked up in and has no meaning on a single reference."
			);
			default -> throw new EvitaInvalidUsageException(
				"Constraint `" + constraint + "` cannot be used in the filter of the fetched references `" +
					this.context.referenceSchema().getName() + "` - the filter is evaluated on every reference " +
					"by itself, and only `and`, `or`, `not`, `inScope`, `entityPrimaryKeyInSet`, " +
					"`entityLocaleEquals`, `entityHaving`, `groupHaving` and the attribute constraints have " +
					"a meaning there."
			);
		};
	}

	/**
	 * Compiles an `inScope` container: its children are compiled for its scope alone, and the rows of owners living in
	 * another scope satisfy it.
	 *
	 * @param inScope the container
	 * @return the node, NULL when all children were dropped
	 * @throws EvitaInvalidUsageException when the scope was not requested
	 */
	@Nullable
	private RowTest compileInScope(@Nonnull FilterInScope inScope) {
		final Scope scope = inScope.getScope();
		Assert.isTrue(
			this.context.requestScopes().contains(scope),
			() -> "Scope `" + scope + "` used in `inScope` filter container was not requested by `scope` constraint!"
		);
		final RowTest child = combine(compileChildren(inScope.getChildren(), EnumSet.of(scope)), RowTest.And::new);
		return child == null ? null : new RowTest.InScope(scope, child);
	}

	/**
	 * Compiles an `entityHaving` into the set of referenced entities its nested filter selects, restricted to the
	 * existing targets of the fetched rows that the root `entityPrimaryKeyInSet` admits.
	 *
	 * @param entityHaving the constraint
	 * @param scopes       the scopes the nested query is planned in
	 * @return the node
	 * @throws EntityNotManagedException when the referenced entity type is not managed by evitaDB
	 */
	@Nonnull
	private RowTest compileEntityHaving(@Nonnull EntityHaving entityHaving, @Nonnull Set<Scope> scopes) {
		final ReferenceSchemaContract referenceSchema = this.context.referenceSchema();
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		if (!referenceSchema.isReferencedEntityTypeManaged()) {
			throw new EntityNotManagedException(referencedEntityType);
		}
		// the child alone, as the translator of `entityHaving` reads it - an applicable container has one
		final FilterConstraint nestedFilter = Objects.requireNonNull(entityHaving.getChild());
		final Supplier<Bitmap> existingTargets = this.context.existingTargets();
		final Bitmap rootBound = this.rootTargetBound;
		final Supplier<int[]> restriction;
		if (existingTargets == null) {
			restriction = null;
		} else if (rootBound == null) {
			restriction = () -> existingTargets.get().getArray();
		} else {
			restriction = () -> intersect(existingTargets.get(), rootBound);
		}
		return new RowTest.TargetInNested(
			new NestedSet(
				this.context.filterByVisitor(), referencedEntityType, nestedFilter, scopes, restriction,
				() -> "filtering reference `" + referenceSchema.getName() + "` by entity `" + referencedEntityType +
					"` having: " + nestedFilter
			)
		);
	}

	/**
	 * Compiles a `groupHaving` into the set of groups its nested filter selects, restricted to the groups of the
	 * fetched rows.
	 *
	 * @param groupHaving the constraint
	 * @param scopes      the scopes the nested query is planned in
	 * @return the node
	 * @throws EvitaInvalidUsageException when the reference declares no group type, or one not managed by evitaDB
	 */
	@Nonnull
	private RowTest compileGroupHaving(@Nonnull GroupHaving groupHaving, @Nonnull Set<Scope> scopes) {
		final ReferenceSchemaContract referenceSchema = this.context.referenceSchema();
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		Assert.notNull(
			referencedGroupType,
			() -> "Filtering constraint `" + groupHaving + "` targets reference `" +
				referenceSchema.getName() + "` that does not reference any group type."
		);
		Assert.isTrue(
			referenceSchema.isReferencedGroupTypeManaged(),
			() -> "Filtering constraint `" + groupHaving + "` targets group entity " +
				"`" + referencedGroupType + "` that is not managed by evitaDB."
		);
		// the child alone, as the translator of `groupHaving` reads it - an applicable container has one
		final FilterConstraint nestedFilter = Objects.requireNonNull(groupHaving.getChild());
		final Supplier<Bitmap> rowGroups = this.context.rowGroups();
		return new RowTest.GroupInNested(
			new NestedSet(
				this.context.filterByVisitor(), referencedGroupType, nestedFilter, scopes,
				rowGroups == null ? null : () -> rowGroups.get().getArray(),
				() -> "filtering reference `" + referenceSchema.getName() + "` by group entity `" +
					referencedGroupType + "` having: " + nestedFilter
			)
		);
	}

	/**
	 * Compiles `attributeEquals` or `attributeInSet` - the leaves the main query answers from the unique index where
	 * the attribute is unique, and which therefore read a localized attribute in any locale in a scope where it is
	 * unique across locales, and in the leaf locale elsewhere.
	 *
	 * @param attributeName  the name of the attribute
	 * @param scopes         the scopes the leaf is compiled for
	 * @param matcherFactory the factory of the matcher of the leaf
	 * @return the node
	 */
	@Nonnull
	private RowTest compileEquality(
		@Nonnull String attributeName,
		@Nonnull Set<Scope> scopes,
		@Nonnull MatcherFactory matcherFactory
	) {
		final AttributeSchemaContract attributeSchema = getAttributeSchema(attributeName, scopes);
		return new RowTest.AttributeMatch(
			getKeysByScope(attributeSchema, scopes, scope -> isBoundToLocale(attributeSchema, scope)),
			matcherFactory.create(getIndexKey(attributeSchema), attributeSchema)
		);
	}

	/**
	 * Compiles a comparison - `attributeLessThan`, `attributeLessThanEquals`, `attributeGreaterThan` or
	 * `attributeGreaterThanEquals`.
	 *
	 * @param comparison      the constraint
	 * @param scopes          the scopes the leaf is compiled for
	 * @param resultPredicate the test of the result of comparing the attribute value with the constraint value
	 * @return the node
	 */
	@Nonnull
	private RowTest compileComparison(
		@Nonnull AbstractAttributeFilterComparisonConstraintLeaf comparison,
		@Nonnull Set<Scope> scopes,
		@Nonnull IntPredicate resultPredicate
	) {
		return compileInLeafLocale(
			comparison.getAttributeName(), scopes,
			(indexKey, attributeSchema) -> AttributeValueMatcher.comparing(
				indexKey, attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces(),
				comparison.getAttributeValue(), resultPredicate
			)
		);
	}

	/**
	 * Compiles a string search - `attributeStartsWith`, `attributeEndsWith` or `attributeContains`.
	 *
	 * @param stringSearch the constraint
	 * @param scopes       the scopes the leaf is compiled for
	 * @param predicate    the test of a value against the searched text
	 * @return the node
	 * @throws EvitaInvalidUsageException when the attribute is not a string
	 */
	@Nonnull
	private RowTest compileStringSearch(
		@Nonnull AbstractAttributeFilterStringSearchConstraintLeaf stringSearch,
		@Nonnull Set<Scope> scopes,
		@Nonnull BiPredicate<String, String> predicate
	) {
		return compileInLeafLocale(
			stringSearch.getAttributeName(), scopes,
			(indexKey, attributeSchema) -> {
				AbstractAttributeStringSearchTranslator.assertStringType(stringSearch, attributeSchema);
				return AttributeValueMatcher.searching(stringSearch.getTextToSearch(), predicate);
			}
		);
	}

	/**
	 * Compiles an attribute leaf the main query answers from the filter index of the query locale - a localized
	 * attribute is read in the leaf locale in every scope, and matches nothing when there is no leaf locale.
	 *
	 * @param attributeName  the name of the attribute
	 * @param scopes         the scopes the leaf is compiled for
	 * @param matcherFactory the factory of the matcher of the leaf
	 * @return the node
	 */
	@Nonnull
	private RowTest compileInLeafLocale(
		@Nonnull String attributeName,
		@Nonnull Set<Scope> scopes,
		@Nonnull MatcherFactory matcherFactory
	) {
		final AttributeSchemaContract attributeSchema = getAttributeSchema(attributeName, scopes);
		return new RowTest.AttributeMatch(
			getKeysInEveryScope(scopes, getLeafLocaleKeys(attributeSchema)),
			matcherFactory.create(getIndexKey(attributeSchema), attributeSchema)
		);
	}

	/**
	 * Compiles an `attributeIs`. A localized attribute is read in the leaf locale in a scope where the value counts in
	 * that locale alone, and in any locale where the attribute is unique across locales or no leaf locale is known.
	 *
	 * @param attributeIs the constraint
	 * @param scopes      the scopes the leaf is compiled for
	 * @return the node
	 */
	@Nonnull
	private RowTest compilePresence(@Nonnull AttributeIs attributeIs, @Nonnull Set<Scope> scopes) {
		final AttributeSchemaContract attributeSchema = getAttributeSchema(attributeIs.getAttributeName(), scopes);
		final boolean present = switch (attributeIs.getAttributeSpecialValue()) {
			case NULL -> false;
			case NOT_NULL -> true;
		};
		// without a leaf locale the carriers are read in every locale - `attributeIs` is admitted then only because
		// a scope makes the attribute unique across locales, and reading no locale would report every row as null
		return new RowTest.AttributePresence(
			getKeysByScope(
				attributeSchema, scopes,
				scope -> this.leafLocale != null && isBoundToLocale(attributeSchema, scope)
			),
			present
		);
	}

	/**
	 * Resolves the attribute on the reference schema - the attributes of the catalog are not looked up in a
	 * reference - and refuses a localized attribute when no locale is known, unless it is unique across locales in
	 * one of the scopes (the rule of the attribute translators). No trait is required, so nothing is counted.
	 *
	 * @param attributeName the name of the attribute
	 * @param scopes        the scopes the leaf is compiled for
	 * @return the schema of the attribute
	 * @throws io.evitadb.api.exception.AttributeNotFoundException when the reference declares no such attribute
	 * @throws EntityLocaleMissingException                        when the attribute cannot be read without a locale
	 */
	@Nonnull
	private AttributeSchemaContract getAttributeSchema(@Nonnull String attributeName, @Nonnull Set<Scope> scopes) {
		final ReferenceSchemaContract referenceSchema = this.context.referenceSchema();
		final AttributeSchemaContract attributeSchema = AttributeSchemaAccessor.verifyAndReturn(
			attributeName,
			scopes,
			referenceSchema.getAttribute(attributeName).orElse(null),
			this.context.filterByVisitor().getCatalogSchema(),
			this.context.entitySchema(),
			referenceSchema,
			NO_TRAITS
		);
		Assert.isTrue(
			!attributeSchema.isLocalized() || this.leafLocale != null ||
				scopes.stream().anyMatch(scope -> !isBoundToLocale(attributeSchema, scope)),
			() -> new EntityLocaleMissingException(attributeName)
		);
		return attributeSchema;
	}

	/**
	 * Returns the keys a leaf reads in each scope: the attribute itself when it is not localized, otherwise the
	 * reading in the leaf locale in a scope where the passed test says so, and the reading in every locale elsewhere.
	 *
	 * @param attributeSchema        the schema of the attribute
	 * @param scopes                 the scopes the leaf is compiled for
	 * @param readsLeafLocaleInScope tells whether a localized attribute is read in the leaf locale alone in the scope
	 * @return the keys indexed by {@link Scope#ordinal()}
	 */
	@Nonnull
	private AttributeKey[][] getKeysByScope(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Scope> scopes,
		@Nonnull Predicate<Scope> readsLeafLocaleInScope
	) {
		final AttributeKey[] leafLocaleKeys = getLeafLocaleKeys(attributeSchema);
		if (!attributeSchema.isLocalized()) {
			return getKeysInEveryScope(scopes, leafLocaleKeys);
		}
		final AttributeKey[][] keysByScope = new AttributeKey[ALL_SCOPES.length][];
		AttributeKey[] everyLocaleKeys = null;
		for (Scope scope : scopes) {
			if (readsLeafLocaleInScope.test(scope)) {
				keysByScope[scope.ordinal()] = leafLocaleKeys;
			} else {
				if (everyLocaleKeys == null) {
					everyLocaleKeys = getEveryLocaleKeys(attributeSchema);
				}
				keysByScope[scope.ordinal()] = everyLocaleKeys;
			}
		}
		return keysByScope;
	}

	/**
	 * Returns the key of the filter index the main query would read the attribute from in the leaf locale - its
	 * locale selects the comparator of a localized string.
	 *
	 * @param attributeSchema the schema of the attribute
	 * @return the index key
	 */
	@Nonnull
	private AttributeIndexKey getIndexKey(@Nonnull AttributeSchemaContract attributeSchema) {
		return new AttributeIndexKey(
			this.context.referenceSchema().getName(),
			attributeSchema.getName(),
			attributeSchema.isLocalized() ? this.leafLocale : null
		);
	}

	/**
	 * Returns the keys of a reading in the leaf locale: the attribute itself when it is not localized, its value in
	 * the leaf locale when it is, and nothing when it is localized and no leaf locale is known.
	 *
	 * @param attributeSchema the schema of the attribute
	 * @return the keys
	 */
	@Nonnull
	private AttributeKey[] getLeafLocaleKeys(@Nonnull AttributeSchemaContract attributeSchema) {
		if (!attributeSchema.isLocalized()) {
			return new AttributeKey[]{new AttributeKey(attributeSchema.getName())};
		} else if (this.leafLocale == null) {
			return NO_KEYS;
		} else {
			return new AttributeKey[]{new AttributeKey(attributeSchema.getName(), this.leafLocale)};
		}
	}

	/**
	 * Returns the keys of a localized attribute in every locale of the owner's schema - every locale a value of it can
	 * be stored in.
	 *
	 * @param attributeSchema the schema of the localized attribute
	 * @return the keys
	 */
	@Nonnull
	private AttributeKey[] getEveryLocaleKeys(@Nonnull AttributeSchemaContract attributeSchema) {
		final Set<Locale> locales = this.context.entitySchema().getLocales();
		final AttributeKey[] keys = new AttributeKey[locales.size()];
		int index = 0;
		for (Locale locale : locales) {
			keys[index++] = new AttributeKey(attributeSchema.getName(), locale);
		}
		return keys;
	}

	/**
	 * Creates the matcher of an attribute leaf once the attribute is resolved.
	 */
	@FunctionalInterface
	private interface MatcherFactory {

		/**
		 * Creates the matcher.
		 *
		 * @param indexKey        the key of the filter index the leaf reads in the leaf locale
		 * @param attributeSchema the schema of the attribute
		 * @return the matcher
		 */
		@Nonnull
		AttributeValueMatcher create(
			@Nonnull AttributeIndexKey indexKey,
			@Nonnull AttributeSchemaContract attributeSchema
		);

	}

}
