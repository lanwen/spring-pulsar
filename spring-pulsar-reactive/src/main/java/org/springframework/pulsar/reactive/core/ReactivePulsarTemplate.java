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

package org.springframework.pulsar.reactive.core;

import java.util.Collections;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.reactive.client.api.MessageSpec;
import org.apache.pulsar.reactive.client.api.MessageSpecBuilder;
import org.apache.pulsar.reactive.client.api.ReactiveMessageSender;
import org.reactivestreams.Publisher;

import org.springframework.core.log.LogAccessor;
import org.springframework.lang.Nullable;
import org.springframework.pulsar.core.DefaultSchemaResolver;
import org.springframework.pulsar.core.SchemaResolver;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A template for executing high-level reactive Pulsar operations.
 *
 * @param <T> the message payload type
 * @author Christophe Bornet
 */
public class ReactivePulsarTemplate<T> implements ReactivePulsarOperations<T> {

	private final LogAccessor logger = new LogAccessor(this.getClass());

	private final ReactivePulsarSenderFactory<T> reactiveMessageSenderFactory;

	private final SchemaResolver schemaResolver;

	/**
	 * Construct a template instance that uses the default schema resolver.
	 * @param reactiveMessageSenderFactory the factory used to create the backing Pulsar
	 * reactive senders
	 */
	public ReactivePulsarTemplate(ReactivePulsarSenderFactory<T> reactiveMessageSenderFactory) {
		this(reactiveMessageSenderFactory, new DefaultSchemaResolver());
	}

	/**
	 * Construct a template instance with a custom schema resolver.
	 * @param reactiveMessageSenderFactory the factory used to create the backing Pulsar
	 * @param schemaResolver the schema resolver to use reactive senders
	 */
	public ReactivePulsarTemplate(ReactivePulsarSenderFactory<T> reactiveMessageSenderFactory,
			SchemaResolver schemaResolver) {
		this.reactiveMessageSenderFactory = reactiveMessageSenderFactory;
		this.schemaResolver = schemaResolver;
	}

	@Override
	public Mono<MessageId> send(T message) {
		return send(null, message);
	}

	@Override
	public Mono<MessageId> send(T message, @Nullable Schema<T> schema) {
		return doSend(null, message, schema, null, null);
	}

	@Override
	public Mono<MessageId> send(@Nullable String topic, T message) {
		return doSend(topic, message, null, null, null);
	}

	@Override
	public Mono<MessageId> send(@Nullable String topic, T message, @Nullable Schema<T> schema) {
		return doSend(topic, message, schema, null, null);
	}

	@Override
	public Flux<MessageId> send(Publisher<T> messages) {
		return send(null, messages);
	}

	@Override
	public Flux<MessageId> send(Publisher<T> messages, @Nullable Schema<T> schema) {
		return doSendMany(null, Flux.from(messages), schema);
	}

	@Override
	public Flux<MessageId> send(@Nullable String topic, Publisher<T> messages) {
		return doSendMany(topic, Flux.from(messages), null);
	}

	@Override
	public Flux<MessageId> send(@Nullable String topic, Publisher<T> messages, @Nullable Schema<T> schema) {
		return doSendMany(topic, Flux.from(messages), schema);
	}

	@Override
	public SendMessageBuilderImpl<T> newMessage(T message) {
		return new SendMessageBuilderImpl<>(this, message);
	}

	@Override
	public SendMessageBuilder<T> newMessages(Publisher<T> messages) {
		return new SendMessageBuilderImpl<>(this, messages);
	}

	private Mono<MessageId> doSend(@Nullable String topic, T message, @Nullable Schema<T> schema,
			@Nullable MessageSpecBuilderCustomizer<T> messageSpecBuilderCustomizer,
			@Nullable ReactiveMessageSenderBuilderCustomizer<T> customizer) {
		String topicName = ReactiveMessageSenderUtils.resolveTopicName(topic, this.reactiveMessageSenderFactory);
		this.logger.trace(() -> String.format("Sending reactive message to '%s' topic", topicName));
		// NOTE: We do not pass the resolved topic name from above as it handles the
		// resolve itself
		ReactiveMessageSender<T> sender = createMessageSender(topic, message, schema, customizer);
		// @formatter:off
		return sender.sendOne(getMessageSpec(messageSpecBuilderCustomizer, message))
				.doOnError(ex -> this.logger.error(ex, () -> String.format("Failed to send message to '%s' topic", topicName)))
				.doOnSuccess(msgId -> this.logger.trace(() -> String.format("Sent message to '%s' topic", topicName)));
		// @formatter:on
	}

