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

package io.evitadb.externalApi.grpc.generated;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */

@io.grpc.stub.annotations.GrpcGenerated
public final class EvitaServiceGrpc {

  private EvitaServiceGrpc() {}

  public static final String SERVICE_NAME = "io.evitadb.externalApi.grpc.generated.EvitaService";

  // Static method descriptors that strictly reflect the proto.
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
   */
  public static abstract class EvitaServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateReadOnlySessionMethod(), responseObserver);
    }

    /**
     */
    public void createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateReadWriteSessionMethod(), responseObserver);
    }

    /**
     */
    public void createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateBinaryReadOnlySessionMethod(), responseObserver);
    }

    /**
     */
    public void createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateBinaryReadWriteSessionMethod(), responseObserver);
    }

    /**
     */
    public void terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTerminateSessionMethod(), responseObserver);
    }

    /**
     */
    public void getCatalogNames(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCatalogNamesMethod(), responseObserver);
    }

    /**
     */
    public void defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDefineCatalogMethod(), responseObserver);
    }

    /**
     */
    public void renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRenameCatalogMethod(), responseObserver);
    }

    /**
     */
    public void replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReplaceCatalogMethod(), responseObserver);
    }

    /**
     */
    public void deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteCatalogIfExistsMethod(), responseObserver);
    }

    /**
     */
    public void update(io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
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
          .build();
    }
  }

  /**
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
     */
    public void createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateReadOnlySessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateReadWriteSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateBinaryReadOnlySessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateBinaryReadWriteSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTerminateSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getCatalogNames(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCatalogNamesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDefineCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRenameCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReplaceCatalogMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteCatalogIfExistsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void update(io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
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
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadOnlySession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateBinaryReadOnlySessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse createBinaryReadWriteSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateBinaryReadWriteSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse terminateSession(io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTerminateSessionMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse getCatalogNames(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCatalogNamesMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse defineCatalog(io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDefineCatalogMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse renameCatalog(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRenameCatalogMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse replaceCatalog(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReplaceCatalogMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse deleteCatalogIfExists(io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteCatalogIfExistsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.google.protobuf.Empty update(io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateMethod(), getCallOptions(), request);
    }
  }

  /**
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
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createReadOnlySession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateReadOnlySessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createReadWriteSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateReadWriteSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createBinaryReadOnlySession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateBinaryReadOnlySessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse> createBinaryReadWriteSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateBinaryReadWriteSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse> terminateSession(
        io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTerminateSessionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse> getCatalogNames(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCatalogNamesMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogResponse> defineCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcDefineCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDefineCatalogMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogResponse> renameCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRenameCatalogMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogResponse> replaceCatalog(
        io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReplaceCatalogMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse> deleteCatalogIfExists(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteCatalogIfExistsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.Empty> update(
        io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE_READ_ONLY_SESSION = 0;
  private static final int METHODID_CREATE_READ_WRITE_SESSION = 1;
  private static final int METHODID_CREATE_BINARY_READ_ONLY_SESSION = 2;
  private static final int METHODID_CREATE_BINARY_READ_WRITE_SESSION = 3;
  private static final int METHODID_TERMINATE_SESSION = 4;
  private static final int METHODID_GET_CATALOG_NAMES = 5;
  private static final int METHODID_DEFINE_CATALOG = 6;
  private static final int METHODID_RENAME_CATALOG = 7;
  private static final int METHODID_REPLACE_CATALOG = 8;
  private static final int METHODID_DELETE_CATALOG_IF_EXISTS = 9;
  private static final int METHODID_UPDATE = 10;

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
        case METHODID_DELETE_CATALOG_IF_EXISTS:
          serviceImpl.deleteCatalogIfExists((io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse>) responseObserver);
          break;
        case METHODID_UPDATE:
          serviceImpl.update((io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.Empty>) responseObserver);
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
              .addMethod(getCreateReadOnlySessionMethod())
              .addMethod(getCreateReadWriteSessionMethod())
              .addMethod(getCreateBinaryReadOnlySessionMethod())
              .addMethod(getCreateBinaryReadWriteSessionMethod())
              .addMethod(getTerminateSessionMethod())
              .addMethod(getGetCatalogNamesMethod())
              .addMethod(getDefineCatalogMethod())
              .addMethod(getRenameCatalogMethod())
              .addMethod(getReplaceCatalogMethod())
              .addMethod(getDeleteCatalogIfExistsMethod())
              .addMethod(getUpdateMethod())
              .build();
        }
      }
    }
    return result;
  }
}
