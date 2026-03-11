package com.saga.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.payment.entity.Payment;
import com.saga.payment.enums.PaymentStatus;
import com.saga.payment.event.*;
import com.saga.payment.repository.PaymentRepository;
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
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // ─────────────── STEP 1: Process Payment ───────────────
    @KafkaListener(topics = "order-created", groupId = "payment-service-group")
    @Transactional
    public void handleOrderCreated(String payload) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
            log.info("[PAYMENT-SERVICE] Processing payment for order={}, amount={}", event.getOrderId(), event.getAmount());

            // Business rule: Fail if amount > 1000 (simulate insufficient funds)
            if (event.getAmount() > 1000) {
                Payment payment = buildPayment(event.getOrderId(), event.getAmount(), PaymentStatus.FAILED);
                paymentRepository.save(payment);

                PaymentFailedEvent failedEvent = PaymentFailedEvent.builder()
                        .orderId(event.getOrderId())
                        .reason("Insufficient funds – amount exceeds limit of 1000")
                        .build();
                publishEvent("payment-failed", failedEvent);
                log.warn("[PAYMENT-SERVICE] Payment FAILED for order={}", event.getOrderId());

            } else {
                Payment payment = buildPayment(event.getOrderId(), event.getAmount(), PaymentStatus.COMPLETED);
                paymentRepository.save(payment);

                PaymentCompletedEvent completedEvent = PaymentCompletedEvent.builder()
                        .orderId(event.getOrderId())
                        .paymentId(payment.getId())
                        .amount(payment.getAmount())
                        .quantity(event.getQuantity())
                        .build();
                publishEvent("payment-completed", completedEvent);
                log.info("[PAYMENT-SERVICE] Payment COMPLETED for order={}", event.getOrderId());
            }
        } catch (Exception e) {
            log.error("[PAYMENT-SERVICE] Error processing order-created", e);
        }
    }

    // ─────────────── COMPENSATING: Inventory Failed → Refund ───────────────
    @KafkaListener(topics = "inventory-failed", groupId = "payment-service-group")
    @Transactional
    public void handleInventoryFailed(String payload) {
        try {
            InventoryFailedEvent event = objectMapper.readValue(payload, InventoryFailedEvent.class);
            log.info("[PAYMENT-SERVICE] Inventory failed for order={}, initiating refund", event.getOrderId());
            processRefund(event.getOrderId());
        } catch (Exception e) {
            log.error("[PAYMENT-SERVICE] Error processing inventory-failed", e);
        }
    }

    // ─────────────── COMPENSATING: Inventory Released → Refund ───────────────
    @KafkaListener(topics = "inventory-released", groupId = "payment-service-group")
    @Transactional
    public void handleInventoryReleased(String payload) {
        try {
            InventoryReleasedEvent event = objectMapper.readValue(payload, InventoryReleasedEvent.class);
            log.info("[PAYMENT-SERVICE] Inventory released for order={}, initiating refund", event.getOrderId());
            processRefund(event.getOrderId());
        } catch (Exception e) {
            log.error("[PAYMENT-SERVICE] Error processing inventory-released", e);
        }
    }

    private void processRefund(String orderId) {
        paymentRepository.findByOrderId(orderId).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            PaymentRefundedEvent refundedEvent = PaymentRefundedEvent.builder()
                    .orderId(orderId)
                    .amount(payment.getAmount())
                    .build();
            publishEvent("payment-refunded", refundedEvent);
            log.info("[PAYMENT-SERVICE] Payment REFUNDED for order={}", orderId);
        });
    }

    private Payment buildPayment(String orderId, Double amount, PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID().toString())
                .orderId(orderId)
                .amount(amount)
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void publishEvent(String topic, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, payload);
            log.info("[PAYMENT-SERVICE] Published to '{}': {}", topic, payload);
        } catch (Exception e) {
            log.error("[PAYMENT-SERVICE] Failed to publish to topic '{}'", topic, e);
        }
    }
}
