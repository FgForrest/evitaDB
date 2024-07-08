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

package io.evitadb.externalApi.grpc.generated;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
 * a way to create sessions and catalogs, and to update the catalog.
 * </pre>
 */

@io.grpc.stub.annotations.GrpcGenerated
public final class EvitaServiceGrpc {

  private EvitaServiceGrpc() {}

  public static final String SERVICE_NAME = "io.evitadb.externalApi.grpc.generated.EvitaService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> getServerStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ServerStatus",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> getServerStatusMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> getServerStatusMethod;
    if ((getServerStatusMethod = EvitaServiceGrpc.getServerStatusMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getServerStatusMethod = EvitaServiceGrpc.getServerStatusMethod) == null) {
          EvitaServiceGrpc.getServerStatusMethod = getServerStatusMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ServerStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ServerStatus"))
              .build();
        }
      }
    }
    return getServerStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadOnlySessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateReadOnlySession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadOnlySessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadOnlySessionMethod;
    if ((getCreateReadOnlySessionMethod = EvitaServiceGrpc.getCreateReadOnlySessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateReadOnlySessionMethod = EvitaServiceGrpc.getCreateReadOnlySessionMethod) == null) {
          EvitaServiceGrpc.getCreateReadOnlySessionMethod = getCreateReadOnlySessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateReadOnlySession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateReadOnlySession"))
              .build();
        }
      }
    }
    return getCreateReadOnlySessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadWriteSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateReadWriteSession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadWriteSessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateReadWriteSessionMethod;
    if ((getCreateReadWriteSessionMethod = EvitaServiceGrpc.getCreateReadWriteSessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateReadWriteSessionMethod = EvitaServiceGrpc.getCreateReadWriteSessionMethod) == null) {
          EvitaServiceGrpc.getCreateReadWriteSessionMethod = getCreateReadWriteSessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateReadWriteSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateReadWriteSession"))
              .build();
        }
      }
    }
    return getCreateReadWriteSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadOnlySessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateBinaryReadOnlySession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadOnlySessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadOnlySessionMethod;
    if ((getCreateBinaryReadOnlySessionMethod = EvitaServiceGrpc.getCreateBinaryReadOnlySessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateBinaryReadOnlySessionMethod = EvitaServiceGrpc.getCreateBinaryReadOnlySessionMethod) == null) {
          EvitaServiceGrpc.getCreateBinaryReadOnlySessionMethod = getCreateBinaryReadOnlySessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateBinaryReadOnlySession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateBinaryReadOnlySession"))
              .build();
        }
      }
    }
    return getCreateBinaryReadOnlySessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadWriteSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateBinaryReadWriteSession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadWriteSessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> getCreateBinaryReadWriteSessionMethod;
    if ((getCreateBinaryReadWriteSessionMethod = EvitaServiceGrpc.getCreateBinaryReadWriteSessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCreateBinaryReadWriteSessionMethod = EvitaServiceGrpc.getCreateBinaryReadWriteSessionMethod) == null) {
          EvitaServiceGrpc.getCreateBinaryReadWriteSessionMethod = getCreateBinaryReadWriteSessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateBinaryReadWriteSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CreateBinaryReadWriteSession"))
              .build();
        }
      }
    }
    return getCreateBinaryReadWriteSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> getTerminateSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TerminateSession",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> getTerminateSessionMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> getTerminateSessionMethod;
    if ((getTerminateSessionMethod = EvitaServiceGrpc.getTerminateSessionMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getTerminateSessionMethod = EvitaServiceGrpc.getTerminateSessionMethod) == null) {
          EvitaServiceGrpc.getTerminateSessionMethod = getTerminateSessionMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest, io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TerminateSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("TerminateSession"))
              .build();
        }
      }
    }
    return getTerminateSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getGetCatalogNamesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCatalogNames",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getGetCatalogNamesMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getGetCatalogNamesMethod;
    if ((getGetCatalogNamesMethod = EvitaServiceGrpc.getGetCatalogNamesMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getGetCatalogNamesMethod = EvitaServiceGrpc.getGetCatalogNamesMethod) == null) {
          EvitaServiceGrpc.getGetCatalogNamesMethod = getGetCatalogNamesMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCatalogNames"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("GetCatalogNames"))
              .build();
        }
      }
    }
    return getGetCatalogNamesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> getDefineCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DefineCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> getDefineCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> getDefineCatalogMethod;
    if ((getDefineCatalogMethod = EvitaServiceGrpc.getDefineCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDefineCatalogMethod = EvitaServiceGrpc.getDefineCatalogMethod) == null) {
          EvitaServiceGrpc.getDefineCatalogMethod = getDefineCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DefineCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DefineCatalog"))
              .build();
        }
      }
    }
    return getDefineCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> getRenameCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RenameCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> getRenameCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> getRenameCatalogMethod;
    if ((getRenameCatalogMethod = EvitaServiceGrpc.getRenameCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getRenameCatalogMethod = EvitaServiceGrpc.getRenameCatalogMethod) == null) {
          EvitaServiceGrpc.getRenameCatalogMethod = getRenameCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RenameCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("RenameCatalog"))
              .build();
        }
      }
    }
    return getRenameCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> getReplaceCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReplaceCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> getReplaceCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> getReplaceCatalogMethod;
    if ((getReplaceCatalogMethod = EvitaServiceGrpc.getReplaceCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getReplaceCatalogMethod = EvitaServiceGrpc.getReplaceCatalogMethod) == null) {
          EvitaServiceGrpc.getReplaceCatalogMethod = getReplaceCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReplaceCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ReplaceCatalog"))
              .build();
        }
      }
    }
    return getReplaceCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RestoreCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogMethod;
    if ((getRestoreCatalogMethod = EvitaServiceGrpc.getRestoreCatalogMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getRestoreCatalogMethod = EvitaServiceGrpc.getRestoreCatalogMethod) == null) {
          EvitaServiceGrpc.getRestoreCatalogMethod = getRestoreCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RestoreCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("RestoreCatalog"))
              .build();
        }
      }
    }
    return getRestoreCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogFromServerFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RestoreCatalogFromServerFile",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogFromServerFileMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogFromServerFileMethod;
    if ((getRestoreCatalogFromServerFileMethod = EvitaServiceGrpc.getRestoreCatalogFromServerFileMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getRestoreCatalogFromServerFileMethod = EvitaServiceGrpc.getRestoreCatalogFromServerFileMethod) == null) {
          EvitaServiceGrpc.getRestoreCatalogFromServerFileMethod = getRestoreCatalogFromServerFileMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RestoreCatalogFromServerFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("RestoreCatalogFromServerFile"))
              .build();
        }
      }
    }
    return getRestoreCatalogFromServerFileMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> getDeleteCatalogIfExistsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteCatalogIfExists",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> getDeleteCatalogIfExistsMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> getDeleteCatalogIfExistsMethod;
    if ((getDeleteCatalogIfExistsMethod = EvitaServiceGrpc.getDeleteCatalogIfExistsMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDeleteCatalogIfExistsMethod = EvitaServiceGrpc.getDeleteCatalogIfExistsMethod) == null) {
          EvitaServiceGrpc.getDeleteCatalogIfExistsMethod = getDeleteCatalogIfExistsMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteCatalogIfExists"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DeleteCatalogIfExists"))
              .build();
        }
      }
    }
    return getDeleteCatalogIfExistsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest,
      com.google.protobuf.Empty> getUpdateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Update",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest.class,
      responseType = com.google.protobuf.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest,
      com.google.protobuf.Empty> getUpdateMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest, com.google.protobuf.Empty> getUpdateMethod;
    if ((getUpdateMethod = EvitaServiceGrpc.getUpdateMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getUpdateMethod = EvitaServiceGrpc.getUpdateMethod) == null) {
          EvitaServiceGrpc.getUpdateMethod = getUpdateMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest, com.google.protobuf.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Update"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("Update"))
              .build();
        }
      }
    }
    return getUpdateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> getListTaskStatusesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListTaskStatuses",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> getListTaskStatusesMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> getListTaskStatusesMethod;
    if ((getListTaskStatusesMethod = EvitaServiceGrpc.getListTaskStatusesMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getListTaskStatusesMethod = EvitaServiceGrpc.getListTaskStatusesMethod) == null) {
          EvitaServiceGrpc.getListTaskStatusesMethod = getListTaskStatusesMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListTaskStatuses"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ListTaskStatuses"))
              .build();
        }
      }
    }
    return getListTaskStatusesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getGetTaskStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaskStatus",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getGetTaskStatusMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getGetTaskStatusMethod;
    if ((getGetTaskStatusMethod = EvitaServiceGrpc.getGetTaskStatusMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getGetTaskStatusMethod = EvitaServiceGrpc.getGetTaskStatusMethod) == null) {
          EvitaServiceGrpc.getGetTaskStatusMethod = getGetTaskStatusMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaskStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("GetTaskStatus"))
              .build();
        }
      }
    }
    return getGetTaskStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getGetTaskStatusesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaskStatuses",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getGetTaskStatusesMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getGetTaskStatusesMethod;
    if ((getGetTaskStatusesMethod = EvitaServiceGrpc.getGetTaskStatusesMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getGetTaskStatusesMethod = EvitaServiceGrpc.getGetTaskStatusesMethod) == null) {
          EvitaServiceGrpc.getGetTaskStatusesMethod = getGetTaskStatusesMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaskStatuses"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("GetTaskStatuses"))
              .build();
        }
      }
    }
    return getGetTaskStatusesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> getCancelTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CancelTask",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> getCancelTaskMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest, io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> getCancelTaskMethod;
    if ((getCancelTaskMethod = EvitaServiceGrpc.getCancelTaskMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getCancelTaskMethod = EvitaServiceGrpc.getCancelTaskMethod) == null) {
          EvitaServiceGrpc.getCancelTaskMethod = getCancelTaskMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest, io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CancelTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("CancelTask"))
              .build();
        }
      }
    }
    return getCancelTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> getListFilesToFetchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListFilesToFetch",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> getListFilesToFetchMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> getListFilesToFetchMethod;
    if ((getListFilesToFetchMethod = EvitaServiceGrpc.getListFilesToFetchMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getListFilesToFetchMethod = EvitaServiceGrpc.getListFilesToFetchMethod) == null) {
          EvitaServiceGrpc.getListFilesToFetchMethod = getListFilesToFetchMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListFilesToFetch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("ListFilesToFetch"))
              .build();
        }
      }
    }
    return getListFilesToFetchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getGetFileToFetchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetFileToFetch",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getGetFileToFetchMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getGetFileToFetchMethod;
    if ((getGetFileToFetchMethod = EvitaServiceGrpc.getGetFileToFetchMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getGetFileToFetchMethod = EvitaServiceGrpc.getGetFileToFetchMethod) == null) {
          EvitaServiceGrpc.getGetFileToFetchMethod = getGetFileToFetchMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetFileToFetch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("GetFileToFetch"))
              .build();
        }
      }
    }
    return getGetFileToFetchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> getFetchFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FetchFile",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> getFetchFileMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest, io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> getFetchFileMethod;
    if ((getFetchFileMethod = EvitaServiceGrpc.getFetchFileMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getFetchFileMethod = EvitaServiceGrpc.getFetchFileMethod) == null) {
          EvitaServiceGrpc.getFetchFileMethod = getFetchFileMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest, io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FetchFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("FetchFile"))
              .build();
        }
      }
    }
    return getFetchFileMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> getDeleteFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteFile",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> getDeleteFileMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> getDeleteFileMethod;
    if ((getDeleteFileMethod = EvitaServiceGrpc.getDeleteFileMethod) == null) {
      synchronized (EvitaServiceGrpc.class) {
        if ((getDeleteFileMethod = EvitaServiceGrpc.getDeleteFileMethod) == null) {
          EvitaServiceGrpc.getDeleteFileMethod = getDeleteFileMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaServiceMethodDescriptorSupplier("DeleteFile"))
              .build();
        }
      }
    }
    return getDeleteFileMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EvitaServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaServiceStub>() {
        @java.lang.Override
        public EvitaServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaServiceStub(channel, callOptions);
        }
      };
    return EvitaServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EvitaServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaServiceBlockingStub>() {
        @java.lang.Override
        public EvitaServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaServiceBlockingStub(channel, callOptions);
        }
      };
    return EvitaServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EvitaServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaServiceFutureStub>() {
        @java.lang.Override
        public EvitaServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaServiceFutureStub(channel, callOptions);
        }
      };
    return EvitaServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static abstract class EvitaServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public void serverStatus(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getServerStatusMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public void createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateReadOnlySessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public void createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateReadWriteSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public void createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateBinaryReadOnlySessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public void createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateBinaryReadWriteSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public void terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTerminateSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public void getCatalogNames(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogNamesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public void defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDefineCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public void renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRenameCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public void replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReplaceCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest> restoreCatalog(
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getRestoreCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public void restoreCatalogFromServerFile(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRestoreCatalogFromServerFileMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public void deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteCatalogIfExistsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public void update(io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public void listTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListTaskStatusesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public void getTaskStatus(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskStatusMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public void getTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskStatusesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public void cancelTask(io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCancelTaskMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public void listFilesToFetch(io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListFilesToFetchMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public void getFileToFetch(io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetFileToFetchMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get file contents
     * </pre>
     */
    public void fetchFile(io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFetchFileMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public void deleteFile(io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteFileMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getServerStatusMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse>(
                  this, METHODID_SERVER_STATUS)))
          .addMethod(
            getCreateReadOnlySessionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                  this, METHODID_CREATE_READ_ONLY_SESSION)))
          .addMethod(
            getCreateReadWriteSessionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                  this, METHODID_CREATE_READ_WRITE_SESSION)))
          .addMethod(
            getCreateBinaryReadOnlySessionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                  this, METHODID_CREATE_BINARY_READ_ONLY_SESSION)))
          .addMethod(
            getCreateBinaryReadWriteSessionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>(
                  this, METHODID_CREATE_BINARY_READ_WRITE_SESSION)))
          .addMethod(
            getTerminateSessionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest,
                io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse>(
                  this, METHODID_TERMINATE_SESSION)))
          .addMethod(
            getGetCatalogNamesMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse>(
                  this, METHODID_GET_CATALOG_NAMES)))
          .addMethod(
            getDefineCatalogMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse>(
                  this, METHODID_DEFINE_CATALOG)))
          .addMethod(
            getRenameCatalogMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest,
                io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse>(
                  this, METHODID_RENAME_CATALOG)))
          .addMethod(
            getReplaceCatalogMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest,
                io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse>(
                  this, METHODID_REPLACE_CATALOG)))
          .addMethod(
            getRestoreCatalogMethod(),
            io.grpc.stub.ServerCalls.asyncClientStreamingCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest,
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>(
                  this, METHODID_RESTORE_CATALOG)))
          .addMethod(
            getRestoreCatalogFromServerFileMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest,
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>(
                  this, METHODID_RESTORE_CATALOG_FROM_SERVER_FILE)))
          .addMethod(
            getDeleteCatalogIfExistsMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse>(
                  this, METHODID_DELETE_CATALOG_IF_EXISTS)))
          .addMethod(
            getUpdateMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest,
                com.google.protobuf.Empty>(
                  this, METHODID_UPDATE)))
          .addMethod(
            getListTaskStatusesMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest,
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse>(
                  this, METHODID_LIST_TASK_STATUSES)))
          .addMethod(
            getGetTaskStatusMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest,
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse>(
                  this, METHODID_GET_TASK_STATUS)))
          .addMethod(
            getGetTaskStatusesMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest,
                io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse>(
                  this, METHODID_GET_TASK_STATUSES)))
          .addMethod(
            getCancelTaskMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest,
                io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse>(
                  this, METHODID_CANCEL_TASK)))
          .addMethod(
            getListFilesToFetchMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest,
                io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse>(
                  this, METHODID_LIST_FILES_TO_FETCH)))
          .addMethod(
            getGetFileToFetchMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest,
                io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse>(
                  this, METHODID_GET_FILE_TO_FETCH)))
          .addMethod(
            getFetchFileMethod(),
            io.grpc.stub.ServerCalls.asyncServerStreamingCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest,
                io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse>(
                  this, METHODID_FETCH_FILE)))
          .addMethod(
            getDeleteFileMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse>(
                  this, METHODID_DELETE_FILE)))
          .build();
    }
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaServiceStub extends io.grpc.stub.AbstractAsyncStub<EvitaServiceStub> {
    private EvitaServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public void serverStatus(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getServerStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public void createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateReadOnlySessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public void createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateReadWriteSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public void createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateBinaryReadOnlySessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public void createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateBinaryReadWriteSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public void terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTerminateSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public void getCatalogNames(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCatalogNamesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public void defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDefineCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public void renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRenameCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public void replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReplaceCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest> restoreCatalog(
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getRestoreCatalogMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public void restoreCatalogFromServerFile(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRestoreCatalogFromServerFileMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public void deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteCatalogIfExistsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public void update(io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public void listTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListTaskStatusesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public void getTaskStatus(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public void getTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskStatusesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public void cancelTask(io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCancelTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public void listFilesToFetch(io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListFilesToFetchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public void getFileToFetch(io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetFileToFetchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get file contents
     * </pre>
     */
    public void fetchFile(io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getFetchFileMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public void deleteFile(io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteFileMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<EvitaServiceBlockingStub> {
    private EvitaServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse serverStatus(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getServerStatusMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateBinaryReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateBinaryReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTerminateSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse getCatalogNames(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCatalogNamesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDefineCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRenameCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReplaceCatalogMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse restoreCatalogFromServerFile(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRestoreCatalogFromServerFileMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteCatalogIfExistsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public com.google.protobuf.Empty update(io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse listTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListTaskStatusesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse getTaskStatus(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskStatusMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse getTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskStatusesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse cancelTask(io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCancelTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse listFilesToFetch(io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListFilesToFetchMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse getFileToFetch(io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetFileToFetchMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get file contents
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> fetchFile(
        io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getFetchFileMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse deleteFile(io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteFileMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaServiceFutureStub extends io.grpc.stub.AbstractFutureStub<EvitaServiceFutureStub> {
    private EvitaServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> serverStatus(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getServerStatusMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read only sessions.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createReadOnlySession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateReadOnlySessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read write sessions.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createReadWriteSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateReadWriteSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read-only session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createBinaryReadOnlySession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateBinaryReadOnlySessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to create read-write session which will return data in binary format. Part of the Private API.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createBinaryReadWriteSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateBinaryReadWriteSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to terminate existing session.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> terminateSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTerminateSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get names of all existing catalogs.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getCatalogNames(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCatalogNamesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to define a new catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> defineCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDefineCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to rename an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> renameCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRenameCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to replace an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> replaceCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReplaceCatalogMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> restoreCatalogFromServerFile(
        io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRestoreCatalogFromServerFileMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to delete an existing catalog.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> deleteCatalogIfExists(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteCatalogIfExistsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to update the catalog with a set of mutations.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.Empty> update(
        io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> listTaskStatuses(
        io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListTaskStatusesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getTaskStatus(
        io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskStatusMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getTaskStatuses(
        io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskStatusesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> cancelTask(
        io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCancelTaskMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> listFilesToFetch(
        io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListFilesToFetchMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getFileToFetch(
        io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetFileToFetchMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> deleteFile(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteFileMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SERVER_STATUS = 0;
  private static final int METHODID_CREATE_READ_ONLY_SESSION = 1;
  private static final int METHODID_CREATE_READ_WRITE_SESSION = 2;
  private static final int METHODID_CREATE_BINARY_READ_ONLY_SESSION = 3;
  private static final int METHODID_CREATE_BINARY_READ_WRITE_SESSION = 4;
  private static final int METHODID_TERMINATE_SESSION = 5;
  private static final int METHODID_GET_CATALOG_NAMES = 6;
  private static final int METHODID_DEFINE_CATALOG = 7;
  private static final int METHODID_RENAME_CATALOG = 8;
  private static final int METHODID_REPLACE_CATALOG = 9;
  private static final int METHODID_RESTORE_CATALOG_FROM_SERVER_FILE = 10;
  private static final int METHODID_DELETE_CATALOG_IF_EXISTS = 11;
  private static final int METHODID_UPDATE = 12;
  private static final int METHODID_LIST_TASK_STATUSES = 13;
  private static final int METHODID_GET_TASK_STATUS = 14;
  private static final int METHODID_GET_TASK_STATUSES = 15;
  private static final int METHODID_CANCEL_TASK = 16;
  private static final int METHODID_LIST_FILES_TO_FETCH = 17;
  private static final int METHODID_GET_FILE_TO_FETCH = 18;
  private static final int METHODID_FETCH_FILE = 19;
  private static final int METHODID_DELETE_FILE = 20;
  private static final int METHODID_RESTORE_CATALOG = 21;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final EvitaServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(EvitaServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SERVER_STATUS:
          serviceImpl.serverStatus((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse>) responseObserver);
          break;
        case METHODID_CREATE_READ_ONLY_SESSION:
          serviceImpl.createReadOnlySession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_CREATE_READ_WRITE_SESSION:
          serviceImpl.createReadWriteSession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_CREATE_BINARY_READ_ONLY_SESSION:
          serviceImpl.createBinaryReadOnlySession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_CREATE_BINARY_READ_WRITE_SESSION:
          serviceImpl.createBinaryReadWriteSession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse>) responseObserver);
          break;
        case METHODID_TERMINATE_SESSION:
          serviceImpl.terminateSession((io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse>) responseObserver);
          break;
        case METHODID_GET_CATALOG_NAMES:
          serviceImpl.getCatalogNames((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse>) responseObserver);
          break;
        case METHODID_DEFINE_CATALOG:
          serviceImpl.defineCatalog((io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse>) responseObserver);
          break;
        case METHODID_RENAME_CATALOG:
          serviceImpl.renameCatalog((io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse>) responseObserver);
          break;
        case METHODID_REPLACE_CATALOG:
          serviceImpl.replaceCatalog((io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse>) responseObserver);
          break;
        case METHODID_RESTORE_CATALOG_FROM_SERVER_FILE:
          serviceImpl.restoreCatalogFromServerFile((io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>) responseObserver);
          break;
        case METHODID_DELETE_CATALOG_IF_EXISTS:
          serviceImpl.deleteCatalogIfExists((io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse>) responseObserver);
          break;
        case METHODID_UPDATE:
          serviceImpl.update((io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.Empty>) responseObserver);
          break;
        case METHODID_LIST_TASK_STATUSES:
          serviceImpl.listTaskStatuses((io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse>) responseObserver);
          break;
        case METHODID_GET_TASK_STATUS:
          serviceImpl.getTaskStatus((io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse>) responseObserver);
          break;
        case METHODID_GET_TASK_STATUSES:
          serviceImpl.getTaskStatuses((io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse>) responseObserver);
          break;
        case METHODID_CANCEL_TASK:
          serviceImpl.cancelTask((io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse>) responseObserver);
          break;
        case METHODID_LIST_FILES_TO_FETCH:
          serviceImpl.listFilesToFetch((io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse>) responseObserver);
          break;
        case METHODID_GET_FILE_TO_FETCH:
          serviceImpl.getFileToFetch((io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse>) responseObserver);
          break;
        case METHODID_FETCH_FILE:
          serviceImpl.fetchFile((io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse>) responseObserver);
          break;
        case METHODID_DELETE_FILE:
          serviceImpl.deleteFile((io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_RESTORE_CATALOG:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.restoreCatalog(
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class EvitaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EvitaServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EvitaService");
    }
  }

  private static final class EvitaServiceFileDescriptorSupplier
      extends EvitaServiceBaseDescriptorSupplier {
    EvitaServiceFileDescriptorSupplier() {}
  }

  private static final class EvitaServiceMethodDescriptorSupplier
      extends EvitaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    EvitaServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (EvitaServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EvitaServiceFileDescriptorSupplier())
              .addMethod(getServerStatusMethod())
              .addMethod(getCreateReadOnlySessionMethod())
              .addMethod(getCreateReadWriteSessionMethod())
              .addMethod(getCreateBinaryReadOnlySessionMethod())
              .addMethod(getCreateBinaryReadWriteSessionMethod())
              .addMethod(getTerminateSessionMethod())
              .addMethod(getGetCatalogNamesMethod())
              .addMethod(getDefineCatalogMethod())
              .addMethod(getRenameCatalogMethod())
              .addMethod(getReplaceCatalogMethod())
              .addMethod(getRestoreCatalogMethod())
              .addMethod(getRestoreCatalogFromServerFileMethod())
              .addMethod(getDeleteCatalogIfExistsMethod())
              .addMethod(getUpdateMethod())
              .addMethod(getListTaskStatusesMethod())
              .addMethod(getGetTaskStatusMethod())
              .addMethod(getGetTaskStatusesMethod())
              .addMethod(getCancelTaskMethod())
              .addMethod(getListFilesToFetchMethod())
              .addMethod(getGetFileToFetchMethod())
              .addMethod(getFetchFileMethod())
              .addMethod(getDeleteFileMethod())
              .build();
        }
      }
    }
    return result;
  }
}
