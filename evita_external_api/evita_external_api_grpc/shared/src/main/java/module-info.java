/**
 * Module contains shared classes between gRPC server and Java client implementation.
 */
module evita.external.api.grpc.shared {

	exports io.evitadb.externalApi.grpc.generated;
	exports io.evitadb.externalApi.grpc.constants;
	exports io.evitadb.externalApi.grpc.requestResponse;
	exports io.evitadb.externalApi.grpc.requestResponse.data.mutation;
	exports io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData;
	exports io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute;
	exports io.evitadb.externalApi.grpc.requestResponse.data.mutation.entity;
	exports io.evitadb.externalApi.grpc.requestResponse.data.mutation.price;
	exports io.evitadb.externalApi.grpc.requestResponse.data.mutation.reference;
	exports io.evitadb.externalApi.grpc.requestResponse.schema;
	exports io.evitadb.externalApi.grpc.requestResponse.schema.mutation;
	exports io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;
	exports io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData;
	exports io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;
	exports io.evitadb.externalApi.grpc.requestResponse.schema.mutation.entity;
	exports io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;
	exports io.evitadb.externalApi.grpc.requestResponse.data;
	exports io.evitadb.externalApi.grpc.dataType;
	exports io.evitadb.externalApi.grpc.query;
	exports io.evitadb.externalApi.grpc.certificate;
	exports io.evitadb.externalApi.grpc.requestResponse.cdc;
	exports io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine;

	requires static lombok;
	requires static jsr305;
	requires static org.slf4j;

	requires com.fasterxml.jackson.databind;

	requires evita.common;
	requires evita.api;
	requires evita.query;
	requires com.google.common;
	requires io.grpc;
	requires io.grpc.stub;
	requires io.grpc.protobuf;
	requires com.google.protobuf;
	requires io.netty.handler;
	requires com.linecorp.armeria;

}
