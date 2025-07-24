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

package io.evitadb.api.proxy.mock;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Example interface mapping a category entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.CATEGORY)
public interface CategoryInterface extends EntityClassifier {

	@PrimaryKeyRef
	int getId();

	@Nonnull
	TestEntity getEntityType();

	@ParentEntity
	@Nullable
	Integer getParentId();

	@ParentEntity
	@Nonnull
	OptionalInt getParentIdIfPresent();

	@ParentEntity
	@Nullable
	CategoryInterface getParentEntity();

	@ParentEntity
	@Nonnull
	Optional<CategoryInterface> getParentEntityIfPresent();

	@ParentEntity
	@Nullable
	EntityReference getParentEntityReference();

	@ParentEntity
	@Nullable
	EntityClassifier getParentEntityClassifier();

	@ParentEntity
	@Nullable
	EntityClassifierWithParent getParentEntityClassifierWithParent();

	@ParentEntity
	@Nullable
	Optional<EntityReference> getParentEntityReferenceIfPresent();

	@Attribute(name = DataGenerator.ATTRIBUTE_CODE)
	@Nonnull
	String getCode();

	@Attribute(name = DataGenerator.ATTRIBUTE_NAME)
	@Nonnull
	String getName();

	@Nonnull
	String getName(@Nonnull Locale locale);

	@Nonnull
	@AttributeRef(DataGenerator.ATTRIBUTE_PRIORITY)
	Long getPriority();

	@Nullable
	@AttributeRef(DataGenerator.ATTRIBUTE_VALIDITY)
	DateTimeRange getValidity();

}
