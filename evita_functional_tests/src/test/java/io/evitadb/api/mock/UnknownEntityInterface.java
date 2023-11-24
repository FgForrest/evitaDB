/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.mock;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.test.generator.DataGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Example interface mapping a category entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Entity(name = "newlyDefinedEntity")
public interface UnknownEntityInterface extends EntityClassifier {

	@PrimaryKey()
	int getId();

	@ParentEntity
	@Nullable
	Integer getParentId();

	@ParentEntity
	@Nullable
	UnknownEntityInterface getParentEntity();

	@Attribute(name = DataGenerator.ATTRIBUTE_CODE)
	@Nonnull
	String getCode();

	@Attribute(name = DataGenerator.ATTRIBUTE_NAME, localized = true)
	@Nonnull
	String getName();

	@AttributeRef(DataGenerator.ATTRIBUTE_NAME)
	@Nonnull
	String getName(@Nonnull Locale locale);

	@Nonnull
	@Attribute(name = DataGenerator.ATTRIBUTE_PRIORITY)
	Long getPriority();

}
