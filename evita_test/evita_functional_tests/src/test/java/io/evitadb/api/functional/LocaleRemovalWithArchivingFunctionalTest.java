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

package io.evitadb.api.functional;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies one entity upsert that changes the locales of an existing entity - removes the last values of a locale, or
 * adds the first value of a new one - and moves the entity to another scope at the same time, in either order of the
 * two within the upsert. The indexes of the source scope must lose every locale the entity had there, those of the
 * target scope must receive exactly the locales the entity has after the upsert, and the localized sortable attribute
 * compounds of its references must follow the locales into the target scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Changing the locales of an entity and its scope in one mutation")
@Tag(CONTRACT)
@Tag(INDEXING)
class LocaleRemovalWithArchivingFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_MEDIA = "media";
	private static final String REFERENCE_GALLERY = "gallery";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_CAPTION = "caption";
	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String COMPOUND_CAPTION_AND_WEIGHT = "captionAndWeight";
	private static final String SHARED_CODE = "SHARED";
	private static final String STATE_WARMING_UP = "WARMING_UP";
	private static final String STATE_ALIVE = "ALIVE";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final int PRODUCT_PK = 2;
	private static final int ARCHIVED_PRODUCT_PK = 3;
	private static final int MEDIA_PK = 1;

	/**
	 * The shape of the product schema and the way the product is created.
	 */
	enum Fixture {
		/** a localized entity attribute indexed in the live scope only, no reference */
		NO_REFERENCE(false, false, false, true),
		/** a localized entity attribute filterable in both scopes, no reference */
		NO_REFERENCE_ARCHIVED_INDEXED(false, true, false, true),
		/** a reference with a localized attribute and a compound, indexed in the live scope only, created later */
		REFERENCE_LIVE_ONLY(true, false, false, false),
		/** as above, but the product is created together with its reference in one upsert */
		REFERENCE_LIVE_ONLY_CREATED_AT_ONCE(true, false, false, true),
		/** a reference with a localized attribute and a compound, indexed in both scopes, created later */
		REFERENCE_ARCHIVED_INDEXED(true, false, true, false),
		/** as above, but the product is created together with its reference in one upsert */
		REFERENCE_ARCHIVED_INDEXED_CREATED_AT_ONCE(true, false, true, true);

		/**
		 * TRUE when the product has a reference with a localized attribute.
		 */
		private final boolean withReference;
		/**
		 * TRUE when the localized entity attribute is filterable in the archived scope too.
		 */
		private final boolean nameArchivedIndexed;
		/**
		 * TRUE when the reference, its attributes and its compound are indexed in the archived scope too.
		 */
		private final boolean referenceArchivedIndexed;
		/**
		 * TRUE when the product and its reference are created in one upsert.
		 */
		private final boolean createdAtOnce;

		Fixture(
			boolean withReference,
			boolean nameArchivedIndexed,
			boolean referenceArchivedIndexed,
			boolean createdAtOnce
		) {
			this.withReference = withReference;
			this.nameArchivedIndexed = nameArchivedIndexed;
			this.referenceArchivedIndexed = referenceArchivedIndexed;
			this.createdAtOnce = createdAtOnce;
		}
	}

	/**
	 * The way the locale change and the scope change reach the engine.
	 */
	enum Shape {
		/** one upsert: the locale removals first, then the scope change */
		ONE_UPSERT_REMOVE_THEN_ARCHIVE,
		/** one upsert: the scope change first, then the locale removals */
		ONE_UPSERT_ARCHIVE_THEN_REMOVE,
		/** one upsert built through the public entity builder */
		ONE_UPSERT_VIA_BUILDER,
		/** the removals in one upsert, the scope change in the next one */
		TWO_UPSERTS_REMOVE_FIRST,
		/** the scope change in one upsert, the removals in the next one */
		TWO_UPSERTS_ARCHIVE_FIRST
	}

	/**
	 * Every combination of the catalog state, the fixture and the shape.
	 *
	 * @return arguments of {@link LocaleRemoval#shouldArchiveEntityLosingItsLastGermanValues}
	 */
	@Nonnull
	static Stream<Arguments> removalVariants() {
		final List<Arguments> result = new ArrayList<>(2 * Fixture.values().length * Shape.values().length);
		for (String state : new String[]{STATE_WARMING_UP, STATE_ALIVE}) {
			for (Fixture fixture : Fixture.values()) {
				for (Shape shape : Shape.values()) {
					result.add(Arguments.of(state, fixture, shape));
				}
			}
		}
		return result.stream();
	}

	/**
	 * Every combination of the catalog state and the order of the scope change within the upsert.
	 *
	 * @return arguments of {@link LocaleAddition#shouldArchiveEntityGainingANewLocale}
	 */
	@Nonnull
	static Stream<Arguments> additionVariants() {
		final List<Arguments> result = new ArrayList<>(4);
		for (String state : new String[]{STATE_WARMING_UP, STATE_ALIVE}) {
			for (boolean archiveFirst : new boolean[]{false, true}) {
				result.add(Arguments.of(state, archiveFirst));
			}
		}
		return result.stream();
	}

	/**
	 * Removing the last values of a locale together with archiving the entity.
	 */
	@Nested
	@DisplayName("when the entity loses a locale")
	class LocaleRemoval {

		@ParameterizedTest(name = "{0} {1} {2}")
		@MethodSource("io.evitadb.api.functional.LocaleRemovalWithArchivingFunctionalTest#removalVariants")
		@DisplayName("the entity ends archived without the removed locale in either scope")
		void shouldArchiveEntityLosingItsLastGermanValues(
			@Nonnull String state,
			@Nonnull Fixture fixture,
			@Nonnull Shape shape
		) {
			final TestPaths paths = createTestPaths(
				"LocaleRemovalWithArchiving_" + state + "_" + fixture + "_" + shape
			);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpLocalizedProduct(session, fixture);
					if (STATE_ALIVE.equals(state)) {
						session.goLiveAndClose();
					}
				});

				final ReferenceKey referenceKey = fixture.withReference ?
					evita.queryCatalog(
						TEST_CATALOG,
						(Function<EvitaSessionContract, ReferenceKey>) session -> session
							.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
							.orElseThrow()
							.getReference(REFERENCE_GALLERY, MEDIA_PK)
							.orElseThrow()
							.getReferenceKey()
					) : null;

				for (Consumer<EvitaSessionContract> step : createSteps(shape, referenceKey)) {
					assertDoesNotThrow(() -> evita.updateCatalog(TEST_CATALOG, step));
				}

				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> {
						final SealedEntity product = session.getEntity(
							ENTITY_PRODUCT, PRODUCT_PK, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()
						).orElseThrow();
						assertEquals(Scope.ARCHIVED, product.getScope());
						assertEquals(Set.of(CZECH, Locale.ENGLISH), product.getLocales());
						// the live global index must not keep the locale the entity no longer has anywhere
						assertEquals(0, countWithLocale(session, Locale.GERMAN, Scope.LIVE));
						assertEquals(0, countWithLocale(session, Locale.GERMAN, Scope.ARCHIVED));
						assertEquals(0, countWithLocale(session, CZECH, Scope.LIVE));
						assertEquals(1, countWithLocale(session, CZECH, Scope.ARCHIVED));
					}
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Builds the session steps delivering the removal of the last German values and the scope change.
		 *
		 * @param shape        the way the changes reach the engine
		 * @param referenceKey the key of the product's reference, NULL when it has none
		 * @return the steps, each run in its own session
		 */
		@Nonnull
		private static List<Consumer<EvitaSessionContract>> createSteps(
			@Nonnull Shape shape,
			@Nullable ReferenceKey referenceKey
		) {
			final List<LocalMutation<?, ?>> removals = new ArrayList<>(2);
			removals.add(new RemoveAttributeMutation(ATTRIBUTE_NAME, Locale.GERMAN));
			if (referenceKey != null) {
				removals.add(
					new ReferenceAttributeMutation(
						referenceKey, new RemoveAttributeMutation(ATTRIBUTE_CAPTION, Locale.GERMAN)
					)
				);
			}
			final LocalMutation<?, ?> archive = new SetEntityScopeMutation(Scope.ARCHIVED);
			return switch (shape) {
				case ONE_UPSERT_REMOVE_THEN_ARCHIVE -> {
					final List<LocalMutation<?, ?>> all = new ArrayList<>(removals);
					all.add(archive);
					yield List.of(session -> session.upsertEntity(upsertOfProduct(all)));
				}
				case ONE_UPSERT_ARCHIVE_THEN_REMOVE -> {
					final List<LocalMutation<?, ?>> all = new ArrayList<>(removals.size() + 1);
					all.add(archive);
					all.addAll(removals);
					yield List.of(session -> session.upsertEntity(upsertOfProduct(all)));
				}
				case ONE_UPSERT_VIA_BUILDER -> List.of(
					session -> {
						final EntityBuilder builder = session
							.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeAttribute(ATTRIBUTE_NAME, Locale.GERMAN);
						if (referenceKey != null) {
							builder.updateReference(
								REFERENCE_GALLERY, MEDIA_PK,
								whichIs -> whichIs.removeAttribute(ATTRIBUTE_CAPTION, Locale.GERMAN)
							);
						}
						builder.setScope(Scope.ARCHIVED);
						builder.upsertVia(session);
					}
				);
				case TWO_UPSERTS_REMOVE_FIRST -> List.of(
					session -> session.upsertEntity(upsertOfProduct(removals)),
					session -> session.upsertEntity(upsertOfProduct(List.of(archive)))
				);
				case TWO_UPSERTS_ARCHIVE_FIRST -> List.of(
					session -> session.upsertEntity(upsertOfProduct(List.of(archive))),
					session -> session.upsertEntity(upsertOfProduct(removals))
				);
			};
		}

	}

	/**
	 * Adding the first value of a new locale together with archiving the entity.
	 */
	@Nested
	@DisplayName("when the entity gains a locale")
	class LocaleAddition {

		@ParameterizedTest(name = "{0} archiveFirst={1}")
		@MethodSource("io.evitadb.api.functional.LocaleRemovalWithArchivingFunctionalTest#additionVariants")
		@DisplayName("the entity ends archived with the gained locale in the archived scope only")
		void shouldArchiveEntityGainingANewLocale(@Nonnull String state, boolean archiveFirst) {
			final TestPaths paths = createTestPaths("LocaleAdditionWithArchiving_" + state + "_" + archiveFirst);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpLocalizedProduct(session, Fixture.NO_REFERENCE);
					if (STATE_ALIVE.equals(state)) {
						session.goLiveAndClose();
					}
				});
				final List<LocalMutation<?, ?>> all = new ArrayList<>(2);
				if (archiveFirst) {
					all.add(new SetEntityScopeMutation(Scope.ARCHIVED));
				}
				all.add(new UpsertAttributeMutation(ATTRIBUTE_NAME, Locale.FRENCH, "Produit fr"));
				if (!archiveFirst) {
					all.add(new SetEntityScopeMutation(Scope.ARCHIVED));
				}
				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) session -> session.upsertEntity(upsertOfProduct(all))
					)
				);
				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> {
						assertEquals(1, countWithLocale(session, Locale.FRENCH, Scope.ARCHIVED));
						assertEquals(0, countWithLocale(session, Locale.FRENCH, Scope.LIVE));
						assertEquals(1, countWithLocale(session, Locale.GERMAN, Scope.ARCHIVED));
						assertEquals(0, countWithLocale(session, Locale.GERMAN, Scope.LIVE));
					}
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

	}

	/**
	 * A unique attribute value removed and the entity archived in one upsert, while another archived entity holds the
	 * same value. Removals run before the scope change, so the archived scope never sees the removed value and the
	 * scope change succeeds - with or without the entity losing a locale on the way. A value kept by the archived
	 * entity is refused in the archived scope and the whole upsert is rolled back.
	 */
	@Nested
	@DisplayName("when the archived scope holds a unique value of the entity")
	class UniqueValueInTargetScope {

		@ParameterizedTest(name = "losing German={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("the entity removing the value is archived without a conflict")
		void shouldArchiveEntityRemovingItsUniqueValue(boolean alsoLosingGerman) {
			final TestPaths paths = createTestPaths("UniqueValueRemovedWithArchiving_" + alsoLosingGerman);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, (Consumer<EvitaSessionContract>) UniqueValueInTargetScope::setUpProductsSharingCode);
				final List<LocalMutation<?, ?>> all = new ArrayList<>(3);
				all.add(new RemoveAttributeMutation(ATTRIBUTE_CODE));
				if (alsoLosingGerman) {
					all.add(new RemoveAttributeMutation(ATTRIBUTE_NAME, Locale.GERMAN));
				}
				all.add(new SetEntityScopeMutation(Scope.ARCHIVED));

				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) session -> session.upsertEntity(upsertOfProduct(all))
					)
				);

				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> {
						final SealedEntity product = session.getEntity(
							ENTITY_PRODUCT, PRODUCT_PK, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()
						).orElseThrow();
						assertEquals(Scope.ARCHIVED, product.getScope());
						assertNull(product.getAttribute(ATTRIBUTE_CODE));
						assertEquals(
							alsoLosingGerman ? Set.of(CZECH) : Set.of(CZECH, Locale.GERMAN),
							product.getLocales()
						);
						assertEquals(List.of(ARCHIVED_PRODUCT_PK), primaryKeysWithSharedCode(session, Scope.ARCHIVED));
						assertEquals(List.of(), primaryKeysWithSharedCode(session, Scope.LIVE));
						assertEquals(alsoLosingGerman ? 0 : 1, countWithLocale(session, Locale.GERMAN, Scope.ARCHIVED));
						assertEquals(0, countWithLocale(session, Locale.GERMAN, Scope.LIVE));
					}
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		@ParameterizedTest(name = "losing German={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("the entity keeping the value is refused and stays live")
		void shouldRefuseArchivingEntityKeepingItsUniqueValue(boolean alsoLosingGerman) {
			final TestPaths paths = createTestPaths("UniqueValueKeptWithArchiving_" + alsoLosingGerman);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, (Consumer<EvitaSessionContract>) UniqueValueInTargetScope::setUpProductsSharingCode);
				final List<LocalMutation<?, ?>> all = new ArrayList<>(2);
				if (alsoLosingGerman) {
					all.add(new RemoveAttributeMutation(ATTRIBUTE_NAME, Locale.GERMAN));
				}
				all.add(new SetEntityScopeMutation(Scope.ARCHIVED));

				assertThrows(
					UniqueValueViolationException.class,
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) session -> session.upsertEntity(upsertOfProduct(all))
					)
				);

				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> {
						final SealedEntity product = session.getEntity(
							ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent()
						).orElseThrow();
						assertEquals(Scope.LIVE, product.getScope());
						assertEquals(Set.of(CZECH, Locale.GERMAN), product.getLocales());
						assertEquals(List.of(PRODUCT_PK), primaryKeysWithSharedCode(session, Scope.LIVE));
						assertEquals(List.of(ARCHIVED_PRODUCT_PK), primaryKeysWithSharedCode(session, Scope.ARCHIVED));
						assertEquals(1, countWithLocale(session, Locale.GERMAN, Scope.LIVE));
					}
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Defines a product schema with an attribute unique in both scopes and two products holding the same value
		 * of it: a live one with Czech and German names, and an archived one.
		 *
		 * @param session the session of the catalog in warm-up
		 */
		private static void setUpProductsSharingCode(@Nonnull EvitaSessionContract session) {
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withLocale(CZECH, Locale.GERMAN)
				.withAttribute(
					ATTRIBUTE_CODE, String.class,
					whichIs -> whichIs.nullable().uniqueInScope(Scope.LIVE, Scope.ARCHIVED)
				)
				.withAttribute(
					ATTRIBUTE_NAME, String.class,
					whichIs -> whichIs.localized().nullable().filterableInScope(Scope.LIVE, Scope.ARCHIVED)
				)
				.updateVia(session);
			session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
				.setAttribute(ATTRIBUTE_CODE, SHARED_CODE)
				.setAttribute(ATTRIBUTE_NAME, CZECH, "Produkt cs")
				.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt de")
				.upsertVia(session);
			session.createNewEntity(ENTITY_PRODUCT, ARCHIVED_PRODUCT_PK)
				.setAttribute(ATTRIBUTE_CODE, SHARED_CODE)
				.setAttribute(ATTRIBUTE_NAME, CZECH, "Archivovaný produkt cs")
				.setScope(Scope.ARCHIVED)
				.upsertVia(session);
		}

		/**
		 * Returns the primary keys of the products the unique index of `scope` answers for the shared code.
		 *
		 * @param session the session to query
		 * @param scope   the scope to query
		 * @return the primary keys found
		 */
		@Nonnull
		private static List<Integer> primaryKeysWithSharedCode(
			@Nonnull EvitaSessionContract session,
			@Nonnull Scope scope
		) {
			return session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_CODE, SHARED_CODE), scope(scope))
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.map(EntityReference::getPrimaryKeyOrThrowException)
				.toList();
		}

	}

	/**
	 * Builds one upsert of the probed product.
	 *
	 * @param mutations the local mutations of the upsert, in the order given
	 * @return the upsert of the existing product
	 */
	@Nonnull
	private static EntityUpsertMutation upsertOfProduct(@Nonnull List<LocalMutation<?, ?>> mutations) {
		return new EntityUpsertMutation(ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST, mutations);
	}

	/**
	 * Counts the products the indexes of `scope` answer for `locale`.
	 *
	 * @param session the session to query
	 * @param locale  the locale to filter by
	 * @param scope   the scope to query
	 * @return the number of products found
	 */
	private static int countWithLocale(
		@Nonnull EvitaSessionContract session,
		@Nonnull Locale locale,
		@Nonnull Scope scope
	) {
		return session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(entityLocaleEquals(locale), scope(scope))
			),
			EntityReference.class
		).getTotalRecordCount();
	}

	/**
	 * Defines the schema of the fixture and the product with Czech, German and English values.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param fixture the shape of the schema and the way the product is created
	 */
	private static void setUpLocalizedProduct(@Nonnull EvitaSessionContract session, @Nonnull Fixture fixture) {
		session.defineEntitySchema(ENTITY_MEDIA).withoutGeneratedPrimaryKey().updateVia(session);
		final Scope[] referenceScopes = fixture.referenceArchivedIndexed ?
			new Scope[]{Scope.LIVE, Scope.ARCHIVED} : new Scope[]{Scope.LIVE};
		final Scope[] nameScopes = fixture.nameArchivedIndexed ?
			new Scope[]{Scope.LIVE, Scope.ARCHIVED} : new Scope[]{Scope.LIVE};
		final EntitySchemaBuilder schema = session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(CZECH, Locale.GERMAN, Locale.ENGLISH, Locale.FRENCH)
			.withAttribute(
				ATTRIBUTE_NAME, String.class,
				whichIs -> whichIs.localized().nullable().filterableInScope(nameScopes)
			);
		if (fixture.withReference) {
			schema.withReferenceToEntity(
				REFERENCE_GALLERY, ENTITY_MEDIA, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(referenceScopes)
					.withAttribute(
						ATTRIBUTE_WEIGHT, Integer.class,
						thatIs -> thatIs.sortableInScope(referenceScopes).nullable()
					)
					.withAttribute(
						ATTRIBUTE_CAPTION, String.class,
						thatIs -> thatIs.localized().sortableInScope(referenceScopes).nullable()
					)
					.withSortableAttributeCompound(
						COMPOUND_CAPTION_AND_WEIGHT,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_CAPTION, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_WEIGHT, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
						},
						thatIs -> thatIs.indexedInScope(referenceScopes)
					)
			);
		}
		schema.updateVia(session);

		session.upsertEntity(session.createNewEntity(ENTITY_MEDIA, MEDIA_PK));
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setAttribute(ATTRIBUTE_NAME, CZECH, "Produkt cs")
			.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt de")
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product en");
		final Consumer<EntityBuilder> addReference = builder -> builder.setReference(
			REFERENCE_GALLERY, MEDIA_PK,
			whichIs -> whichIs
				.setAttribute(ATTRIBUTE_WEIGHT, 1)
				.setAttribute(ATTRIBUTE_CAPTION, CZECH, "Popisek")
				.setAttribute(ATTRIBUTE_CAPTION, Locale.GERMAN, "Bildunterschrift")
				.setAttribute(ATTRIBUTE_CAPTION, Locale.ENGLISH, "Caption")
		);
		if (fixture.withReference && fixture.createdAtOnce) {
			addReference.accept(product);
		}
		product.upsertVia(session);
		if (fixture.withReference && !fixture.createdAtOnce) {
			final EntityBuilder existing = session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			addReference.accept(existing);
			existing.upsertVia(session);
		}
	}

}
