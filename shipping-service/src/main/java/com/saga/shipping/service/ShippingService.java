package com.saga.shipping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.shipping.entity.Shipment;
import com.saga.shipping.enums.ShipmentStatus;
import com.saga.shipping.event.*;
import com.saga.shipping.repository.ShipmentRepository;
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
public class ShippingService {

    private final ShipmentRepository shipmentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // ─────────────── STEP 3: Create Shipment ───────────────
    @KafkaListener(topics = "inventory-reserved", groupId = "shipping-service-group")
    @Transactional
    public void handleInventoryReserved(String payload) {
        try {
            InventoryReservedEvent event = objectMapper.readValue(payload, InventoryReservedEvent.class);
            log.info("[SHIPPING-SERVICE] Creating shipment for order={}", event.getOrderId());

            String trackingNumber = "TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            Shipment shipment = Shipment.builder()
                    .id(UUID.randomUUID().toString())
                    .orderId(event.getOrderId())
                    .trackingNumber(trackingNumber)
                    .status(ShipmentStatus.CREATED)
                    .createdAt(LocalDateTime.now())
                    .build();
            shipmentRepository.save(shipment);

            ShipmentCreatedEvent createdEvent = ShipmentCreatedEvent.builder()
                    .orderId(event.getOrderId())
                    .shipmentId(shipment.getId())
                    .trackingNumber(trackingNumber)
                    .build();
            publishEvent("shipment-created", createdEvent);
            log.info("[SHIPPING-SERVICE] Shipment CREATED for order={}, tracking={}", event.getOrderId(), trackingNumber);

        } catch (Exception e) {
            log.error("[SHIPPING-SERVICE] Error processing inventory-reserved", e);
        }
    }

    private void publishEvent(String topic, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, payload);
            log.info("[SHIPPING-SERVICE] Published to '{}': {}", topic, payload);
        } catch (Exception e) {
            log.error("[SHIPPING-SERVICE] Failed to publish to topic '{}'", topic, e);
        }
    }
}
