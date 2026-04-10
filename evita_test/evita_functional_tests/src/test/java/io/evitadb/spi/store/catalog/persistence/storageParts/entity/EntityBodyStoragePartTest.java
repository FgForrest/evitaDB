/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.spi.store.catalog.persistence.storageParts.entity;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart.LocaleModificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityBodyStoragePart} verifying scope management, parent management, versioning,
 * locale management for attributes and associated data, and mark-for-removal behavior.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityBodyStoragePart behavioral tests")
class EntityBodyStoragePartTest {

	private static final int PRIMARY_KEY = 1;
	private static final Locale LOCALE_EN = Locale.ENGLISH;
	private static final Locale LOCALE_DE = Locale.GERMAN;
	private static final Locale LOCALE_FR = Locale.FRENCH;

	/**
	 * Creates a pre-populated {@link EntityBodyStoragePart} with version 1, LIVE scope, no parent,
	 * and the specified attribute locales and associated data keys.
	 *
	 * @param attributeLocales the attribute locales to seed
	 * @param associatedDataKeys the associated data keys to seed
	 * @return new storage part instance
	 */
	@Nonnull
	private static EntityBodyStoragePart createPopulatedPart(
		@Nonnull Set<Locale> attributeLocales,
		@Nonnull Set<AssociatedDataKey> associatedDataKeys
	) {
		// compute locales from both attribute locales and associated data key locales
		final LinkedHashSet<Locale> locales = new LinkedHashSet<>(attributeLocales);
		for (AssociatedDataKey key : associatedDataKeys) {
			if (key.locale() != null) {
				locales.add(key.locale());
			}
		}
		return new EntityBodyStoragePart(
			1, PRIMARY_KEY, Scope.LIVE, null,
			locales,
			new LinkedHashSet<>(attributeLocales),
			new LinkedHashSet<>(associatedDataKeys),
			128
		);
	}

	@Nested
	@DisplayName("Scope management")
	class ScopeManagement {

		@Test
		@DisplayName("should mark dirty when changing scope")
		void shouldMarkDirtyWhenChangingScope() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);
			assertFalse(part.isDirty());

			part.setScope(Scope.ARCHIVED);

