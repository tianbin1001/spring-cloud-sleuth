/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.sleuth.autoconfig;

import java.util.ArrayList;
import java.util.List;

import brave.Tracing;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.baggage.BaggagePropagationCustomizer;
import brave.handler.SpanHandler;
import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.RateLimitingSampler;
import brave.sampler.Sampler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.api.BDDAssertions;
import org.junit.jupiter.api.Test;
import zipkin2.reporter.InMemoryReporterMetrics;
import zipkin2.reporter.Reporter;
import zipkin2.reporter.ReporterMetrics;
import zipkin2.reporter.brave.ZipkinSpanHandler;
import zipkin2.reporter.metrics.micrometer.MicrometerReporterMetrics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.cloud.sleuth.autoconfig.TraceAutoConfiguration.SPAN_HANDLER_COMPARATOR;

public class TraceAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class));

	@Test
	void span_handler_comparator() {
		SpanHandler handler1 = mock(SpanHandler.class);
		SpanHandler handler2 = mock(SpanHandler.class);
		ZipkinSpanHandler zipkin1 = mock(ZipkinSpanHandler.class);
		ZipkinSpanHandler zipkin2 = mock(ZipkinSpanHandler.class);

		ArrayList<SpanHandler> spanHandlers = new ArrayList<>();
		spanHandlers.add(handler1);
		spanHandlers.add(zipkin1);
		spanHandlers.add(handler2);
		spanHandlers.add(zipkin2);

		spanHandlers.sort(SPAN_HANDLER_COMPARATOR);

		assertThat(spanHandlers).containsExactly(handler1, handler2, zipkin1, zipkin2);
	}

	@Test
	void should_apply_micrometer_reporter_metrics_when_meter_registry_bean_present() {
		this.contextRunner.withUserConfiguration(WithMeterRegistry.class)
				.run((context) -> {
					ReporterMetrics bean = context.getBean(ReporterMetrics.class);

					BDDAssertions.then(bean)
							.isInstanceOf(MicrometerReporterMetrics.class);
				});
	}

	@Test
	void should_apply_in_memory_metrics_when_meter_registry_bean_missing() {
		this.contextRunner.run((context) -> {
			ReporterMetrics bean = context.getBean(ReporterMetrics.class);

			BDDAssertions.then(bean).isInstanceOf(InMemoryReporterMetrics.class);
		});
	}

	@Test
	void should_apply_in_memory_metrics_when_meter_registry_class_missing() {
		this.contextRunner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
				.run((context) -> {
					ReporterMetrics bean = context.getBean(ReporterMetrics.class);

					BDDAssertions.then(bean).isInstanceOf(InMemoryReporterMetrics.class);
				});
	}

	/**
	 * Duplicates
	 * {@link org.springframework.cloud.sleuth.sampler.SamplerAutoConfigurationTests}
	 * intentionally, to ensure configuration condition bugs do not exist.
	 */
	@Test
	void should_use_NEVER_SAMPLER_when_only_logging() {
		this.contextRunner.run((context -> {
			final Sampler bean = context.getBean(Sampler.class);
			BDDAssertions.then(bean).isSameAs(Sampler.NEVER_SAMPLE);
		}));
	}

	/**
	 * Duplicates
	 * {@link org.springframework.cloud.sleuth.sampler.SamplerAutoConfigurationTests}
	 * intentionally, to ensure configuration condition bugs do not exist.
	 */
	@Test
	void should_use_RateLimitedSampler_when_reporting() {
		this.contextRunner.withUserConfiguration(WithReporter.class).run((context -> {
			final Sampler bean = context.getBean(Sampler.class);
			BDDAssertions.then(bean).isInstanceOf(RateLimitingSampler.class);
		}));
	}

	/**
	 * Duplicates
	 * {@link org.springframework.cloud.sleuth.sampler.SamplerAutoConfigurationTests}
	 * intentionally, to ensure configuration condition bugs do not exist.
	 */
	@Test
	void should_override_sampler() {
		this.contextRunner.withUserConfiguration(WithReporter.class, WithSampler.class)
				.run((context -> {
					final Sampler bean = context.getBean(Sampler.class);
					BDDAssertions.then(bean).isSameAs(Sampler.ALWAYS_SAMPLE);
				}));
	}

	@Test
	void should_use_B3Propagation_factory_by_default() {
		this.contextRunner.run((context -> {
			final Propagation.Factory bean = context.getBean(Propagation.Factory.class);
			BDDAssertions.then(bean).isInstanceOf(Propagation.Factory.class);
		}));
	}

	@Test
	void should_use_baggageBean() {
		this.contextRunner.withUserConfiguration(WithBaggageBeans.class, Baggage.class)
				.run((context -> {
					final Baggage bean = context.getBean(Baggage.class);
					BDDAssertions.then(bean.fields).containsOnly(
							BaggageField.create("country-code"),
							BaggageField.create("x-vcap-request-id"));
				}));
	}

	@Test
	void should_use_local_keys_from_properties() {
		this.contextRunner.withPropertyValues("spring.sleuth.baggage.local-fields=bp")
				.withUserConfiguration(Baggage.class).run((context -> {
					final Baggage bean = context.getBean(Baggage.class);
					BDDAssertions.then(bean.fields)
							.containsExactly(BaggageField.create("bp"));
				}));
	}

	@Test
	void should_combine_baggage_beans_and_properties() {
		this.contextRunner.withPropertyValues("spring.sleuth.baggage.local-fields=bp")
				.withUserConfiguration(WithBaggageBeans.class, Baggage.class)
				.run((context -> {
					final Baggage bean = context.getBean(Baggage.class);
					BDDAssertions.then(bean.fields).containsOnly(
							BaggageField.create("country-code"),
							BaggageField.create("x-vcap-request-id"),
							BaggageField.create("bp"));
				}));
	}

	@Test
	void should_use_baggagePropagationFactoryBuilder_bean() {
		// BaggagePropagation.FactoryBuilder unwraps itself if there are no baggage fields
		// defined
		this.contextRunner
				.withUserConfiguration(WithBaggagePropagationFactoryBuilderBean.class)
				.run((context -> BDDAssertions
						.then(context.getBean(Propagation.Factory.class))
						.isSameAs(B3SinglePropagation.FACTORY)));
	}

	@Configuration
	static class Baggage {

		List<BaggageField> fields;

		@Autowired
		Baggage(Tracing tracing) {
			// When predefined baggage fields exist, the result !=
			// TraceContextOrSamplingFlags.EMPTY
			TraceContextOrSamplingFlags emptyExtraction = tracing.propagation()
					.extractor((c, k) -> null).extract(Boolean.TRUE);
			fields = BaggageField.getAll(emptyExtraction);
		}

	}

	@Configuration
	static class WithBaggageBeans {

		@Bean
		BaggagePropagationCustomizer countryCode() {
			return fb -> fb
					.add(SingleBaggageField.remote(BaggageField.create("country-code")));
		}

		@Bean
		BaggagePropagationCustomizer requestId() {
			return fb -> fb.add(
					SingleBaggageField.remote(BaggageField.create("x-vcap-request-id")));
		}

	}

	@Configuration
	static class WithMeterRegistry {

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

	}

	@Configuration
	static class WithReporter {

		@Bean
		Reporter<zipkin2.Span> spanReporter() {
			return zipkin2.Span::toString;
		}

	}

	@Configuration
	static class WithSampler {

		@Bean
		Sampler alwaysSampler() {
			return Sampler.ALWAYS_SAMPLE;
		}

	}

	@Configuration
	static class WithLocalKeys {

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

	}

	@Configuration
	static class WithBaggagePropagationFactoryBuilderBean {

		@Bean
		BaggagePropagation.FactoryBuilder baggagePropagationFactoryBuilderBean() {
			return BaggagePropagation.newFactoryBuilder(B3SinglePropagation.FACTORY);
		}

	}

}