package com.videoservice.worker;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class WorkerRabbitConfig {
    static final String EXCHANGE = "video.processing";
    static final String JOBS = "processing.jobs";
    static final String DEAD_EXCHANGE = "video.processing.dead";

    @Bean DirectExchange processingExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean DirectExchange processingDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }
    @Bean Queue processingJobs() {
        return QueueBuilder.durable(JOBS).deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey("processing.jobs.dead").build();
    }
    @Bean Queue processingJobsDead() { return new Queue("processing.jobs.dead", true); }
    @Bean Binding processingJobsBinding(Queue processingJobs, DirectExchange processingExchange) {
        return BindingBuilder.bind(processingJobs).to(processingExchange).with("processing.requested");
    }
    @Bean Binding processingJobsDeadBinding(Queue processingJobsDead, DirectExchange processingDeadExchange) {
        return BindingBuilder.bind(processingJobsDead).to(processingDeadExchange).with("processing.jobs.dead");
    }
    @Bean RabbitTemplateCustomizer mandatoryRouting() { return template -> template.setMandatory(true); }
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
