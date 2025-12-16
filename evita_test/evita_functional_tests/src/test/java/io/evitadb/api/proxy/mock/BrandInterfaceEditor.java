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

import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.test.Entities;

import java.util.function.Consumer;

/**
 * Example interface mapping a brand entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.BRAND)
public interface BrandInterfaceEditor extends BrandInterface, InstanceEditor<BrandInterface> {

	BrandInterfaceEditor setCode(String code);

	@ReferenceRef(Entities.STORE)
	BrandInterfaceEditor setStore(int storeId);

	@ReferenceRef(Entities.STORE)
	BrandInterfaceEditor setNewStore(@CreateWhenMissing Consumer<StoreInterfaceEditor> storeConsumer);

}
