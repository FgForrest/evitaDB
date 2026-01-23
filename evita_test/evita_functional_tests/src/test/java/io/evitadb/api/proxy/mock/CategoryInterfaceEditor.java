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
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.annotation.AssociatedDataRef;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Example interface mapping a category entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface CategoryInterfaceEditor extends CategoryInterface, InstanceEditor<CategoryInterface> {

	@ParentEntity
	CategoryInterfaceEditor setParentId(@Nullable Integer parentId);

	@ParentEntity
	CategoryInterfaceEditor setParentEntity(@Nullable CategoryInterface parentEntity);

	@ParentEntity
	CategoryInterfaceEditor setParentEntityReference(@Nullable EntityReference parentEntityReference);

	@ParentEntity
	CategoryInterfaceEditor setParentEntityClassifier(@Nullable EntityClassifier parentEntityClassifier);

	@ParentEntity
	CategoryInterfaceEditor setParentEntityClassifierWithParent(@Nullable EntityClassifierWithParent parentEntityClassifierWithParent);

	@ParentEntity
	CategoryInterfaceEditor withParent(int parentPrimaryKey, @Nonnull Consumer<CategoryInterfaceEditor> setupLogic);

	@ParentEntity
	@RemoveWhenExists
	void removeParent();

	@ParentEntity
	@RemoveWhenExists
	boolean removeParentAndReturnResult();

	@ParentEntity
	@RemoveWhenExists
	Integer removeParentAndReturnItsPrimaryKey();

	@ParentEntity
	@RemoveWhenExists
	CategoryInterface removeParentAndReturnIt();

	CategoryInterfaceEditor setCode(@Nonnull String code);

	CategoryInterfaceEditor setName(@Nonnull String name);

	CategoryInterfaceEditor setName(@Nonnull Locale locale, @Nonnull String name);

	CategoryInterfaceEditor setPriority(@Nonnull Long priority);

	CategoryInterfaceEditor setValidity(@Nullable DateTimeRange validity);

	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_LABELS)
	void setLabels(Labels labels);

	@AssociatedDataRef(DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES)
	void setReferencedFiles(ReferencedFileSet referencedFiles);

}
