package com.saga.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.order.dto.CreateOrderRequest;
import com.saga.order.entity.Order;
import com.saga.order.enums.OrderStatus;
import com.saga.order.event.*;
import com.saga.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        Order order = Order.builder()
                .id(UUID.randomUUID().toString())
                .customerId(request.getCustomerId())
                .amount(request.getAmount())
                .quantity(request.getQuantity())
                .status(OrderStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        orderRepository.save(order);
        log.info("[ORDER-SERVICE] Order created: id={}, amount={}, quantity={}",
                order.getId(), order.getAmount(), order.getQuantity());

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .amount(order.getAmount())
                .quantity(order.getQuantity())
                .build();

        publishEvent("order-created", event);
        return order;
    }

    // ─────────────── COMPENSATING: Payment Failed ───────────────
    @KafkaListener(topics = "payment-failed", groupId = "order-service-group")
    @Transactional
    public void handlePaymentFailed(String payload) {
        try {
            PaymentFailedEvent event = objectMapper.readValue(payload, PaymentFailedEvent.class);
            log.warn("[ORDER-SERVICE] Payment failed for order={}, reason={}", event.getOrderId(), event.getReason());
            updateOrderStatus(event.getOrderId(), OrderStatus.CANCELLED);
        } catch (Exception e) {
            log.error("[ORDER-SERVICE] Error processing payment-failed", e);
        }
    }

    // ─────────────── COMPENSATING: Payment Refunded (after inventory/shipment fail) ───────────────
    @KafkaListener(topics = "payment-refunded", groupId = "order-service-group")
    @Transactional
    public void handlePaymentRefunded(String payload) {
        try {
            PaymentRefundedEvent event = objectMapper.readValue(payload, PaymentRefundedEvent.class);
            log.info("[ORDER-SERVICE] Payment refunded for order={}, cancelling order", event.getOrderId());
            updateOrderStatus(event.getOrderId(), OrderStatus.CANCELLED);
        } catch (Exception e) {
            log.error("[ORDER-SERVICE] Error processing payment-refunded", e);
        }
    }

    // ─────────────── SUCCESS: Shipment Created ───────────────
    @KafkaListener(topics = "shipment-created", groupId = "order-service-group")
    @Transactional
    public void handleShipmentCreated(String payload) {
        try {
            ShipmentCreatedEvent event = objectMapper.readValue(payload, ShipmentCreatedEvent.class);
            log.info("[ORDER-SERVICE] Shipment created for order={}, tracking={}", event.getOrderId(), event.getTrackingNumber());
            updateOrderStatus(event.getOrderId(), OrderStatus.COMPLETED);
        } catch (Exception e) {
            log.error("[ORDER-SERVICE] Error processing shipment-created", e);
        }
    }

    private void updateOrderStatus(String orderId, OrderStatus status) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(status);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);
            log.info("[ORDER-SERVICE] Order {} status → {}", orderId, status);
        });
    }

    private void publishEvent(String topic, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, payload);
            log.info("[ORDER-SERVICE] Published to '{}': {}", topic, payload);
        } catch (Exception e) {
            log.error("[ORDER-SERVICE] Failed to publish to topic '{}'", topic, e);
        }
    }
}
