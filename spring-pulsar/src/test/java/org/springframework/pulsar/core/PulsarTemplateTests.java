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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.interceptor.ProducerInterceptor;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.pulsar.core.PulsarOperations.SendMessageBuilder;
import org.springframework.pulsar.test.support.PulsarTestContainerSupport;

/**
 * Tests for {@link PulsarTemplate}.
 *
 * @author Soby Chacko
 * @author Chris Bono
 * @author Alexander Preuß
 * @author Christophe Bornet
 */
class PulsarTemplateTests implements PulsarTestContainerSupport {

	@ParameterizedTest(name = "{0}")
	@MethodSource("sendMessageTestProvider")
	void sendMessageTest(String testName, SendTestArgs testArgs) throws Exception {
		// Use the test args to construct the params to pass to send handler
		String topic = testName;
		String subscription = topic + "-sub";
		String msgPayload = topic + "-msg";
		TypedMessageBuilderCustomizer<String> messageCustomizer = null;
		if (testArgs.messageCustomizer) {
			messageCustomizer = (mb) -> mb.key("foo-key");
		}
		ProducerBuilderCustomizer<String> producerCustomizer = null;
		if (testArgs.producerCustomizer) {
			producerCustomizer = (pb) -> pb.producerName("foo-producer");
		}
		try (PulsarClient client = PulsarClient.builder().serviceUrl(PulsarTestContainerSupport.getPulsarBrokerUrl())
				.build()) {
			try (Consumer<String> consumer = client.newConsumer(Schema.STRING).topic(topic)
					.subscriptionName(subscription).subscribe()) {
				Map<String, Object> producerConfig = testArgs.explicitTopic ? Collections.emptyMap()
						: Collections.singletonMap("topicName", topic);
				PulsarProducerFactory<String> producerFactory = new DefaultPulsarProducerFactory<>(client,
						producerConfig);
				PulsarTemplate<String> pulsarTemplate = new PulsarTemplate<>(producerFactory);

				Object sendResponse;
				if (testArgs.simpleApi) {
					if (testArgs.explicitSchema && testArgs.explicitTopic) {
						sendResponse = testArgs.async ? pulsarTemplate.sendAsync(topic, msgPayload, Schema.STRING)
								: pulsarTemplate.send(topic, msgPayload, Schema.STRING);
					}
					else if (testArgs.explicitSchema) {
						sendResponse = testArgs.async ? pulsarTemplate.sendAsync(msgPayload, Schema.STRING)
								: pulsarTemplate.send(msgPayload, Schema.STRING);
					}
					else if (testArgs.explicitTopic) {
						sendResponse = testArgs.async ? pulsarTemplate.sendAsync(topic, msgPayload)
								: pulsarTemplate.send(topic, msgPayload);
					}
					else {
						sendResponse = testArgs.async ? pulsarTemplate.sendAsync(msgPayload)
								: pulsarTemplate.send(msgPayload);
					}
				}
				else {
					SendMessageBuilder<String> messageBuilder = pulsarTemplate.newMessage(msgPayload);
					if (testArgs.explicitTopic) {
						messageBuilder = messageBuilder.withTopic(topic);
					}
					if (testArgs.explicitSchema) {
						messageBuilder = messageBuilder.withSchema(Schema.STRING);
					}
					if (messageCustomizer != null) {
						messageBuilder = messageBuilder.withMessageCustomizer(messageCustomizer);
					}
					if (producerCustomizer != null) {
						messageBuilder = messageBuilder.withProducerCustomizer(producerCustomizer);
					}
					sendResponse = testArgs.async ? messageBuilder.sendAsync() : messageBuilder.send();
				}

				if (sendResponse instanceof CompletableFuture) {
					sendResponse = ((CompletableFuture<?>) sendResponse).get(3, TimeUnit.SECONDS);
				}
				assertThat(sendResponse).isNotNull();

				CompletableFuture<Message<String>> receiveMsgFuture = consumer.receiveAsync();
				Message<String> msg = receiveMsgFuture.get(3, TimeUnit.SECONDS);

				assertThat(msg.getData()).asString().isEqualTo(msgPayload);
				if (messageCustomizer != null) {
					assertThat(msg.getKey()).isEqualTo("foo-key");
				}
				if (producerCustomizer != null) {
					assertThat(msg.getProducerName()).isEqualTo("foo-producer");
				}
				// Make sure the producer was closed by the template (albeit indirectly as
				// client removes closed producers)
				await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(client).extracting("producers")
						.asInstanceOf(InstanceOfAssertFactories.COLLECTION).isEmpty());
			}
		}
	}

	private static Stream<Arguments> sendMessageTestProvider() {
		return Stream.of(arguments("simpleSend", SendTestArgs.simple().sync()),
				arguments("simpleSendWithTopic", SendTestArgs.simple().sync().topic()),
				arguments("simpleSendWithSchema", SendTestArgs.simple().sync().schema()),
				arguments("simpleSendWithTopicAndSchema", SendTestArgs.simple().sync().topic().schema()),
				arguments("simpleAsyncSend", SendTestArgs.simple().async()),
				arguments("simpleAsyncSendWithTopic", SendTestArgs.simple().async().topic()),
				arguments("simpleAsyncSendWithSchema", SendTestArgs.simple().async().schema()),
				arguments("simpleAsyncSendWithTopicAndSchema", SendTestArgs.simple().async().topic().schema()),
				arguments("fluentSend", SendTestArgs.fluent().sync()),
				arguments("fluentSendWithSchema", SendTestArgs.fluent().sync().schema()),
				arguments("fluentSendWithTopic", SendTestArgs.fluent().sync().topic()),
				arguments("fluentSendWithMessageCustomizer", SendTestArgs.fluent().sync().messageCustomizer()),
				arguments("fluentSendWithProducerCustomizer", SendTestArgs.fluent().sync().producerCustomizer()),
				arguments("fluentSendWithTopicAndSchema", SendTestArgs.fluent().sync().topic().schema()),
				arguments("fluentSendWithTopicAndSchemaAndCustomizers",
						SendTestArgs.fluent().sync().topic().schema().messageCustomizer().producerCustomizer()),
				arguments("fluentAsyncSend", SendTestArgs.fluent().async()),
				arguments("fluentAsyncSendWithSchema", SendTestArgs.fluent().async().schema()),
				arguments("fluentAsyncSendWithTopic", SendTestArgs.fluent().async().topic()),
				arguments("fluentAsyncSendWithMessageCustomizer", SendTestArgs.fluent().async().messageCustomizer()),
				arguments("fluentAsyncSendWithProducerCustomizer", SendTestArgs.fluent().async().producerCustomizer()),
				arguments("fluentAsyncSendWithTopicAndSchema", SendTestArgs.fluent().async().topic().schema()),
				arguments("fluentAsyncSendWithTopicAndSchemaAndCustomizers",
						SendTestArgs.fluent().async().topic().schema().messageCustomizer().producerCustomizer()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("interceptorInvocationTestProvider")
	void interceptorInvocationTest(String topic, List<ProducerInterceptor> interceptors) throws Exception {
		try (PulsarClient client = PulsarClient.builder().serviceUrl(PulsarTestContainerSupport.getPulsarBrokerUrl())
				.build()) {
			PulsarProducerFactory<String> producerFactory = new DefaultPulsarProducerFactory<>(client,
					Collections.singletonMap("topicName", topic));
			PulsarTemplate<String> pulsarTemplate = new PulsarTemplate<>(producerFactory, interceptors);
			pulsarTemplate.send("test-interceptor");
			for (ProducerInterceptor interceptor : interceptors) {
				verify(interceptor, atLeastOnce()).eligible(any(Message.class));
			}
		}
	}

	private static Stream<Arguments> interceptorInvocationTestProvider() {
		return Stream.of(
				arguments(Named.of("singleInterceptor", "iit-topic-1"),
						Collections.singletonList(mock(ProducerInterceptor.class))),
				arguments(Named.of("multipleInterceptors", "iit-topic-2"),
						List.of(mock(ProducerInterceptor.class), mock(ProducerInterceptor.class))));
	}

	@Test
	void sendMessageWithSpecificSchema() throws Exception {
		String topic = "smt-specific-schema-topic";
		try (PulsarClient client = PulsarClient.builder().serviceUrl(PulsarTestContainerSupport.getPulsarBrokerUrl())
				.build()) {
			try (Consumer<Foo> consumer = client.newConsumer(Schema.JSON(Foo.class)).topic(topic)
					.subscriptionName("smt-specific-schema-subscription").subscribe()) {
				PulsarProducerFactory<Foo> producerFactory = new DefaultPulsarProducerFactory<>(client,
						Collections.singletonMap("topicName", topic));
				PulsarTemplate<Foo> pulsarTemplate = new PulsarTemplate<>(producerFactory);
				Foo foo = new Foo("Foo-" + UUID.randomUUID(), "Bar-" + UUID.randomUUID());
				pulsarTemplate.send(foo, Schema.JSON(Foo.class));
				assertThat(consumer.receiveAsync()).succeedsWithin(Duration.ofSeconds(3)).extracting(Message::getValue)
						.isEqualTo(foo);
			}
		}
	}

	@Test
	void sendMessageWithSpecificSchemaInferredByCustomTypeMappings() throws Exception {
		String topic = "smt-specific-schema-custom-topic";
		try (PulsarClient client = PulsarClient.builder().serviceUrl(PulsarTestContainerSupport.getPulsarBrokerUrl())
				.build()) {
			try (Consumer<Foo> consumer = client.newConsumer(Schema.JSON(Foo.class)).topic(topic)
					.subscriptionName("smt-specific-schema-custom-subscription").subscribe()) {
				PulsarProducerFactory<Foo> producerFactory = new DefaultPulsarProducerFactory<>(client,
						Collections.singletonMap("topicName", topic));
				// Custom schema resolver allows not specifying the schema when sending
				DefaultSchemaResolver schemaResolver = new DefaultSchemaResolver();
				schemaResolver.addCustomSchemaMapping(Foo.class, Schema.JSON(Foo.class));
				PulsarTemplate<Foo> pulsarTemplate = new PulsarTemplate<>(producerFactory, Collections.emptyList(),
						schemaResolver, null, null);

				Foo foo = new Foo("Foo-" + UUID.randomUUID(), "Bar-" + UUID.randomUUID());
				pulsarTemplate.send(foo);
				assertThat(consumer.receiveAsync()).succeedsWithin(Duration.ofSeconds(3)).extracting(Message::getValue)
						.isEqualTo(foo);
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void sendMessageWithEncryptionKeys() throws Exception {
		String topic = "smt-encryption-keys-topic";
		try (PulsarClient client = PulsarClient.builder().serviceUrl(PulsarTestContainerSupport.getPulsarBrokerUrl())
				.build()) {
			PulsarProducerFactory<String> producerFactory = mock(PulsarProducerFactory.class);
			when(producerFactory.createProducer(Schema.STRING, topic, Set.of("key"), new ArrayList<>()))
					.thenReturn(client.newProducer(Schema.STRING).topic(topic).create());
			PulsarTemplate<String> pulsarTemplate = new PulsarTemplate<>(producerFactory);
			pulsarTemplate.newMessage("msg").withTopic(topic).withEncryptionKeys(Set.of("key")).send();
			verify(producerFactory).createProducer(Schema.STRING, topic, Set.of("key"), new ArrayList<>());
		}
	}

	static final class SendTestArgs {

		private final boolean simpleApi;

		private boolean async;

		private boolean explicitTopic;

		private boolean explicitSchema;

		private boolean messageCustomizer;

		private boolean producerCustomizer;

		private SendTestArgs(boolean simpleApi) {
			this.simpleApi = simpleApi;
		}

		static SendTestArgs simple() {
			return new SendTestArgs(true);
		}

		static SendTestArgs fluent() {
			return new SendTestArgs(false);
		}

		SendTestArgs async() {
			this.async = true;
			return this;
		}

		SendTestArgs sync() {
			this.async = false;
			return this;
		}

		SendTestArgs topic() {
			this.explicitTopic = true;
			return this;
		}

		SendTestArgs schema() {
			this.explicitSchema = true;
			return this;
		}

		SendTestArgs messageCustomizer() {
			this.messageCustomizer = true;
			return this;
		}

		SendTestArgs producerCustomizer() {
			this.producerCustomizer = true;
			return this;
		}

	}

	record Foo(String foo, String bar) {
	}

}
