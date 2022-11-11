package me.geales.spring.trace.logging;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.tracing.Tracer;
import reactor.core.observability.DefaultSignalListener;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;

@SpringBootApplication
@RestController
public class TraceLoggingTest {
    private static final Logger logger = LoggerFactory.getLogger(TraceLoggingTest.class);
    private final Tracer tracer;

    public TraceLoggingTest(Tracer tracer) {
        this.tracer = tracer;
    }

    public static void main(String[] args) {
        SpringApplication.run(TraceLoggingTest.class, args);
    }

    @ConditionalOnClass({ContextSnapshot.class, Hooks.class})
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @Bean
    public ApplicationListener<ContextRefreshedEvent> reactiveObservableHook() {
        return event -> Hooks.onEachOperator(
                ObservationContextSnapshotLifter.class.getSimpleName(),
                Operators.lift(ObservationContextSnapshotLifter.lifter()));
    }

    @GetMapping("/")
    public String helloWorld() {
        var msg = "hello, world!";
        logger.info(msg);
        return msg;
    }

    @GetMapping("/mono")
    public Mono<String> helloWorldMono() {
        var returnValue = Mono
                .just("hello, world!")
                .transformDeferredContextual((stringMono, contextView) -> {
                    logger.info("hello, world! {}", tracer.currentTraceContext().context().spanId());
                    return stringMono
                            .delayElement(Duration.ofMillis(1000))
                            .transformDeferredContextual((stringMono1, contextView1) -> {
                                logger.info("hello, nested World!");
                                return stringMono1;
                            });
                });
        return returnValue.tap(contextView -> new DefaultSignalListener<>() {
            @Override
            public void doOnNext(String value) throws Throwable {
                logger.info("hello tapped World! message: {}", value);
                super.doOnNext(value);
            }
        });
    }

    @GetMapping("/exception")
    public String exception() {
        var msg = "exception!";
        logger.info(msg);
        throw new RuntimeException(msg);
    }
}
