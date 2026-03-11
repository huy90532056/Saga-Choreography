Mở terminal chạy lệnh: docker-compose up -d
Chạy từng service trong 4 service

Mở post man lưu: 
http://localhost:8081/api/orders 
http://localhost:8081/api/orders
mẫu json:
{
  "customerId": "cust-1",
  "amount": 500,
  "quantity": 10
}

Kafka UI: http://localhost:9090
http://localhost:8081/h2-console (orders)
http://localhost:8082/h2-console (payments)
http://localhost:8083/h2-console (inventory)
http://localhost:8084/h2-console (shipments)

Service	Database URL
order-service	jdbc:h2:mem:orderdb
payment-service	jdbc:h2:mem:paymentdb
inventory-service	jdbc:h2:mem:inventorydb
shipping-service	jdbc:h2:mem:shippingdb

Cơ chế happy case: amount <= 1000 và quantity <= 50
<img width="478" height="423" alt="image" src="https://github.com/user-attachments/assets/0b3909f8-8f40-462f-ac45-37733ce0484c" />

Cơ chế không happy:
Case 1:
<img width="453" height="261" alt="image" src="https://github.com/user-attachments/assets/1ee500e7-b4d4-4441-9dad-e98bf2427f94" />
Case 2:
<img width="510" height="443" alt="image" src="https://github.com/user-attachments/assets/a351609d-e246-4039-be63-cd9ce29643af" />

code flow:
OrderController.createOrder()
    → OrderService.createOrder()          → publish [order-created]
        → PaymentService.handleOrderCreated()   → publish [payment-completed]
            → InventoryService.handlePaymentCompleted() → publish [inventory-reserved]
                → ShippingService.handleInventoryReserved()  → publish [shipment-created]
                    → OrderService.handleShipmentCreated()
                        → Order status = COMPLETED 

                  
