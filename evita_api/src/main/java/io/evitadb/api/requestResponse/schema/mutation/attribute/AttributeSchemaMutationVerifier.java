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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * The checks a rename by {@link ModifyAttributeSchemaNameMutation} and a removal by
 * {@link RemoveAttributeSchemaMutation} pass before the attribute leaves its name, shared by the global, entity and
 * reference levels the mutations apply to.
 *
 * A rename takes the attribute out under its old name and puts it back under the new one. It must therefore not
 * replace another attribute that holds the new name. Neither a rename nor a removal may leave a sortable attribute
 * compound naming an attribute that no longer exists - the compound index of every later upsert would fail on the
 * missing attribute. The entity and reference schema builders refuse such a removal on their own; these checks cover
 * the mutations that reach the schema without them, including the removal of a global attribute, which the catalog
 * schema builder does not check against the compounds of the entity schemas using it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class AttributeSchemaMutationVerifier {

	private AttributeSchemaMutationVerifier() {
		// static utility
	}

	/**
	 * Refuses a rename of a global attribute to the name of another global attribute, and any rename one of the entity
	 * schemas using the attribute refuses. The entity schemas are verified here, before the catalog schema changes,
	 * because the rename reaches them one after another and a refusal by one of them would leave the ones before it
	 * renamed.
	 *
	 * @param catalogSchema the catalog schema the attribute is renamed in
	 * @param entitySchemas the entity schemas using the global attribute
	 * @param name          the current name of the attribute
	 * @param newName       the name the attribute is renamed to
	 * @throws InvalidSchemaMutationException when the rename is refused
	 */
	static void verifyRenameInCatalog(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull Collection<EntitySchemaContract> entitySchemas,
		@Nonnull String name,
		@Nonnull String newName
	) {
		if (catalogSchema.getAttribute(newName).isPresent()) {
			throw new InvalidSchemaMutationException(
				"The attribute `" + newName + "` already exists in catalog `" + catalogSchema.getName() +
					"` schema, the attribute `" + name + "` cannot be renamed to it!"
			);
		}
		for (final EntitySchemaContract entitySchema : entitySchemas) {
			verifyRenameInEntity(entitySchema, name, newName);
		}
	}

	/**
	 * Refuses a rename of an entity attribute - an attribute of its own or a global one it uses - to the name of
	 * another attribute of the entity, and a rename of an attribute a sortable attribute compound of the entity is
	 * made of.
	 *
	 * @param entitySchema the entity schema the attribute is renamed in
	 * @param name         the current name of the attribute
	 * @param newName      the name the attribute is renamed to
	 * @throws InvalidSchemaMutationException when the rename is refused
	 */
	static void verifyRenameInEntity(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String name,
		@Nonnull String newName
	) {
		if (entitySchema.getAttribute(newName).isPresent()) {
			throw new InvalidSchemaMutationException(
				"The attribute `" + newName + "` already exists in entity `" + entitySchema.getName() +
					"` schema, the attribute `" + name + "` cannot be renamed to it!"
			);
		}
		verifyNotInCompound(
			entitySchema.getSortableAttributeCompoundsForAttribute(name),
			"The attribute `" + name + "` of entity `" + entitySchema.getName() + "`"
		);
	}

	/**
	 * Refuses a rename of a reference attribute to the name of another attribute of the reference, a rename of an
	 * attribute a sortable attribute compound of the reference is made of, and a rename of an attribute a reflected
	 * reference inherits from its original - the inherited attribute would stay next to the renamed copy, and it can be
	 * renamed only in the original reference.
	 *
	 * @param entitySchema    the entity schema holding the reference
	 * @param referenceSchema the reference schema the attribute is renamed in
	 * @param name            the current name of the attribute
	 * @param newName         the name the attribute is renamed to
	 * @throws InvalidSchemaMutationException when the rename is refused
	 */
	static void verifyRenameInReference(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String name,
		@Nonnull String newName
	) {
		final String owner = "reference `" + referenceSchema.getName() + "` of entity `" + entitySchema.getName() + "`";
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema &&
			!reflectedReferenceSchema.getDeclaredAttributes().containsKey(name)) {
			throw new InvalidSchemaMutationException(
				"The attribute `" + name + "` of " + owner + " is inherited from reference `" +
					reflectedReferenceSchema.getReflectedReferenceName() + "` of entity `" +
					reflectedReferenceSchema.getReferencedEntityType() + "` and can be renamed only there!"
			);
		}
		if (referenceSchema.getAttribute(newName).isPresent()) {
			throw new InvalidSchemaMutationException(
				"The attribute `" + newName + "` already exists in " + owner + " schema, the attribute `" + name +
					"` cannot be renamed to it!"
			);
		}
		verifyNotInCompound(
			referenceSchema.getSortableAttributeCompoundsForAttribute(name),
			"The attribute `" + name + "` of " + owner
		);
	}

	/**
	 * Refuses a removal of an entity attribute - an attribute of its own or a global one it uses - that a sortable
	 * attribute compound of the entity is made of.
	 *
	 * @param entitySchema the entity schema the attribute is removed from
	 * @param name         the name of the attribute
	 * @throws SortableAttributeCompoundSchemaException when the removal is refused
	 */
	static void verifyRemovalInEntity(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String name
	) {
		verifyRemovalNotInCompound(
			entitySchema.getSortableAttributeCompoundsForAttribute(name),
			"The attribute `" + name + "` of entity `" + entitySchema.getName() + "`"
		);
	}

	/**
	 * Refuses a removal of a reference attribute that a sortable attribute compound of the reference is made of.
	 * An attribute a reflected reference inherits from its original is not removed by the mutation at all, so there
	 * is nothing to refuse.
	 *
	 * @param entitySchema    the entity schema holding the reference
	 * @param referenceSchema the reference schema the attribute is removed from
	 * @param name            the name of the attribute
	 * @throws SortableAttributeCompoundSchemaException when the removal is refused
	 */
	static void verifyRemovalInReference(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String name
	) {
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema &&
			!reflectedReferenceSchema.getDeclaredAttributes().containsKey(name)) {
			return;
		}
		verifyRemovalNotInCompound(
			referenceSchema.getSortableAttributeCompoundsForAttribute(name),
			"The attribute `" + name + "` of reference `" + referenceSchema.getName() + "` of entity `" +
				entitySchema.getName() + "`"
		);
	}

	/**
	 * Refuses the rename when any of the compounds is made of the attribute.
	 *
	 * @param compounds the sortable attribute compounds made of the attribute
	 * @param attribute the description of the attribute the message starts with
	 * @throws InvalidSchemaMutationException when there is such a compound
	 */
	private static void verifyNotInCompound(
		@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> compounds,
		@Nonnull String attribute
	) {
		if (!compounds.isEmpty()) {
			throw new InvalidSchemaMutationException(
				attribute + " cannot be renamed, it is part of the sortable attribute " + describe(compounds) +
					"! Remove the " + noun(compounds) + " before renaming the attribute."
			);
		}
	}

	/**
	 * Refuses the removal when any of the compounds is made of the attribute, with the exception the schema builders
	 * refuse the same removal with.
	 *
	 * @param compounds the sortable attribute compounds made of the attribute
	 * @param attribute the description of the attribute the message starts with
	 * @throws SortableAttributeCompoundSchemaException when there is such a compound
	 */
	private static void verifyRemovalNotInCompound(
		@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> compounds,
		@Nonnull String attribute
	) {
		if (!compounds.isEmpty()) {
			throw new SortableAttributeCompoundSchemaException(
				attribute + " cannot be removed, it is part of the sortable attribute " + describe(compounds) +
					"! Remove the " + noun(compounds) + " before removing the attribute.",
				compounds.iterator().next()
			);
		}
	}

	/**
	 * Names the compounds for a refusal message, for example "compounds `a`, `b`".
	 *
	 * @param compounds the compounds to name, never empty
	 * @return the noun followed by the quoted compound names
	 */
	@Nonnull
	private static String describe(@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> compounds) {
		final StringBuilder description = new StringBuilder(noun(compounds)).append(' ');
		boolean first = true;
		for (final SortableAttributeCompoundSchemaContract compound : compounds) {
			if (!first) {
				description.append(", ");
			}
			description.append('`').append(compound.getName()).append('`');
			first = false;
		}
		return description.toString();
	}

	/**
	 * Returns "compound" or "compounds" depending on the count.
	 *
	 * @param compounds the compounds to count
	 * @return the noun matching the count
	 */
	@Nonnull
	private static String noun(@Nonnull Collection<? extends SortableAttributeCompoundSchemaContract> compounds) {
		return compounds.size() == 1 ? "compound" : "compounds";
	}

}
