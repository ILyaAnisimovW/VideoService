package com.videoservice.processing;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;

@Configuration
@EnableRabbit
public class RabbitProcessingConfig {
    public static final String EXCHANGE = "video.processing";
    public static final String JOBS = "processing.jobs";
    public static final String RESULTS = "processing.results";
    public static final String DEAD_EXCHANGE = "video.processing.dead";

    @Bean
    DirectExchange processingExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue processingJobs() {
        return QueueBuilder.durable(JOBS).deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey("processing.jobs.dead").build();
    }

    @Bean
    Queue processingResults() {
        return QueueBuilder.durable(RESULTS).deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey("processing.results.dead").build();
    }

    @Bean
    DirectExchange processingDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    Queue processingJobsDead() { return new Queue("processing.jobs.dead", true); }

    @Bean
    Queue processingResultsDead() { return new Queue("processing.results.dead", true); }

    @Bean
    Binding jobsDeadBinding(Queue processingJobsDead, DirectExchange processingDeadExchange) {
        return BindingBuilder.bind(processingJobsDead).to(processingDeadExchange).with("processing.jobs.dead");
    }

    @Bean
    Binding resultsDeadBinding(Queue processingResultsDead, DirectExchange processingDeadExchange) {
        return BindingBuilder.bind(processingResultsDead).to(processingDeadExchange).with("processing.results.dead");
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(4)
                .backOffOptions(1000, 2.0, 5000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }

    @Bean
    Binding requestedBinding(Queue processingJobs, DirectExchange processingExchange) {
        return BindingBuilder.bind(processingJobs).to(processingExchange).with("processing.requested");
    }

    @Bean
    Binding succeededBinding(Queue processingResults, DirectExchange processingExchange) {
        return BindingBuilder.bind(processingResults).to(processingExchange).with("processing.succeeded");
    }

    @Bean
    Binding failedBinding(Queue processingResults, DirectExchange processingExchange) {
        return BindingBuilder.bind(processingResults).to(processingExchange).with("processing.failed");
    }

    @Bean
    RabbitTemplateCustomizer mandatoryRouting() {
        return template -> template.setMandatory(true);
    }
}
