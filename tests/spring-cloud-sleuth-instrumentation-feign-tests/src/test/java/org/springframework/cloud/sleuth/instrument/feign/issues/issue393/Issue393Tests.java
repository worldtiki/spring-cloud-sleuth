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

package org.springframework.cloud.sleuth.instrument.feign.issues.issue393;

import java.util.stream.Collectors;

import brave.Tracing;
import brave.handler.SpanHandler;
import brave.sampler.Sampler;
import brave.test.TestSpanHandler;
import feign.okhttp.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.BDDAssertions.then;

@FeignClient(name = "no-name", url = "http://localhost:9978")
interface MyNameRemote {

	@RequestMapping(value = "/name/{id}", method = RequestMethod.GET)
	String getName(@PathVariable("id") String id);

}

/**
 * @author Marcin Grzejszczak
 */

@SpringBootTest(classes = Application.class,
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(
		properties = { "spring.application.name=demo-feign-uri", "server.port=9978" })
public class Issue393Tests {

	RestTemplate template = new RestTemplate();

	@Autowired
	Tracing tracer;

	@Autowired
	TestSpanHandler spans;

	@BeforeEach
	public void open() {
		this.spans.clear();
	}

	@Test
	public void should_successfully_work_when_service_discovery_is_on_classpath_and_feign_uses_url() {
		String url = "http://localhost:9978/hello/mikesarver";

		ResponseEntity<String> response = this.template.getForEntity(url, String.class);

		then(response.getBody()).isEqualTo("mikesarver foo");
		// retries
		then(this.spans).hasSize(2);
		then(this.spans.spans().stream().map(span -> span.tags().get("http.path"))
				.collect(Collectors.toList())).containsOnly("/name/mikesarver");
	}

}

@Configuration
@EnableAutoConfiguration(
		// spring boot test will otherwise instrument the client and server with the
		// same bean factory which isn't expected
		excludeName = "org.springframework.cloud.sleuth.instrument.web.TraceWebServletAutoConfiguration")
@EnableFeignClients
@EnableDiscoveryClient
class Application {

	@Bean
	public DemoController demoController(MyNameRemote myNameRemote) {
		return new DemoController(myNameRemote);
	}

	// issue #513
	@Bean
	public OkHttpClient myOkHttpClient() {
		return new OkHttpClient();
	}

	@Bean
	public feign.Logger.Level feignLoggerLevel() {
		return feign.Logger.Level.BASIC;
	}

	@Bean
	public Sampler defaultSampler() {
		return Sampler.ALWAYS_SAMPLE;
	}

	@Bean
	public SpanHandler testSpanHandler() {
		return new TestSpanHandler();
	}

}

@RestController
class DemoController {

	private final MyNameRemote myNameRemote;

	DemoController(MyNameRemote myNameRemote) {
		this.myNameRemote = myNameRemote;
	}

	@RequestMapping("/hello/{name}")
	public String getHello(@PathVariable("name") String name) {
		return this.myNameRemote.getName(name) + " foo";
	}

	@RequestMapping("/name/{name}")
	public String getName(@PathVariable("name") String name) {
		return name;
	}

}
