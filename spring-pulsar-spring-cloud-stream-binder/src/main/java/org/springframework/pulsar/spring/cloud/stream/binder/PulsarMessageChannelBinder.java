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

package org.springframework.pulsar.spring.cloud.stream.binder;

import java.util.Objects;

import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.SchemaType;

import org.springframework.cloud.stream.binder.AbstractMessageChannelBinder;
import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.BinderSpecificPropertiesProvider;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.ExtendedPropertiesBinder;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.core.ResolvableType;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.pulsar.core.PulsarConsumerFactory;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.pulsar.core.SchemaResolver;
import org.springframework.pulsar.listener.AbstractPulsarMessageListenerContainer;
import org.springframework.pulsar.listener.DefaultPulsarMessageListenerContainer;
import org.springframework.pulsar.listener.PulsarContainerProperties;
import org.springframework.pulsar.listener.PulsarRecordMessageListener;
import org.springframework.pulsar.spring.cloud.stream.binder.properties.PulsarConsumerProperties;
import org.springframework.pulsar.spring.cloud.stream.binder.properties.PulsarExtendedBindingProperties;
import org.springframework.pulsar.spring.cloud.stream.binder.properties.PulsarProducerProperties;
import org.springframework.pulsar.spring.cloud.stream.binder.provisioning.PulsarTopicProvisioner;

/**
 * {@link Binder} implementation for Apache Pulsar.
 *
 * @author Soby Chacko
 * @author Chris Bono
 */
