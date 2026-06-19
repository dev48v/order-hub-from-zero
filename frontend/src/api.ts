import type { Order, CreateOrderRequest } from './types'

// If VITE_API_URL is set (the Render backend), talk to the real Spring Boot API.
// Otherwise fall back to an in-memory mock so the live Vercel demo always works —
// even while the free-tier Render service is cold-starting.
const BASE = import.meta.env.VITE_API_URL as string | undefined
export const USING_MOCK = !BASE

// ---------- real API ----------
async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.detail || body.message || `Request failed (${res.status})`)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

// ---------- mock API ----------
const uid = () => crypto.randomUUID()
let mockStore: Order[] = [
  { id: uid(), customer: 'Aisha', item: 'Mechanical Keyboard', quantity: 2, status: 'PLACED', createdAt: new Date(Date.now() - 36e5).toISOString() },
  { id: uid(), customer: 'Rahul', item: 'USB-C Hub', quantity: 1, status: 'CONFIRMED', createdAt: new Date(Date.now() - 72e5).toISOString() },
]
const delay = <T>(v: T, ms = 280) => new Promise<T>((r) => setTimeout(() => r(v), ms))

const mock = {
  list: () => delay([...mockStore].reverse()),
  place: (req: CreateOrderRequest) => {
    const o: Order = { id: uid(), ...req, status: 'PLACED', createdAt: new Date().toISOString() }
    mockStore.push(o)
    return delay(o)
  },
  confirm: (id: string) => {
    const o = mockStore.find((x) => x.id === id)
    if (!o) return Promise.reject(new Error('Order not found'))
    if (o.status !== 'PLACED') return Promise.reject(new Error(`Only a PLACED order can be confirmed (was ${o.status})`))
    o.status = 'CONFIRMED'
    return delay(o)
  },
}

// ---------- one interface the UI uses, real or mock ----------
export const api = {
  listOrders: (): Promise<Order[]> =>
    USING_MOCK ? mock.list() : http<Order[]>('/api/orders'),

  placeOrder: (req: CreateOrderRequest): Promise<Order> =>
    USING_MOCK ? mock.place(req) : http<Order>('/api/orders', { method: 'POST', body: JSON.stringify(req) }),

  confirmOrder: (id: string): Promise<Order> =>
    USING_MOCK ? mock.confirm(id) : http<Order>(`/api/orders/${id}/confirm`, { method: 'POST' }),
}