			assertTrue(part.isDirty());
			assertEquals(Scope.ARCHIVED, part.getScope());
		}

		@Test
		@DisplayName("should not mark dirty when setting the same scope")
		void shouldNotMarkDirtyWhenSettingSameScope() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			part.setScope(Scope.LIVE);

			assertFalse(part.isDirty());
		}
	}

	@Nested
	@DisplayName("Parent management")
	class ParentManagement {

		@Test
		@DisplayName("should mark dirty when setting parent from null")
		void shouldMarkDirtyWhenSettingParentFromNull() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);
			assertNull(part.getParent());
			assertFalse(part.isDirty());

			part.setParent(10);

			assertTrue(part.isDirty());
			assertEquals(10, part.getParent());
		}

		@Test
		@DisplayName("should mark dirty when changing parent to another value")
		void shouldMarkDirtyWhenChangingParentToAnotherValue() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, 10,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			part.setParent(20);

			assertTrue(part.isDirty());
			assertEquals(20, part.getParent());
		}

		@Test
		@DisplayName("should mark dirty when setting parent to null")
		void shouldMarkDirtyWhenSettingParentToNull() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, 10,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			part.setParent(null);

			assertTrue(part.isDirty());
			assertNull(part.getParent());
		}

		@Test
		@DisplayName("should not mark dirty when setting same parent")
		void shouldNotMarkDirtyWhenSettingSameParent() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, 10,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			part.setParent(10);

			assertFalse(part.isDirty());
		}

		@Test
		@DisplayName("should not mark dirty when setting null parent on null parent")
		void shouldNotMarkDirtyWhenSettingNullParentOnNullParent() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			part.setParent(null);

			assertFalse(part.isDirty());
		}
	}

	@Nested
	@DisplayName("Version tracking")
	class Versioning {

		@Test
		@DisplayName("should return incremented version when dirty")
		void shouldReturnIncrementedVersionWhenDirty() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				5, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			part.setScope(Scope.ARCHIVED);
			assertTrue(part.isDirty());

			assertEquals(6, part.getVersion());
		}

		@Test
		@DisplayName("should return original version when clean")
		void shouldReturnOriginalVersionWhenClean() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				5, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			assertFalse(part.isDirty());
			assertEquals(5, part.getVersion());
		}
	}

	@Nested
	@DisplayName("Attribute locale management")
	class AttributeLocaleManagement {

		@Test
		@DisplayName("should add attribute locale and recompute entity locales")
		void shouldAddAttributeLocaleAndRecomputeEntityLocales() {
			final EntityBodyStoragePart part = createPopulatedPart(
				new LinkedHashSet<>(), new LinkedHashSet<>()
			);

			final LocaleModificationResult result = part.addAttributeLocale(LOCALE_EN);

			assertTrue(result.attributeLocalesChanged());
			assertTrue(result.entityLocalesChanged());
			assertTrue(part.getAttributeLocales().contains(LOCALE_EN));
			assertTrue(part.getLocales().contains(LOCALE_EN));
		}

		@Test
		@DisplayName("should return no changes when adding duplicate attribute locale")
		void shouldReturnNoChangesWhenAddingDuplicateAttributeLocale() {
			final LinkedHashSet<Locale> attributeLocales = new LinkedHashSet<>();
			attributeLocales.add(LOCALE_EN);
			final EntityBodyStoragePart part = createPopulatedPart(
				attributeLocales, new LinkedHashSet<>()
			);

			final LocaleModificationResult result = part.addAttributeLocale(LOCALE_EN);

			assertFalse(result.attributeLocalesChanged());
			assertFalse(result.entityLocalesChanged());
		}

		@Test
		@DisplayName("should remove attribute locale and recompute entity locales")
		void shouldRemoveAttributeLocaleAndRecomputeEntityLocales() {
			final LinkedHashSet<Locale> attributeLocales = new LinkedHashSet<>();
			attributeLocales.add(LOCALE_EN);
			final EntityBodyStoragePart part = createPopulatedPart(
				attributeLocales, new LinkedHashSet<>()
			);

			final LocaleModificationResult result = part.removeAttributeLocale(LOCALE_EN);

			assertTrue(result.attributeLocalesChanged());
			assertTrue(result.entityLocalesChanged());
			assertFalse(part.getAttributeLocales().contains(LOCALE_EN));
			assertFalse(part.getLocales().contains(LOCALE_EN));
		}

		@Test
		@DisplayName("should return no changes when removing absent attribute locale")
		void shouldReturnNoChangesWhenRemovingAbsentAttributeLocale() {
			final EntityBodyStoragePart part = createPopulatedPart(
				new LinkedHashSet<>(), new LinkedHashSet<>()
			);

			final LocaleModificationResult result = part.removeAttributeLocale(LOCALE_EN);

			assertFalse(result.attributeLocalesChanged());
			assertFalse(result.entityLocalesChanged());
		}
	}

	@Nested
	@DisplayName("Associated data key management")
	class AssociatedDataKeyManagement {

		@Test
		@DisplayName("should add localized associated data key and update entity locales")
		void shouldAddLocalizedAssociatedDataKeyAndUpdateEntityLocales() {
			final EntityBodyStoragePart part = createPopulatedPart(
				new LinkedHashSet<>(), new LinkedHashSet<>()
			);

			final boolean localesChanged = part.addAssociatedDataKey(
				new AssociatedDataKey("description", LOCALE_DE)
			);

			assertTrue(localesChanged);
			assertTrue(part.getLocales().contains(LOCALE_DE));
			assertTrue(part.getAssociatedDataKeys().contains(new AssociatedDataKey("description", LOCALE_DE)));
		}

		@Test
		@DisplayName("should add non-localized associated data key without affecting locales")
		void shouldAddNonLocalizedAssociatedDataKeyWithoutAffectingLocales() {
			final EntityBodyStoragePart part = createPopulatedPart(
				new LinkedHashSet<>(), new LinkedHashSet<>()
			);

			final boolean localesChanged = part.addAssociatedDataKey(
				new AssociatedDataKey("metadata")
			);

			assertFalse(localesChanged);
			assertTrue(part.getAssociatedDataKeys().contains(new AssociatedDataKey("metadata")));
		}

		@Test
		@DisplayName("should not add duplicate associated data key")
		void shouldNotAddDuplicateAssociatedDataKey() {
			final LinkedHashSet<AssociatedDataKey> existingKeys = new LinkedHashSet<>();
			existingKeys.add(new AssociatedDataKey("description", LOCALE_EN));
			final EntityBodyStoragePart part = createPopulatedPart(
				new LinkedHashSet<>(), existingKeys
			);
			// reset dirty to detect no change
			part.setDirty(false);

			final boolean localesChanged = part.addAssociatedDataKey(
				new AssociatedDataKey("description", LOCALE_EN)
			);

			assertFalse(localesChanged);
			assertFalse(part.isDirty());
		}

		@Test
		@DisplayName("should remove localized associated data key and update entity locales")
		void shouldRemoveLocalizedAssociatedDataKeyAndUpdateEntityLocales() {
			final LinkedHashSet<AssociatedDataKey> existingKeys = new LinkedHashSet<>();
			existingKeys.add(new AssociatedDataKey("description", LOCALE_FR));
			final EntityBodyStoragePart part = createPopulatedPart(
				new LinkedHashSet<>(), existingKeys
			);

			final boolean localesChanged = part.removeAssociatedDataKey(
				new AssociatedDataKey("description", LOCALE_FR)
			);

			assertTrue(localesChanged);
			assertFalse(part.getLocales().contains(LOCALE_FR));
		}

		@Test
		@DisplayName("should not remove absent associated data key")
		void shouldNotRemoveAbsentAssociatedDataKey() {
			final EntityBodyStoragePart part = createPopulatedPart(
				new LinkedHashSet<>(), new LinkedHashSet<>()
			);
			part.setDirty(false);

			final boolean localesChanged = part.removeAssociatedDataKey(
				new AssociatedDataKey("nonexistent", LOCALE_EN)
			);

			assertFalse(localesChanged);
			assertFalse(part.isDirty());
		}
	}

	@Nested
	@DisplayName("Locale recomputation")
	class LocaleRecomputation {

		@Test
		@DisplayName("should merge locales from attributes and associated data")
		void shouldMergeLocalesFromAttributesAndAssociatedData() {
			final LinkedHashSet<Locale> attributeLocales = new LinkedHashSet<>();
			attributeLocales.add(LOCALE_EN);
			final LinkedHashSet<AssociatedDataKey> assocKeys = new LinkedHashSet<>();
			assocKeys.add(new AssociatedDataKey("desc", LOCALE_DE));
			final EntityBodyStoragePart part = createPopulatedPart(attributeLocales, assocKeys);

			assertTrue(part.getLocales().contains(LOCALE_EN));
			assertTrue(part.getLocales().contains(LOCALE_DE));
		}

		@Test
		@DisplayName("should retain locale when removed from one source but present in another")
		void shouldRetainLocaleWhenRemovedFromOneSourceButPresentInAnother() {
			final LinkedHashSet<Locale> attributeLocales = new LinkedHashSet<>();
			attributeLocales.add(LOCALE_EN);
			final LinkedHashSet<AssociatedDataKey> assocKeys = new LinkedHashSet<>();
			assocKeys.add(new AssociatedDataKey("desc", LOCALE_EN));
			final EntityBodyStoragePart part = createPopulatedPart(attributeLocales, assocKeys);

			// removing attribute locale for EN should NOT remove EN from entity locales
			// because it is still in associated data
			final LocaleModificationResult result = part.removeAttributeLocale(LOCALE_EN);

			assertTrue(result.attributeLocalesChanged());
			assertFalse(result.entityLocalesChanged());
			assertTrue(part.getLocales().contains(LOCALE_EN));
		}
	}

	@Nested
	@DisplayName("Mark for removal")
	class MarkForRemoval {

		@Test
		@DisplayName("should mark part as empty when marked for removal")
		void shouldMarkPartAsEmptyWhenMarkedForRemoval() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			part.markForRemoval();

			assertTrue(part.isEmpty());
			assertTrue(part.isMarkedForRemoval());
		}

		@Test
		@DisplayName("should not be empty when not marked for removal")
		void shouldNotBeEmptyWhenNotMarkedForRemoval() {
			final EntityBodyStoragePart part = new EntityBodyStoragePart(
				1, PRIMARY_KEY, Scope.LIVE, null,
				new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), 128
			);

			assertFalse(part.isEmpty());
			assertFalse(part.isMarkedForRemoval());
		}
	}

	@Nested
	@DisplayName("LocaleModificationResult behavior")
	class LocaleModificationResultTest {

		@Test
		@DisplayName("should report no change when both flags are false")
		void shouldReportNoChangeWhenBothFlagsAreFalse() {
			final LocaleModificationResult result = LocaleModificationResult.NO_CHANGES;

			assertFalse(result.anyChangeOccurred());
			assertFalse(result.attributeLocalesChanged());
			assertFalse(result.entityLocalesChanged());
		}

		@Test
		@DisplayName("should report change when attribute locales changed")
		void shouldReportChangeWhenAttributeLocalesChanged() {
			final LocaleModificationResult result = new LocaleModificationResult(true, false);

			assertTrue(result.anyChangeOccurred());
			assertTrue(result.attributeLocalesChanged());
			assertFalse(result.entityLocalesChanged());
		}

		@Test
		@DisplayName("should report change when entity locales changed")
		void shouldReportChangeWhenEntityLocalesChanged() {
			final LocaleModificationResult result = new LocaleModificationResult(false, true);

			assertTrue(result.anyChangeOccurred());
			assertFalse(result.attributeLocalesChanged());
			assertTrue(result.entityLocalesChanged());
		}
	}

}
