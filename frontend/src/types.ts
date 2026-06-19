// The API contract, mirrored from the Spring Boot OrderResponse / CreateOrderRequest.
export type OrderStatus = 'PLACED' | 'CONFIRMED' | 'SHIPPED' | 'CANCELLED'

export interface Order {
  id: string
  customer: string
  item: string
  quantity: number
  status: OrderStatus
  createdAt: string
}

export interface CreateOrderRequest {
  customer: string
  item: string
  quantity: number
}
