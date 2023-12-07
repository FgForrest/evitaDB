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

public class ProductStockAvailability {
    public int Id { get; set; }
    public string StockName { get; set; }

    // this field will not be serialized, because it is a private field and not a public property
    // with public getter and public setter
    private Uri stockUrl;

    // this property will not be serialized, because does not have a public getter
    public Uri StockMotive { private get; set;}

    // this property will not be serialized, it is decorated with `NonSerializableData` attribute
    [NonSerializableData]
    public Uri StockBaseUrl { get; set; }
}