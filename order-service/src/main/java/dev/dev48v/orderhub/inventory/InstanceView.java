package dev.dev48v.orderhub.inventory;

// Day 22 — order-service's view of "which inventory-service instance answered this call".
// inventory-service exposes GET /api/inventory/instance returning its own identity (service id, Eureka
// instance id, port); the Feign client below binds that JSON onto this record. It exists purely to make
// client-side load balancing OBSERVABLE: call the endpoint many times and watch the instanceId change as
// the load balancer spreads requests across instances. Like the other cross-service DTOs, this is the
// consumer's own copy of the wire contract — the two services share no Java types.
public record InstanceView(String serviceId, String instanceId, int port) {
}