public class PulsarMessageChannelBinder extends
		AbstractMessageChannelBinder<ExtendedConsumerProperties<PulsarConsumerProperties>, ExtendedProducerProperties<PulsarProducerProperties>, PulsarTopicProvisioner>
		implements ExtendedPropertiesBinder<MessageChannel, PulsarConsumerProperties, PulsarProducerProperties> {

	private final PulsarTemplate<Object> pulsarTemplate;

	private final PulsarConsumerFactory<?> pulsarConsumerFactory;

	private final SchemaResolver schemaResolver;

	private PulsarExtendedBindingProperties extendedBindingProperties = new PulsarExtendedBindingProperties();

	public PulsarMessageChannelBinder(PulsarTopicProvisioner provisioningProvider,
			PulsarTemplate<Object> pulsarTemplate, PulsarConsumerFactory<?> pulsarConsumerFactory,
			SchemaResolver schemaResolver) {
		super(null, provisioningProvider);
		this.pulsarTemplate = pulsarTemplate;
		this.pulsarConsumerFactory = pulsarConsumerFactory;
		this.schemaResolver = schemaResolver;
	}

	@Override
	protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
			ExtendedProducerProperties<PulsarProducerProperties> producerProperties, MessageChannel errorChannel) {
		final Schema<Object> schema;
		if (producerProperties.isUseNativeEncoding()) {
			schema = resolveSchema(producerProperties.getExtension().getSchemaType(),
					producerProperties.getExtension().getMessageType(),
					producerProperties.getExtension().getMessageKeyType(),
					producerProperties.getExtension().getMessageValueType());
			Objects.requireNonNull(schema, "Could not determine producer schema for " + destination.getName());
		}
		else {
			schema = null;
		}
		return message -> {
			try {
				PulsarMessageChannelBinder.this.pulsarTemplate.sendAsync(destination.getName(), message.getPayload(),
						schema);
			}
			catch (PulsarClientException ex) {
				this.logger.trace("Failed to send message to destination: " + destination.getName(), ex);
			}
		};
	}

	@Override
	protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group,
			ExtendedConsumerProperties<PulsarConsumerProperties> properties) {
		PulsarContainerProperties pulsarContainerProperties = new PulsarContainerProperties();
		pulsarContainerProperties.setTopics(new String[] { destination.getName() });
		PulsarMessageDrivenChannelAdapter pulsarMessageDrivenChannelAdapter = new PulsarMessageDrivenChannelAdapter();
		pulsarContainerProperties.setMessageListener((PulsarRecordMessageListener<?>) (consumer, msg) -> {
			Message<Object> message = MessageBuilder.withPayload(msg.getValue()).build();
			pulsarMessageDrivenChannelAdapter.send(message);
		});
		if (properties.isUseNativeDecoding()) {
			Schema<Object> schema = resolveSchema(properties.getExtension().getSchemaType(),
					properties.getExtension().getMessageType(), properties.getExtension().getMessageKeyType(),
					properties.getExtension().getMessageValueType());
			pulsarContainerProperties.setSchema(
					Objects.requireNonNull(schema, "Could not determine consumer schema for " + destination.getName()));
		}
		else {
			pulsarContainerProperties.setSchema(Schema.BYTES);
		}
		String subscriptionName = PulsarBinderUtils.subscriptionName(properties.getExtension(), destination);
		pulsarContainerProperties.setSubscriptionName(subscriptionName);
		DefaultPulsarMessageListenerContainer<?> container = new DefaultPulsarMessageListenerContainer<>(
				this.pulsarConsumerFactory, pulsarContainerProperties);
		pulsarMessageDrivenChannelAdapter.setMessageListenerContainer(container);
		return pulsarMessageDrivenChannelAdapter;
	}

	// VisibleForTesting
	@Nullable
	Schema<Object> resolveSchema(@Nullable SchemaType schemaType, @Nullable Class<?> messageType,
			@Nullable Class<?> messageKeyType, @Nullable Class<?> messageValueType) {
		if (schemaType == null) {
			schemaType = SchemaType.NONE;
		}
		ResolvableType resolvableType = null;
		if (schemaType.isStruct()) {
			resolvableType = ResolvableType.forClass(Objects.requireNonNull(messageType,
					"'message-type' required for 'schema-type' " + schemaType.name()));
		}
		else if (schemaType == SchemaType.KEY_VALUE) {
			resolvableType = ResolvableType.forClassWithGenerics(KeyValue.class,
					Objects.requireNonNull(messageKeyType, "'message-key-type' required for 'schema-type' KEY_VALUE"),
					Objects.requireNonNull(messageValueType,
							"'message-value-type' required for 'schema-type' KEY_VALUE"));
		}
		else if (schemaType == SchemaType.NONE) {
			if (messageType != null) {
				resolvableType = ResolvableType.forClass(messageType);
			}
			else if (messageKeyType != null && messageValueType != null) {
				resolvableType = ResolvableType.forClassWithGenerics(KeyValue.class, messageKeyType, messageValueType);
			}
			if (resolvableType == null) {
				throw new IllegalArgumentException(
						"'message-type' OR ('message-key-type' AND 'message-value-type') required for 'schema-type' NONE");
			}
		}
		// TODO if schema == null then default lookup bean Schema<?> w/ name == binding
		return this.schemaResolver.getSchema(schemaType, resolvableType);
	}

	@Override
	public PulsarConsumerProperties getExtendedConsumerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedConsumerProperties(channelName);
	}

	@Override
	public PulsarProducerProperties getExtendedProducerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedProducerProperties(channelName);
	}

	@Override
	public String getDefaultsPrefix() {
		return null;
	}

	@Override
	public Class<? extends BinderSpecificPropertiesProvider> getExtendedPropertiesEntryClass() {
		return null;
	}

	public PulsarExtendedBindingProperties getExtendedBindingProperties() {
		return this.extendedBindingProperties;
	}

	public void setExtendedBindingProperties(PulsarExtendedBindingProperties extendedBindingProperties) {
		this.extendedBindingProperties = extendedBindingProperties;
	}

	static class PulsarMessageDrivenChannelAdapter extends MessageProducerSupport {

		AbstractPulsarMessageListenerContainer<?> messageListenerContainer;

		public void send(Message<?> message) {
			sendMessage(message);
		}

		@Override
		protected void doStart() {
			this.messageListenerContainer.start();
		}

		@Override
		protected void doStop() {
			this.messageListenerContainer.stop();
		}

		public void setMessageListenerContainer(AbstractPulsarMessageListenerContainer<?> messageListenerContainer) {
			this.messageListenerContainer = messageListenerContainer;
		}

	}

}
