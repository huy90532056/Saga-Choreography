package com.saga.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.inventory.entity.InventoryReservation;
import com.saga.inventory.enums.ReservationStatus;
import com.saga.inventory.event.*;
import com.saga.inventory.repository.InventoryReservationRepository;
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
public class InventoryService {

    private final InventoryReservationRepository reservationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // ─────────────── STEP 2: Reserve Inventory ───────────────
    @KafkaListener(topics = "payment-completed", groupId = "inventory-service-group")
    @Transactional
    public void handlePaymentCompleted(String payload) {
        try {
            PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);
            log.info("[INVENTORY-SERVICE] Reserving inventory for order={}, quantity={}", event.getOrderId(), event.getQuantity());

            // Business rule: Fail if quantity > 50 (simulate out of stock)
            if (event.getQuantity() > 50) {
                InventoryReservation reservation = buildReservation(event.getOrderId(), event.getQuantity(), ReservationStatus.FAILED);
                reservationRepository.save(reservation);

                InventoryFailedEvent failedEvent = InventoryFailedEvent.builder()
                        .orderId(event.getOrderId())
                        .reason("Out of stock – quantity exceeds available stock of 50")
                        .build();
                publishEvent("inventory-failed", failedEvent);
                log.warn("[INVENTORY-SERVICE] Inventory reservation FAILED for order={}", event.getOrderId());

            } else {
                InventoryReservation reservation = buildReservation(event.getOrderId(), event.getQuantity(), ReservationStatus.RESERVED);
                reservationRepository.save(reservation);

                InventoryReservedEvent reservedEvent = InventoryReservedEvent.builder()
                        .orderId(event.getOrderId())
                        .reservationId(reservation.getId())
                        .quantity(event.getQuantity())
                        .build();
                publishEvent("inventory-reserved", reservedEvent);
                log.info("[INVENTORY-SERVICE] Inventory RESERVED for order={}", event.getOrderId());
            }
        } catch (Exception e) {
            log.error("[INVENTORY-SERVICE] Error processing payment-completed", e);
        }
    }

    // ─────────────── COMPENSATING: Shipment Failed → Release Inventory ───────────────
    @KafkaListener(topics = "shipment-failed", groupId = "inventory-service-group")
    @Transactional
    public void handleShipmentFailed(String payload) {
        try {
            ShipmentFailedEvent event = objectMapper.readValue(payload, ShipmentFailedEvent.class);
            log.info("[INVENTORY-SERVICE] Shipment failed for order={}, releasing inventory", event.getOrderId());

            reservationRepository.findByOrderId(event.getOrderId()).ifPresent(reservation -> {
                reservation.setStatus(ReservationStatus.RELEASED);
                reservation.setUpdatedAt(LocalDateTime.now());
                reservationRepository.save(reservation);

                InventoryReleasedEvent releasedEvent = InventoryReleasedEvent.builder()
                        .orderId(event.getOrderId())
                        .quantity(reservation.getQuantity())
                        .build();
                publishEvent("inventory-released", releasedEvent);
                log.info("[INVENTORY-SERVICE] Inventory RELEASED for order={}", event.getOrderId());
            });
        } catch (Exception e) {
            log.error("[INVENTORY-SERVICE] Error processing shipment-failed", e);
        }
    }

    private InventoryReservation buildReservation(String orderId, Integer quantity, ReservationStatus status) {
        return InventoryReservation.builder()
                .id(UUID.randomUUID().toString())
                .orderId(orderId)
                .quantity(quantity)
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void publishEvent(String topic, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, payload);
            log.info("[INVENTORY-SERVICE] Published to '{}': {}", topic, payload);
        } catch (Exception e) {
            log.error("[INVENTORY-SERVICE] Failed to publish to topic '{}'", topic, e);
        }
    }
}
