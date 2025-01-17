/*
 * Copyright 2023 the original author or authors.
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

package org.springframework.pulsar.spring.cloud.stream.binder.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link ConfigurationProperties} for Pulsar binder configuration.
 *
 * @author Soby Chacko
 */
@ConfigurationProperties(prefix = "spring.cloud.stream.pulsar.binder")
public class PulsarBinderConfigurationProperties {

	private int partitionCount = 1;

	public int partitionCount() {
		return this.partitionCount;
	}

	public void setPartitionCount(int numberOfPartitions) {
		this.partitionCount = numberOfPartitions;
	}

}
