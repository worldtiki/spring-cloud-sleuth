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

package org.springframework.cloud.sleuth.instrument.web;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.actuate.endpoint.EndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.cloud.sleuth.autoconfig.TraceAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * @author Marcin Grzejszczak
 */
public class SkipPatternProviderConfigTest {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					DispatcherServletAutoConfiguration.class,
					InfoEndpointAutoConfiguration.class,
					HealthEndpointAutoConfiguration.class,
					EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class,
					TraceAutoConfiguration.class, SkipPatternConfiguration.class));

	@Test
	public void should_return_null_when_cleared() throws Exception {
		contextRunner.withPropertyValues("spring.sleuth.web.skip-pattern")
				.run(context -> then(context.getBean("sleuthSkipPatternProvider"))
						.hasToString("null"));
	}

	@Test
	public void should_pick_skip_pattern_from_sleuth_properties() throws Exception {
		contextRunner.withPropertyValues("spring.sleuth.web.skip-pattern=foo.*|bar.*")
				.run(context -> {
					final String pattern = extractPattern(context);
					then(pattern).isEqualTo("foo.*|bar.*");
				});
	}

	@Test
	public void should_combine_skip_pattern_and_additional_pattern_when_all_are_not_empty() {
		contextRunner
				.withPropertyValues("spring.sleuth.web.skip-pattern=foo.*|bar.*",
						"spring.sleuth.web.additional-skip-pattern=baz.*|faz.*")
				.run(context -> {
					final String pattern = extractPattern(context);
					then(pattern).isEqualTo("foo.*|bar.*|baz.*|faz.*");
				});
	}

	@Test
	public void should_return_empty_when_management_context_has_no_context_path()
			throws Exception {
		Optional<Pattern> pattern = new SkipPatternConfiguration.ManagementSkipPatternProviderConfig()
				.skipPatternForManagementServerProperties(
						new ManagementServerProperties())
				.skipPattern();

		then(pattern).isEmpty();
	}

	@Test
	public void should_return_management_context_with_context_path() throws Exception {
		contextRunner
				.withConfiguration(
						UserConfigurations.of(ManagementContextAutoConfiguration.class,
								ServerPropertiesConfig.class))
				.withPropertyValues("management.server.servlet.context-path=foo")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"/actuator(/|/(health|health/.*|info|info/.*))?", "foo.*",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_empty_when_no_endpoints() {
		EndpointsSupplier<ExposableWebEndpoint> endpointsSupplier = Collections::emptyList;
		Optional<Pattern> pattern = new SkipPatternConfiguration.ActuatorSkipPatternProviderConfig()
				.skipPatternForActuatorEndpointsSamePort(new ServerProperties(),
						new WebEndpointProperties(), endpointsSupplier)
				.skipPattern();

		then(pattern).isEmpty();
	}

	@Test
	public void should_return_endpoints_without_context_path() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"/actuator(/|/(health|health/.*|info|info/.*))?",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_with_context_path() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("server.servlet.context-path=foo").run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"foo/actuator(/|/(health|health/.*|info|info/.*))?",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_without_context_path_and_base_path_set_to_root() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("management.endpoints.web.base-path=/")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"/(health|health/.*|info|info/.*)",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_with_context_path_and_base_path_set_to_root() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("management.endpoints.web.base-path=/",
						"server.servlet.context-path=foo")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"foo(/|/(health|health/.*|info|info/.*))?",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_with_context_path_and_base_path_set_to_root_different_port() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("management.endpoints.web.base-path=/",
						"management.server.port=0", "server.servlet.context-path=foo")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"/(health|health/.*|info|info/.*)",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_with_actuator_context_path_only() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("management.endpoints.web.base-path=/mgt",
						"server.servlet.context-path=foo")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"foo/mgt(/|/(health|health/.*|info|info/.*))?",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_with_actuator_default_context_path_different_port() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("management.server.port=0",
						"server.servlet.context-path=foo")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"/actuator(/|/(health|health/.*|info|info/.*))?",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_with_actuator_context_path_only_different_port() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("management.endpoints.web.base-path=/mgt",
						"management.server.port=0", "server.servlet.context-path=foo")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"/mgt(/|/(health|health/.*|info|info/.*))?",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_return_endpoints_with_context_path_different_port() {
		contextRunner
				.withConfiguration(UserConfigurations.of(ServerPropertiesConfig.class))
				.withPropertyValues("management.server.port=0",
						"server.servlet.context-path=foo")
				.run(context -> {
					then(extractAllPatterns(context)).containsExactlyInAnyOrder(
							"/actuator(/|/(health|health/.*|info|info/.*))?",
							SleuthWebProperties.DEFAULT_SKIP_PATTERN);
				});
	}

	@Test
	public void should_combine_skip_patterns_from_list() throws Exception {
		SkipPatternConfiguration configuration = new SkipPatternConfiguration();
		configuration.patterns.addAll(Arrays.asList(foo(), bar()));

		Pattern pattern = configuration.sleuthSkipPatternProvider().skipPattern();

		then(pattern.pattern()).isEqualTo("foo|bar");
	}

	private SingleSkipPattern foo() {
		return () -> Optional.of(Pattern.compile("foo"));
	}

	private SingleSkipPattern bar() {
		return () -> Optional.of(Pattern.compile("bar"));
	}

	/**
	 * Extracts the patterns from pattern provider
	 */
	private String extractPattern(ApplicationContext context) {
		SkipPatternProvider skipPatternProvider = context
				.getBean(SkipPatternProvider.class);
		return skipPatternProvider.skipPattern().pattern();
	}

	/**
	 * Extracts all single patterns
	 */
	private Collection<String> extractAllPatterns(ApplicationContext context) {
		return context.getBeansOfType(SingleSkipPattern.class).values().stream()
				.map(SingleSkipPattern::skipPattern).filter(Optional::isPresent)
				.map(Optional::get).map(Pattern::pattern).collect(Collectors.toList());
	}

	@Configuration
	@EnableConfigurationProperties(ServerProperties.class)
	static class ServerPropertiesConfig {

	}

}
