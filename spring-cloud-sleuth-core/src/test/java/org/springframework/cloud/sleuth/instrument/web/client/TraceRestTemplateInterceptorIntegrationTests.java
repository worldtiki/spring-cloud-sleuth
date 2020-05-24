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

package org.springframework.cloud.sleuth.instrument.web.client;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.http.HttpTracing;
import brave.propagation.StrictCurrentTraceContext;
import brave.spring.web.TracingClientHttpRequestInterceptor;
import brave.test.TestSpanHandler;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.assertj.core.api.BDDAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.fail;

/**
 * @author Marcin Grzejszczak
 */
public class TraceRestTemplateInterceptorIntegrationTests {

	public final MockWebServer mockWebServer = new MockWebServer();

	@BeforeEach
	void before() throws IOException {
		mockWebServer.start();
	}

	@AfterEach
	void after() throws IOException {
		mockWebServer.close();
	}

	StrictCurrentTraceContext currentTraceContext = StrictCurrentTraceContext.create();

	TestSpanHandler spans = new TestSpanHandler();

	Tracing tracing = Tracing.newBuilder().currentTraceContext(this.currentTraceContext)
			.addSpanHandler(this.spans).build();

	Tracer tracer = this.tracing.tracer();

	private RestTemplate template = new RestTemplate(clientHttpRequestFactory());

	@BeforeEach
	public void setup() {
		this.template.setInterceptors(Arrays
				.<ClientHttpRequestInterceptor>asList(TracingClientHttpRequestInterceptor
						.create(HttpTracing.create(this.tracing))));
	}

	@AfterEach
	public void clean() {
		this.tracing.close();
		this.currentTraceContext.close();
	}

	// Issue #198
	@Test
	public void spanRemovedFromThreadUponException() throws IOException {
		this.mockWebServer.enqueue(
				new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
		Span span = this.tracer.nextSpan().name("new trace");

		try (Tracer.SpanInScope ws = this.tracer.withSpanInScope(span.start())) {
			this.template.getForEntity(
					"http://localhost:" + this.mockWebServer.getPort() + "/exception",
					Map.class).getBody();
			fail("should throw an exception");
		}
		catch (RuntimeException e) {
			BDDAssertions.then(e).hasRootCauseInstanceOf(IOException.class);
		}
		finally {
			span.finish();
		}

		// 1 span "new race", 1 span "rest template"
		BDDAssertions.then(this.spans).hasSize(2);
		MutableSpan span1 = this.spans.get(0);
		BDDAssertions.then(span1.error()).hasMessage("Read timed out");
		BDDAssertions.then(span1.kind()).isEqualTo(Span.Kind.CLIENT);
	}

	private ClientHttpRequestFactory clientHttpRequestFactory() {
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
		factory.setReadTimeout(100);
		factory.setConnectTimeout(100);
		return factory;
	}

}