	private Flux<MessageId> doSendMany(@Nullable String topic, Flux<T> messages, @Nullable Schema<T> schema) {
		String topicName = ReactiveMessageSenderUtils.resolveTopicName(topic, this.reactiveMessageSenderFactory);
		this.logger.trace(() -> String.format("Sending reactive messages to '%s' topic", topicName));

		if (schema != null) {
			/*
			 * If schema specified we can create the message sender right away and use
			 * ReactiveMessageSender::sendMany to send them as a stream. Otherwise, we
			 * need to wait to get a message to create the sender, and we can't share it
			 * between messages. So we create one each time and use
			 * ReactiveMessageSender::sendOne to send messages individually.
			 */
			// NOTE: We do not pass the resolved topic name from above as it handles the
			// resolve itself
			ReactiveMessageSender<T> sender = createMessageSender(topic, null, schema, null);
			return messages.map(MessageSpec::of).as(sender::sendMany)
					.doOnError(ex -> this.logger.error(ex,
							() -> String.format("Failed to send messages to '%s' topic", topicName)))
					.doOnNext(
							msgId -> this.logger.trace(() -> String.format("Sent messages to '%s' topic", topicName)));
		}
		return messages.flatMapSequential(message -> doSend(topic, message, schema, null, null));
	}

	private static <T> MessageSpec<T> getMessageSpec(
			@Nullable MessageSpecBuilderCustomizer<T> messageSpecBuilderCustomizer, T message) {
		MessageSpecBuilder<T> messageSpecBuilder = MessageSpec.builder(message);
		if (messageSpecBuilderCustomizer != null) {
			messageSpecBuilderCustomizer.customize(messageSpecBuilder);
		}
		return messageSpecBuilder.build();
	}

	private ReactiveMessageSender<T> createMessageSender(@Nullable String topic, T message, @Nullable Schema<T> schema,
			@Nullable ReactiveMessageSenderBuilderCustomizer<T> customizer) {
		if (schema == null) {
			schema = this.schemaResolver.getSchema(message);
		}
		return this.reactiveMessageSenderFactory.createSender(topic, schema,
				customizer == null ? Collections.emptyList() : Collections.singletonList(customizer));
	}

	public static class SendMessageBuilderImpl<T> implements SendMessageBuilder<T> {

		private final ReactivePulsarTemplate<T> template;

		private final T message;

		private final Publisher<T> messages;

		private String topic;

		private Schema<T> schema;

		private MessageSpecBuilderCustomizer<T> messageCustomizer;

		private ReactiveMessageSenderBuilderCustomizer<T> senderCustomizer;

		SendMessageBuilderImpl(ReactivePulsarTemplate<T> template, T message) {
			this.template = template;
			this.message = message;
			this.messages = null;
		}

		SendMessageBuilderImpl(ReactivePulsarTemplate<T> template, Publisher<T> messages) {
			this.template = template;
			this.message = null;
			this.messages = messages;
		}

		@Override
		public SendMessageBuilderImpl<T> withTopic(String topic) {
			this.topic = topic;
			return this;
		}

		@Override
		public SendMessageBuilderImpl<T> withSchema(Schema<T> schema) {
			this.schema = schema;
			return this;
		}

		@Override
		public SendMessageBuilderImpl<T> withMessageCustomizer(MessageSpecBuilderCustomizer<T> messageCustomizer) {
			this.messageCustomizer = messageCustomizer;
			return this;
		}

		@Override
		public SendMessageBuilderImpl<T> withSenderCustomizer(
				ReactiveMessageSenderBuilderCustomizer<T> senderCustomizer) {
			this.senderCustomizer = senderCustomizer;
			return this;
		}

		@Override
		public Mono<MessageId> send() {
			return this.template.doSend(this.topic, this.message, this.schema, this.messageCustomizer,
					this.senderCustomizer);
		}

		@Override
		public Flux<MessageId> sendMany() {
			return this.template.doSendMany(this.topic, Flux.from(this.messages), this.schema);
		}

	}

}
