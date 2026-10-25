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
import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeValueMatchingTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.core.query.filter.translator.attribute.AbstractAttributeTranslator.isBoundToQueryLocale;
import static java.util.Optional.ofNullable;

/**
 * Compiles the filter of the fetched references (`referenceContent(..., filterBy(...))`) into a
 * {@link ReferenceRowFilter} - a tree of {@link RowTest} nodes judging one reference row of one owner entity at a time.
 * The planning check and the fetch run this one compiler, so what the check accepts the fetch evaluates. Whether a
 * leaf is refused depends on the schemas alone, in both. A nested `entityHaving` / `groupHaving` filter is validated
 * whatever the data only by a check, which resolves the nested set while compiling; a fetch resolves it when the
 * first row reaches it (see {@link NestedSet}), so on a path planned without a check (`getEntity`, an enrichment) a
 * bad nested filter fails only when some row reaches its node.
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
 * The locale of the leaf is the reference level's own opinion - the `entityLocaleEquals` of the filter outside its
 * `entityHaving` and `groupHaving` containers, resolved by {@link QueryUtils#resolveLocale} - and the locale of the
 * owner request when the filter names none. A nested `entityHaving` / `groupHaving` filter without an opinion of its
 * own inherits this locale (see {@link NestedSet}). The body fetch resolves differently: the opinion of the
 * referenced entity level comes first there ({@link ReferencedEntityFetcher#findReferenceFilterLocale}), so the
 * leaves may read another locale than the fetched bodies. A localized attribute without any locale is refused with
 * {@link EntityLocaleMissingException}, unless the attribute is unique across locales in a scope the leaf is compiled
 * for - the rule the attribute translators apply.
 *
 * ## Readings chosen per scope of the owner
 *
 * The flags of an attribute may differ per scope, and so does the index the main query would read it from: a
 * localized attribute unique across locales in a scope is read in any locale there, and in the leaf locale where it is
 * not. Every attribute node therefore carries one reading per scope it is compiled for, each chosen by that scope's own
 * declaration, and a row is read by the scope of its owner - also an owner living outside the scopes of the request,
 * which the fetch compiles a reading for too (see {@link CompileContext}). An attribute unique in one scope and only
 * filterable in another is read in each scope by that scope's declaration - not by a lookup in the unique index of one
 * scope that passes over the other.
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
	 * The attribute leaves choose a reading for every scope an owner whose rows they test can live in: the scopes the
	 * references are looked up in, and the scopes of the owners. An owner may live outside the scopes of the request -
	 * an entity archived by `archiveEntity`, moved by `upsertAndFetchEntity` or enriched by a request reading another
	 * scope - and its rows are read by the declaration of its own scope, as those of every other owner. The nested
	 * queries and the `inScope` containers keep the scopes the references are looked up in - those of the request and
	 * of the root `scope(...)`. A check tests no row and passes no owner scope, and an owner scope can only admit a
	 * localized leaf the check refuses for the lack of a locale (see {@link #getAttributeSchema}), never refuse one
	 * the check admits - so what the check accepts the fetch evaluates.
	 *
	 * @param entitySchema    the schema of the entity owning the references
	 * @param referenceSchema the schema of the filtered reference
	 * @param filterByVisitor the visitor the nested queries are planned with
	 * @param requestScopes   the scopes of the request the references are fetched for
	 * @param ownerScopes     the scopes the owners whose rows are tested live in, which may lie outside the scopes of
	 *                        the request; empty when no row is tested (a check)
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
		@Nonnull Set<Scope> ownerScopes,
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
		final ReferenceRowFilterCompiler compiler = new ReferenceRowFilterCompiler(
			context,
			ofNullable(QueryUtils.resolveLocale(filterBy)).orElse(context.ownerLocale()),
			getRootTargetBound(filterBy)
		);
		final Set<Scope> examinedScopes = ReferencedEntityFetcher.gatherSearchedScopes(
			filterBy, context.requestScopes()
		);
		final Set<Scope> ownerScopes = context.ownerScopes();
		final RowTest root = compiler.compileRoot(
			filterBy,
			examinedScopes,
			// the owners live in the examined scopes on every path that plans a query, so nothing is allocated there
			examinedScopes.containsAll(ownerScopes) ?
				examinedScopes : CollectionUtils.combine(examinedScopes, ownerScopes)
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
			case 1 -> children.getFirst();
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
	 * @param readingScopes  the examined scopes and the scopes of the owners whose rows are tested
	 * @return the root node, NULL when the filter has no condition on the row
	 */
	@Nullable
	private RowTest compileRoot(
		@Nonnull FilterBy filterBy,
		@Nonnull Set<Scope> examinedScopes,
		@Nonnull Set<Scope> readingScopes
	) {
		final FilterConstraint[] children = filterBy.getChildren();
		final List<RowTest> compiled = new ArrayList<>(children.length);
		for (FilterConstraint child : children) {
			if (!(child instanceof EntityScope)) {
				addIfPresent(compiled, compile(child, examinedScopes, readingScopes));
			}
		}
		return combine(compiled, RowTest.And::new);
	}

	/**
	 * Compiles the children of a container.
	 *
	 * @param children          the children
	 * @param nestedQueryScopes the scopes the nested queries of the children are planned in
	 * @param readingScopes     the scopes the attribute leaves of the children choose a reading for
	 * @return the compiled children, without those dropped
	 */
	@Nonnull
	private List<RowTest> compileChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Set<Scope> nestedQueryScopes,
		@Nonnull Set<Scope> readingScopes
	) {
		final List<RowTest> compiled = new ArrayList<>(children.length);
		for (FilterConstraint child : children) {
			addIfPresent(compiled, compile(child, nestedQueryScopes, readingScopes));
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
	 * @param constraint        the constraint
	 * @param nestedQueryScopes the scopes the nested queries of `entityHaving` / `groupHaving` are planned in - the
	 *                          examined scopes, or the scope of the enclosing `inScope`
	 * @param readingScopes     the scopes the attribute leaves choose a reading for - those of every owner whose
	 *                          rows can reach the constraint: the examined scopes and the scopes of the owners, or
	 *                          the scope of the enclosing `inScope`
	 * @return the node, NULL when the constraint is dropped
	 * @throws EvitaInvalidUsageException when the constraint has no meaning on a row
	 */
	@Nullable
	private RowTest compile(
		@Nonnull FilterConstraint constraint,
		@Nonnull Set<Scope> nestedQueryScopes,
		@Nonnull Set<Scope> readingScopes
	) {
		if (!constraint.isApplicable()) {
			return null;
		}
		return switch (constraint) {
			case And and ->
				combine(compileChildren(and.getChildren(), nestedQueryScopes, readingScopes), RowTest.And::new);
			case Or or ->
				combine(compileChildren(or.getChildren(), nestedQueryScopes, readingScopes), RowTest.Or::new);
			case Not not -> {
				final RowTest child = compile(not.getChild(), nestedQueryScopes, readingScopes);
				yield child == null ? null : new RowTest.Not(child);
			}
			case FilterInScope inScope -> compileInScope(inScope, nestedQueryScopes);
			case EntityLocaleEquals localeEquals -> new RowTest.OwnerLocale(localeEquals.getLocale());
			case EntityPrimaryKeyInSet primaryKeyInSet -> {
				final int[] primaryKeys = primaryKeyInSet.getPrimaryKeys();
				Arrays.sort(primaryKeys);
				yield new RowTest.TargetIn(primaryKeys);
			}
			case EntityHaving entityHaving -> compileEntityHaving(entityHaving, nestedQueryScopes);
			case GroupHaving groupHaving -> compileGroupHaving(groupHaving, nestedQueryScopes);
			case AttributeIs attributeIs -> compilePresence(attributeIs, readingScopes);
			case EntityScope entityScope -> throw new EvitaInvalidUsageException(
				"Constraint `" + entityScope + "` can be used in the filter of the fetched references `" +
					this.context.referenceSchema().getName() + "` only directly in its `filterBy` - it selects " +
					"the scopes the references are looked up in and has no meaning on a single reference."
			);
			default -> {
				final FilteringConstraintTranslator<? extends FilterConstraint> translator =
					FilterByVisitor.getTranslator(constraint.getClass());
				if (translator instanceof AttributeValueMatchingTranslator<?> matchingTranslator &&
					constraint instanceof AttributeConstraint<?> attributeConstraint
				) {
					yield compileAttributeValue(
						matchingTranslator, constraint, attributeConstraint.getAttributeName(), readingScopes
					);
				}
				throw new EvitaInvalidUsageException(
					"Constraint `" + constraint + "` cannot be used in the filter of the fetched references `" +
						this.context.referenceSchema().getName() + "` - the filter is evaluated on every reference " +
						"by itself, and only `and`, `or`, `not`, `inScope`, `entityPrimaryKeyInSet`, " +
						"`entityLocaleEquals`, `entityHaving`, `groupHaving` and the attribute constraints have " +
						"a meaning there."
				);
			}
		};
	}

	/**
	 * Compiles an `inScope` container: its children are compiled for its scope alone - the nested queries are planned
	 * in it and only the owners living in it reach the attribute leaves - and the rows of owners living in another
	 * scope satisfy it.
	 *
	 * @param inScope           the container
	 * @param nestedQueryScopes the scopes the container is compiled in - the examined scopes at the root, the scope of
	 *                          the enclosing `inScope` below it
	 * @return the node, NULL when all children were dropped
	 * @throws EvitaInvalidUsageException when the scope is not among the scopes the container is compiled in
	 */
	@Nullable
	private RowTest compileInScope(@Nonnull FilterInScope inScope, @Nonnull Set<Scope> nestedQueryScopes) {
		final Scope scope = inScope.getScope();
		Assert.isTrue(
			nestedQueryScopes.contains(scope),
			() -> "Scope `" + scope + "` used in `inScope` filter container was not requested by `scope` constraint!"
		);
		final Set<Scope> scopes = EnumSet.of(scope);
		final RowTest child = combine(compileChildren(inScope.getChildren(), scopes, scopes), RowTest.And::new);
		return child == null ? null : new RowTest.InScope(scope, child);
	}

	/**
	 * Compiles an `entityHaving` into the set of referenced entities its nested filter selects, restricted to the
	 * existing targets of the fetched rows that the root `entityPrimaryKeyInSet` admits. A row whose target lies
	 * outside the root keys may be answered "not selected" by the node although the nested filter selects its target -
	 * the root conjunct rejects that row anyway, so no row verdict changes, but the node answers correctly only inside
	 * this tree.
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
		final FilterConstraint nestedFilter = HavingTranslatorHelper.conjunctionOf(entityHaving.getChildren());
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
				this.context.filterByVisitor(), referencedEntityType, nestedFilter, scopes,
				this.leafLocale, restriction,
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
		final FilterConstraint nestedFilter = HavingTranslatorHelper.conjunctionOf(groupHaving.getChildren());
		final Supplier<Bitmap> rowGroups = this.context.rowGroups();
		return new RowTest.GroupInNested(
			new NestedSet(
				this.context.filterByVisitor(), referencedGroupType, nestedFilter, scopes, this.leafLocale,
				rowGroups == null ? null : () -> rowGroups.get().getArray(),
				() -> "filtering reference `" + referenceSchema.getName() + "` by group entity `" +
					referencedGroupType + "` having: " + nestedFilter
			)
		);
	}

	/**
	 * Compiles a constraint testing a single attribute value - the matcher is created by the translator of the
	 * constraint, which also tells the index the main query answers it from, and so the locales the leaf reads.
	 *
	 * The translator is looked up by the class of the constraint, so it is the translator of this very constraint; the
	 * cast of the constraint to the type the translator is parameterized with is the one
	 * {@link FilterByVisitor#visit} makes for every translator.
	 *
	 * @param translator    the translator of the constraint
	 * @param constraint    the constraint
	 * @param attributeName the name of the attribute
	 * @param scopes        the scopes the leaf is compiled for
	 * @return the node
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private RowTest compileAttributeValue(
		@Nonnull AttributeValueMatchingTranslator translator,
		@Nonnull FilterConstraint constraint,
		@Nonnull String attributeName,
		@Nonnull Set<Scope> scopes
	) {
		final BiFunction<AttributeIndexKey, AttributeSchemaContract, AttributeValueMatcher> matcherFactory =
			(indexKey, attributeSchema) -> translator.createMatcher(
				constraint, attributeSchema, indexKey,
				this.context.filterByVisitor().getQueryContext()
					.getEntityCollectionOrThrowException(this.context.entitySchema().getName(), "normalize reference rows")
					.getAttributeNormalizationResolver()
					.resolve(this.context.referenceSchema().getName(), attributeSchema, indexKey.locale()),
				this.context.filterByVisitor()::getNow
			);
		return translator.isAnsweredByUniqueIndex() ?
			compileEquality(attributeName, scopes, matcherFactory) :
			compileInLeafLocale(attributeName, scopes, matcherFactory);
	}

	/**
	 * Compiles a leaf the main query answers from the unique index where the attribute is unique - `attributeEquals`
	 * or `attributeInSet` - and which therefore reads a localized attribute in any locale in a scope where it is
	 * unique across locales, and in the leaf locale elsewhere.
	 *
	 * @param attributeName  the name of the attribute
	 * @param scopes         the scopes the leaf is compiled for
	 * @param matcherFactory the factory of the matcher of the leaf from the index key and the schema of the attribute
	 * @return the node
	 */
	@Nonnull
	private RowTest compileEquality(
		@Nonnull String attributeName,
		@Nonnull Set<Scope> scopes,
		@Nonnull BiFunction<AttributeIndexKey, AttributeSchemaContract, AttributeValueMatcher> matcherFactory
	) {
		final AttributeSchemaContract attributeSchema = getAttributeSchema(attributeName, scopes);
		final AttributeValueMatcher[] matchersByScope = new AttributeValueMatcher[ALL_SCOPES.length];
		for (final Scope scope : scopes) {
			final AttributeIndexKey indexKey = new AttributeIndexKey(
				this.context.referenceSchema().getName(), attributeName,
				attributeSchema.isLocalized() && isBoundToQueryLocale(attributeSchema, scope) ? this.leafLocale : null
			);
			matchersByScope[scope.ordinal()] = matcherFactory.apply(indexKey, attributeSchema);
		}
		return new RowTest.AttributeMatch(
			getKeysByScope(attributeSchema, scopes, scope -> isBoundToQueryLocale(attributeSchema, scope)),
			matchersByScope
		);
	}

	/**
	 * Compiles an attribute leaf the main query answers from the filter index of the query locale - a localized
	 * attribute is read in the leaf locale in every scope, and matches nothing when there is no leaf locale.
	 *
	 * @param attributeName  the name of the attribute
	 * @param scopes         the scopes the leaf is compiled for
	 * @param matcherFactory the factory of the matcher of the leaf from the index key and the schema of the attribute
	 * @return the node
	 */
	@Nonnull
	private RowTest compileInLeafLocale(
		@Nonnull String attributeName,
		@Nonnull Set<Scope> scopes,
		@Nonnull BiFunction<AttributeIndexKey, AttributeSchemaContract, AttributeValueMatcher> matcherFactory
	) {
		final AttributeSchemaContract attributeSchema = getAttributeSchema(attributeName, scopes);
		final AttributeValueMatcher matcher = matcherFactory.apply(getIndexKey(attributeSchema), attributeSchema);
		final AttributeValueMatcher[] matchersByScope = new AttributeValueMatcher[ALL_SCOPES.length];
		for (final Scope scope : scopes) {
			matchersByScope[scope.ordinal()] = matcher;
		}
		return new RowTest.AttributeMatch(
			getKeysInEveryScope(scopes, getLeafLocaleKeys(attributeSchema)), matchersByScope
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
				scope -> this.leafLocale != null && isBoundToQueryLocale(attributeSchema, scope)
			),
			present
		);
	}

	/**
	 * Resolves the attribute on the reference schema - the attributes of the catalog are not looked up in a
	 * reference - and refuses a localized attribute when no locale is known, unless it is unique across locales in
	 * one of the scopes (the rule of the attribute translators). No trait is required, so nothing is counted.
	 *
	 * No other refusal of a leaf depends on the scopes, and this one can only be lifted by a scope added, never
	 * raised - so the fetch, which adds the scopes of owners living outside the request, refuses nothing the check,
	 * compiled for the scopes of the request alone, admits.
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
				scopes.stream().anyMatch(scope -> !isBoundToQueryLocale(attributeSchema, scope)),
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

}
