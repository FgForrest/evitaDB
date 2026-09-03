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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.requestResponse.EntityFetchAwareDecorator;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.distance;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContentAnd;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.having;
import static io.evitadb.api.query.QueryConstraints.hierarchyContent;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfSelf;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRootSelf;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinSelf;
import static io.evitadb.api.query.QueryConstraints.level;
import static io.evitadb.api.query.QueryConstraints.node;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.parents;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.stopAt;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.PROXY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for the parent chain returned by `hierarchyContent` - see issue #1365
 * (https://github.com/FgForrest/evitaDB/issues/1365).
 *
 * The class asserts the behaviour matrix of
 * `documentation/adr/2026-08-03-hierarchy-content-parents-behaviour.md`, which has two columns.
 * `MATCHING` is the default - what a query gets when it names no
 * {@link io.evitadb.api.query.require.HierarchyParentsBehaviour} at all - and it cuts the chain just
 * below the first ancestor whose *requested* body cannot be materialized, so every ancestor the caller
 * receives carries the body that was asked for. `COMPLETE` is the opt-in: such an ancestor stays in the
 * chain as a bodyless pointer and the walk continues above it, so a body may well be reported above a
 * pointer.
 *
 * Each nested class holds one family of matrix rows, and the two cells of one row are adjacent methods
 * rather than being split by mode - `LocaleGateTest` holds the P rows, `RequirementVariationTest` the N
 * rows and the requirement controls, `BrokenChainTest` the K rows. `TypedProxyTest` collects every row
 * that observes the chain through a typed proxy instead of the raw API, the two statistics classes cover
 * the other production caller of the same upward walk, and `IndexInvariantTest` pins states of the index
 * behind the chain.
 *
 * A row is written as a {@link ParameterizedTest} over both behaviours only when its mode-insensitivity
 * is a *claim of the design*: no ancestor body was requested, no query locale gates anything, nothing
 * along the chain is unmaterializable, or the bound admits no ancestor at all. Rows that merely happen
 * to agree today stay single-mode, which is why
 * `shouldFilterOutNonLocalizedEntityItselfUnderQueryLocale_nonLocalizedSchema` and
 * `shouldReturnAncestorBodyWhenLocaleIsRequestedOnlyInsideEntityFetch_N5variant` are not parameterised.
 *
 * The `before #1365 (measured)` column of that matrix is history and is no longer asserted anywhere.
 * It differed from the `MATCHING` column in one respect only: an unmaterializable **immediate**
 * parent used to be reported as a stray bodyless pointer, because the parent slot was left empty and
 * the decorator fell back to the pointer its delegate carries. That fallback is gone, which is the
 * single silent change the default carries for an existing caller.
 *
 * Every method that pins a matrix row carries that row's identifier in its name and in its display
 * name; the remaining methods pin a control, a variant of a row, or an index invariant the matrix has
 * no row for.
 *
 * Assertions run against the raw {@link SealedEntity} API rather than through typed proxy interfaces,
 * because {@link io.evitadb.api.proxy.impl.ProxyUtils#createOptionalWrapper} picks a swallowing
 * wrapper for a getter that neither returns an {@link Optional} nor declares an exception, which
 * would report "never requested" and "cannot be materialized" identically and let a broken
 * implementation pass. The deliberate exceptions are the rows in {@link TypedProxyTest}, which are about
 * the proxy surface itself and therefore have to cross to it. Every one of them either declares the
 * exception, so that the rethrowing wrapper is chosen, returns an {@link Optional}, or exists precisely
 * to observe the swallowing wrapper against a declaring twin on the same fetch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Evita hierarchyContent parent chain - parents behaviour matrix")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(HIERARCHY)
class HierarchyContentParentsBehaviourFunctionalTest {
	/**
	 * Name of the shared data set holding the whole behaviour matrix fixture.
	 */
	private static final String DATA_SET = "hierarchyContentParentsBehaviour";
	/**
	 * Name of the second, deliberately small data set - the only one this class is allowed to write to.
	 *
	 * The matrix fixture above is shared read-only by every other method here, which is what makes it
	 * cheap; a test that upserts into it would change what its neighbours measure. An index invariant
	 * about a *mutation* cannot be asserted without writing, so it gets a fixture of its own instead of
	 * the read-only lock being taken off the shared one.
	 */
	private static final String MUTABLE_DATA_SET = "hierarchyContentParentsBehaviourMutable";
	/**
	 * Czech locale - the locale a node holds data in when it must be unmaterializable under the
	 * English query locale.
	 *
	 * The language-only form is deliberate and matches the sibling suite
	 * {@link ManagedReferenceLocaleFunctionalTest}; the country-qualified
	 * {@link io.evitadb.test.generator.DataGenerator#CZECH_LOCALE} is a different locale, and mixing the
	 * two in one schema would declare one Czech locale while the upsert wrote another.
	 */
	private static final Locale LOCALE_CZECH = new Locale("cs");
	/**
	 * Rendering of a chain that ends, i.e. whose last element reports an empty
	 * {@link EntityClassifierWithParent#getParentEntity()}.
	 */
	private static final String CHAIN_END = "(end)";
	/**
	 * Upper bound on the length of a collected parent chain. It is a loose cycle guard rather than a tight
	 * expectation: the deepest fixture hierarchy is five nodes deep, so four ancestors is the most any
	 * legitimate chain here can hold, and sixteen sits far enough above that to leave room for a chain the
	 * fixture grows without failing on a correct result. Anything beyond it means the walk found a cycle
	 * and must fail rather than spin.
	 */
	private static final int MAX_CHAIN_LENGTH = 16;

	/**
	 * Builds the whole behaviour-matrix fixture in a single shared catalog.
	 *
	 * The `CATEGORY` collection is hierarchical and localized in `cs` and `en`, carries a localized
	 * nullable `name` and a global nullable `code`. A node created with English data is
	 * materializable under the English query locale; a node created with Czech data only is not,
	 * while still holding a readable global `code`. The `BRAND` collection is hierarchical, carries
	 * the same global `code`, and declares no locale at all.
	 *
	 * Primary keys are grouped by the matrix row they serve: `1-3` the fully materializable control,
	 * `11-12` row P1, `21-23` row P2, `31-34` row P3, `41-44` row P4, `51-54` row P5, `61-63` row P6,
	 * `71-72` row K1, `81-83` row K2, `91-94` the deeper deleted-root probe, `101-103` the non-localized
	 * `BRAND` hierarchy, `111-112` row K6, `121-125` rows K3/K4/K5, `131-134` the Czech-only
	 * mid-chain deletion, `141-144` the ring, `151-156` the shared-ancestor bound probe and `163-166` its
	 * pointer twin.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 * @return an empty data carrier - the fixture is addressed by primary key, not by shared objects
	 */
	@DataSet(DATA_SET)
	DataCarrier setUp(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withLocale(LOCALE_CZECH, Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateAndFetchVia(session);

				// a flat collection referencing the hierarchical categories, so that the parent statistics can
				// also be asked for through `hierarchyOfReference` rather than only for the queried entity itself
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE_CZECH, Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						ReferenceSchemaEditor::indexedForFiltering
					)
					.updateAndFetchVia(session);

				// the brand schema declares no locale whatsoever - a query-level locale filter therefore
				// matches nothing in this collection at all
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> {})
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateAndFetchVia(session);

				// control - every node materializable under the English query locale
				createEnglishCategory(session, 1, null);
				createEnglishCategory(session, 2, 1);
				createEnglishCategory(session, 3, 2);

				// P1 - the immediate parent holds Czech data only
				createCzechCategory(session, 11, null);
				createEnglishCategory(session, 12, 11);

				// P2 - the root holds Czech data only
				createCzechCategory(session, 21, null);
				createEnglishCategory(session, 22, 21);
				createEnglishCategory(session, 23, 22);

				// P3 - a Czech-only node in the middle with a materializable root above it
				createEnglishCategory(session, 31, null);
				createCzechCategory(session, 32, 31);
				createEnglishCategory(session, 33, 32);
				createEnglishCategory(session, 34, 33);

				// P4 - two adjacent Czech-only ancestors with a materializable root above them
				createEnglishCategory(session, 41, null);
				createCzechCategory(session, 42, 41);
				createCzechCategory(session, 43, 42);
				createEnglishCategory(session, 44, 43);

				// P5 - Czech-only, materializable, Czech-only, interleaved up to the root
				createCzechCategory(session, 51, null);
				createEnglishCategory(session, 52, 51);
				createCzechCategory(session, 53, 52);
				createEnglishCategory(session, 54, 53);

				// P6 - two Czech-only ancestors all the way up to the root
				createCzechCategory(session, 61, null);
				createCzechCategory(session, 62, 61);
				createEnglishCategory(session, 63, 62);

				// K1 - the immediate parent is a root that gets deleted below
				createEnglishCategory(session, 71, null);
				createEnglishCategory(session, 72, 71);

				// K2 - a deleted root two levels up
				createEnglishCategory(session, 81, null);
				createEnglishCategory(session, 82, 81);
				createEnglishCategory(session, 83, 82);

				// a deleted root three levels up, plus the index-invariant assertion
				createEnglishCategory(session, 91, null);
				createEnglishCategory(session, 92, 91);
				createEnglishCategory(session, 93, 92);
				createEnglishCategory(session, 94, 93);

				// K6 - a parent primary key that was never created; the upsert is accepted all the same
				createEnglishCategory(session, 111, 999);
				createEnglishCategory(session, 112, 111);

				// K3 / K4 / K5 - a mid-chain node that gets deleted below, with three descendants
				createEnglishCategory(session, 121, null);
				createEnglishCategory(session, 122, 121);
				createEnglishCategory(session, 123, 122);
				createEnglishCategory(session, 124, 123);
				createEnglishCategory(session, 125, 124);

				// a Czech-only mid-chain node that gets deleted below - deletion and locale combined
				createEnglishCategory(session, 131, null);
				createCzechCategory(session, 132, 131);
				createEnglishCategory(session, 133, 132);
				createEnglishCategory(session, 134, 133);

				// the ring - `141` starts out as a root with `142` below it and is re-pointed at `142` below,
				// which closes the pair into a ring and detaches the `143 -> 144` tail hanging under `141`
				createEnglishCategory(session, 141, null);
				createEnglishCategory(session, 142, 141);
				createEnglishCategory(session, 143, 141);
				createEnglishCategory(session, 144, 143);

				// the shared-ancestor bound probe - `151 <- 152 <- 153 <- 154` with `155` hanging under `153`
				// and `156` under `154`. The primary keys are pinned rather than incidental: the parents of the
				// two queried entities are sorted ascending before they are walked, so `153` is processed first
				// and `154` second, and swapping the two keys would hide an ancestor chain overwritten by a
				// sub-chain that a bound measured from the deeper entity cut short
				createEnglishCategory(session, 151, null);
				createEnglishCategory(session, 152, 151);
				createEnglishCategory(session, 153, 152);
				createEnglishCategory(session, 154, 153);
				createEnglishCategory(session, 155, 153);
				createEnglishCategory(session, 156, 154);

				// the pointer twin of the probe above - `163` points at the never-created `998`, `164` hangs
				// under it, and the two queried entities `165` and `166` hang under `163` and `164`. The same
				// ordering argument applies, with a phantom pointer in place of the truncated ancestor
				createEnglishCategory(session, 163, 998);
				createEnglishCategory(session, 164, 163);
				createEnglishCategory(session, 165, 163);
				createEnglishCategory(session, 166, 164);

				// products reaching the category hierarchy through a reference - `301` sits under the P3 leaf,
				// `302` under the intact control chain
				createEnglishProduct(session, 301, 34);
				createEnglishProduct(session, 302, 3);

				// the non-localized hierarchy
				createBrand(session, 101, null);
				createBrand(session, 102, 101);
				createBrand(session, 103, 102);
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// three roots - their subtrees are orphaned and keep pointing at a key that no longer resolves
				deleteFixtureNode(session, 71);
				deleteFixtureNode(session, 81);
				deleteFixtureNode(session, 91);
				// two mid-chain nodes - their descendants become orphans
				deleteFixtureNode(session, 122);
				deleteFixtureNode(session, 132);
				// re-pointing a root at its own descendant closes the pair into a ring
				repointFixtureNode(session, 141, 142);
			}
		);

		return new DataCarrier();
	}

	/**
	 * Builds the writable fixture - one intact `CATEGORY` chain `1 -> 2 -> 3` under the same schema the
	 * matrix fixture uses, in a catalog of its own that a test may upsert into.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 * @return an empty data carrier - the fixture is addressed by primary key, not by shared objects
	 */
	@DataSet(value = MUTABLE_DATA_SET, readOnly = false, destroyAfterClass = true)
	DataCarrier setUpMutable(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withLocale(LOCALE_CZECH, Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateAndFetchVia(session);

				createEnglishCategory(session, 1, null);
				createEnglishCategory(session, 2, 1);
				createEnglishCategory(session, 3, 2);
			}
		);
		return new DataCarrier();
	}

	/**
	 * Typed proxy over the `CATEGORY` collection whose parent getter **declares** an exception, so that
	 * {@link io.evitadb.api.proxy.impl.ProxyUtils#createOptionalWrapper} picks the rethrowing wrapper
	 * instead of the swallowing one. Without the `throws` clause the wrapper catches the
	 * {@link ContextMissingException} and hands back `null` instead, and the exception this interface
	 * exists to observe would be invisible - which is what {@link ParentSwallowingCategory} is for.
	 *
	 * The two further `@ParentEntity` getters exist because the entity-typed one is the only proxy form
	 * that needs the ancestor body: a primary key and a raw classifier are both carried by a bodyless
	 * pointer, so those two forms must keep answering where the entity-typed one has to give up.
	 *
	 * The two {@link Optional}-returning getters are the third wrapper family the same annotation can
	 * reach. They pick the swallowing and the rethrowing wrapper by the very same rule - the presence of
	 * a `throws` clause - but they also change the *extractor*, which asks
	 * {@link io.evitadb.api.requestResponse.data.EntityContract#parentAvailable()} first and answers with
	 * an empty result rather than an exception when the hierarchy was never fetched.
	 */
	@EntityRef(Entities.CATEGORY)
	public interface ParentRethrowingCategory extends EntityClassifier {

		/**
		 * Returns the primary key of the proxied category.
		 *
		 * @return the primary key of the proxied category
		 */
		@PrimaryKeyRef
		int getId();

		/**
		 * Returns the immediate parent of the proxied category.
		 *
		 * @return the immediate parent proxy, or `null` when the category is a root
		 * @throws ContextMissingException when the parent is present in the chain but carries no body
		 */
		@ParentEntity
		@Nullable
		ParentRethrowingCategory getParentEntity() throws ContextMissingException;

		/**
		 * Returns the primary key of the immediate parent of the proxied category. The boxed form is
		 * deliberate - a root has no parent at all and the getter must be able to say so without an
		 * unboxing failure.
		 *
		 * @return the primary key of the immediate parent, or `null` when the category is a root
		 */
		@ParentEntity
		@Nullable
		Integer getParentId();

		/**
		 * Returns the immediate parent of the proxied category as the raw classifier the chain carries,
		 * without asking for a proxy over it. It is the only proxy form that hands back a bodyless
		 * pointer as such, and therefore the only one from which the chain above a pointer can be
		 * walked further.
		 *
		 * @return the immediate parent classifier, or `null` when the category is a root
		 */
		@ParentEntity
		@Nullable
		EntityClassifierWithParent getParentClassifier();

		/**
		 * Returns the immediate parent of the proxied category wrapped in an {@link Optional}. The getter
		 * declares no exception, so the swallowing member of the `Optional` wrapper family is chosen and a
		 * parent that carries no body is reported as an empty result.
		 *
		 * @return the immediate parent proxy, or an empty optional when there is none to build a proxy over
		 */
		@ParentEntity
		@Nonnull
		Optional<ParentRethrowingCategory> getParentEntityIfPresent();

		/**
		 * Returns the immediate parent of the proxied category wrapped in an {@link Optional}, declaring the
		 * exception so that the rethrowing member of the same wrapper family is chosen instead. It is the
		 * `Optional` twin of {@link #getParentEntity()} and the only way to tell an absent parent apart from
		 * one that is present without a body.
		 *
		 * @return the immediate parent proxy, or an empty optional when the category is a root
		 * @throws ContextMissingException when the parent is present in the chain but carries no body
		 */
		@ParentEntity
		@Nonnull
		Optional<ParentRethrowingCategory> getParentEntityOrFail() throws ContextMissingException;

	}

	/**
	 * Typed proxy over the `CATEGORY` collection whose parent getter declares **no** exception, so that
	 * {@link io.evitadb.api.proxy.impl.ProxyUtils#createOptionalWrapper} picks the swallowing wrapper.
	 * It is the counterpart of {@link ParentRethrowingCategory} and pins the other half of the
	 * contract: the very same fetch that makes the declaring getter throw must make this one report
	 * `null`, exactly as a reference whose body was not fetched does.
	 */
	@EntityRef(Entities.CATEGORY)
	public interface ParentSwallowingCategory extends EntityClassifier {

		/**
		 * Returns the primary key of the proxied category.
		 *
		 * @return the primary key of the proxied category
		 */
		@PrimaryKeyRef
		int getId();

		/**
		 * Returns the immediate parent of the proxied category, or `null` both when the category is
		 * a root and when the parent carries no body.
		 *
		 * @return the immediate parent proxy, or `null`
		 */
		@ParentEntity
		@Nullable
		ParentSwallowingCategory getParentEntity();

	}

	/**
	 * Record projection over the `CATEGORY` collection. It reaches the parent axis through a completely
	 * different code path from the two interfaces above: a constructor parameter is filled by an extractor
	 * that {@link io.evitadb.api.proxy.impl.entity.GetParentEntityMethodClassifier#getExtractorIfPossible}
	 * hands out once, with no result wrapper in front of it, so neither the swallowing nor the rethrowing
	 * behaviour of a getter applies and whatever the extractor raises reaches the caller as it is.
	 *
	 * The self-referencing `parentEntity` component is what makes the projection recursive, and it is the
	 * component that meets a bodyless pointer. The boxed `parentId` beside it is carried by a pointer just
	 * as it is by a body, so the two components answer differently on the same fetch.
	 *
	 * @param id           the primary key of the projected category
	 * @param parentId     the primary key of its immediate parent, or `null` when it is a root
	 * @param parentEntity the projection of its immediate parent, or `null` when it is a root
	 */
	@EntityRef(Entities.CATEGORY)
	public record ParentProjectingCategory(
		@PrimaryKeyRef int id,
		@ParentEntity Integer parentId,
		@ParentEntity ParentProjectingCategory parentEntity
	) implements Serializable {
	}

	/* ------------------------------------------------------------------------------------------ */
	/* Fixture helpers                                                                             */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Creates a category holding English localized data plus the global `code` attribute, so
	 * that it is materializable under an English query locale.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 */
	private static void createEnglishCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		createCategory(session, primaryKey, parentPrimaryKey, Locale.ENGLISH);
	}

	/**
	 * Creates a category holding Czech localized data plus the global `code` attribute. Such a
	 * node is not materializable under an English query locale even though its global attribute is
	 * perfectly readable.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 */
	private static void createCzechCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		createCategory(session, primaryKey, parentPrimaryKey, LOCALE_CZECH);
	}

	/**
	 * Creates a category carrying the global `code` attribute and a localized `name` in
	 * the single requested locale.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 * @param locale           the only locale the created node holds data in
	 */
	private static void createCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey,
		@Nonnull Locale locale
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.CATEGORY, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, expectedCode(primaryKey))
			.setAttribute(ATTRIBUTE_NAME, locale, "Category " + primaryKey);
		if (parentPrimaryKey != null) {
			builder.setParent(parentPrimaryKey);
		}
		builder.upsertVia(session);
	}

	/**
	 * Creates a brand in the non-localized hierarchical collection. The global `code` attribute is
	 * assigned by the same rule the categories use, so the shared body assertion works on brands too.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 */
	private static void createBrand(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.BRAND, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, expectedCode(primaryKey))
			.setAttribute(ATTRIBUTE_NAME, "Brand " + primaryKey);
		if (parentPrimaryKey != null) {
			builder.setParent(parentPrimaryKey);
		}
		builder.upsertVia(session);
	}

	/**
	 * Creates a product holding English localized data and referencing a single category, so that the
	 * category hierarchy can be reached through `hierarchyOfReference` from a query on another collection.
	 *
	 * @param session            the session to upsert through
	 * @param primaryKey         the primary key to assign
	 * @param categoryPrimaryKey the primary key of the category the product refers to
	 */
	private static void createEnglishProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		int categoryPrimaryKey
	) {
		session.createNewEntity(Entities.PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, expectedCode(primaryKey))
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product " + primaryKey)
			.setReference(Entities.CATEGORY, categoryPrimaryKey)
			.upsertVia(session);
	}

	/**
	 * Deletes a fixture category and asserts the deletion really happened. A deletion that silently
	 * did nothing would surface as a wrong parent chain several rows away from its cause.
	 *
	 * @param session    the session to delete through
	 * @param primaryKey the primary key of the fixture node to delete
	 */
	private static void deleteFixtureNode(@Nonnull EvitaSessionContract session, int primaryKey) {
		assertTrue(
			session.deleteEntity(Entities.CATEGORY, primaryKey),
			"Fixture node " + primaryKey + " was expected to exist and be deleted"
		);
	}

	/**
	 * Re-points an existing fixture category at another node through the ordinary public write path, which
	 * is the only way a ring can be built: the index refuses a node that is its own parent, so the shortest
	 * ring is a pair created as parent and child and then closed by pointing the parent back at the child.
	 *
	 * @param session             the session to upsert through
	 * @param primaryKey          the primary key of the fixture node to move
	 * @param newParentPrimaryKey the primary key of the node it must point at afterwards
	 */
	private static void repointFixtureNode(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		int newParentPrimaryKey
	) {
		session.getEntity(Entities.CATEGORY, primaryKey, attributeContentAll(), hierarchyContent())
			.orElseThrow(() -> new AssertionError("Fixture node " + primaryKey + " was not created."))
			.openForWrite()
			.setParent(newParentPrimaryKey)
			.upsertVia(session);
	}

	/**
	 * Returns the value of the global `code` attribute a fixture node of the given primary key
	 * carries. Reading it back is what proves an element of the chain really has a body.
	 *
	 * @param primaryKey the primary key of the fixture node
	 * @return the expected global attribute value
	 */
	@Nonnull
	private static String expectedCode(int primaryKey) {
		return "c" + primaryKey;
	}

	/* ------------------------------------------------------------------------------------------ */
	/* Query and assertion helpers                                                                 */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Returns the requirement every matrix row is measured under, asked under the given behaviour -
	 * `hierarchyContent` fetching the complete attribute content of every ancestor it reaches.
	 *
	 * @param behaviour the parents behaviour the requirement is asked under
	 * @return the standard `hierarchyContent` requirement
	 */
	@Nonnull
	private static HierarchyContent standardRequirement(@Nonnull HierarchyParentsBehaviour behaviour) {
		return hierarchyContent(behaviour, entityFetch(attributeContentAll()));
	}

	/**
	 * Returns the standard requirement asked under the default behaviour.
	 *
	 * @return the standard `hierarchyContent` requirement
	 */
	@Nonnull
	private static HierarchyContent standardRequirement() {
		return standardRequirement(HierarchyParentsBehaviour.MATCHING);
	}

	/**
	 * Returns the same requirement {@link #standardRequirement()} builds, asked under
	 * {@link HierarchyParentsBehaviour#COMPLETE} instead of the default.
	 *
	 * @return the standard `hierarchyContent` requirement in complete mode
	 */
	@Nonnull
	private static HierarchyContent completeRequirement() {
		return standardRequirement(HierarchyParentsBehaviour.COMPLETE);
	}

	/**
	 * Returns the bare form of the requirement - the parent axis with no ancestor body requested at all -
	 * asked under the given behaviour. It is the shape `entityFetchAll()` emits, and the one the two
	 * behaviours are specified to answer identically.
	 *
	 * @param behaviour the parents behaviour the requirement is asked under
	 * @return the bare `hierarchyContent` requirement
	 */
	@Nonnull
	private static HierarchyContent bareRequirement(@Nonnull HierarchyParentsBehaviour behaviour) {
		return hierarchyContent(behaviour);
	}

	/**
	 * Returns the filter every behaviour-matrix query selects its leaf with - the queried primary key
	 * alone, or the same primary key gated by the English query locale the P rows are measured under.
	 *
	 * @param primaryKey      the primary key of the queried entity
	 * @param withQueryLocale whether the filter carries `entityLocaleEquals(en)`
	 * @return the filter to place in the query
	 */
	@Nonnull
	private static FilterBy leafFilter(int primaryKey, boolean withQueryLocale) {
		return withQueryLocale ?
			filterBy(entityPrimaryKeyInSet(primaryKey), entityLocaleEquals(Locale.ENGLISH)) :
			filterBy(entityPrimaryKeyInSet(primaryKey));
	}

	/**
	 * Returns the parent chain of the given entity under the standard requirement and the English
	 * query locale - the combination the behaviour matrix calls `standard`.
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key of the queried leaf
	 * @return the parent chain, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> standardParentChain(@Nonnull Evita evita, int primaryKey) {
		return parentChainUnder(evita, primaryKey, HierarchyParentsBehaviour.MATCHING);
	}

	/**
	 * Returns the parent chain of the given entity under the standard requirement asked in complete mode
	 * and the English query locale - the `COMPLETE` cell of a `standard` matrix row.
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key of the queried leaf
	 * @return the parent chain, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> completeParentChain(@Nonnull Evita evita, int primaryKey) {
		return parentChainUnder(evita, primaryKey, HierarchyParentsBehaviour.COMPLETE);
	}

	/**
	 * Returns the parent chain of the given entity under the standard requirement asked in the given mode
	 * and the English query locale - the single entry point every parameterised matrix row goes through.
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key of the queried leaf
	 * @param behaviour  the parents behaviour the requirement is asked under
	 * @return the parent chain, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> parentChainUnder(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull HierarchyParentsBehaviour behaviour
	) {
		return fetchParentChain(evita, primaryKey, standardRequirement(behaviour), true);
	}

	/**
	 * Runs the standard behaviour-matrix query against the `CATEGORY` collection.
	 *
	 * @param evita                the embedded evitaDB instance
	 * @param primaryKey           the primary key of the queried leaf
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param withQueryLocale      whether the query carries `entityLocaleEquals(en)`
	 * @return the queried entity, or empty when the filter matched nothing
	 */
	@Nonnull
	private static Optional<SealedEntity> fetchLeaf(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull HierarchyContent hierarchyRequirement,
		boolean withQueryLocale
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						leafFilter(primaryKey, withQueryLocale),
						require(entityFetch(attributeContentAll(), hierarchyRequirement))
					)
				);
			}
		);
	}

	/**
	 * Runs the standard behaviour-matrix query and returns the parent chain of the queried entity.
	 *
	 * @param evita                the embedded evitaDB instance
	 * @param primaryKey           the primary key of the queried leaf
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param withQueryLocale      whether the query carries `entityLocaleEquals(en)`
	 * @return the parent chain, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> fetchParentChain(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull HierarchyContent hierarchyRequirement,
		boolean withQueryLocale
	) {
		return parentChainOf(
			fetchLeaf(evita, primaryKey, hierarchyRequirement, withQueryLocale)
				.orElseThrow(() -> new AssertionError("Entity with primary key " + primaryKey + " was not returned."))
		);
	}

	/**
	 * Runs one query selecting several `CATEGORY` entities at once under the given requirement and the
	 * English query locale, and returns them in the order the engine reported them.
	 *
	 * A query for more than one entity is what makes the per-entity state of the upward walk observable:
	 * the walk collects the parents of every queried entity in a single pass and reuses one chain cache
	 * across them, so anything it forgets to reset between two entities shows up here and nowhere else.
	 *
	 * @param evita                the embedded evitaDB instance
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param primaryKeys          the primary keys of the queried entities
	 * @return the queried entities
	 */
	@Nonnull
	private static List<SealedEntity> fetchLeaves(
		@Nonnull Evita evita,
		@Nonnull HierarchyContent hierarchyRequirement,
		@Nonnull int... primaryKeys
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.CATEGORY),
						filterBy(entityPrimaryKeyInSet(primaryKeys), entityLocaleEquals(Locale.ENGLISH)),
						require(entityFetch(attributeContentAll(), hierarchyRequirement))
					)
				);
			}
		);
	}

	/**
	 * Returns the parent chain of the entity carrying the given primary key out of a batch fetched by
	 * {@link #fetchLeaves(Evita, HierarchyContent, int...)}.
	 *
	 * @param leaves     the queried entities
	 * @param primaryKey the primary key whose chain is wanted
	 * @return the parent chain, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> parentChainOf(
		@Nonnull List<SealedEntity> leaves,
		int primaryKey
	) {
		return parentChainOf(
			leaves.stream()
				.filter(it -> it.getPrimaryKeyOrThrowException() == primaryKey)
				.findFirst()
				.orElseThrow(
					() -> new AssertionError("Entity with primary key " + primaryKey + " was not returned.")
				)
		);
	}

	/**
	 * Returns the parent chain of the `CATEGORY` a `PRODUCT` refers to, fetched through
	 * `referenceContent` rather than directly. It is the second usage shape the user documentation shows
	 * for `hierarchyContent`, and the one that carries the behaviour argument into a sub-fetcher rather
	 * than into the fetcher of the queried entity itself.
	 *
	 * @param evita             the embedded evitaDB instance
	 * @param productPrimaryKey the primary key of the queried product
	 * @param behaviour         the parents behaviour the requirement is asked under
	 * @return the parent chain of the referenced category, ordered from its immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> referencedCategoryParentChain(
		@Nonnull Evita evita,
		int productPrimaryKey,
		@Nonnull HierarchyParentsBehaviour behaviour
	) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(productPrimaryKey), entityLocaleEquals(Locale.ENGLISH)),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(attributeContentAll(), standardRequirement(behaviour))
								)
							)
						)
					)
				);
			}
		).orElseThrow(
			() -> new AssertionError("Product with primary key " + productPrimaryKey + " was not returned.")
		);

		final ReferenceContract reference = product.getReferences(Entities.CATEGORY).stream()
			.findFirst()
			.orElseThrow(() -> new AssertionError("Product " + productPrimaryKey + " reported no category."));
		return parentChainOf(
			reference.getReferencedEntity()
				.orElseThrow(() -> new AssertionError("The referenced category body was not fetched."))
		);
	}

	/**
	 * Fetches an entity, hands the still-open session and the entity to `reWrap`, and returns the parent
	 * chain of whatever comes back. Every enrich / limit call re-wraps the decorator over a raw delegate
	 * and has to carry the already resolved parent slot across, so this is the entry point for the rows
	 * that pin what a round trip through the session does to a chain the fetcher had already settled.
	 *
	 * @param evita                the embedded evitaDB instance
	 * @param primaryKey           the primary key of the queried leaf
	 * @param hierarchyRequirement the `hierarchyContent` requirement the entity is first fetched under
	 * @param reWrap               the session call under test
	 * @return the parent chain of the re-wrapped entity, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> parentChainAfterReWrap(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull HierarchyContent hierarchyRequirement,
		@Nonnull BiFunction<EvitaSessionContract, SealedEntity, SealedEntity> reWrap
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						leafFilter(primaryKey, true),
						require(entityFetch(attributeContentAll(), hierarchyRequirement))
					)
				).orElseThrow(
					() -> new AssertionError("Entity with primary key " + primaryKey + " was not returned.")
				);
				return parentChainOf(reWrap.apply(session, entity));
			}
		);
	}

	/**
	 * Runs the standard behaviour-matrix query against the non-localized `BRAND` collection.
	 *
	 * @param evita           the embedded evitaDB instance
	 * @param primaryKey      the primary key of the queried brand
	 * @param withQueryLocale whether the query carries `entityLocaleEquals(en)`
	 * @return every brand the query matched
	 */
	@Nonnull
	private static List<SealedEntity> fetchBrands(
		@Nonnull Evita evita,
		int primaryKey,
		boolean withQueryLocale
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.BRAND),
						leafFilter(primaryKey, withQueryLocale),
						require(entityFetch(attributeContentAll(), standardRequirement()))
					)
				);
			}
		);
	}

	/**
	 * Fetches a category through the typed proxy interface under the standard requirement and the
	 * English query locale.
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key of the queried category
	 * @return the proxy over the queried category
	 */
	@Nonnull
	private static ParentRethrowingCategory fetchCategoryProxy(@Nonnull Evita evita, int primaryKey) {
		return fetchCategoryProxy(evita, primaryKey, standardRequirement(), ParentRethrowingCategory.class);
	}

	/**
	 * Fetches a category through the given typed proxy interface under the English query locale.
	 *
	 * @param evita                the embedded evitaDB instance
	 * @param primaryKey           the primary key of the queried category
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param contract             the proxy contract the entity is requested as
	 * @param <T>                  the type of the proxy contract
	 * @return the proxy over the queried category
	 */
	@Nonnull
	private static <T extends Serializable> T fetchCategoryProxy(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull HierarchyContent hierarchyRequirement,
		@Nonnull Class<T> contract
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOne(
					query(
						collection(Entities.CATEGORY),
						leafFilter(primaryKey, true),
						require(entityFetch(attributeContentAll(), hierarchyRequirement))
					),
					contract
				);
			}
		).orElseThrow(() -> new AssertionError("Entity with primary key " + primaryKey + " was not returned."));
	}

	/**
	 * Returns the primary keys of every category the hierarchy index can reach from one of its roots.
	 * Membership of this set is the observable that separates a node placed in the hierarchy from an
	 * orphan or from a node with no placement at all.
	 *
	 * @param evita the embedded evitaDB instance
	 * @return the primary keys of all root-reachable categories
	 */
	@Nonnull
	private static Set<Integer> rootReachableCategoryPrimaryKeys(@Nonnull Evita evita) {
		final List<EntityReferenceContract> hierarchyMemberReferences = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfEntityReferences(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinRootSelf()),
						require(page(1, 500))
					)
				);
			}
		);
		final Set<Integer> hierarchyMembers = new HashSet<>(hierarchyMemberReferences.size() * 2);
		for (EntityReferenceContract reference : hierarchyMemberReferences) {
			hierarchyMembers.add(reference.getPrimaryKey());
		}
		return hierarchyMembers;
	}

	/**
	 * Fetches a single category by its primary key with no hierarchy requirement at all, which is how a
	 * test tells "this entity is gone" apart from "this entity is no longer reachable from a root".
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key to look up
	 * @return the category, or empty when no entity carries that primary key
	 */
	@Nonnull
	private static Optional<SealedEntity> fetchCategoryByPrimaryKey(@Nonnull Evita evita, int primaryKey) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(entityFetch(attributeContentAll()))
					)
				);
			}
		);
	}

	/**
	 * Runs the `parents` hierarchy extra-result over the `CATEGORY` collection for a single queried node
	 * and renders the returned {@link LevelInfo} tree, asking for every ancestor body and no query locale.
	 * This is the second production caller of the upward walk - the one that produces hierarchy statistics
	 * rather than a `hierarchyContent` chain.
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key of the single node the hierarchy filter selects
	 * @return the rendered parent statistics, one node per line
	 */
	@Nonnull
	private static String renderParentStatistics(@Nonnull Evita evita, int primaryKey) {
		return renderParentStatistics(evita, primaryKey, null, entityFetch(attributeContentAll()));
	}

	/**
	 * Runs the `parents` hierarchy extra-result over the `CATEGORY` collection for a single queried node
	 * under an optional query locale and an optional ancestor-body requirement, and renders the returned
	 * {@link LevelInfo} tree.
	 *
	 * Both knobs exist to separate the two decisions the extra result makes. The locale is the gate that
	 * decides which ancestors the statistics tree contains at all, and it is applied by the hierarchy
	 * filtering predicate before any body is fetched; the body requirement decides only what an admitted
	 * node carries. Rendering the same fixture with and without ancestor bodies is therefore the direct
	 * assertion that the second decision never changes the shape the first one produced.
	 *
	 * @param evita          the embedded evitaDB instance
	 * @param primaryKey     the primary key of the single node the hierarchy filter selects
	 * @param locale         the query locale to filter by, or `null` to query without one
	 * @param ancestorBodies the body requirement for every reported node, or `null` to ask for none
	 * @return the rendered parent statistics, one node per line
	 */
	@Nonnull
	private static String renderParentStatistics(
		@Nonnull Evita evita,
		int primaryKey,
		@Nullable Locale locale,
		@Nullable EntityFetch ancestorBodies
	) {
		final FilterBy filterBy = locale == null ?
			filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(primaryKey))) :
			filterBy(entityLocaleEquals(locale), hierarchyWithinSelf(entityPrimaryKeyInSet(primaryKey)));
		final List<LevelInfo> statistics = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy,
						require(
							hierarchyOfSelf(
								ancestorBodies == null ?
									parents("parents") : parents("parents", ancestorBodies)
							)
						)
					),
					EntityReference.class
				).getExtraResult(Hierarchy.class).getSelfHierarchy("parents");
			}
		);
		final StringBuilder result = new StringBuilder(128);
		renderLevelInfo(result, statistics, 0);
		return result.toString();
	}

	/**
	 * Runs the `parents` hierarchy extra-result over the `CATEGORY` hierarchy reached through a reference
	 * from the `PRODUCT` collection, and renders the returned {@link LevelInfo} tree.
	 *
	 * The `having` bound is what makes this query worth asking: it names the ancestors the traversal may
	 * pass through explicitly, by primary key, and it is the one shape in which the membership gate of the
	 * statistics tree is not the query locale alone. The two are conjoined rather than one replacing the
	 * other, so a node the bound names is still admitted only if it holds data in the query locale.
	 *
	 * @param evita                          the embedded evitaDB instance
	 * @param categoryPrimaryKey             the primary key of the single category the hierarchy filter selects
	 * @param locale                         the query locale, applied to the queried `PRODUCT` entities
	 * @param traversableCategoryPrimaryKeys the primary keys the `having` bound admits into the traversal
	 * @return the rendered parent statistics, one node per line
	 */
	@Nonnull
	private static String renderReferencedParentStatistics(
		@Nonnull Evita evita,
		int categoryPrimaryKey,
		@Nonnull Locale locale,
		@Nonnull int... traversableCategoryPrimaryKeys
	) {
		final List<LevelInfo> statistics = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityLocaleEquals(locale),
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(categoryPrimaryKey),
								having(entityPrimaryKeyInSet(traversableCategoryPrimaryKeys))
							)
						),
						require(
							hierarchyOfReference(
								Entities.CATEGORY,
								parents("parents", entityFetch(attributeContentAll()))
							)
						)
					),
					EntityReference.class
				).getExtraResult(Hierarchy.class).getReferenceHierarchy(Entities.CATEGORY, "parents");
			}
		);
		final StringBuilder result = new StringBuilder(128);
		renderLevelInfo(result, statistics, 0);
		return result.toString();
	}

	/**
	 * Renders a {@link LevelInfo} tree in the same `B(pk)` / `P(pk)` notation the parent chains use, one
	 * node per line, indented by its depth and marking the node the hierarchy filter selected.
	 *
	 * @param output     the builder to render into
	 * @param levelInfos the nodes to render at this depth
	 * @param depth      the current nesting depth, zero for the top of the returned tree
	 */
	private static void renderLevelInfo(
		@Nonnull StringBuilder output,
		@Nonnull List<LevelInfo> levelInfos,
		int depth
	) {
		for (LevelInfo levelInfo : levelInfos) {
			output.append("   ".repeat(depth))
				.append(shapeOf(levelInfo.entity()))
				.append('(')
				.append(levelInfo.entity().getPrimaryKeyOrThrowException())
				.append(')')
				.append(levelInfo.requested() ? " (requested)" : "")
				.append('\n');
			renderLevelInfo(output, levelInfo.children(), depth + 1);
		}
	}

	/**
	 * Walks the parent axis of the given entity from the immediate parent upwards until
	 * {@link EntityClassifierWithParent#getParentEntity()} reports an empty optional. The walk is
	 * bounded by {@link #MAX_CHAIN_LENGTH}, so a regression producing a cyclic chain fails the test
	 * instead of hanging the surefire fork.
	 *
	 * @param entity the queried entity whose ancestors to collect
	 * @return the ancestors in leaf-to-root order, the queried entity itself excluded
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> parentChainOf(@Nonnull SealedEntity entity) {
		final List<EntityClassifierWithParent> chain = new ArrayList<>(8);
		Optional<EntityClassifierWithParent> current = entity.getParentEntity();
		while (current.isPresent()) {
			final EntityClassifierWithParent node = current.get();
			chain.add(node);
			if (chain.size() > MAX_CHAIN_LENGTH) {
				throw new AssertionError(
					"The parent chain of entity " + entity.getPrimaryKey() + " exceeded " + MAX_CHAIN_LENGTH +
						" elements, which the fixture cannot produce - collected so far: " + describeChain(chain)
				);
			}
			current = node.getParentEntity();
		}
		return chain;
	}

	/**
	 * Renders a parent chain in the notation the behaviour matrix uses: `B(pk)` for an ancestor
	 * present with a body, `P(pk)` for a bodyless pointer, and a trailing `(end)` marking
	 * the position where the chain stops.
	 *
	 * @param chain the chain to render
	 * @return the rendered chain, always terminated by `(end)`
	 */
	@Nonnull
	private static String describeChain(@Nonnull List<EntityClassifierWithParent> chain) {
		final StringBuilder result = new StringBuilder(64);
		for (EntityClassifierWithParent node : chain) {
			result.append(shapeOf(node))
				.append('(')
				.append(node.getPrimaryKey())
				.append(") -> ");
		}
		return result.append(CHAIN_END).toString();
	}

	/**
	 * Returns the one-letter shape the behaviour-matrix notation gives the passed chain element: `B` for an
	 * ancestor present with a body, `P` for a bodyless pointer. Defined here once so that the notation the
	 * assertions parse and the notation the renderers produce cannot drift apart.
	 *
	 * @param node the chain element to classify
	 * @return `B` when the element carries a body, `P` otherwise
	 */
	@Nonnull
	private static String shapeOf(@Nonnull EntityClassifier node) {
		return node instanceof SealedEntity ? "B" : "P";
	}

	/**
	 * Asserts an entire parent chain in one call: the rendered chain must equal the expected elements
	 * joined in the behaviour-matrix notation, and every element is then checked individually -
	 * `B(pk)` through {@link #assertBody(EntityClassifierWithParent, int)} and `P(pk)` through
	 * {@link #assertPointer(EntityClassifierWithParent, int)}. An expected element written in neither
	 * shape is a mistake in the test itself and fails immediately rather than being skipped.
	 *
	 * @param chain            the chain returned by the engine
	 * @param expectedElements the expected elements in leaf-to-root order, e.g. `B(22)`, `P(21)`
	 * @throws AssertionError when the rendered chain differs, an element fails its own check, or an
	 *                        expected element uses neither the `B(pk)` nor the `P(pk)` shape
	 */
	private static void assertChain(
		@Nonnull List<EntityClassifierWithParent> chain,
		@Nonnull String... expectedElements
	) {
		final StringBuilder expected = new StringBuilder(64);
		for (String expectedElement : expectedElements) {
			expected.append(expectedElement).append(" -> ");
		}
		expected.append(CHAIN_END);
		assertEquals(expected.toString(), describeChain(chain));

		for (int i = 0; i < expectedElements.length; i++) {
			final String expectedElement = expectedElements[i];
			final int primaryKey = Integer.parseInt(
				expectedElement.substring(expectedElement.indexOf('(') + 1, expectedElement.indexOf(')'))
			);
			final char shape = expectedElement.charAt(0);
			if (shape == 'B') {
				assertBody(chain.get(i), primaryKey);
			} else if (shape == 'P') {
				assertPointer(chain.get(i), primaryKey);
			} else {
				throw new AssertionError(
					"Chain element `" + expectedElement + "` must start with `B` for a body or `P` for a pointer."
				);
			}
		}
	}

	/**
	 * Asserts that the given chain element is a materialized entity carrying the expected primary key
	 * and the global `code` attribute the fixture assigned to it.
	 *
	 * @param node       the chain element to check
	 * @param primaryKey the primary key the element must carry
	 * @return the element narrowed to {@link SealedEntity}
	 */
	@Nonnull
	private static SealedEntity assertBody(@Nonnull EntityClassifierWithParent node, int primaryKey) {
		final SealedEntity sealedEntity = assertInstanceOf(
			SealedEntity.class, node,
			"Ancestor " + primaryKey + " was expected to carry a body."
		);
		assertEquals(primaryKey, sealedEntity.getPrimaryKey());
		assertEquals(
			expectedCode(primaryKey), sealedEntity.getAttribute(ATTRIBUTE_CODE),
			"The body of ancestor " + primaryKey + " must expose its global attribute."
		);
		return sealedEntity;
	}

	/**
	 * Asserts that the given chain element is a bodyless pointer carrying the expected primary key.
	 * It is an {@link EntityReferenceWithParent}, which is explicitly not a {@link SealedEntity} - and
	 * that is precisely what makes its attributes unreadable, since only a sealed entity exposes them.
	 *
	 * @param node       the chain element to check
	 * @param primaryKey the primary key the pointer must carry
	 */
	private static void assertPointer(@Nonnull EntityClassifierWithParent node, int primaryKey) {
		assertFalse(
			node instanceof SealedEntity,
			"Ancestor " + primaryKey + " was expected to be bodyless, but carries a body."
		);
		final EntityReferenceWithParent pointer = assertInstanceOf(EntityReferenceWithParent.class, node);
		assertEquals(primaryKey, pointer.getPrimaryKey());
		assertEquals(Entities.CATEGORY, pointer.getType());
	}

	/**
	 * Pins both cells of every row where a query-level locale makes an ancestor unmaterializable - the
	 * shapes #1365 reports - together with the fully materializable control.
	 *
	 * The `MATCHING` cell of a row and its `COMPLETE` twin sit next to each other, because the pair is the
	 * claim: the default cuts the chain below the first ancestor whose requested body cannot be
	 * materialized, while `COMPLETE` keeps that ancestor as a bodyless pointer and walks on above it.
	 */
	@Nested
	@DisplayName("Locale gate (P rows)")
	class LocaleGateTest {

		/**
		 * Matrix row P1 - `12 -> 11(cs)` under the standard requirement. The only ancestor cannot be
		 * materialized, so the chain is cut below it and the queried entity reports no parent at all.
		 *
		 * This is the one row where `MATCHING` differs from the behaviour that preceded it: the stray
		 * bodyless pointer to 11 that the delegate fallback used to produce is gone.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1: the chain is cut below a locale-less immediate parent")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheChainBelowALocaleLessImmediateParent_P1(Evita evita) {
			assertChain(standardParentChain(evita, 12));
		}

		/**
		 * Matrix row P1 under `COMPLETE` - `12 -> 11(cs)`. The unmaterializable immediate parent is
		 * reported as a bodyless pointer instead of disappearing with the rest of the chain.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1: a locale-less immediate parent is reported as a bodyless pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportLocaleLessImmediateParentAsPointer_P1(Evita evita) {
			assertChain(completeParentChain(evita, 12), "P(11)");
		}

		/**
		 * Matrix row P2 - `23 -> 22 -> 21(cs)` under the standard requirement. The chain is cut below the
		 * unmaterializable root and its materializable child is reported with its body.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P2: the chain is cut below a locale-less root, keeping the materializable parent")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheChainBelowALocaleLessRootAndKeepMaterializableParent_P2(Evita evita) {
			assertChain(standardParentChain(evita, 23), "B(22)");
		}

		/**
		 * Matrix row P2 under `COMPLETE` - `23 -> 22 -> 21(cs)`. The unmaterializable root is reported as a
		 * pointer above the materializable ancestor that carries a body.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P2: a locale-less root is reported as a pointer above the materializable parent")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportLocaleLessRootAsPointerAboveMaterializableParent_P2(Evita evita) {
			assertChain(completeParentChain(evita, 23), "B(22)", "P(21)");
		}

		/**
		 * Matrix row P3 - `34 -> 33 -> 32(cs) -> 31` under the standard requirement. The cut takes the
		 * materializable root above the unmaterializable ancestor with it, which is what makes `MATCHING`
		 * a truncation of the chain rather than a filter over its elements.
		 *
		 * The terminal `B(33)` reports `parentAvailable() == true`. That flag says only that hierarchy
		 * data was fetched for the ancestor -
		 * {@link io.evitadb.api.requestResponse.data.structure.EntityDecorator#parentAvailable()} is the
		 * stored flag combined with the hierarchy predicate, not a statement that a parent exists - so it
		 * is `true` on a genuine root as well (see the control row). A `MATCHING` cut is therefore
		 * indistinguishable from a root, exactly as a `stopAt` cut is.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3: the cut discards the materializable root above the locale-less ancestor")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDiscardTheMaterializableRootAboveALocaleLessAncestor_P3(Evita evita) {
			final List<EntityClassifierWithParent> chain = standardParentChain(evita, 34);
			// 32 holds Czech data only and 31 is perfectly materializable, yet both are gone - the chain
			// is cut below 32 rather than stepping over it
			assertChain(chain, "B(33)");
			assertTrue(
				assertBody(chain.get(0), 33).parentAvailable(),
				"A cut chain reports parentAvailable() on 33 exactly as a genuine root does."
			);
		}

		/**
		 * Matrix row P3 under `COMPLETE` - `34 -> 33 -> 32(cs) -> 31`. The acceptance test of the whole
		 * mode: it is the only row proving the walk no longer stops at the first missing body, since the
		 * materializable root 31 is reported *above* the pointer at 32.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3: the materializable root above a locale-less ancestor is reported with its body")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportMaterializableRootAboveLocaleLessAncestor_P3(Evita evita) {
			assertChain(completeParentChain(evita, 34), "B(33)", "P(32)", "B(31)");
		}

		/**
		 * Matrix row P4 - `44 -> 43(cs) -> 42(cs) -> 41` under the standard requirement. The first
		 * unmaterializable ancestor is the immediate parent, so nothing of the chain is reported.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P4: two adjacent locale-less ancestors leave no chain at all")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportNoChainForTwoAdjacentLocaleLessAncestors_P4(Evita evita) {
			assertChain(standardParentChain(evita, 44));
		}

		/**
		 * Matrix row P4 under `COMPLETE` - `44 -> 43(cs) -> 42(cs) -> 41`. Two adjacent unmaterializable
		 * ancestors yield two pointers rather than collapsing into one, and the materializable root above
		 * them is still reached.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P4: two adjacent locale-less ancestors are reported as two separate pointers")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTwoAdjacentLocaleLessAncestorsAsTwoPointers_P4(Evita evita) {
			assertChain(completeParentChain(evita, 44), "P(43)", "P(42)", "B(41)");
		}

		/**
		 * Matrix row P5 - `54 -> 53(cs) -> 52 -> 51(cs)` under the standard requirement. The materializable
		 * 52 sits above the unmaterializable immediate parent and is therefore discarded with it - the row
		 * where `MATCHING` visibly gives up an ancestor it could have returned.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P5: a materializable ancestor above a locale-less parent is discarded")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDiscardMaterializableAncestorAboveLocaleLessParent_P5(Evita evita) {
			// 52 carries English data and would materialize, but it sits above the locale-less 53
			assertChain(standardParentChain(evita, 54));
		}

		/**
		 * Matrix row P5 under `COMPLETE` - `54 -> 53(cs) -> 52 -> 51(cs)`. Bodies and pointers alternate
		 * along one chain, which is the shape the default can never produce.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P5: bodies and pointers alternate along the reported chain")
		@UseDataSet(DATA_SET)
		@Test
		void shouldAlternateBodiesAndPointersAlongTheChain_P5(Evita evita) {
			assertChain(completeParentChain(evita, 54), "P(53)", "B(52)", "P(51)");
		}

		/**
		 * Matrix row P6 - `63 -> 62(cs) -> 61(cs)` under the standard requirement. Every ancestor up to the
		 * root is unmaterializable, so the chain is cut immediately.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P6: locale-less ancestors all the way to the root leave no chain at all")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportNoChainForLocaleLessAncestorsUpToRoot_P6(Evita evita) {
			assertChain(standardParentChain(evita, 63));
		}

		/**
		 * Matrix row P6 under `COMPLETE` - `63 -> 62(cs) -> 61(cs)`. Every ancestor up to the root is
		 * unmaterializable, so the whole chain comes back as pointers and none of it is lost.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P6: locale-less ancestors up to the root are all reported as pointers")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportLocaleLessAncestorsUpToRootAsPointers_P6(Evita evita) {
			assertChain(completeParentChain(evita, 63), "P(62)", "P(61)");
		}

		/**
		 * Fixture control - `3 -> 2 -> 1`, every node materializable. Nothing along the chain can fail to
		 * materialize, so the mode has nothing to substitute and both cells of the row are the same full
		 * chain of bodies; without this row a mode that turned every ancestor into a pointer would pass
		 * most of the class.
		 *
		 * The terminal `B(1)` is the genuine root, and it reports `parentAvailable() == true` all the
		 * same. That was measured rather than derived:
		 * {@link io.evitadb.api.requestResponse.data.structure.EntityDecorator#parentAvailable()} answers
		 * "was hierarchy data fetched for this entity", not "does this entity have a parent". The flag is
		 * therefore identical on a genuine root, on a `stopAt` cut and on a chain cut by an
		 * unmaterializable ancestor, so nothing on the raw API separates the three - which is the
		 * ambiguity the P and K rows keep running into.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: a fully materializable chain is returned complete, with bodies")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldReturnCompleteChainWithBodiesWhenEveryAncestorMaterializes_control(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			final SealedEntity leaf = fetchLeaf(evita, 3, standardRequirement(behaviour), true).orElseThrow();
			assertEquals(expectedCode(3), leaf.getAttribute(ATTRIBUTE_CODE));

			final List<EntityClassifierWithParent> chain = parentChainOf(leaf);
			assertChain(chain, "B(2)", "B(1)");
			assertTrue(
				assertBody(chain.get(1), 1).parentAvailable(),
				"The genuine root 1 reports parentAvailable(), exactly as a cut chain does."
			);
		}

		/**
		 * Pins how the IO statistics of a chain of bodies add up. Every ancestor body is a decorator of its own,
		 * and {@link EntityFetchAwareDecorator#getIoFetchCount()} already folds in whatever the parent slot holds -
		 * so the whole tail above a decorated immediate parent is reached at read time and must not be added to the
		 * decorator's own counter as well. It is added there only for the tail that read time cannot reach, above a
		 * bodyless pointer.
		 *
		 * The bounded arm is what makes this measurable without knowing any absolute cost: the same requirement
		 * reads 2 identically in both arms, so whatever the unbounded arm reports over the bounded one is exactly
		 * what the chain above 2 contributed - and that is 1, once.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: an ancestor contributes its IO statistics to the chain exactly once")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCountTheIoStatisticsOfAnAncestorOnce_control(Evita evita) {
			final List<EntityClassifierWithParent> wholeChain = fetchParentChain(
				evita, 3, standardRequirement(), true
			);
			assertChain(wholeChain, "B(2)", "B(1)");
			final List<EntityClassifierWithParent> boundedChain = fetchParentChain(
				evita, 3, hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())), true
			);
			assertChain(boundedChain, "B(2)");

			final EntityFetchAwareDecorator root = assertInstanceOf(
				EntityFetchAwareDecorator.class, wholeChain.get(1)
			);
			final EntityFetchAwareDecorator immediate = assertInstanceOf(
				EntityFetchAwareDecorator.class, wholeChain.get(0)
			);
			final EntityFetchAwareDecorator immediateAlone = assertInstanceOf(
				EntityFetchAwareDecorator.class, boundedChain.get(0)
			);

			assertTrue(root.getIoFetchCount() > 0, "Reading the root body has to cost at least one fetch.");
			assertEquals(
				root.getIoFetchCount(),
				immediate.getIoFetchCount() - immediateAlone.getIoFetchCount(),
				"The root must contribute its fetch count to the chain exactly once."
			);
			assertEquals(
				root.getIoFetchedBytes(),
				immediate.getIoFetchedBytes() - immediateAlone.getIoFetchedBytes(),
				"The root must contribute its fetched bytes to the chain exactly once."
			);
		}

		/**
		 * Two queried entities of the P3 fixture whose ancestors overlap - `34 -> 33 -> 32(cs) -> 31` and
		 * `33 -> 32(cs) -> 31` - asked in one query under `COMPLETE`. The upward walk keeps one chain cache
		 * for the whole query on the assumption that ancestors are shared between queried entities, and this
		 * is the only row where a shared ancestor is a bodyless *pointer* rather than a body.
		 *
		 * It also pins that the same node is reported identically whether it is the top of one chain or a
		 * link in the middle of another: 32 is a pointer in both, and 31 carries its body in both.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3: two queried entities sharing a pointer ancestor each get the whole chain")
		@UseDataSet(DATA_SET)
		@Test
		void shouldShareAPointerAncestorBetweenTwoQueriedEntities_P3(Evita evita) {
			final List<SealedEntity> leaves = fetchLeaves(evita, completeRequirement(), 33, 34);
			assertEquals(2, leaves.size(), "Both queried categories must be returned.");
			assertChain(parentChainOf(leaves, 34), "B(33)", "P(32)", "B(31)");
			assertChain(parentChainOf(leaves, 33), "P(32)", "B(31)");
		}
	}

	/**
	 * Pins how the shape of the `hierarchyContent` requirement itself changes the returned chain - no
	 * ancestor bodies at all, the three `stopAt` bounds, no query locale, `dataInLocales` nested inside the
	 * ancestor `entityFetch`, a request narrowed to the single global attribute the gated ancestor really
	 * holds, and the requirement carried into a sub-fetcher through `referenceContent`.
	 *
	 * The last row in this group varies the schema rather than the requirement: it pins why the
	 * behaviour matrix has no row for a collection that declares no locale at all.
	 */
	@Nested
	@DisplayName("Requirement variations (N rows)")
	class RequirementVariationTest {

		/**
		 * Matrix row N1 - `23 -> 22 -> 21(cs)` with a bare `hierarchyContent()`. No body is requested, so
		 * the locale gate never fires and the whole primary-key axis is returned. Nothing can fail to
		 * materialize, so the mode has nothing to act on and both cells of the row are the same chain -
		 * which is what keeps `entityFetchAll()`, which emits a bare `hierarchyContent()`, insensitive to
		 * the mode.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N1: the full primary-key chain survives when no ancestor bodies are requested")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldReturnFullPrimaryKeyChainWhenNoBodiesRequested_N1(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			// the ancestor axis itself is complete - the locale gate only fires once bodies are fetched
			assertChain(fetchParentChain(evita, 23, bareRequirement(behaviour), true), "P(22)", "P(21)");
		}

		/**
		 * Matrix row N2 - `12 -> 11(cs)` with `stopAt(distance(1))`. Narrowing the walk to a single step
		 * changes nothing, because the only ancestor inside the bound is the unmaterializable 11: the
		 * chain is cut below it exactly as in P1.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N2: stopAt(distance(1)) leaves no chain when the only ancestor is locale-less")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportNoChainUnderStopAtDistanceOne_N2(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 12, hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())), true
				)
			);
		}

		/**
		 * Matrix row N2 under `COMPLETE` - `12 -> 11(cs)` with `stopAt(distance(1))`. The bound admits the
		 * only ancestor there is, and the mode reports it as a pointer.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N2: stopAt(distance(1)) admits the locale-less parent as a pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportLocaleLessParentAsPointerUnderStopAtDistanceOne_N2(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 12,
					hierarchyContent(
						HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1)),
						entityFetch(attributeContentAll())
					),
					true
				),
				"P(11)"
			);
		}

		/**
		 * Matrix row N3 - `34 -> 33 -> 32(cs) -> 31` with `stopAt(distance(2))`. The bound would admit both
		 * 33 and 32, but the chain is cut below 32 before the bound is ever reached, so it is the single
		 * body P3 returns - a prefix of a truncated chain is a truncation of the prefix.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N3: stopAt(distance(2)) drops the locale-less ancestor it would have reached")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropLocaleLessAncestorUnderStopAtDistanceTwo_N3(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 34, hierarchyContent(stopAt(distance(2)), entityFetch(attributeContentAll())), true
				),
				"B(33)"
			);
		}

		/**
		 * Matrix row N3 under `COMPLETE` - `34 -> 33 -> 32(cs) -> 31` with `stopAt(distance(2))`. The bound
		 * truncates the P3 chain at distance two, so the materializable root that P3 reports above the
		 * pointer is cut - a prefix of the complete chain, which is what makes a `stopAt` cut and a mode
		 * compose rather than interfere.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N3: stopAt(distance(2)) truncates the complete chain to its first two ancestors")
		@UseDataSet(DATA_SET)
		@Test
		void shouldTruncateTheCompleteChainUnderStopAtDistanceTwo_N3(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 34,
					hierarchyContent(
						HierarchyParentsBehaviour.COMPLETE, stopAt(distance(2)),
						entityFetch(attributeContentAll())
					),
					true
				),
				"B(33)", "P(32)"
			);
		}

		/**
		 * Matrix row N4 - `12 -> 11(cs)` with no query locale at all. The control that isolates the
		 * query-level `entityLocaleEquals` as the cause: without it nothing gates the ancestor, nothing
		 * turns into a pointer, and both cells of the row are the same chain of bodies.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N4: without a query locale the chain is complete and carries bodies")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldReturnCompleteChainWithBodiesWithoutQueryLocale_N4(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			// removing the single entityLocaleEquals line repairs the chain - the gate is the query locale
			assertChain(fetchParentChain(evita, 12, standardRequirement(behaviour), false), "B(11)");
		}

		/**
		 * Matrix row N5 - `12 -> 11(cs)` with a query locale plus `dataInLocales(en)` inside the parent
		 * `entityFetch`. The inner requirement does not override the query-level gate, so the chain is cut
		 * below 11 exactly as in P1.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N5: dataInLocales inside the parent entityFetch does not rescue the ancestor")
		@UseDataSet(DATA_SET)
		@Test
		void shouldNotRescueAncestorWithDataInLocalesInsideEntityFetch_N5(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 12,
					hierarchyContent(entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH))),
					true
				)
			);
		}

		/**
		 * Matrix row N5 under `COMPLETE` - `12 -> 11(cs)` with `dataInLocales(en)` inside the parent
		 * `entityFetch`. The inner requirement does not lift the query-level gate in either mode, so the
		 * ancestor is a pointer here exactly as in P1.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N5: dataInLocales inside the parent entityFetch still leaves a pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldStillReportAPointerWithDataInLocalesInsideEntityFetch_N5(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 12,
					hierarchyContent(
						HierarchyParentsBehaviour.COMPLETE,
						entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH))
					),
					true
				),
				"P(11)"
			);
		}

		/**
		 * Companion of matrix row N5 - the same `dataInLocales(en)` inside the parent `entityFetch`, but
		 * with no query-level locale. The ancestor body comes back with its localized attribute absent.
		 *
		 * The row is deliberately not parameterised: its agreement across the two modes is incidental
		 * rather than a claim of the design, since it exercises what a *returned body* contains and not
		 * whether an ancestor is admitted to the chain at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N5 variant: with the locale requested only inside entityFetch the body is returned")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReturnAncestorBodyWhenLocaleIsRequestedOnlyInsideEntityFetch_N5variant(Evita evita) {
			final List<EntityClassifierWithParent> chain = fetchParentChain(
				evita, 12,
				hierarchyContent(entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH))),
				false
			);
			// the very same inner requirement that cannot rescue the ancestor in N5 returns its body here,
			// with the localized attribute simply absent - only the query-level locale acts as a gate
			assertChain(chain, "B(11)");
			assertNull(assertBody(chain.get(0), 11).getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
		}

		/**
		 * Variant of matrix row P3 requesting only the global `code` attribute. Narrowing the request to
		 * an attribute the gated ancestor genuinely holds does not save it - the chain is cut at 33
		 * exactly as in P3, and the surviving 33 still delivers that attribute.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3 variant: requesting only a global attribute does not save a deeper ancestor")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropDeeperAncestorEvenWhenOnlyGlobalAttributeRequested_P3variant(Evita evita) {
			final List<EntityClassifierWithParent> chain = fetchParentChain(
				evita, 34, hierarchyContent(entityFetch(attributeContent(ATTRIBUTE_CODE))), true
			);
			assertChain(chain, "B(33)");
			// the narrowed requirement still delivers the global attribute on the ancestor that survives
			assertEquals(expectedCode(33), assertBody(chain.get(0), 33).getAttribute(ATTRIBUTE_CODE));
		}

		/**
		 * `stopAt(distance(1))` on the fully materializable control chain. Nothing along it can fail to
		 * materialize, so the bound answers the same in both modes: the cut is reported exactly the way a
		 * genuine root is - an empty `getParentEntity()` - and `parentAvailable()` is `true` on both, so
		 * the raw API offers nothing that separates a requested cut from the end of a hierarchy.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: stopAt(distance(1)) cuts a complete chain indistinguishably from a root")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldCutFullyMaterializableChainUnderStopAtDistanceOne_control(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			final List<EntityClassifierWithParent> chain = fetchParentChain(
				evita, 3,
				hierarchyContent(behaviour, stopAt(distance(1)), entityFetch(attributeContentAll())),
				true
			);
			assertChain(chain, "B(2)");
			assertTrue(
				assertBody(chain.get(0), 2).parentAvailable(),
				"The cut chain still reports parentAvailable() on 2."
			);
		}

		/**
		 * The same cut expressed as a level bound rather than a distance, on the fully materializable
		 * control chain `1 -> 2 -> 3`. Nothing is broken here, so 2 is at level 2 and 1 at level 1, and a
		 * bound of 2 keeps 2 and drops 1. Without this row the two broken-chain level cases would pass
		 * just as well if `level` had silently become an alias of `distance`.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: stopAt(level(2)) cuts a complete chain indistinguishably from a root")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldCutACompleteChainByLevelIndistinguishablyFromARoot_control(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			final List<EntityClassifierWithParent> chain = fetchParentChain(
				evita, 3,
				hierarchyContent(behaviour, stopAt(level(2)), entityFetch(attributeContentAll())),
				true
			);
			assertChain(chain, "B(2)");
			assertTrue(
				assertBody(chain.get(0), 2).parentAvailable(),
				"The cut chain still reports parentAvailable() on 2."
			);
		}

		/**
		 * `stopAt(level(3))` on the same control chain `1 -> 2 -> 3`, a bound that admits no ancestor at
		 * all: the immediate parent 2 sits at level 2 and a bottom-up level bound keeps only what is at
		 * least as deep as the bound. The queried entity must then report no parent whatsoever, in the bare
		 * form as well as with ancestor bodies requested - an empty chain is a resolved chain, and letting
		 * it read as "nobody resolved the parent" would hand the caller the raw immediate parent the entity
		 * carries and defeat every cut this requirement can express.
		 *
		 * A bound that admits nothing leaves the mode nothing to decide, so the row is the same in both.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: a level bound that admits no ancestor leaves no parent behind")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldReportNoParentWhenTheLevelBoundAdmitsNoAncestor_control(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			assertChain(fetchParentChain(evita, 3, hierarchyContent(behaviour, stopAt(level(3))), true));
			assertChain(
				fetchParentChain(
					evita, 3,
					hierarchyContent(behaviour, stopAt(level(3)), entityFetch(attributeContentAll())),
					true
				)
			);
		}

		/**
		 * `stopAt(level(2))` on the P3 fixture `34 -> 33 -> 32(cs) -> 31` under the default. The chain is
		 * fully rooted here, so every level is knowable - 31 is level 1, 32 level 2, 33 level 3 - and a
		 * bottom-up level bound keeps what is at least as deep as the bound. The bound would admit 33 and
		 * 32, but the chain is cut below the unmaterializable 32 first.
		 *
		 * Every other level row of this class sits on a *broken* chain, where the rule is that a level bound
		 * never cuts a fragment of unknown depth - and would pass just as well if the bound were ignored
		 * entirely. This row is the one that makes a level bound observable under a locale gate.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N3 variant: a level bound over a rooted chain still stops below the locale-less ancestor")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutBelowTheLocaleLessAncestorUnderALevelBound_N3variant(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 34, hierarchyContent(stopAt(level(2)), entityFetch(attributeContentAll())), true
				),
				"B(33)"
			);
		}

		/**
		 * The `COMPLETE` twin of the row above - `stopAt(level(2))` on the rooted P3 chain. The bound admits
		 * 33 and 32 and refuses the root 31 at level 1, so the mode reports the pointer at 32 and the bound
		 * cuts the body that P3 otherwise returns above it.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N3 variant: a level bound cuts the complete chain above the pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheCompleteChainAboveThePointerUnderALevelBound_N3variant(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 34,
					hierarchyContent(
						HierarchyParentsBehaviour.COMPLETE, stopAt(level(2)),
						entityFetch(attributeContentAll())
					),
					true
				),
				"B(33)", "P(32)"
			);
		}

		/**
		 * `stopAt(node(...))` selecting 33 on the P3 fixture `34 -> 33 -> 32(cs) -> 31`. The node bound is
		 * the one stop predicate that carries state across the walk: it admits ancestors up to and
		 * including the first one matching the filter and refuses everything above it. Ending the walk
		 * *below* the unmaterializable 32 leaves the mode nothing to decide, which makes this the node
		 * analogue of the distance rows that agree across both cells.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N3 variant: a node bound ending below the locale-less ancestor makes the mode inert")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldMakeTheModeInertWithANodeBoundBelowTheLocaleLessAncestor_N3variant(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			assertChain(
				fetchParentChain(
					evita, 34,
					hierarchyContent(
						behaviour,
						stopAt(node(filterBy(entityPrimaryKeyInSet(33)))),
						entityFetch(attributeContentAll())
					),
					true
				),
				"B(33)"
			);
		}

		/**
		 * `stopAt(node(...))` selecting exactly the node the mode turns into a pointer - 32 on the P3
		 * fixture, under `COMPLETE`. The bound and the mode meet on the same ancestor: the node filter
		 * admits 32 and refuses everything above it, while the mode keeps 32 in the chain without a body.
		 * The result is the pointer as the top of the chain, with the materializable root 31 that P3
		 * otherwise reports above it suppressed by the bound.
		 *
		 * The node filter is evaluated against the hierarchy index rather than against the query locale, so
		 * an ancestor holding no data in the query locale is still matchable by primary key - which is what
		 * makes the bound land on 32 at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3 variant: a node bound landing on the pointer itself tops the chain with it")
		@UseDataSet(DATA_SET)
		@Test
		void shouldTopTheChainWithThePointerTheNodeBoundLandsOn_P3variant(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 34,
					hierarchyContent(
						HierarchyParentsBehaviour.COMPLETE,
						stopAt(node(filterBy(entityPrimaryKeyInSet(32)))),
						entityFetch(attributeContentAll())
					),
					true
				),
				"B(33)", "P(32)"
			);
		}

		/**
		 * The P3 chain reached through `referenceContent` from the `PRODUCT` collection rather than
		 * directly - the second usage shape the user documentation shows for `hierarchyContent`, and the
		 * one that carries the behaviour argument into a sub-fetcher instead of into the fetcher of the
		 * queried entity. Under the default the chain of the referenced category 34 is cut below the
		 * unmaterializable 32 exactly as row P3 is cut when 34 is queried directly.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3 through referenceContent: the referenced entity's chain is cut the same way")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheReferencedEntityChainBelowALocaleLessAncestor_P3(Evita evita) {
			assertChain(
				referencedCategoryParentChain(evita, 301, HierarchyParentsBehaviour.MATCHING),
				"B(33)"
			);
		}

		/**
		 * The `COMPLETE` twin of the row above. The behaviour argument survives being carried into the
		 * sub-fetcher, so the referenced category reports the pointer at 32 and the materializable root
		 * above it, exactly as the directly queried 34 does.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3 through referenceContent: the mode reaches the sub-fetcher unchanged")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCarryTheModeIntoTheReferencedEntityChain_P3(Evita evita) {
			assertChain(
				referencedCategoryParentChain(evita, 301, HierarchyParentsBehaviour.COMPLETE),
				"B(33)", "P(32)", "B(31)"
			);
		}

		/**
		 * Two `hierarchyContent` siblings in one `entityFetch` - the shape `entityFetchAllContentAnd` makes
		 * when a caller adds an explicit behaviour to the full content set, since `entityFetchAllContent()`
		 * already puts a bare `hierarchyContent()` in the array. The decision record's rule that a body-less
		 * side expresses no preference exists exactly so this combination resolves to the explicit
		 * `COMPLETE`, and the chain of 34 should therefore read `B(33) -> P(32) -> B(31)`.
		 *
		 * The two siblings are reduced with the very `combineWith` that rule lives in, so the query sees one
		 * requirement and the explicit behaviour survives.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N1 variant: entityFetchAll combined with an explicit behaviour resolves to the explicit one")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCombineEntityFetchAllWithAnExplicitBehaviour_N1variant(Evita evita) {
			final SealedEntity leaf = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOneSealedEntity(
						query(
							collection(Entities.CATEGORY),
							leafFilter(34, true),
							require(
								entityFetch(
									entityFetchAllContentAnd(
										hierarchyContent(
											HierarchyParentsBehaviour.COMPLETE,
											entityFetch(attributeContentAll())
										)
									)
								)
							)
						)
					).orElseThrow(() -> new AssertionError("Category 34 was not returned."));
				}
			);
			assertChain(parentChainOf(leaf), "B(33)", "P(32)", "B(31)");
		}

		/**
		 * The other half of the combining rule: two siblings that *both* ask for ancestor bodies and disagree on the
		 * behaviour cannot be reconciled, because neither mode returns a superset of the other. The disagreement has
		 * to reach the caller as a usage error rather than being silently resolved to one of the two.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N1 variant: two body-requesting siblings under different behaviours fail the query")
		@UseDataSet(DATA_SET)
		@Test
		void shouldRefuseTwoBodyRequestingSiblingsWithDifferentBehaviours_N1variant(Evita evita) {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.queryOneSealedEntity(
							query(
								collection(Entities.CATEGORY),
								leafFilter(34, true),
								require(
									entityFetch(
										hierarchyContent(
											HierarchyParentsBehaviour.COMPLETE, entityFetch(attributeContentAll())
										),
										hierarchyContent(
											HierarchyParentsBehaviour.MATCHING, entityFetch(attributeContentAll())
										)
									)
								)
							)
						);
					}
				)
			);
			assertTrue(
				exception.getMessage().contains("different parents behaviour"),
				"The refusal must name the disagreement the two siblings could not resolve, but was: " +
					exception.getMessage()
			);
		}

		/**
		 * Two queried entities whose parents are an ancestor and a descendant of each other, asked in one
		 * query under a distance bound - `151 <- 152 <- 153 <- 154` with 155 hanging under 153 and 156
		 * under 154. Each entity is entitled to two ancestors of its own: 155 to `153, 152` and 156 to
		 * `154, 153`.
		 *
		 * The bound is measured from the queried entity, so the sub-chain of 153 that the walk collects
		 * while serving 156 is one link shorter than the chain 155 is entitled to. The two are cached under
		 * the same primary key, and the parents are walked in ascending primary-key order, so 153 is served
		 * first and overwritten second.
		 *
		 * A sub-chain collected under a bound measured from somewhere else is therefore not shared: each queried
		 * entity gets the chain its own bound admits, whichever of the two the walk reaches first.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N2 variant: two queried entities sharing an ancestor each keep their own bounded chain")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportEachQueriedEntityChainIndependentlyUnderADistanceBound_N2variant(Evita evita) {
			final List<SealedEntity> leaves = fetchLeaves(
				evita, hierarchyContent(stopAt(distance(2))), 155, 156
			);
			assertEquals(2, leaves.size(), "Both queried categories must be returned.");
			assertChain(parentChainOf(leaves, 156), "P(154)", "P(153)");
			assertChain(parentChainOf(leaves, 155), "P(153)", "P(152)");
		}

		/**
		 * The pointer twin of the row above - `163` points at the never-created 998, `164` hangs under it,
		 * and 165 and 166 hang under 163 and 164 respectively. The unresolvable key sits at distance two
		 * from 165, which the bound admits, and at distance three from 166, which it refuses; so 165 is
		 * entitled to `163, 998` and 166 only to `164, 163`.
		 *
		 * The unresolvable key is admitted or refused per queried entity, exactly like a resolvable ancestor, so
		 * the walk 166 runs cannot suppress the pointer 165 is entitled to.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K6 variant: a shared ancestor keeps the phantom pointer the bound admits below it")
		@UseDataSet(DATA_SET)
		@Test
		void shouldKeepThePhantomPointerForTheShallowerQueriedEntity_K6variant(Evita evita) {
			final List<SealedEntity> leaves = fetchLeaves(
				evita, hierarchyContent(stopAt(distance(2))), 165, 166
			);
			assertEquals(2, leaves.size(), "Both queried categories must be returned.");
			assertChain(parentChainOf(leaves, 166), "P(164)", "P(163)");
			assertChain(parentChainOf(leaves, 165), "P(163)", "P(998)");
		}

		/**
		 * Pins why the behaviour matrix carries no non-localized row: a collection whose schema declares no
		 * locale matches nothing under a query-level `entityLocaleEquals`, so the queried entity itself is
		 * filtered out before its parents are ever considered. The control arm runs the identical query
		 * without the locale and gets the brand and its complete parent chain, so the empty result above
		 * cannot be produced by a missing fixture.
		 *
		 * The row is deliberately not parameterised: it is about the query filter rejecting the queried
		 * entity, which happens before any parent behaviour applies, so its mode-insensitivity is
		 * incidental rather than a claim of the design.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Non-localized collection: a query-level locale filters out the queried entity itself")
		@UseDataSet(DATA_SET)
		@Test
		void shouldFilterOutNonLocalizedEntityItselfUnderQueryLocale_nonLocalizedSchema(Evita evita) {
			// the brand schema declares no locale, so nothing in it matches entityLocaleEquals - the parent
			// chain of a non-localized hierarchy is simply not reachable through a query-level locale filter
			final List<SealedEntity> localeFilteredBrands = fetchBrands(evita, 103, true);
			assertTrue(
				localeFilteredBrands.isEmpty(),
				"Expected no brand to match a query-level locale, but got: " + localeFilteredBrands
			);

			final List<SealedEntity> brands = fetchBrands(evita, 103, false);
			assertEquals(1, brands.size(), "The very same query without a locale must return the brand.");
			assertEquals(103, brands.get(0).getPrimaryKey());
			assertChain(parentChainOf(brands.get(0)), "B(102)", "B(101)");
		}
	}

	/**
	 * Pins both cells of every row where the chain is broken by a deleted or never-created ancestor. Every
	 * ancestor the index still holds is reported, and the primary key it cannot resolve is a link of the
	 * chain like any other - it simply can never yield a body, so `MATCHING` cuts the chain just below it
	 * while `COMPLETE` reports it as the pointer that tops the chain. Where the break sits, and whether the
	 * vanished node had a parent of its own, makes no difference to the rule.
	 */
	@Nested
	@DisplayName("Broken chains (K rows)")
	class BrokenChainTest {

		/**
		 * Matrix row K1 - `72 -> 71`, where 71 was a root that has been deleted. The queried entity still
		 * carries 71 as its parent primary key, but no body can ever be materialized for it, so the chain
		 * is cut below it and 72 reports no parent at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K1: the chain is cut below a deleted root sitting at the immediate-parent position")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheChainBelowADeletedRootParent_K1(Evita evita) {
			assertChain(standardParentChain(evita, 72));
		}

		/**
		 * Matrix row K1 under `COMPLETE` - `72 -> 71`, where the root 71 was deleted. The primary key the
		 * queried entity still carries is reported as a pointer, and nothing exists above it to walk.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K1: a deleted root at the immediate-parent position is reported as a pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportDeletedRootParentAsPointer_K1(Evita evita) {
			assertChain(completeParentChain(evita, 72), "P(71)");
		}

		/**
		 * Matrix row K2 - `83 -> 82 -> 81`, where 81 was a deleted root. The chain is structurally broken at
		 * 81: removing the root un-indexed it and orphaned the subtree below it, so 82 is reported with its
		 * body and the chain is cut just below the unresolvable 81, exactly as it is cut below a break left
		 * by a deleted mid-chain ancestor (K4).
		 *
		 * The terminal `B(82)` reports `parentAvailable() == true`, which a genuine root does as well (see
		 * the control row), so neither observable separates this cut from the end of a hierarchy.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K2: the chain is cut below a deleted root sitting two levels up")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheChainBelowADeletedRootTwoLevelsUp_K2(Evita evita) {
			final List<EntityClassifierWithParent> chain = standardParentChain(evita, 83);
			assertChain(chain, "B(82)");
			assertTrue(
				assertBody(chain.get(0), 82).parentAvailable(),
				"The cut chain still reports parentAvailable() on 82."
			);
		}

		/**
		 * Matrix row K2 under `COMPLETE` - `83 -> 82 -> 81`, where the root 81 was deleted. The pointer at
		 * the break is built from the primary key the reachable 82 still points at, which the index can no
		 * longer resolve.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K2: the deleted root two levels up tops the chain as a pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportDeletedRootTwoLevelsUpAsPointer_K2(Evita evita) {
			assertChain(completeParentChain(evita, 83), "B(82)", "P(81)");
		}

		/**
		 * Variant of matrix row K2 - `94 -> 93 -> 92 -> 91`, where the deleted root 91 sits three levels
		 * up. Both reachable ancestors below the break are reported with their bodies and the walk stops at
		 * the break, so a deletion at the root of a chain reads exactly like the mid-chain break of K5.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K2 variant: a deleted root three levels up disappears silently as well")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropDeletedRootThreeLevelsUp_K2variant(Evita evita) {
			assertChain(standardParentChain(evita, 94), "B(93)", "B(92)");
		}

		/**
		 * Matrix row K3 - `123 -> 122 -> 121`, where the mid-chain 122 has been deleted and sits at the
		 * immediate-parent position. The chain is cut below it, exactly as a locale-less immediate parent
		 * is cut in P1: an unresolvable primary key can never yield the requested body either.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K3: the chain is cut below a deleted mid-chain immediate parent")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheChainBelowADeletedMidChainParent_K3(Evita evita) {
			assertChain(standardParentChain(evita, 123));
		}

		/**
		 * Matrix row K3 under `COMPLETE` - `123 -> 122 -> 121`, where the mid-chain 122 was deleted and
		 * sits at the immediate-parent position. The deleted key is reported as a pointer; the root 121
		 * above it is unreachable, so the chain ends there in both modes alike.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K3: a deleted mid-chain immediate parent is reported as a pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportDeletedMidChainParentAsPointer_K3(Evita evita) {
			assertChain(completeParentChain(evita, 123), "P(122)");
		}

		/**
		 * Variant of matrix row N1 measured on the broken chain of K3 - `123 -> 122 deleted` with a bare
		 * `hierarchyContent()`. No body is requested, so nothing can fail to materialize and the whole
		 * chain of parent primary keys the entity carries is reported - here the single unresolvable key
		 * 122, which is a link of that chain like any other. That is the bare form's own rule rather than a
		 * mode, so both cells of the row are the same chain.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N1 variant: a bare hierarchyContent reports the unresolvable parent primary key")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldReportTheUnresolvableParentKeyWhenNoBodiesRequested_N1variant(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			assertChain(fetchParentChain(evita, 123, bareRequirement(behaviour), true), "P(122)");
		}

		/**
		 * Matrix row K4 - `124 -> 123 -> 122 -> 121`, where the deleted mid-chain 122 sits exactly two
		 * levels above the queried entity. The reachable ancestor 123 is reported with its body and the
		 * chain is cut just below 122, which can never yield the requested body; the root 121 above it
		 * cannot be reached at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4: the ancestor below a deleted mid-chain ancestor two levels up is returned with its body")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportReachableAncestorWhenDeletedMidChainAncestorSitsTwoLevelsUp_K4(Evita evita) {
			assertChain(standardParentChain(evita, 124), "B(123)");
		}

		/**
		 * Matrix row K4 under `COMPLETE` - `124 -> 123 -> 122 -> 121`, the break two levels up. The
		 * reachable 123 carries its body and the unresolvable 122 above it is the pointer that tops the
		 * chain.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4: a body is reported below the pointer at a break two levels up")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportABodyBelowThePointerAtABreakTwoLevelsUp_K4(Evita evita) {
			assertChain(completeParentChain(evita, 124), "B(123)", "P(122)");
		}

		/**
		 * Variant of matrix row K4 with a bare `hierarchyContent()`. No body is requested, so nothing can
		 * fail to materialize and the chain of parent primary keys the entity carries is reported whole -
		 * the reachable 123 and, above it, the unresolvable 122 that 123 still points at. The bare form
		 * answers the same in both modes, and that equality is the claim being pinned.
		 *
		 * @param behaviour the parents behaviour under test
		 * @param evita     the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4 variant: without ancestor bodies the unresolvable key tops the reported chain")
		@UseDataSet(DATA_SET)
		@ParameterizedTest(name = "{0}")
		@EnumSource(HierarchyParentsBehaviour.class)
		void shouldReportTheUnresolvableKeyAboveTheReachableAncestorWhenNoBodiesRequested_K4variant(
			HierarchyParentsBehaviour behaviour,
			Evita evita
		) {
			assertChain(fetchParentChain(evita, 124, bareRequirement(behaviour), true), "P(123)", "P(122)");
		}

		/**
		 * Variant of matrix row K4 with no query locale. The break is structural, so removing the locale
		 * that gates every P row leaves the reported chain untouched.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4 variant: the reported chain is the same when the query carries no locale")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportReachableAncestorWhenDeletedMidChainAncestorSitsTwoLevelsUpWithoutQueryLocale_K4variant(
				Evita evita
		) {
			assertChain(fetchParentChain(evita, 124, standardRequirement(), false), "B(123)");
		}

		/**
		 * Variant of matrix row K4 on the Czech-only mid-chain deletion - `134 -> 133 -> 132 -> 131`. The
		 * deleted 132 held Czech data only, and the deletion path is unaffected by the ancestor's locale -
		 * the reachable 133 holds English data and is reported with its body, exactly as K4 reports 123.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4 variant: the child of a deleted Czech-only ancestor two levels up is returned with its body")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportReachableAncestorWhenDeletedLocaleLessMidChainAncestorSitsTwoLevelsUp_K4variant(Evita evita) {
			assertChain(standardParentChain(evita, 134), "B(133)");
		}

		/**
		 * Variant of matrix row K4 bounding the walk by `stopAt(distance(1))`. The unresolvable 122 sits at
		 * distance two, beyond the bound, so it is cut exactly as a resolvable ancestor at that distance
		 * would be: the pointer at a break is an element of the chain, not an addition on top of it.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4 variant: a distance bound cuts the pointer at a break like any other ancestor")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutThePointerAtABreakByADistanceBound_K4variant(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 124,
					hierarchyContent(
						HierarchyParentsBehaviour.COMPLETE, stopAt(distance(1)),
						entityFetch(attributeContentAll())
					),
					true
				),
				"B(123)"
			);
		}

		/**
		 * Matrix row K5 - `125 -> 124 -> 123 -> 122`, where the break sits three levels up. Both ancestors
		 * below the break carry English data, so both are reported with their bodies and the chain ends at
		 * the break.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K5: a break three levels up returns both reachable ancestors with their bodies")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportBothReachableAncestorsWhenBreakSitsThreeLevelsUp_K5(Evita evita) {
			assertChain(standardParentChain(evita, 125), "B(124)", "B(123)");
		}

		/**
		 * Matrix row K5 under `COMPLETE` - `125 -> 124 -> 123 -> 122`, the break three levels up. Both
		 * reachable ancestors carry their bodies and the unresolvable key tops the chain.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K5: two bodies are reported below the pointer at a break three levels up")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTwoBodiesBelowThePointerAtABreakThreeLevelsUp_K5(Evita evita) {
			assertChain(completeParentChain(evita, 125), "B(124)", "B(123)", "P(122)");
		}

		/**
		 * Variant of matrix row K5 measuring the `stopAt(level(N))` decision the walk's `level` argument
		 * feeds, which every other row in this class makes through `distance` instead. `level` is an
		 * absolute depth counted from the top of the tree, and the depth of a fragment sitting under a
		 * break cannot be known - the walk reports -1 for every one of its nodes, and a level bound never
		 * cuts an unknown level. So the whole reachable fragment survives a bound that would have cut the
		 * same chain intact: in `121 -> 122(deleted) -> 123 -> 124 -> 125` both 123 and 124 are returned.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K5 variant: a level bound of two cannot cut a fragment of unknown depth")
		@UseDataSet(DATA_SET)
		@Test
		void shouldNotCutTheReachableFragmentByALevelBoundWhenBreakSitsAboveIt_K5variant(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 125, hierarchyContent(stopAt(level(2)), entityFetch(attributeContentAll())), true
				),
				"B(124)", "B(123)"
			);
		}

		/**
		 * The other side of the pair above - the same fetch under the lowest bound the constraint accepts.
		 * Both bounds return the same fragment, which is the whole point: on a broken chain the answer does
		 * not depend on `N` at all, so a caller that needs a bound which still holds there has to express it
		 * as a `distance`.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K5 variant: a level bound of one returns the very same fragment")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheWholeFragmentWhenTheLevelBoundAdmitsItsTop_K5variant(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 125, hierarchyContent(stopAt(level(1)), entityFetch(attributeContentAll())), true
				),
				"B(124)", "B(123)"
			);
		}

		/**
		 * Variant of matrix row K5 bounding the walk by `stopAt(node(...))`, the one stop predicate that
		 * carries state across the walk rather than deciding per node: it admits ancestors up to and
		 * including the first one matching the filter, and refuses everything above it. Selecting 123 must
		 * therefore end the chain at 123, suppressing the pointer to the unresolvable 122 that the
		 * unbounded K5 row reports right above it.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K5 variant: a node bound ending at 123 suppresses the pointer above it")
		@UseDataSet(DATA_SET)
		@Test
		void shouldSuppressThePointerAtABreakAboveTheNodeTheBoundStopsAt_K5variant(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 125,
					hierarchyContent(
						HierarchyParentsBehaviour.COMPLETE,
						stopAt(node(filterBy(entityPrimaryKeyInSet(123)))),
						entityFetch(attributeContentAll())
					),
					true
				),
				"B(124)", "B(123)"
			);
		}

		/**
		 * Matrix row K6 - `111 -> 999`, a parent primary key that was never created. The upsert setting it
		 * was accepted, and the dangling key can never yield a body, so the chain is cut below it.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K6: the chain is cut below a never-created parent primary key")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutTheChainBelowANeverCreatedParent_K6(Evita evita) {
			// the upsert that set parent 999 was accepted even though 999 never existed
			assertChain(standardParentChain(evita, 111));
		}

		/**
		 * Matrix row K6 under `COMPLETE` - `111 -> 999`, a parent primary key that was never created. A
		 * dangling key reaching the index from the ingest side is reported exactly like one left behind by
		 * a deletion.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K6: a never-created parent primary key is reported as a pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportNeverCreatedParentAsPointer_K6(Evita evita) {
			assertChain(completeParentChain(evita, 111), "P(999)");
		}

		/**
		 * Companion of matrix row K6 - `112 -> 111 -> 999`. A parent primary key that was never created
		 * breaks the chain from the ingest side exactly as a deletion breaks it, with no deletion involved:
		 * 111 is reported with its body and the dangling 999 above it is not reported at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K6 variant: the ancestor below a never-created ancestor two levels up is returned with its body")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportReachableAncestorWhenNeverCreatedAncestorSitsTwoLevelsUp_K6variant(Evita evita) {
			assertChain(standardParentChain(evita, 112), "B(111)");
		}

		/**
		 * Matrix row K6 and its companion asked in one query - `111 -> 999` and `112 -> 111 -> 999`, where
		 * one queried entity points straight at the break and the other reaches it through a resolvable
		 * ancestor. Nothing else in this class queries more than one entity at a time, and the two shapes
		 * are what makes the per-entity state of the walk observable: the parents are walked in ascending
		 * primary-key order, so the resolvable 111 is served first and the unresolvable 999 second, and the
		 * accumulator that decides whether a walk offered any node at all has to be cleared between the two.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K6: two queried entities, one pointing straight at the break, each keep their own chain")
		@UseDataSet(DATA_SET)
		@Test
		void shouldKeepTheChainsOfTwoQueriedEntitiesApartAcrossABreak_K6(Evita evita) {
			final List<SealedEntity> leaves = fetchLeaves(evita, standardRequirement(), 111, 112);
			assertEquals(2, leaves.size(), "Both queried categories must be returned.");
			assertChain(parentChainOf(leaves, 111));
			assertChain(parentChainOf(leaves, 112), "B(111)");
		}

		/**
		 * The `COMPLETE` twin of the row above. The unresolvable 999 tops both chains, once as the only
		 * element of 111's chain and once above the body of 111 in 112's, so the same primary key is
		 * reported consistently whether it is reached directly or through an ancestor.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K6: two queried entities report the same break consistently under the mode")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheSameBreakToBothQueriedEntities_K6(Evita evita) {
			final List<SealedEntity> leaves = fetchLeaves(evita, completeRequirement(), 111, 112);
			assertEquals(2, leaves.size(), "Both queried categories must be returned.");
			assertChain(parentChainOf(leaves, 111), "P(999)");
			assertChain(parentChainOf(leaves, 112), "B(111)", "P(999)");
		}

		/**
		 * A chain that leads into a ring instead of reaching a top - `144 -> 143 -> 141 -> 142 -> 141`,
		 * where 141 and 142 point at each other after 141 was re-pointed at its own descendant. The index
		 * treats the return to an already collected node as a break and stops there, so the walk terminates
		 * and each ring member is offered exactly once; the chain the caller receives should therefore hold
		 * 143, 141 and 142 and stop.
		 *
		 * The top of the fragment still carries a parent primary key, and it is indistinguishable from one the
		 * index cannot resolve unless the keys already placed in the chain are known - so the fetcher is told
		 * which they are and refuses to append a member it has already reported.
		 *
		 * The timeout is not decoration - a walk that followed the ring would never return at all, and the
		 * bound is deliberately generous because it costs nothing on a passing run.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Ring: the chain below a ring terminates and reports each member once")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportEachRingMemberOnceBelowARing_ring(Evita evita) {
			assertTimeoutPreemptively(
				Duration.ofSeconds(30),
				() -> assertChain(standardParentChain(evita, 144), "B(143)", "B(141)", "B(142)")
			);
		}

		/**
		 * The `COMPLETE` twin of the row above. Nothing along the chain fails to materialize, so the mode
		 * has nothing to substitute and the ring is reported exactly as the default reports it.
		 *
		 * Each member is reported once here too, so no ancestor contributes its IO statistics to the chain
		 * twice.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Ring: the mode reports the chain below a ring exactly as the default does")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheRingIdenticallyUnderTheMode_ring(Evita evita) {
			assertTimeoutPreemptively(
				Duration.ofSeconds(30),
				() -> assertChain(completeParentChain(evita, 144), "B(143)", "B(141)", "B(142)")
			);
		}
	}

	/**
	 * Pins what each typed-proxy form makes of the parent chain - the one place in this class where the
	 * assertions deliberately leave the raw {@link SealedEntity} API.
	 *
	 * The forms differ in what they need from an ancestor and in which result wrapper
	 * {@link io.evitadb.api.proxy.impl.ProxyUtils#createOptionalWrapper} picks for them, and the two
	 * together decide what a bodyless pointer looks like from the outside: an entity-typed getter needs the
	 * body and either throws or reports nothing, while a primary-key or classifier getter reads what the
	 * pointer itself carries and keeps answering. A record projection reaches the same axis through a
	 * constructor parameter with no wrapper in front of it at all.
	 */
	@Nested
	@DisplayName("Typed proxy over the parent chain")
	@Tag(PROXY)
	class TypedProxyTest {

		/**
		 * The typed-proxy view of matrix row P1, and the one place where the `MATCHING` default is visibly
		 * different from the behaviour that preceded it. A `@ParentEntity` getter that declares an exception
		 * gets the rethrowing wrapper, so it used to surface the {@link ContextMissingException} raised on
		 * the bodyless pointer at 11. The chain is now cut below 11 instead, the getter sees no parent, and
		 * the wrapper returns `null` - a silent root where an exception used to be raised.
		 *
		 * The control on the fully materializable 3 proves the interface really resolves a parent when one
		 * carries a body, so the `null` cannot come from a proxy that never works at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1 proxy: a locale-less immediate parent leaves a typed proxy with no parent at all")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportNoParentWhenTypedProxyDereferencesLocaleLessImmediateParent_P1proxy(Evita evita) {
			final ParentRethrowingCategory localeLessParentHolder = fetchCategoryProxy(evita, 12);
			assertNull(
				localeLessParentHolder.getParentEntity(),
				"The chain is cut below 11, so the typed getter must report no parent rather than throw."
			);

			final ParentRethrowingCategory control = fetchCategoryProxy(evita, 3);
			final ParentRethrowingCategory parent = control.getParentEntity();
			assertNotNull(parent, "The materializable parent 2 must be returned as a proxy.");
			assertEquals(2, parent.getId());
		}

		/**
		 * The typed-proxy view of matrix row P1 under `COMPLETE` - `12 -> 11(cs)`. This is the mode that
		 * keeps the unmaterializable ancestor in the chain, so the proxy really does meet a bodyless
		 * pointer here, and a getter that declares the exception must be told about it rather than shown
		 * a root that does not exist.
		 *
		 * The message is asserted, not only the exception type: the caller of this query did ask for the
		 * ancestor bodies, so the advice to add `hierarchyContent` with `entityFetch` would send them
		 * chasing a requirement their query already carries.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1 proxy: a declaring getter throws on the bodyless pointer at the immediate parent")
		@UseDataSet(DATA_SET)
		@Test
		void shouldThrowWhenDeclaringProxyGetterMeetsPointer_P1proxy(Evita evita) {
			final ParentRethrowingCategory pointerParentHolder = fetchCategoryProxy(
				evita, 12, completeRequirement(), ParentRethrowingCategory.class
			);

			final ContextMissingException exception = assertThrows(
				ContextMissingException.class,
				pointerParentHolder::getParentEntity,
				"The pointer at 11 carries no body, so a getter declaring the exception must receive it."
			);
			assertTrue(
				exception.getMessage().contains("`" + Entities.CATEGORY + "` with primary key `11`"),
				"The message must name the pointer it was raised on, but was: " + exception.getMessage()
			);
			assertTrue(
				exception.getMessage().contains(HierarchyParentsBehaviour.COMPLETE.name()),
				"The message must name the behaviour that put the pointer there, but was: " + exception.getMessage()
			);
			assertFalse(
				exception.getMessage().contains("was not fetched along with the entity"),
				"The body was requested by this very query, so the message must not claim it was not asked " +
					"for, but was: " + exception.getMessage()
			);
		}

		/**
		 * The other half of the wrapper contract on the very same fetch as
		 * {@link #shouldThrowWhenDeclaringProxyGetterMeetsPointer_P1proxy}: a getter that declares no
		 * exception gets the swallowing wrapper, which turns the raised
		 * {@link ContextMissingException} into an absent result. The two together are what makes a
		 * bodyless ancestor behave exactly like a reference whose body was not fetched.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1 proxy: a non-declaring getter reports no parent on the very same pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportNoParentWhenSwallowingProxyGetterMeetsPointer_P1proxy(Evita evita) {
			final ParentSwallowingCategory pointerParentHolder = fetchCategoryProxy(
				evita, 12, completeRequirement(), ParentSwallowingCategory.class
			);
			assertNull(
				pointerParentHolder.getParentEntity(),
				"A getter that declares no exception must swallow it and report no parent."
			);
		}

		/**
		 * The third wrapper family on the same pointer: a getter returning an {@link Optional} picks the
		 * swallowing or the rethrowing member by the very same rule - the presence of a `throws` clause -
		 * so the two getters on one fetch report an empty optional and an exception respectively.
		 *
		 * The `Optional` path also changes the extractor, which consults `parentAvailable()` before it
		 * reads the slot, so this is the only proxy form where an entity that never had its hierarchy
		 * fetched is answered without an exception at all. The control on the fully materializable 3 proves
		 * both getters really resolve a parent that carries a body.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1 proxy: the two optional getters disagree on the very same pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportAnEmptyOptionalAndThrowOnTheSamePointer_P1proxy(Evita evita) {
			final ParentRethrowingCategory pointerParentHolder = fetchCategoryProxy(
				evita, 12, completeRequirement(), ParentRethrowingCategory.class
			);
			assertEquals(
				Optional.empty(), pointerParentHolder.getParentEntityIfPresent(),
				"The optional getter declaring no exception must report the pointer as an absent parent."
			);
			assertThrows(
				ContextMissingException.class,
				pointerParentHolder::getParentEntityOrFail,
				"The optional getter declaring the exception must receive it on the very same pointer."
			);

			final ParentRethrowingCategory control = fetchCategoryProxy(
				evita, 3, completeRequirement(), ParentRethrowingCategory.class
			);
			assertEquals(
				2, control.getParentEntityIfPresent().orElseThrow().getId(),
				"The swallowing optional getter must resolve a parent that carries a body."
			);
			assertEquals(
				2, control.getParentEntityOrFail().orElseThrow().getId(),
				"The rethrowing optional getter must resolve a parent that carries a body."
			);
		}

		/**
		 * A pointer is bodyless, not identity-less. The primary-key form of the `@ParentEntity` getter
		 * therefore keeps answering on matrix row P1 under `COMPLETE` where the entity-typed form gives
		 * up, and so does the raw-classifier form - both read what the pointer itself carries and neither
		 * needs the ancestor body.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1 proxy: the primary-key and classifier getters still answer on a bodyless pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReturnParentPrimaryKeyWhenProxyGetterMeetsPointer_P1proxy(Evita evita) {
			final ParentRethrowingCategory pointerParentHolder = fetchCategoryProxy(
				evita, 12, completeRequirement(), ParentRethrowingCategory.class
			);

			final Integer parentId = pointerParentHolder.getParentId();
			assertNotNull(parentId, "The pointer carries the primary key 11, so the getter must report it.");
			assertEquals(11, parentId.intValue());

			final EntityClassifierWithParent parentClassifier = pointerParentHolder.getParentClassifier();
			assertNotNull(parentClassifier, "The classifier form must hand back the pointer itself.");
			assertPointer(parentClassifier, 11);
		}

		/**
		 * The record projection of matrix row P1 in both modes - the second of the two proxy sites the
		 * parent axis reaches, and the one with no result wrapper in front of it. Under the default the
		 * chain is cut below 11, so the whole projection succeeds with both parent components empty; under
		 * `COMPLETE` the pointer at 11 reaches the extractor, which has no way to build a projection over
		 * a bodyless ancestor and raises instead.
		 *
		 * The raised {@link ContextMissingException} does not reach the caller as it is: a constructor
		 * projection is filled inside the proxy factory, which wraps anything a parameter extractor throws in
		 * an {@link EntityClassInvalidException} naming the record it could not fill. Both layers are asserted,
		 * because the wrapper alone would also be raised by a record shape the factory cannot map at all.
		 *
		 * The control on the fully materializable `3 -> 2 -> 1` proves the projection really does resolve a
		 * parent that carries a body, so neither the empty components nor the exception can come from a
		 * record shape the proxy factory cannot fill at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1 proxy: the record projection is empty under the default and raises under the mode")
		@UseDataSet(DATA_SET)
		@Test
		void shouldProjectTheParentAxisIntoARecord_P1proxy(Evita evita) {
			final ParentProjectingCategory cutChain = fetchCategoryProxy(
				evita, 12, standardRequirement(), ParentProjectingCategory.class
			);
			assertEquals(12, cutChain.id());
			assertNull(cutChain.parentId(), "The chain is cut below 11, so no parent primary key is reported.");
			assertNull(cutChain.parentEntity(), "The chain is cut below 11, so no parent projection is built.");

			final EntityClassInvalidException wrapped = assertThrows(
				EntityClassInvalidException.class,
				() -> fetchCategoryProxy(evita, 12, completeRequirement(), ParentProjectingCategory.class),
				"The pointer at 11 carries no body, so no projection can be built over it."
			);
			final ContextMissingException cause = assertInstanceOf(
				ContextMissingException.class, wrapped.getCause(),
				"The wrapper must carry the reason the projection could not be filled."
			);
			assertTrue(
				cause.getMessage().contains("`" + Entities.CATEGORY + "` with primary key `11`"),
				"The cause must name the pointer it was raised on, but was: " + cause.getMessage()
			);

			final ParentProjectingCategory control = fetchCategoryProxy(
				evita, 3, completeRequirement(), ParentProjectingCategory.class
			);
			assertEquals(3, control.id());
			assertEquals(2, control.parentId());
			assertNotNull(control.parentEntity(), "The materializable parent 2 must be projected.");
			assertEquals(2, control.parentEntity().id());
		}

		/**
		 * The control for the three proxy forms on the fully materializable `3 -> 2 -> 1`, asked in the
		 * same mode. It proves the `null` and the exception the P1 rows above observe come from the
		 * bodyless pointer rather than from a proxy contract that never resolves a parent at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: every proxy form resolves the parent when the parent carries a body")
		@UseDataSet(DATA_SET)
		@Test
		void shouldResolveParentThroughEveryProxyFormWhenBodyIsPresent_control(Evita evita) {
			final ParentRethrowingCategory declaringHolder = fetchCategoryProxy(
				evita, 3, completeRequirement(), ParentRethrowingCategory.class
			);
			final ParentRethrowingCategory parent = declaringHolder.getParentEntity();
			assertNotNull(parent, "The materializable parent 2 must be returned as a proxy.");
			assertEquals(2, parent.getId());

			final Integer parentId = declaringHolder.getParentId();
			assertNotNull(parentId, "The primary-key form must report the parent 2 as well.");
			assertEquals(2, parentId.intValue());

			final ParentSwallowingCategory swallowingHolder = fetchCategoryProxy(
				evita, 3, completeRequirement(), ParentSwallowingCategory.class
			);
			final ParentSwallowingCategory swallowingParent = swallowingHolder.getParentEntity();
			assertNotNull(swallowingParent, "The non-declaring getter must resolve a parent that has a body.");
			assertEquals(2, swallowingParent.getId());
		}

		/**
		 * The typed-proxy view of matrix row P3 under `COMPLETE` - `34 -> 33 -> 32(cs) -> 31`. It walks the
		 * chain the way an application would: the proxy of 34 hands back the proxy of 33, and asking 33 for
		 * its parent meets the pointer at 32 and throws.
		 *
		 * It also pins the limit of the typed surface. The body of 31 above the pointer is present in the
		 * chain - the raw-API row of this matrix asserts `B(33) -> P(32) -> B(31)` - but no typed proxy of
		 * it can be reached, because the only step that would produce one is the entity-typed getter that
		 * has to give up on 32. The classifier form is the way past the pointer, and what it yields above
		 * it is the raw {@link SealedEntity}, not a proxy.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3 proxy: the typed walk stops at the pointer, and only the classifier form gets past it")
		@UseDataSet(DATA_SET)
		@Test
		void shouldStopTypedProxyWalkAtPointerAndCrossItAsClassifier_P3proxy(Evita evita) {
			final ParentRethrowingCategory leaf = fetchCategoryProxy(
				evita, 34, completeRequirement(), ParentRethrowingCategory.class
			);
			final ParentRethrowingCategory bodyParent = leaf.getParentEntity();
			assertNotNull(bodyParent, "33 carries a body, so the typed getter must hand back its proxy.");
			assertEquals(33, bodyParent.getId());

			final ContextMissingException exception = assertThrows(
				ContextMissingException.class,
				bodyParent::getParentEntity,
				"The parent of 33 is the bodyless pointer at 32, which no proxy can be built over."
			);
			assertTrue(
				exception.getMessage().contains("`" + Entities.CATEGORY + "` with primary key `32`"),
				"The message must name the pointer it was raised on, but was: " + exception.getMessage()
			);

			final Integer pointerId = bodyParent.getParentId();
			assertNotNull(pointerId, "The pointer at 32 still carries its primary key.");
			assertEquals(32, pointerId.intValue());

			final EntityClassifierWithParent pointer = bodyParent.getParentClassifier();
			assertNotNull(pointer, "The classifier form must hand back the pointer at 32.");
			assertPointer(pointer, 32);
			final EntityClassifierWithParent aboveThePointer = pointer.getParentEntity()
				.orElseThrow(() -> new AssertionError("The chain must continue above the pointer at 32."));
			assertBody(aboveThePointer, 31);
		}
	}

	/**
	 * Pins what a round trip through the session does to a chain the fetcher had already settled. Every
	 * `enrichEntity` and `enrichOrLimitEntity` call builds a fresh decorator over a raw delegate, and the
	 * raw delegate still carries the immediate parent primary key the entity was stored with - so a
	 * resolved chain that is not carried across verbatim silently falls back to that single raw ancestor,
	 * which is the very thing the default mode exists to hide.
	 *
	 * The rows below use the P1 fixture, whose chain the default cuts to nothing, because an empty chain
	 * and the fallback are the two answers that differ most visibly.
	 */
	@Nested
	@DisplayName("Re-wrapping a resolved chain")
	class ReWrapTest {

		/**
		 * Matrix row P1 re-wrapped by `enrichOrLimitEntity` asking for the very same requirement. The chain
		 * was resolved and found empty, and enriching an entity with a requirement it already satisfies must
		 * not change what it reports.
		 *
		 * An enrichment request carries no filter of its own - it names the entity's locales through
		 * `dataInLocales` instead - so the parent walk it runs would otherwise materialize the very ancestor the
		 * query locale hid. The parent body fetch therefore inherits that locale, which is what keeps the cut in
		 * place across a call that was asked to add nothing.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1: enrichOrLimitEntity keeps the chain the query locale cut")
		@UseDataSet(DATA_SET)
		@Test
		void shouldKeepTheCutChainAfterEnrichOrLimitEntity_P1(Evita evita) {
			assertChain(
				parentChainAfterReWrap(
					evita, 12, standardRequirement(),
					(session, entity) -> session.enrichOrLimitEntity(entity, standardRequirement())
				)
			);
		}

		/**
		 * The `COMPLETE` twin of the row above. The mode keeps the ancestor the locale hides as a bodyless
		 * pointer, so the round trip has a non-empty chain to preserve and the assertion distinguishes a
		 * preserved cut from a chain that simply came back empty.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1: enrichOrLimitEntity keeps the pointer the mode substituted for the hidden ancestor")
		@UseDataSet(DATA_SET)
		@Test
		void shouldKeepTheSubstitutedPointerAfterEnrichOrLimitEntity_P1(Evita evita) {
			assertChain(
				parentChainAfterReWrap(
					evita, 12, completeRequirement(),
					(session, entity) -> session.enrichOrLimitEntity(entity, completeRequirement())
				),
				"P(11)"
			);
		}

		/**
		 * The same row re-wrapped by a bare `enrichEntity` whose requirement names no `hierarchyContent` at
		 * all. No parent fetcher runs, so nothing recomputes the chain and the slot the first fetch resolved
		 * is all the new decorator has to go on.
		 *
		 * The slot is carried across verbatim, terminator included, so the entity keeps reporting the empty
		 * chain the query resolved for it.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1: the cut chain survives a bare enrichEntity that asks for no hierarchy")
		@UseDataSet(DATA_SET)
		@Test
		void shouldKeepTheCutChainAfterBareEnrichEntity_P1(Evita evita) {
			assertChain(
				parentChainAfterReWrap(
					evita, 12, standardRequirement(),
					(session, entity) -> session.enrichEntity(entity, attributeContent(ATTRIBUTE_CODE))
				)
			);
		}

		/**
		 * The `COMPLETE` mirror on matrix row P3 - `34 -> 33 -> 32(cs) -> 31`, whose resolved chain is three
		 * elements long and alternates a body, a pointer and a body. A round trip through the session must
		 * hand it back unchanged rather than replacing it with the single raw ancestor the delegate carries.
		 *
		 * The body above the pointer is the one element a re-derivation cannot reproduce, because the enrichment
		 * reuses the bodies the first fetch had already read and only the immediate parent is among them - so the
		 * chain has to be carried across rather than walked again.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3: enrichOrLimitEntity keeps the body sitting above the pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldKeepThePointerChainAfterEnrichOrLimitEntity_P3(Evita evita) {
			assertChain(
				parentChainAfterReWrap(
					evita, 34, completeRequirement(),
					(session, entity) -> session.enrichOrLimitEntity(entity, completeRequirement())
				),
				"B(33)", "P(32)", "B(31)"
			);
		}
	}

	/**
	 * Pins the second production caller of the upward walk. `hierarchyContent` is the one every row of the
	 * behaviour matrix goes through; the `parents` hierarchy extra-result is the other, and it renders the
	 * very same walk as a {@link LevelInfo} tree rather than as a parent chain. Before the walk was
	 * rewritten an orphaned start node produced no parents at all here, so the broken-chain shape of this
	 * caller is new and had no coverage of its own.
	 *
	 * The queried node itself is the deepest element of the returned tree and is marked as requested; its
	 * reachable ancestors nest above it, outermost first.
	 */
	@Nested
	@DisplayName("Parent statistics over a broken chain")
	class ParentStatisticsOverBrokenChainTest {

		/**
		 * The broken chain `121 -> 122(deleted) -> 123 -> 124 -> 125` queried at its leaf. The walk starts
		 * at 125 itself here rather than at its parent, and it reports the fragment the index still holds -
		 * 123 and 124 - with 121 above the break left out entirely.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("The reachable fragment is reported as the parent statistics of an orphaned node")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheReachableFragmentAsParentStatistics(Evita evita) {
			assertEquals(
				"""
					B(123)
					   B(124)
					      B(125) (requested)
					""",
				renderParentStatistics(evita, 125)
			);
		}

		/**
		 * The intact control chain `1 -> 2 -> 3` under the identical query. Without it the broken case
		 * above could pass while the computer reported nothing useful at all, since a tree of the wrong
		 * shape and a tree that stops early look alike when only one of them is measured.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: an intact chain is reported from its real root down to the queried node")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheWholeChainAsParentStatistics_control(Evita evita) {
			assertEquals(
				"""
					B(1)
					   B(2)
					      B(3) (requested)
					""",
				renderParentStatistics(evita, 3)
			);
		}
	}

	/**
	 * Pins what a query locale does to the `parents` hierarchy extra-result, over the very fixtures the
	 * behaviour matrix uses for `hierarchyContent`. The two callers walk the same axis and answer the
	 * locale gate differently on purpose, and this class is where that difference is written down.
	 *
	 * A `hierarchyContent` chain is the ancestor axis of an entity the caller fetched, so
	 * {@link HierarchyParentsBehaviour} lets the caller choose between a chain of nothing but
	 * materializable bodies and a chain that keeps a bodyless pointer where a body is impossible. The
	 * statistics tree is a different product: it is a picture of the hierarchy's shape, built to be
	 * rendered as a menu or a breadcrumb, so it is never cut and never holds a hole for want of a body.
	 * The extra result therefore behaves as {@link HierarchyParentsBehaviour#COMPLETE} at all times and
	 * takes no argument to say so.
	 *
	 * What the tree *contains* is settled one layer earlier, by the hierarchy filtering predicate that
	 * carries the query filter and its locale gate, before any body is fetched. That is why an ancestor
	 * holding no data in the query locale is absent here rather than present as a pointer, and why the
	 * absence is identical whether or not ancestor bodies were requested at all - the pair of rows below
	 * asserts exactly that equality.
	 *
	 * The gate holds on every path into the tree, including the `hierarchyOfReference` one, where a
	 * `having` bound names the traversable ancestors explicitly. The bound narrows the gate rather than
	 * replacing it, so the last two rows here reach the same fixtures through a reference and get the
	 * trees the `hierarchyOfSelf` rows above them get.
	 */
	@Nested
	@DisplayName("Parent statistics under a query locale")
	class ParentStatisticsUnderQueryLocaleTest {

		/**
		 * The P2 fixture `21(cs) -> 22 -> 23` queried at its leaf under the English locale. The Czech-only
		 * root holds no English data, so the locale gate keeps it out of the tree and the statistics stop
		 * at the materializable `22` - the same place the `MATCHING` chain of row P2 stops.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("A locale-less root is absent from the reported tree")
		@UseDataSet(DATA_SET)
		@Test
		void shouldOmitALocaleLessRoot_P2(Evita evita) {
			assertEquals(
				"""
					B(22)
					   B(23) (requested)
					""",
				renderParentStatistics(evita, 23, Locale.ENGLISH, entityFetch(attributeContentAll()))
			);
		}

		/**
		 * The P3 fixture `31 -> 32(cs) -> 33 -> 34` queried at its leaf under the English locale. This is
		 * where the two callers part company: the `MATCHING` chain of row P3 stops below the Czech-only
		 * `32` and never reports `31`, while the statistics tree drops `32` alone and keeps the
		 * materializable root above it - which is what a breadcrumb needs and a cut cannot give.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("A locale-less ancestor is dropped while the materializable root above it stays")
		@UseDataSet(DATA_SET)
		@Test
		void shouldKeepTheRootAboveALocaleLessAncestor_P3(Evita evita) {
			assertEquals(
				"""
					B(31)
					   B(33)
					      B(34) (requested)
					""",
				renderParentStatistics(evita, 34, Locale.ENGLISH, entityFetch(attributeContentAll()))
			);
		}

		/**
		 * The K4 fixture `121 -> 122(deleted) -> 123 -> 124` queried at `124` under the English locale.
		 * A structural break is not a locale question, so the locale changes nothing here: the walk
		 * reports the fragment the index still holds and stops at it, exactly as it does with no locale.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("A break in the chain stops the tree at the reachable fragment under a locale too")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheReachableFragmentUnderALocale_K4(Evita evita) {
			assertEquals(
				"""
					B(123)
					   B(124) (requested)
					""",
				renderParentStatistics(evita, 124, Locale.ENGLISH, entityFetch(attributeContentAll()))
			);
		}

		/**
		 * The P2 fixture again, this time asking for no ancestor bodies at all. Every node comes back as a
		 * bodyless classifier, and the set of nodes is the one the body-carrying row above reports.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Without ancestor bodies the reported nodes are the same ones, bodyless")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheSameNodesWithoutBodies_P2(Evita evita) {
			assertEquals(
				"""
					P(22)
					   P(23) (requested)
					""",
				renderParentStatistics(evita, 23, Locale.ENGLISH, null)
			);
		}

		/**
		 * The P3 fixture asked without ancestor bodies. `32` is missing here as well, which is the proof
		 * that the locale gate removed it before any body was fetched - a body requirement cannot be the
		 * reason for an absence that survives the requirement being taken away.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("The locale-less ancestor is absent even when no body was asked for")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportTheSameNodesWithoutBodies_P3(Evita evita) {
			assertEquals(
				"""
					P(31)
					   P(33)
					      P(34) (requested)
					""",
				renderParentStatistics(evita, 34, Locale.ENGLISH, null)
			);
		}

		/**
		 * The P3 chain reached through a reference from the `PRODUCT` collection, with the traversable
		 * ancestors named by a `having` bound that lists every one of them, the Czech-only `32` included.
		 * This is the one shape in which the membership gate of the statistics tree is not the query
		 * locale alone, and it pins how the two combine: the `having` bound and the locale are conjoined,
		 * so naming `32` does not admit it, and the tree is the very one the `hierarchyOfSelf` row above
		 * reports for the same fixture.
		 *
		 * The gate is the guard this row exists for. A `hierarchyOfReference` request carries no
		 * predicate derived from the query's filtering formula, so the locale predicate has to be
		 * conjoined with the `having` predicate explicitly; when that conjunction was dropped, `32`
		 * entered the tree as a node whose body the English query locale cannot materialize.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("A having bound naming a locale-less ancestor still does not admit it")
		@UseDataSet(DATA_SET)
		@Test
		void shouldGateAHavingBoundOnTheQueryLocaleToo_P3(Evita evita) {
			assertEquals(
				"""
					B(31)
					   B(33)
					      B(34) (requested)
					""",
				renderReferencedParentStatistics(evita, 34, Locale.ENGLISH, 31, 32, 33, 34)
			);
		}

		/**
		 * The intact control chain `1 -> 2 -> 3` reached through the same reference and the same `having`
		 * bound, every node of it holding English data. The whole chain survives the conjoined gate, which
		 * is what separates the row above from a `having` bound that simply admits nothing: without this
		 * control, a gate rejecting every node would report the same absence and pass.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: a fully materializable chain reached through a reference carries bodies")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReportBodiesThroughAReferenceWhenEveryAncestorMaterializes_control(Evita evita) {
			assertEquals(
				"""
					B(1)
					   B(2)
					      B(3) (requested)
					""",
				renderReferencedParentStatistics(evita, 3, Locale.ENGLISH, 1, 2, 3)
			);
		}
	}

	/**
	 * Pins invariants the behaviour matrix has no row for, because they are not shapes of a returned
	 * chain but states of the index behind it.
	 */
	@Nested
	@DisplayName("Index invariants")
	class IndexInvariantTest {

		/**
		 * Removing a hierarchical entity un-indexes it whether or not it had a parent, so a deleted root
		 * stops matching `hierarchyWithinRootSelf()` and its descendants become orphans instead of staying
		 * attached through a node that resolves to no entity. This is part of #1365 because it decides what
		 * a broken chain means to the parent fetcher: a deletion at the root of a chain now breaks it in
		 * exactly the way a mid-chain deletion does, which is asserted here alongside it.
		 *
		 * The two halves are not the same disappearance and the assertions keep them apart. The deleted
		 * node leaves the index outright and resolves to no entity at all; its descendants are still
		 * present and still fetchable, and what they lose is only their route down from a root - which is
		 * what makes them orphans rather than deletions.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("A deleted root leaves the index and its descendants leave the root-reachable hierarchy")
		@UseDataSet(DATA_SET)
		@Test
		void shouldRemoveDeletedRootAndOrphanItsDescendants(Evita evita) {
			final Set<Integer> hierarchyMembers = rootReachableCategoryPrimaryKeys(evita);

			final int[] deletedRoots = {71, 81, 91};
			// 71, 81 and 91 were deleted, so the hierarchy index must no longer hold them
			for (int deletedRoot : deletedRoots) {
				assertFalse(
					hierarchyMembers.contains(deletedRoot),
					"The deleted root " + deletedRoot + " must leave the hierarchy index."
				);
			}
			final int[] orphanedDescendants = {72, 82, 83, 92, 93, 94};
			for (int descendant : orphanedDescendants) {
				assertFalse(
					hierarchyMembers.contains(descendant),
					"Node " + descendant + " must leave the root-reachable hierarchy when the root above it is deleted."
				);
			}

			// a mid-chain deletion behaves the same way - the deleted node leaves and its subtree is orphaned
			for (int deletedMidChainNode : new int[]{122, 132}) {
				assertFalse(
					hierarchyMembers.contains(deletedMidChainNode),
					"The deleted mid-chain node " + deletedMidChainNode + " must leave the hierarchy index."
				);
			}
			final int[] orphanedByMidChainDeletion = {123, 124, 125, 133, 134};
			for (int orphan : orphanedByMidChainDeletion) {
				assertFalse(
					hierarchyMembers.contains(orphan),
					"Node " + orphan + " must leave the root-reachable hierarchy when the node above it is deleted."
				);
			}

			// what separates the two disappearances: an orphan is still a live entity that a primary-key
			// filter finds, while the node whose deletion orphaned it resolves to nothing at all
			for (int orphan : orphanedDescendants) {
				assertTrue(
					fetchCategoryByPrimaryKey(evita, orphan).isPresent(),
					"The orphaned node " + orphan + " must still exist as an entity."
				);
			}
			for (int orphan : orphanedByMidChainDeletion) {
				assertTrue(
					fetchCategoryByPrimaryKey(evita, orphan).isPresent(),
					"The orphaned node " + orphan + " must still exist as an entity."
				);
			}

			// the fixture still has a hierarchy to report, so the assertions above cannot pass vacuously
			assertTrue(
				hierarchyMembers.contains(121) && hierarchyMembers.contains(131),
				"The roots that were never deleted must stay in the hierarchy index."
			);

			// every one of the three deleted roots resolves to no entity at all
			for (int deletedRoot : deletedRoots) {
				final Optional<SealedEntity> removedRoot = fetchCategoryByPrimaryKey(evita, deletedRoot);
				assertTrue(
					removedRoot.isEmpty(),
					"The deleted root " + deletedRoot + " must not be fetchable, but was: " + removedRoot
				);
			}
		}

		/**
		 * Clearing an entity's parent through the public API is the promotion of that entity to a root -
		 * `removeParent` leaves the entity in place and reports no parent for it afterwards, and the
		 * hierarchy index follows: the node is re-placed as a root rather than un-indexed, so it keeps
		 * matching `hierarchyWithinRootSelf()` and its subtree stays attached below it.
		 *
		 * The counterfactual is the mirror of the phantom root - un-indexing the node would contradict the
		 * invariant the rest of this class rests on, that a root is a node with a `null` parent in the
		 * index, and would silently orphan everything underneath. The case is reachable from ordinary
		 * generated data, since the data generator clears a parent whenever its random hierarchy picks the
		 * root branch.
		 *
		 * This is the only method in the class that writes, so it runs against the small writable fixture
		 * rather than the shared matrix one, and destroys it afterwards.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Clearing a parent re-roots the entity instead of dropping it out of the hierarchy")
		@UseDataSet(value = MUTABLE_DATA_SET, destroyAfterTest = true)
		@Test
		void shouldKeepAnEntityInTheHierarchyWhenItsParentIsCleared(Evita evita) {
			// a fresh branch below the intact root 1 - 201 hangs under it and 202 hangs under 201
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEnglishCategory(session, 201, 1);
					createEnglishCategory(session, 202, 201);
				}
			);
			final Set<Integer> beforeClearing = rootReachableCategoryPrimaryKeys(evita);
			assertTrue(
				beforeClearing.contains(201) && beforeClearing.contains(202),
				"The fresh branch must be reachable from the root before its parent is cleared."
			);

			// clearing the parent makes 201 a root as far as the entity itself is concerned
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.CATEGORY, 201, attributeContentAll(), hierarchyContent())
						.orElseThrow(() -> new AssertionError("Category 201 was not created."))
						.openForWrite()
						.removeParent()
						.upsertVia(session);
				}
			);

			// the entity survived the mutation and reports no parent at all, exactly as a root does
			assertTrue(
				fetchCategoryByPrimaryKey(evita, 201).isPresent(),
				"Category 201 must still exist after its parent was cleared."
			);
			assertChain(standardParentChain(evita, 201));

			// the cleared node is back in the hierarchy as a root, and it brought its subtree with it
			final Set<Integer> afterClearing = rootReachableCategoryPrimaryKeys(evita);
			assertTrue(
				afterClearing.contains(201),
				"Category 201 must return to the hierarchy as a root of its own."
			);
			assertTrue(
				afterClearing.contains(202),
				"The child of 201 must stay attached below it rather than being orphaned."
			);
			// the untouched root is still there, so the two assertions above cannot pass by the whole
			// hierarchy having collapsed into one flat set of roots
			assertTrue(
				afterClearing.contains(1),
				"The root the branch was created under must stay in the hierarchy index."
			);
		}
	}

}
