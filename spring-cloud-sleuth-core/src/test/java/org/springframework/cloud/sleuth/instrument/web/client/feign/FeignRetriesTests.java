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

package org.springframework.cloud.sleuth.instrument.web.client.feign;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import brave.Span;
import brave.Tracing;
import brave.http.HttpTracing;
import brave.propagation.StrictCurrentTraceContext;
import brave.test.TestSpanHandler;
import feign.Client;
import feign.Feign;
import feign.FeignException;
import feign.Request;
import feign.RequestLine;
import feign.RequestTemplate;
import feign.Response;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.beans.factory.BeanFactory;

import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.assertj.core.api.BDDAssertions.then;

/**
 * @author Marcin Grzejszczak
 */
@ExtendWith(MockitoExtension.class)
public class FeignRetriesTests {

	public final MockWebServer server = new MockWebServer();

	@BeforeEach
	void before() throws IOException {
		this.server.start();
	}

	@AfterEach
	void after() throws IOException {
		this.server.close();
	}

	@Mock(lenient = true)
	BeanFactory beanFactory;

	StrictCurrentTraceContext currentTraceContext = StrictCurrentTraceContext.create();

	TestSpanHandler spans = new TestSpanHandler();

	Tracing tracing = Tracing.newBuilder().currentTraceContext(this.currentTraceContext)
			.addSpanHandler(this.spans).build();

	HttpTracing httpTracing = HttpTracing.newBuilder(this.tracing).build();

	@BeforeEach
	@AfterEach
	public void setup() {
		BDDMockito.given(this.beanFactory.getBean(HttpTracing.class))
				.willReturn(this.httpTracing);
	}

	@AfterEach
	public void close() {
		this.tracing.close();
		this.currentTraceContext.close();
	}

	@Test
	public void testRetriedWhenExceededNumberOfRetries() {
		Client client = (request, options) -> {
			throw new IOException();
		};
		String url = "http://localhost:" + this.server.getPort();

		TestInterface api = Feign.builder()
				.client(new TracingFeignClient(this.httpTracing, client))
				.target(TestInterface.class, url);

		try {
			api.decodedPost();
			failBecauseExceptionWasNotThrown(FeignException.class);
		}
		catch (FeignException e) {
		}
	}

	@Test
	public void testRetriedWhenRequestEventuallyIsSent() {
		String url = "http://localhost:" + this.server.getPort();
		final AtomicInteger atomicInteger = new AtomicInteger();
		// Client to simulate a retry scenario
		final Client client = (request, options) -> {
			// we simulate an exception only for the first request
			if (atomicInteger.get() == 1) {
				throw new IOException();
			}
			else {
				// with the second retry (first retry) we send back good result
				return Response.builder().status(200).reason("OK")
						.headers(new HashMap<>()).body("OK", Charset.defaultCharset())
						.request(Request.create(Request.HttpMethod.POST, "/foo",
								new HashMap<>(), Request.Body.empty(),
								new RequestTemplate()))
						.build();
			}
		};
		TestInterface api = Feign.builder()
				.client(new TracingFeignClient(this.httpTracing, (request, options) -> {
					atomicInteger.incrementAndGet();
					return client.execute(request, options);
				})).target(TestInterface.class, url);

		then(api.decodedPost()).isEqualTo("OK");
		// request interception should take place only twice (1st request & 2nd retry)
		then(atomicInteger.get()).isEqualTo(2);
		then(this.spans.get(0).error()).isInstanceOf(IOException.class);
		then(this.spans.get(1).kind()).isEqualTo(Span.Kind.CLIENT);
	}

	interface TestInterface {

		@RequestLine("POST /")
		String decodedPost();

	}

}
