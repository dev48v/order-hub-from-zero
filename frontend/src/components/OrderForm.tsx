import { useState } from 'react'
import type { CreateOrderRequest } from '../types'

// "Place an order" card. Client-side validation mirrors the backend's
// @NotBlank / @Positive so the UX matches what the API would enforce.
export function OrderForm({ onPlace, busy }: { onPlace: (req: CreateOrderRequest) => Promise<void>; busy: boolean }) {
  const [customer, setCustomer] = useState('')
  const [item, setItem] = useState('')
  const [quantity, setQuantity] = useState(1)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!customer.trim() || !item.trim()) return setError('Customer and item are required.')
    if (quantity < 1) return setError('Quantity must be at least 1.')
    setError(null)
    await onPlace({ customer: customer.trim(), item: item.trim(), quantity })
    setCustomer(''); setItem(''); setQuantity(1)
  }

  const field = 'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-brand-500 focus:ring-2 focus:ring-brand-500/20'

  return (
    <form onSubmit={submit} className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <h2 className="mb-4 text-sm font-semibold text-slate-900">Place an order</h2>
      <div className="space-y-3">
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Customer</label>
          <input className={field} value={customer} onChange={(e) => setCustomer(e.target.value)} placeholder="e.g. Devanshu" />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Item</label>
          <input className={field} value={item} onChange={(e) => setItem(e.target.value)} placeholder="e.g. Mechanical Keyboard" />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Quantity</label>
          <input type="number" min={1} className={field} value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
        </div>
        {error && <p className="text-xs text-rose-600">{error}</p>}
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-brand-700 disabled:opacity-50"
        >
          {busy ? 'Placing…' : 'Place order'}
        </button>
      </div>
    </form>
  )
}
