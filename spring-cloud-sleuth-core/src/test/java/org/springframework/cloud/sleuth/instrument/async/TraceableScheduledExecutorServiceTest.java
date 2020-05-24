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

package org.springframework.cloud.sleuth.instrument.async;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import brave.Tracing;
import brave.propagation.StrictCurrentTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.BDDMockito;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.sleuth.SpanNamer;
import org.springframework.cloud.sleuth.internal.DefaultSpanNamer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * @author Marcin Grzejszczak
 */
@ExtendWith(MockitoExtension.class)
public class TraceableScheduledExecutorServiceTest {

	StrictCurrentTraceContext currentTraceContext = StrictCurrentTraceContext.create();

	Tracing tracing = Tracing.newBuilder().currentTraceContext(this.currentTraceContext)
			.build();

	@Mock(lenient = true)
	BeanFactory beanFactory;

	@Mock
	ScheduledExecutorService scheduledExecutorService;

	@InjectMocks
	TraceableScheduledExecutorService traceableScheduledExecutorService;

	@BeforeEach
	public void setup() {
		beanFactory();
	}

	@AfterEach
	public void close() {
		this.tracing.close();
		this.currentTraceContext.close();
	}

	@Test
	public void should_schedule_a_trace_runnable() throws Exception {
		this.traceableScheduledExecutorService.schedule(aRunnable(), 1L, TimeUnit.DAYS);

		then(this.scheduledExecutorService).should().schedule(
				BDDMockito.argThat(
						matcher(Runnable.class, instanceOf(TraceRunnable.class))),
				anyLong(), any(TimeUnit.class));
	}

	@Test
	public void should_schedule_a_trace_callable() throws Exception {
		this.traceableScheduledExecutorService.schedule(aCallable(), 1L, TimeUnit.DAYS);

		then(this.scheduledExecutorService).should().schedule(
				BDDMockito.argThat(
						matcher(Callable.class, instanceOf(TraceCallable.class))),
				anyLong(), any(TimeUnit.class));
	}

	@Test
	public void should_schedule_at_fixed_rate_a_trace_runnable() throws Exception {
		this.traceableScheduledExecutorService.scheduleAtFixedRate(aRunnable(), 1L, 1L,
				TimeUnit.DAYS);

		then(this.scheduledExecutorService).should().scheduleAtFixedRate(
				BDDMockito.argThat(
						matcher(Runnable.class, instanceOf(TraceRunnable.class))),
				anyLong(), anyLong(), any(TimeUnit.class));
	}

	@Test
	public void should_schedule_with_fixed_delay_a_trace_runnable() throws Exception {
		this.traceableScheduledExecutorService.scheduleWithFixedDelay(aRunnable(), 1L, 1L,
				TimeUnit.DAYS);

		then(this.scheduledExecutorService).should().scheduleWithFixedDelay(
				BDDMockito.argThat(
						matcher(Runnable.class, instanceOf(TraceRunnable.class))),
				anyLong(), anyLong(), any(TimeUnit.class));
	}

	@Test
	public void should_not_schedule_a_trace_runnable_when_context_not_ready()
			throws Exception {
		SleuthContextListenerAccessor.set(this.beanFactory, false);
		this.traceableScheduledExecutorService.schedule(aRunnable(), 1L, TimeUnit.DAYS);

		then(this.scheduledExecutorService).should(never()).schedule(
				BDDMockito.argThat(
						matcher(Runnable.class, instanceOf(TraceRunnable.class))),
				anyLong(), any(TimeUnit.class));
	}

	@Test
	public void should_not_schedule_a_trace_callable_when_context_not_ready()
			throws Exception {
		SleuthContextListenerAccessor.set(this.beanFactory, false);
		this.traceableScheduledExecutorService.schedule(aCallable(), 1L, TimeUnit.DAYS);

		then(this.scheduledExecutorService).should(never()).schedule(
				BDDMockito.argThat(
						matcher(Callable.class, instanceOf(TraceCallable.class))),
				anyLong(), any(TimeUnit.class));
	}

	@Test
	public void should_not_schedule_at_fixed_rate_a_trace_runnable_when_context_not_ready()
			throws Exception {
		SleuthContextListenerAccessor.set(this.beanFactory, false);
		this.traceableScheduledExecutorService.scheduleAtFixedRate(aRunnable(), 1L, 1L,
				TimeUnit.DAYS);

		then(this.scheduledExecutorService).should(never()).scheduleAtFixedRate(
				BDDMockito.argThat(
						matcher(Runnable.class, instanceOf(TraceRunnable.class))),
				anyLong(), anyLong(), any(TimeUnit.class));
	}

	@Test
	public void should_not_schedule_with_fixed_delay_a_trace_runnable_when_context_not_ready()
			throws Exception {
		SleuthContextListenerAccessor.set(this.beanFactory, false);
		this.traceableScheduledExecutorService.scheduleWithFixedDelay(aRunnable(), 1L, 1L,
				TimeUnit.DAYS);

		then(this.scheduledExecutorService).should(never()).scheduleWithFixedDelay(
				BDDMockito.argThat(
						matcher(Runnable.class, instanceOf(TraceRunnable.class))),
				anyLong(), anyLong(), any(TimeUnit.class));
	}

	Predicate<Object> instanceOf(Class clazz) {
		return (argument) -> argument.getClass().isAssignableFrom(clazz);
	}

	<T> ArgumentMatcher<T> matcher(Class<T> clazz, Predicate predicate) {
		return predicate::test;
	}

	Runnable aRunnable() {
		return () -> {
		};
	}

	Callable<?> aCallable() {
		return () -> null;
	}

	BeanFactory beanFactory() {
		BDDMockito.given(this.beanFactory.getBean(Tracing.class))
				.willReturn(this.tracing);
		BDDMockito.given(this.beanFactory.getBean(SpanNamer.class))
				.willReturn(new DefaultSpanNamer());
		SleuthContextListenerAccessor.set(this.beanFactory, true);
		return this.beanFactory;
	}

}
