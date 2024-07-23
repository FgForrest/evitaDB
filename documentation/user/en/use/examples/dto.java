/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

public class ProductStockAvailability implements Serializable {
    private int id;
    private String stockName;
    @NonSerializedData
    private URL stockUrl;
    private URL stockMotive;

    // id gets serialized - both methods are present and
    // are valid JavaBean property methods
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    // stockName gets serialized - both methods are present
    // and are valid JavaBean property methods
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    // stockUrl will not be serialized
    // corresponding field is annotated with @NonSerializedData
    public URL getStockUrl() { return stockUrl; }
    public void setStockUrl(URL stockUrl) { this.stockUrl = stockUrl; }

    // active will not be serialized - it has no corresponding mutator method
    public boolean isActive() { return false; }

    // stock motive will not be serialized
    // because getter method is marked with @NonSerializedData
    @NonSerializedData
    public URL getStockMotive() { return stockMotive; }
    public void setStockMotive(URL stockMotive) { this.stockMotive = stockMotive; }
}
