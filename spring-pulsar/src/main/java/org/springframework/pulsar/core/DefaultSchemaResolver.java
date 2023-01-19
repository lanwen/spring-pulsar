/*
 * Copyright 2022-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.pulsar.core;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.pulsar.client.api.Schema;

import org.springframework.lang.Nullable;

/**
 * Default schema resolver capable of handling basic message types.
 *
 * <p>
 * Additional message types can be configured with the {@link #DefaultSchemaResolver(Map)}
 * constructor.
 *
 * @author Soby Chacko
 * @author Alexander Preuß
 * @author Chris Bono
 */
public class DefaultSchemaResolver implements SchemaResolver {

	private static final Map<Class<?>, Schema<?>> BASE_SCHEMA_MAPPINGS = new HashMap<>();
	static {
		BASE_SCHEMA_MAPPINGS.put(byte[].class, Schema.BYTES);
		BASE_SCHEMA_MAPPINGS.put(String.class, Schema.STRING);
		BASE_SCHEMA_MAPPINGS.put(Byte.class, Schema.INT8);
		BASE_SCHEMA_MAPPINGS.put(byte.class, Schema.INT8);
		BASE_SCHEMA_MAPPINGS.put(Short.class, Schema.INT16);
		BASE_SCHEMA_MAPPINGS.put(short.class, Schema.INT16);
		BASE_SCHEMA_MAPPINGS.put(Integer.class, Schema.INT32);
		BASE_SCHEMA_MAPPINGS.put(int.class, Schema.INT32);
		BASE_SCHEMA_MAPPINGS.put(Long.class, Schema.INT64);
		BASE_SCHEMA_MAPPINGS.put(long.class, Schema.INT64);
		BASE_SCHEMA_MAPPINGS.put(Boolean.class, Schema.BOOL);
		BASE_SCHEMA_MAPPINGS.put(boolean.class, Schema.BOOL);
		BASE_SCHEMA_MAPPINGS.put(ByteBuffer.class, Schema.BYTEBUFFER);
		BASE_SCHEMA_MAPPINGS.put(ByteBuffer.allocate(0).getClass(), Schema.BYTEBUFFER);
		BASE_SCHEMA_MAPPINGS.put(ByteBuffer.allocateDirect(0).getClass(), Schema.BYTEBUFFER);
		BASE_SCHEMA_MAPPINGS.put(Date.class, Schema.DATE);
		BASE_SCHEMA_MAPPINGS.put(Double.class, Schema.DOUBLE);
		BASE_SCHEMA_MAPPINGS.put(double.class, Schema.DOUBLE);
		BASE_SCHEMA_MAPPINGS.put(Float.class, Schema.FLOAT);
		BASE_SCHEMA_MAPPINGS.put(float.class, Schema.FLOAT);
		BASE_SCHEMA_MAPPINGS.put(Instant.class, Schema.INSTANT);
		BASE_SCHEMA_MAPPINGS.put(LocalDate.class, Schema.LOCAL_DATE);
		BASE_SCHEMA_MAPPINGS.put(LocalDateTime.class, Schema.LOCAL_DATE_TIME);
		BASE_SCHEMA_MAPPINGS.put(LocalTime.class, Schema.LOCAL_TIME);
	}

	private final Map<Class<?>, Schema<?>> customSchemaMappings;

	/**
	 * Constructs a resolver with no custom type mappings.
	 */
	public DefaultSchemaResolver() {
		this(Collections.emptyMap());
	}

	/**
	 * Constructs a resolver with custom type mappings.
	 * @param customTypeSchemaMappings additional type to schema mappings to use
	 */
	public DefaultSchemaResolver(Map<Class<?>, Schema<?>> customTypeSchemaMappings) {
		this.customSchemaMappings = Objects.requireNonNull(customTypeSchemaMappings);
	}

	@Override
	public <T> Schema<T> getSchema(Class<?> messageClass, boolean returnDefault) {
		Schema<?> schema = BASE_SCHEMA_MAPPINGS.get(messageClass);
		if (schema == null) {
			schema = getCustomSchemaOrMaybeDefault(messageClass, returnDefault);
		}
		return schema != null ? castToType(schema) : null;
	}

	@Nullable
	private Schema<?> getCustomSchemaOrMaybeDefault(Class<?> messageClass, boolean returnDefault) {
		return this.customSchemaMappings.getOrDefault(messageClass, (returnDefault ? Schema.BYTES : null));
	}

	@SuppressWarnings({ "unchecked" })
	private <X> Schema<X> castToType(Schema<?> rawSchema) {
		return (Schema<X>) rawSchema;
	}

}