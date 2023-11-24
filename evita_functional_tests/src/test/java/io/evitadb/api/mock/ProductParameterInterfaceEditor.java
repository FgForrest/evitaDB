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

import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;

import java.util.function.Consumer;

/**
 * Example interface mapping a product parameter reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ProductParameterInterfaceEditor extends ProductParameterInterface, InstanceEditor<ProductParameterInterface> {

	ProductParameterInterfaceEditor setPriority(Long priority);

	@ReferencedEntity
	@CreateWhenMissing
	ParameterInterfaceEditor getOrCreateParameter();

	@ReferencedEntityGroup
	@RemoveWhenExists
	ProductParameterInterfaceEditor removeParameterGroup();

	void setParameterGroup(Integer parameterGroup);

	void setParameterGroupEntityClassifier(EntityReferenceContract entityClassifier);

	void setParameterGroupEntity(ParameterGroupInterfaceEditor groupEntity);

	@ReferencedEntityGroup
	@CreateWhenMissing
	ProductParameterInterfaceEditor getOrCreateParameterGroupEntity(Consumer<ParameterGroupInterfaceEditor> groupEntity);

}
