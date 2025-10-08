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

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Example interface mapping a product category reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ProductCategoryInterfaceEditor extends ProductCategoryInterface, InstanceEditor<ProductCategoryInterface> {

	ProductCategoryInterfaceEditor setOrderInCategory(@Nonnull Long orderInCategory);

	ProductCategoryInterfaceEditor setLabel(@Nonnull String label);
	ProductCategoryInterfaceEditor setShadow(boolean shadow);

	ProductCategoryInterfaceEditor setLabel(Locale locale, @Nonnull String label);

}

