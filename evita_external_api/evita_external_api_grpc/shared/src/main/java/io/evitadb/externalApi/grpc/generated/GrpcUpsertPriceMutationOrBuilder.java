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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcPriceMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcUpsertPriceMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcUpsertPriceMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Contains identification of the price in the external systems. This id is expected to be used for the synchronization
   * of the price in relation with the primary source of the prices.
   * This id is used to uniquely find a price within same price list and currency and is mandatory.
   * </pre>
   *
   * <code>int32 priceId = 1;</code>
   * @return The priceId.
   */
  int getPriceId();

  /**
   * <pre>
   * Contains identification of the price list in the external system. Each price must reference a price list. Price list
   * identification may refer to another Evita entity or may contain any external price list identification
   * (for example id or unique name of the price list in the external system).
   * Single entity is expected to have single price for the price list unless there is `validity` specified.
   * In other words there is no sense to have multiple concurrently valid prices for the same entity that have roots
   * in the same price list.
   * </pre>
   *
   * <code>string priceList = 2;</code>
   * @return The priceList.
   */
  java.lang.String getPriceList();
  /**
   * <pre>
   * Contains identification of the price list in the external system. Each price must reference a price list. Price list
   * identification may refer to another Evita entity or may contain any external price list identification
   * (for example id or unique name of the price list in the external system).
   * Single entity is expected to have single price for the price list unless there is `validity` specified.
   * In other words there is no sense to have multiple concurrently valid prices for the same entity that have roots
   * in the same price list.
   * </pre>
   *
   * <code>string priceList = 2;</code>
   * @return The bytes for priceList.
   */
  com.google.protobuf.ByteString
      getPriceListBytes();

  /**
   * <pre>
   * Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrency currency = 3;</code>
   * @return Whether the currency field is set.
   */
  boolean hasCurrency();
  /**
   * <pre>
   * Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrency currency = 3;</code>
   * @return The currency.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCurrency getCurrency();
  /**
   * <pre>
   * Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCurrency currency = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcCurrencyOrBuilder getCurrencyOrBuilder();

  /**
   * <pre>
   * Some special products (such as master products, or product sets) may contain prices of all "subordinate" products
   * so that the aggregating product can represent them in certain views on the product. In that case there is need
   * to distinguish the projected prices of the subordinate product in the one that represents them.
   * Inner record id must contain positive value.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value innerRecordId = 4;</code>
   * @return Whether the innerRecordId field is set.
   */
  boolean hasInnerRecordId();
  /**
   * <pre>
   * Some special products (such as master products, or product sets) may contain prices of all "subordinate" products
   * so that the aggregating product can represent them in certain views on the product. In that case there is need
   * to distinguish the projected prices of the subordinate product in the one that represents them.
   * Inner record id must contain positive value.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value innerRecordId = 4;</code>
   * @return The innerRecordId.
   */
  com.google.protobuf.Int32Value getInnerRecordId();
  /**
   * <pre>
   * Some special products (such as master products, or product sets) may contain prices of all "subordinate" products
   * so that the aggregating product can represent them in certain views on the product. In that case there is need
   * to distinguish the projected prices of the subordinate product in the one that represents them.
   * Inner record id must contain positive value.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value innerRecordId = 4;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getInnerRecordIdOrBuilder();

  /**
   * <pre>
   * Price without tax.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal priceWithoutTax = 5;</code>
   * @return Whether the priceWithoutTax field is set.
   */
  boolean hasPriceWithoutTax();
  /**
   * <pre>
   * Price without tax.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal priceWithoutTax = 5;</code>
   * @return The priceWithoutTax.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getPriceWithoutTax();
  /**
   * <pre>
   * Price without tax.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal priceWithoutTax = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalOrBuilder getPriceWithoutTaxOrBuilder();

  /**
   * <pre>
   * Tax rate percentage (i.e. for 19% it'll be 19.00)
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal taxRate = 6;</code>
   * @return Whether the taxRate field is set.
   */
  boolean hasTaxRate();
  /**
   * <pre>
   * Tax rate percentage (i.e. for 19% it'll be 19.00)
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal taxRate = 6;</code>
   * @return The taxRate.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getTaxRate();
  /**
   * <pre>
   * Tax rate percentage (i.e. for 19% it'll be 19.00)
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal taxRate = 6;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalOrBuilder getTaxRateOrBuilder();

  /**
   * <pre>
   * Price with tax.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal priceWithTax = 7;</code>
   * @return Whether the priceWithTax field is set.
   */
  boolean hasPriceWithTax();
  /**
   * <pre>
   * Price with tax.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal priceWithTax = 7;</code>
   * @return The priceWithTax.
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimal getPriceWithTax();
  /**
   * <pre>
   * Price with tax.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcBigDecimal priceWithTax = 7;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcBigDecimalOrBuilder getPriceWithTaxOrBuilder();

  /**
   * <pre>
   * Date and time interval for which the price is valid (inclusive).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange validity = 8;</code>
   * @return Whether the validity field is set.
   */
  boolean hasValidity();
  /**
   * <pre>
   * Date and time interval for which the price is valid (inclusive).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange validity = 8;</code>
   * @return The validity.
   */
  io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange getValidity();
  /**
   * <pre>
   * Date and time interval for which the price is valid (inclusive).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcDateTimeRange validity = 8;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcDateTimeRangeOrBuilder getValidityOrBuilder();

  /**
   * <pre>
   * Controls whether price is subject to filtering / sorting logic, non-sellable prices will be fetched along with
   * entity but won't be considered when evaluating search query. These prices may be
   * used for "informational" prices such as reference price (the crossed out price often found on e-commerce sites
   * as "usual price") but are not considered as the "selling" price.
   * </pre>
   *
   * <code>bool sellable = 9 [deprecated = true];</code>
   * @deprecated
   * @return The sellable.
   */
  @java.lang.Deprecated boolean getSellable();

  /**
   * <pre>
   * Controls whether price is subject to filtering / sorting logic, non-indexed prices will be fetched along with
   * entity but won't be considered when evaluating search query. These prices may be
   * used for "informational" prices such as reference price (the crossed out price often found on e-commerce sites
   * as "usual price") but are not considered as the "selling" price.
   * </pre>
   *
   * <code>bool indexed = 10;</code>
   * @return The indexed.
   */
  boolean getIndexed();
}
