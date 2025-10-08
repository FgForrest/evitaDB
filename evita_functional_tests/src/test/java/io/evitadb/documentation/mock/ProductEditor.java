/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.documentation.mock;

import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Price;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * We have these classes because of `java.md` documentation file. Unfortunately, there is problem with Proxycian and
 * ByteBuddy in the JShell REPL classloader and we need to declare the main interfaces on standard classpath.
 */
public interface ProductEditor extends Product, InstanceEditor<Product> {

	ProductEditor setCode(String code);

	ProductEditor setName(String name, Locale locale);

	ProductEditor setEAN(String ean);

	@AttributeRef("manufacturedBefore")
	ProductEditor setYears(int... year);

	ProductEditor setReferencedFiles(Product.ReferencedFiles files);

	ProductEditor setParentEntity(Integer parentId);

	@Price
	ProductEditor setPrices(PriceContract... price);

	@ReferenceRef("marketingBrand")
	ProductEditor addOrUpdateMarketingBrand(int brandId, @CreateWhenMissing Consumer<BrandEditor> brandEditor);

	@ReferenceRef("licensingBrands")
	ProductEditor addOrUpdateLicensingBrand(int brandId, @CreateWhenMissing Consumer<BrandEditor> brandEditor);

	@ReferenceRef("licensingBrands")
	@RemoveWhenExists
	ProductEditor removeLicensingBrandById(int brandId);

	interface BrandEditor extends Product.Brand {

		BrandEditor setBrandGroup(Integer brandGroupId);

		BrandEditor setMarket(String market);

	}

}
