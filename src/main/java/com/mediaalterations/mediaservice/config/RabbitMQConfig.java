package com.mediaalterations.mediaservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange.process}")
    private String exchange;

    @Value("${rabbitmq.queue.process}")
    private String queue;

    @Value("${rabbitmq.queue.process.routingKey}")
    private String routingKey;

    @Bean
    public Queue orderQueue(){
        //a durable queue is a queue whose metadata is stored on disk and that will survive a broker (server) restart
        return QueueBuilder.durable(queue).build();
    }
    //FanoutExchange, DirectExchange, HeadersExchange
    @Bean
    public TopicExchange orderExchange(){
        return new TopicExchange(exchange);
    }

    @Bean
    public MessageConverter jsonMessageConverter(){
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public Binding binding(){
        return BindingBuilder
                .bind(orderQueue())
                .to(orderExchange())
                .with(routingKey);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(final ConnectionFactory connectionFactory){
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }

}
