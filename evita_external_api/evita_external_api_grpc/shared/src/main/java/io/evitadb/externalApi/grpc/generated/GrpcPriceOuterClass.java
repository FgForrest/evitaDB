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
// source: GrpcPrice.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcPriceOuterClass {
  private GrpcPriceOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcPrice_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcPrice_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\017GrpcPrice.proto\022%io.evitadb.externalAp" +
      "i.grpc.generated\032\036google/protobuf/wrappe" +
      "rs.proto\032\030GrpcEvitaDataTypes.proto\"\223\004\n\tG" +
      "rpcPrice\022\017\n\007priceId\030\001 \001(\005\022\021\n\tpriceList\030\002" +
      " \001(\t\022E\n\010currency\030\003 \001(\01323.io.evitadb.exte" +
      "rnalApi.grpc.generated.GrpcCurrency\0222\n\ri" +
      "nnerRecordId\030\004 \001(\0132\033.google.protobuf.Int" +
      "32Value\022N\n\017priceWithoutTax\030\005 \001(\01325.io.ev" +
      "itadb.externalApi.grpc.generated.GrpcBig" +
      "Decimal\022F\n\007taxRate\030\006 \001(\01325.io.evitadb.ex" +
      "ternalApi.grpc.generated.GrpcBigDecimal\022" +
      "K\n\014priceWithTax\030\007 \001(\01325.io.evitadb.exter" +
      "nalApi.grpc.generated.GrpcBigDecimal\022J\n\010" +
      "validity\030\010 \001(\01328.io.evitadb.externalApi." +
      "grpc.generated.GrpcDateTimeRange\022\024\n\010sell" +
      "able\030\t \001(\010B\002\030\001\022\017\n\007version\030\n \001(\005\022\017\n\007index" +
      "ed\030\013 \001(\010B\014P\001\252\002\007EvitaDBb\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.google.protobuf.WrappersProto.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcPrice_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcPrice_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcPrice_descriptor,
        new java.lang.String[] { "PriceId", "PriceList", "Currency", "InnerRecordId", "PriceWithoutTax", "TaxRate", "PriceWithTax", "Validity", "Sellable", "Version", "Indexed", });
    com.google.protobuf.WrappersProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
